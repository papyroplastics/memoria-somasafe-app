package app.somasafe.device

import android.util.Log
import app.somasafe.bluetooth.BleConnection
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout

private const val TAG = "ClientBuffer"
private const val STATE_NOT_READY = 0
private const val STATE_READY = 1
private const val STATE_TIMEOUT_MS = 5_000L

/**
 * Client side of the firmware's client_buffer GATT service, used to stage a
 * model into the device. Mirrors firmware/scripts/lib/client_buf.py: reset the
 * buffer to NOT_READY, size it, write the bytes, then flip it to READY so the
 * on-device consumer (the ML task) picks it up.
 */
class ClientBuffer(private val connection: BleConnection) {
    private val bufChr = connection.characteristic(SomaSafeUuids.BUF_CHR)
        ?: error("client buffer data characteristic not found")
    private val stateChr = connection.characteristic(SomaSafeUuids.BUF_STATE_CHR)
        ?: error("client buffer state characteristic not found")
    private val sizeDsc = connection.descriptor(SomaSafeUuids.BUF_SIZE_DSC)
        ?: error("client buffer size descriptor not found")

    /** Stage [data] into the buffer, leaving it NOT_READY (not yet consumed). */
    suspend fun upload(data: ByteArray) {
        setNotReady()
        setSize(data.size)
        write(data)
    }

    /** Flip the staged buffer to READY so the consumer starts using it. */
    suspend fun ready() {
        connection.writeCharacteristic(stateChr, u8(STATE_READY))
    }

    private suspend fun setNotReady() {
        val confirmed = CompletableDeferred<Unit>()
        connection.enableNotifications(stateChr) { data ->
            if (data.isNotEmpty() && (data[0].toInt() and 0xFF) == STATE_NOT_READY) {
                confirmed.complete(Unit)
            }
        }
        try {
            connection.writeCharacteristic(stateChr, u8(STATE_NOT_READY))
            withTimeout(STATE_TIMEOUT_MS) { confirmed.await() }
        } finally {
            connection.disableNotifications(stateChr)
        }
    }

    private suspend fun setSize(size: Int) {
        connection.writeDescriptor(sizeDsc, u32le(size))
        val readBack = connection.readDescriptor(sizeDsc).u32leAt(0)
        check(readBack == size.toLong()) { "buffer size mismatch: wrote $size, read $readBack" }
    }

    private suspend fun write(data: ByteArray) {
        val chunk = connection.maxPayload.coerceAtLeast(20)
        var pos = 0
        while (pos < data.size) {
            val end = minOf(pos + chunk, data.size)
            connection.writeCharacteristic(bufChr, data.copyOfRange(pos, end), withResponse = true)
            pos = end
        }
        Log.i(TAG, "wrote ${data.size} model bytes")
    }
}
