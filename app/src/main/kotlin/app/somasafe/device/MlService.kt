package app.somasafe.device

import android.util.Log
import app.somasafe.bluetooth.BleConnection
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

private const val TAG = "MlService"

/**
 * One reassembled inference result. [features] is the quantized input feature
 * vector echoed back by the device and [score] the model output tensor, both
 * raw int8 bytes. [sequenceN] matches the PPG window the inference ran on.
 */
data class MlResult(
    val sequenceN: Long,
    val features: ByteArray,
    val score: ByteArray,
)

val ML_ERROR_NAMES = mapOf(
    0 to "NONE",
    1 to "MODEL_LOAD",
    2 to "UNSUPPORTED_OP",
    3 to "TENSOR_ALLOC",
    4 to "INVOKE",
    5 to "INVALID_SHAPE",
)

/**
 * Client side of the firmware ML service. Reassembles each result transaction
 * and parses the service-layer payload: sequence_n(u32) | features | score.
 * Mirrors firmware/scripts/lib/ml_service.py (without the dataset comparison).
 */
class MlService(
    private val connection: BleConnection,
    private val featuresLen: Int,
    private val scoreLen: Int,
) {
    private val resultsChr = connection.characteristic(SomaSafeUuids.ML_RESULTS_CHR)
        ?: error("ML results characteristic not found")
    private val errorsChr = connection.characteristic(SomaSafeUuids.ML_ERRORS_CHR)
        ?: error("ML errors characteristic not found")

    fun results(): Flow<MlResult> = flow {
        val reassembler = TransactionReassembler()
        connection.notifications(resultsChr).collect { data ->
            val payload = reassembler.feed(data) ?: return@collect
            parse(payload)?.let { emit(it) }
        }
    }

    /** Error codes notified by the device. See [ML_ERROR_NAMES]. */
    fun errors(): Flow<Int> = flow {
        connection.notifications(errorsChr).collect { data ->
            if (data.isNotEmpty()) emit(data[0].toInt() and 0xFF)
        }
    }

    private fun parse(payload: ByteArray): MlResult? {
        val expected = SEQ_LEN + featuresLen + scoreLen
        if (payload.size != expected) {
            Log.w(TAG, "ML payload length ${payload.size}, expected $expected")
            return null
        }
        val sequenceN = payload.u32leAt(0)
        val features = payload.copyOfRange(SEQ_LEN, SEQ_LEN + featuresLen)
        val score = payload.copyOfRange(SEQ_LEN + featuresLen, expected)
        return MlResult(sequenceN, features, score)
    }

    companion object {
        private const val SEQ_LEN = 4
    }
}
