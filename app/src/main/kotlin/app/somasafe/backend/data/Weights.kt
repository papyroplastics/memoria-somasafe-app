package app.somasafe.backend.data

import android.content.Context
import app.somasafe.capture.domain.leBytes
import app.somasafe.capture.domain.leFloats

/** State of the locally trained update relative to the downloaded model snapshot. */
enum class WeightsStatus { MISSING, OUTDATED, CURRENT }

/** State of a model's quantized artifact relative to its local weights. */
enum class QuantStatus { MISSING, OUTDATED, CURRENT }

/**
 * On-device training writes two raw little-endian float32 blobs: `base_weights.bin`
 * (the global snapshot baked into the trainable that training started from) and
 * `trained_weights.bin` (the absolute trained weights, kept so a later epoch can
 * resume from them). The federated update is the delta `trained − base`, computed at
 * upload time and never stored. The base snapshot's id lives in `meta.json`
 * (`weightsId`) and rides the submission URL.
 */
fun saveTrainedWeights(context: Context, key: String, base: FloatArray, trained: FloatArray) {
    modelDir(context, key).mkdirs()
    baseWeightsFile(context, key).writeBytes(base.leBytes())
    trainedWeightsFile(context, key).writeBytes(trained.leBytes())
}

fun loadBaseWeights(context: Context, key: String): FloatArray? =
    runCatching { baseWeightsFile(context, key).readBytes().leFloats() }.getOrNull()

fun loadTrainedWeights(context: Context, key: String): FloatArray? =
    runCatching { trainedWeightsFile(context, key).readBytes().leFloats() }.getOrNull()

/** The federated update `trained − base`, or null if training hasn't produced weights. */
fun loadTrainedDelta(context: Context, key: String): FloatArray? {
    val base = loadBaseWeights(context, key) ?: return null
    val trained = loadTrainedWeights(context, key) ?: return null
    return FloatArray(trained.size) { trained[it] - base[it] }
}

/**
 * Whether a locally trained update exists and still derives from the snapshot the
 * downloaded trainable carries. MISSING just means "not trained yet"; OUTDATED means
 * the trainable was re-downloaded after training (its file is newer than the trained
 * weights), so the update no longer matches the baked-in global.
 */
fun weightsStatus(context: Context, key: String): WeightsStatus {
    val trained = trainedWeightsFile(context, key)
    if (!trained.exists()) return WeightsStatus.MISSING
    val trainable = trainableFile(context, key)
    return if (trainable.exists() && trainable.lastModified() > trained.lastModified()) {
        WeightsStatus.OUTDATED
    } else {
        WeightsStatus.CURRENT
    }
}

/** Whether the quantized artifact is missing, present, or stale vs the trained weights. */
fun quantStatus(context: Context, key: String): QuantStatus {
    val quantized = quantizedFile(context, key)
    if (!quantized.exists() || loadSignedModelMeta(context, key) == null) return QuantStatus.MISSING
    val trained = trainedWeightsFile(context, key)
    return if (trained.exists() && trained.lastModified() > quantized.lastModified()) {
        QuantStatus.OUTDATED
    } else {
        QuantStatus.CURRENT
    }
}
