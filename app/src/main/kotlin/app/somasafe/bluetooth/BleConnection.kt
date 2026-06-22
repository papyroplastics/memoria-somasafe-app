package app.somasafe.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "BleConnection"
private const val OP_TIMEOUT_MS = 10_000L
private val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

/**
 * Coroutine wrapper over [BluetoothGatt], roughly the Android counterpart of
 * Python's BleakClient: GATT operations are suspend functions and notifications
 * are cold [Flow]s. Android only allows one outstanding GATT operation at a
 * time, so every read/write is serialized through a single mutex; notifications
 * arrive out of band and are dispatched to per-characteristic sinks.
 */
@SuppressLint("MissingPermission")
class BleConnection(
    private val context: Context,
    private val device: BluetoothDevice,
) {
    private val _connectionState = MutableStateFlow(BluetoothProfile.STATE_DISCONNECTED)
    val connectionState = _connectionState.asStateFlow()

    private val _mtu = MutableStateFlow(-1)
    val mtu = _mtu.asStateFlow()

    private val _services = MutableStateFlow<List<BluetoothGattService>>(emptyList())
    val services = _services.asStateFlow()

    private var gatt: BluetoothGatt? = null

    private val opMutex = Mutex()
    private var pendingOp: CompletableDeferred<ByteArray>? = null
    private var ready: CompletableDeferred<Unit>? = null

    private val notifySinks = ConcurrentHashMap<UUID, (ByteArray) -> Unit>()

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            _connectionState.value = newState
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> gatt.requestMtu(517)
                BluetoothProfile.STATE_DISCONNECTED -> {
                    ready?.takeIf { !it.isCompleted }
                        ?.completeExceptionally(IOException("disconnected (status $status)"))
                    failPending(IOException("disconnected (status $status)"))
                }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            _mtu.value = mtu
            gatt.discoverServices()
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            _services.value = gatt.services
            ready?.takeIf { !it.isCompleted }?.complete(Unit)
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic,
            value: ByteArray, status: Int,
        ) = completePending(value, status)

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int,
        ) = completePending(ByteArray(0), status)

        override fun onDescriptorRead(
            gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor,
            status: Int, value: ByteArray,
        ) = completePending(value, status)

        override fun onDescriptorWrite(
            gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int,
        ) = completePending(ByteArray(0), status)

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray,
        ) {
            notifySinks[characteristic.uuid]?.invoke(value)
        }
    }

    private fun completePending(value: ByteArray, status: Int) {
        val op = pendingOp ?: return
        pendingOp = null
        if (status == BluetoothGatt.GATT_SUCCESS) op.complete(value)
        else op.completeExceptionally(IOException("GATT op failed with status $status"))
    }

    private fun failPending(cause: Throwable) {
        pendingOp?.takeIf { !it.isCompleted }?.completeExceptionally(cause)
        pendingOp = null
    }

    /** Connect and suspend until services are discovered. */
    suspend fun connect() {
        val signal = CompletableDeferred<Unit>()
        ready = signal
        gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        signal.await()
    }

    fun close() {
        notifySinks.clear()
        gatt?.disconnect()
        gatt?.close()
        gatt = null
        _connectionState.value = BluetoothProfile.STATE_DISCONNECTED
        _services.value = emptyList()
    }

    /** Largest payload that fits one notification/write without response. */
    val maxPayload: Int get() = (_mtu.value.takeIf { it > 0 } ?: 23) - 3

    fun characteristic(uuid: UUID): BluetoothGattCharacteristic? =
        gatt?.services?.firstNotNullOfOrNull { it.getCharacteristic(uuid) }

    fun descriptor(uuid: UUID): BluetoothGattDescriptor? =
        gatt?.services
            ?.flatMap { it.characteristics }
            ?.firstNotNullOfOrNull { it.getDescriptor(uuid) }

    /**
     * Resolve a characteristic within a specific service. Necessary when the
     * same characteristic UUID appears in more than one service (e.g. the client
     * buffer attributes shared by the ML and device services).
     */
    fun characteristic(serviceUuid: UUID, charUuid: UUID): BluetoothGattCharacteristic? =
        gatt?.getService(serviceUuid)?.getCharacteristic(charUuid)

    fun descriptor(serviceUuid: UUID, charUuid: UUID, dscUuid: UUID): BluetoothGattDescriptor? =
        characteristic(serviceUuid, charUuid)?.getDescriptor(dscUuid)

    suspend fun readCharacteristic(chr: BluetoothGattCharacteristic): ByteArray =
        op { it.readCharacteristic(chr) }

    suspend fun writeCharacteristic(
        chr: BluetoothGattCharacteristic,
        value: ByteArray,
        withResponse: Boolean = true,
    ): ByteArray {
        val type = if (withResponse) BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        else BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        return op { it.writeCharacteristic(chr, value, type) == BluetoothStatusCodes.SUCCESS }
    }

    suspend fun readDescriptor(dsc: BluetoothGattDescriptor): ByteArray =
        op { it.readDescriptor(dsc) }

    suspend fun writeDescriptor(dsc: BluetoothGattDescriptor, value: ByteArray): ByteArray =
        op { it.writeDescriptor(dsc, value) == BluetoothStatusCodes.SUCCESS }

    private suspend fun op(initiate: (BluetoothGatt) -> Boolean): ByteArray = opMutex.withLock {
        val g = gatt ?: throw IOException("not connected")
        val deferred = CompletableDeferred<ByteArray>()
        pendingOp = deferred
        if (!initiate(g)) {
            pendingOp = null
            throw IOException("failed to initiate GATT operation")
        }
        try {
            withTimeout(OP_TIMEOUT_MS) { deferred.await() }
        } catch (e: TimeoutCancellationException) {
            // Still under the lock, so pendingOp is necessarily this op: clear it
            // so a late callback can't be misattributed to the next operation.
            pendingOp = null
            throw IOException("GATT operation timed out")
        }
    }

    /**
     * Route a characteristic's notifications to [onValue] and suspend until the
     * CCCD write that enables them has completed, so the caller can safely
     * trigger device activity that depends on the subscription being live.
     */
    suspend fun enableNotifications(
        chr: BluetoothGattCharacteristic,
        onValue: (ByteArray) -> Unit,
    ) {
        notifySinks[chr.uuid] = onValue
        gatt?.setCharacteristicNotification(chr, true)
        chr.getDescriptor(CCCD_UUID)?.let { cccd ->
            writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
        }
    }

    fun disableNotifications(chr: BluetoothGattCharacteristic) {
        notifySinks.remove(chr.uuid)
        runCatching { gatt?.setCharacteristicNotification(chr, false) }
    }

    /**
     * Subscribe to a characteristic's notifications as a cold [Flow]. Enabling
     * writes the CCCD; cancelling the collector disables it. A dropped
     * connection stops the flow rather than throwing into the collector.
     */
    fun notifications(chr: BluetoothGattCharacteristic): Flow<ByteArray> = callbackFlow {
        if (gatt == null) {
            close()
            return@callbackFlow
        }
        runCatching { enableNotifications(chr) { value -> trySend(value) } }
            .onFailure { Log.w(TAG, "failed to enable notifications for ${chr.uuid}", it) }

        awaitClose { disableNotifications(chr) }
    }
}
