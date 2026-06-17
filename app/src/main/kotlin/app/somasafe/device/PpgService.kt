package app.somasafe.device

import android.util.Log
import app.somasafe.bluetooth.BleConnection
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

private const val TAG = "PpgService"

/**
 * One reassembled PPG window. [ppg] and [acc] are the de-interleaved raw little
 * endian float32 samples (PPG at 64 Hz, ACC at 32 Hz). [deviceStartMs] and
 * [deviceEndMs] are device-uptime timestamps. [sequenceN] is the service-layer
 * slice sequence number used to match against ML results; it is NOT unique
 * across device resets.
 */
data class PpgSample(
    val sequenceN: Long,
    val sliceSeconds: Int,
    val deviceStartMs: Long,
    val deviceEndMs: Long,
    val ppg: ByteArray,
    val acc: ByteArray,
)

/**
 * Client side of the firmware PPG service. Reassembles each window transaction
 * and parses the service-layer framing (see firmware ppg/service.c):
 *
 *   header: sliceSeconds(u8) | sequence_n(u32) | start_ms(u32)
 *   body:   per second, ppgPerSec float32 + accPerSec float32 (interleaved)
 *   tail:   end_ms(u32)
 */
class PpgService(private val connection: BleConnection) {
    private val dataChr = connection.characteristic(SomaSafeUuids.PPG_DATA_CHR)
        ?: error("PPG data characteristic not found")

    fun samples(): Flow<PpgSample> = flow {
        val reassembler = TransactionReassembler()
        connection.notifications(dataChr).collect { data ->
            val payload = reassembler.feed(data) ?: return@collect
            parse(payload)?.let { emit(it) }
        }
    }

    private fun parse(payload: ByteArray): PpgSample? {
        if (payload.size < HEADER_LEN + TAIL_LEN) {
            Log.w(TAG, "PPG payload too small: ${payload.size} bytes")
            return null
        }

        val sliceSeconds = payload[0].toInt() and 0xFF
        val sequenceN = payload.u32leAt(1)
        val startMs = payload.u32leAt(5)
        val endMs = payload.u32leAt(payload.size - TAIL_LEN)

        val ppgBytesPerSec = PPG_PER_SEC * Float.SIZE_BYTES
        val accBytesPerSec = ACC_PER_SEC * Float.SIZE_BYTES
        val expected = HEADER_LEN + sliceSeconds * (ppgBytesPerSec + accBytesPerSec) + TAIL_LEN
        if (payload.size != expected) {
            Log.w(TAG, "PPG payload length ${payload.size}, expected $expected for $sliceSeconds s")
            return null
        }

        val ppg = ByteArray(sliceSeconds * ppgBytesPerSec)
        val acc = ByteArray(sliceSeconds * accBytesPerSec)
        var off = HEADER_LEN
        for (sec in 0 until sliceSeconds) {
            payload.copyInto(ppg, sec * ppgBytesPerSec, off, off + ppgBytesPerSec)
            off += ppgBytesPerSec
            payload.copyInto(acc, sec * accBytesPerSec, off, off + accBytesPerSec)
            off += accBytesPerSec
        }

        return PpgSample(sequenceN, sliceSeconds, startMs, endMs, ppg, acc)
    }

    companion object {
        // Per-second sample counts, mirroring firmware PPG_SAMPLE_RATE / PPG_ACC_RATE.
        const val PPG_PER_SEC = 64
        const val ACC_PER_SEC = 32

        private const val HEADER_LEN = 9 // sliceSeconds(1) + seq(4) + start_ms(4)
        private const val TAIL_LEN = 4   // end_ms(4)
    }
}
