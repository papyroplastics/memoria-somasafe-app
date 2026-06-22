package app.somasafe.device

import app.somasafe.bluetooth.BleConnection
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout

private const val SIGN_TIMEOUT_MS = 10_000L

/**
 * Client side of the firmware device service (attestation): read the
 * factory-provisioned serial and have the device sign an arbitrary payload with
 * its ECDSA P-256 private key. Mirrors firmware/scripts/test_sign.py.
 */
class DeviceService(private val connection: BleConnection) {
    private val serialChr =
        connection.characteristic(SomaSafeUuids.DEVICE_SVC, SomaSafeUuids.DEVICE_SERIAL_CHR)
            ?: error("device serial characteristic not found")
    private val signChr =
        connection.characteristic(SomaSafeUuids.DEVICE_SVC, SomaSafeUuids.DEVICE_SIGN_CHR)
            ?: error("device sign characteristic not found")

    /** Read the device serial (single MTU read, ASCII). */
    suspend fun readSerial(): String =
        String(connection.readCharacteristic(serialChr), Charsets.US_ASCII)

    /**
     * Upload [payload] to the device's sign buffer and return the DER ECDSA
     * signature over its SHA-256. The device signs the hash internally, then
     * resets the buffer to NOT_READY (which we wait for, mirroring test_sign.py).
     */
    suspend fun sign(payload: ByteArray): ByteArray {
        val buffer = ClientBuffer(connection, SomaSafeUuids.DEVICE_SVC)
        buffer.start()

        val signature = CompletableDeferred<ByteArray>()
        val reassembler = TransactionReassembler()
        connection.enableNotifications(signChr) { data ->
            if (!signature.isCompleted) {
                reassembler.feed(data)?.let { signature.complete(it) }
            }
        }

        try {
            buffer.upload(payload)
            buffer.ready()
            val sig = withTimeout(SIGN_TIMEOUT_MS) { signature.await() }
            buffer.waitState(ClientBuffer.STATE_NOT_READY)
            return sig
        } finally {
            connection.disableNotifications(signChr)
            buffer.stop()
        }
    }
}
