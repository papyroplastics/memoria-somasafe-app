package app.somasafe.backend.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** State of the locally trained update relative to the downloaded model snapshot. */
enum class WeightsStatus { MISSING, OUTDATED, CURRENT }

/** State of a model's quantized artifact relative to its local weights. */
enum class QuantStatus { MISSING, OUTDATED, CURRENT }

/**
 * Locally trained weights, persisted as `weights.json`. Written only by
 * on-device training — the global weights come baked into the trainable
 * `.tflite` and are never stored separately. [weightsId] is the `GlobalWeights`
 * snapshot the training started from (recorded in `meta.json` when the
 * trainable was downloaded); it rides the submission URL so aggregation knows
 * the base each update was trained against.
 */
data class StoredWeights(
    val parameters: FloatArray,
    val weightsId: Long,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("parameters", JSONArray().apply { parameters.forEach { put(it.toDouble()) } })
        put("weights_id", weightsId)
    }

    companion object {
        fun fromJson(o: JSONObject): StoredWeights {
            val arr = o.getJSONArray("parameters")
            return StoredWeights(
                parameters = FloatArray(arr.length()) { arr.getDouble(it).toFloat() },
                weightsId = o.getLong("weights_id"),
            )
        }
    }
}

fun loadWeights(context: Context, key: String): StoredWeights? =
    runCatching { StoredWeights.fromJson(JSONObject(weightsFile(context, key).readText())) }.getOrNull()

/** Persist [weights] as the model's `weights.json` (called after training). */
fun saveWeights(context: Context, key: String, weights: StoredWeights) {
    modelDir(context, key).mkdirs()
    weightsFile(context, key).writeText(weights.toJson().toString())
}

/**
 * Whether a locally trained update exists and whether its base snapshot is still
 * the one the downloaded model carries. MISSING just means "not trained yet";
 * OUTDATED means the trainable was re-downloaded (new snapshot) after training.
 */
fun weightsStatus(context: Context, key: String): WeightsStatus {
    val local = loadWeights(context, key) ?: return WeightsStatus.MISSING
    val base = loadModelMeta(context, key)?.weightsId
    return if (base != null && local.weightsId == base) WeightsStatus.CURRENT else WeightsStatus.OUTDATED
}

/** Whether the quantized artifact is missing, present, or stale vs the trained weights. */
fun quantStatus(context: Context, key: String): QuantStatus {
    val quantized = quantizedFile(context, key)
    if (!quantized.exists() || loadSignedModelMeta(context, key) == null) return QuantStatus.MISSING
    val weights = weightsFile(context, key)
    return if (weights.exists() && weights.lastModified() > quantized.lastModified()) {
        QuantStatus.OUTDATED
    } else {
        QuantStatus.CURRENT
    }
}
