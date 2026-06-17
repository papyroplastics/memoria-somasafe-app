package app.somasafe.model

import android.content.Context
import app.somasafe.backend.RemoteModel
import app.somasafe.backend.downloadQuantized
import app.somasafe.backend.quantizedFile
import app.somasafe.backend.trainableFile
import app.somasafe.backend.weightsFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** State of a model's quantized variant relative to its extracted weights. */
enum class QuantStatus { MISSING, OUTDATED, CURRENT }

/**
 * Model preparation steps that bridge the on-device LiteRT runtime and the
 * backend's quantization endpoint:
 *
 *  1. [extractWeights] runs the trainable model's `save` signature to pull its
 *     weights out and stores them as `weights.json` (the shape `/quantize`
 *     wants).
 *  2. [extractAndQuantize] makes sure weights exist (extracting them first if
 *     needed) and then asks the backend to quantize, storing the returned int8
 *     model as `quantized.tflite` (the artifact uploaded to the device).
 */
object ModelPrep {

    /** Extract the trainable model's weights and write them to `weights.json`. */
    suspend fun extractWeights(context: Context, key: String): Result<Unit> =
        withContext(Dispatchers.Default) {
            runCatching {
                val trainable = trainableFile(context, key)
                require(trainable.exists()) { "trainable model not downloaded" }

                val weights = LiteRtModel(trainable.absolutePath).use { it.saveWeights() }
                val json = JSONObject().apply {
                    put("parameters", JSONArray().apply { weights.forEach { put(it.toDouble()) } })
                }
                withContext(Dispatchers.IO) { weightsFile(context, key).writeText(json.toString()) }
            }
        }

    /** Extract weights if they are missing, then quantize via the backend. */
    suspend fun extractAndQuantize(context: Context, model: RemoteModel): Result<Unit> {
        if (!weightsFile(context, model.key).exists()) {
            extractWeights(context, model.key).onFailure { return Result.failure(it) }
        }
        return downloadQuantized(context, model)
    }

    /** Whether the quantized model is missing, present, or stale vs the weights file. */
    fun quantStatus(context: Context, key: String): QuantStatus {
        val quantized = quantizedFile(context, key)
        if (!quantized.exists()) return QuantStatus.MISSING
        val weights = weightsFile(context, key)
        return if (weights.exists() && weights.lastModified() > quantized.lastModified()) {
            QuantStatus.OUTDATED
        } else {
            QuantStatus.CURRENT
        }
    }
}
