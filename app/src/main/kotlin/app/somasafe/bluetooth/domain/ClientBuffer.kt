package app.somasafe.bluetooth.domain

import android.util.Log
import app.somasafe.bluetooth.data.BleConnection
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import java.util.UUID
import app.somasafe.bluetooth.data.SomaSafeUuids

private const val TAG = "ClientBuffer"
private const val STATE_TIMEOUT_MS = 5_000L

/**
 * Client side of the firmware's client_buffer GATT service, used to stage bytes
 * into a buffer the device consumes (the ML model, or a payload to sign). The
 * generic buffer attributes appear in multiple services, so [serviceUuid]
 * selects which service's buffer this drives.
 *
 * Mirrors firmware/scripts/lib/client_buf.py: [start] subscribes to the state
 * characteristic permanently (state transitions can now be server-initiated —
 * e.g. the device resetting the buffer to NOT_READY after consuming it — and
 * arrive at any time), and the local mirror is kept in sync from those
 * notifications. Size/write are guarded by the mirrored state.
 */
class ClientBuffer(
    private val connection: BleConnection,
    serviceUuid: UUID,
) {
    private val bufChr = connection.characteristic(serviceUuid, SomaSafeUuids.BUF_CHR)
        ?: error("client buffer data characteristic not found")
    private val stateChr = connection.characteristic(serviceUuid, SomaSafeUuids.BUF_STATE_CHR)
        ?: error("client buffer state characteristic not found")
    private val sizeDsc =
        connection.descriptor(serviceUuid, SomaSafeUuids.BUF_CHR, SomaSafeUuids.BUF_SIZE_DSC)
            ?: error("client buffer size descriptor not found")

    private val state = MutableStateFlow(STATE_NOT_READY)

    /**
     * Subscribe to state notifications and read the current state. Must be called
     * once before upload/ready; the subscription stays live until [stop] so
     * server-initiated transitions are observed.
     */
    suspend fun start() {
        connection.enableNotifications(stateChr) { data ->
            if (data.isNotEmpty()) state.value = data[0].toInt() and 0xFF
        }
        connection.readCharacteristic(stateChr).takeIf { it.isNotEmpty() }
            ?.let { state.value = it[0].toInt() and 0xFF }
    }

    fun stop() = connection.disableNotifications(stateChr)

    suspend fun waitState(target: Int) {
        state.first { it == target }
    }

    /** Stage [data] into the buffer, leaving it NOT_READY (not yet consumed). */
    suspend fun upload(data: ByteArray) {
        setNotReady()
        setSize(data.size)
        write(data)
    }

    /** Flip the staged buffer to READY so the consumer starts using it. */
    suspend fun ready() {
        connection.writeCharacteristic(stateChr, u8(STATE_READY))
        state.value = STATE_READY
    }

    private suspend fun setNotReady() {
        // The device resets asynchronously (worker task) and notifies; our live
        // subscription updates the mirror, so just wait for it to land.
        connection.writeCharacteristic(stateChr, u8(STATE_NOT_READY))
        withTimeout(STATE_TIMEOUT_MS) { waitState(STATE_NOT_READY) }
    }

    private suspend fun setSize(size: Int) {
        check(state.value == STATE_NOT_READY) { "buffer not NOT_READY (state ${state.value})" }
        connection.writeDescriptor(sizeDsc, u32le(size))
        val readBack = connection.readDescriptor(sizeDsc).u32leAt(0)
        check(readBack == size.toLong()) { "buffer size mismatch: wrote $size, read $readBack" }
    }

    private suspend fun write(data: ByteArray) {
        check(state.value == STATE_NOT_READY) { "buffer not NOT_READY (state ${state.value})" }
        val chunk = connection.maxPayload.coerceAtLeast(20)
        var pos = 0
        while (pos < data.size) {
            val end = minOf(pos + chunk, data.size)
            connection.writeCharacteristic(bufChr, data.copyOfRange(pos, end), withResponse = true)
            pos = end
        }
        Log.i(TAG, "wrote ${data.size} bytes")
    }

    companion object {
        const val STATE_NOT_READY = 0
        const val STATE_READY = 1
    }
}
