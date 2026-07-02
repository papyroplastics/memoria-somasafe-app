package app.somasafe.training.data

import android.content.Context
import app.somasafe.backend.data.FINGERPRINT_HEADER
import app.somasafe.backend.data.RemoteModel
import app.somasafe.backend.data.authedRequest
import app.somasafe.backend.data.modelDir
import org.json.JSONObject
import java.io.File

const val NORM_FILENAME = "norm.json"

/** The signature block the on-device trainer reads its params from. */
private const val TRAIN_SIGNATURE = "train"

/** Local normalization-params state relative to the model's upstream architecture. */
enum class NormStatus { MISSING, OUTDATED, CURRENT }

fun normFile(context: Context, key: String): File = File(modelDir(context, key), NORM_FILENAME)

/**
 * The model's normalization params, pulled from `/model/norm/{key}` and cached as
 * `norm.json`. The served shape is model-specific — a block per signature that consumes
 * normalized inputs, each mapping an input name to a per-channel `(mean, std)`. This
 * trainer reads the `train` signature's `signal` and `cond` inputs; the std already
 * carries the load-time EPS, so it applies `(x - mean) / std` verbatim, matching the
 * backend.
 *
 * These are keyed to the **architecture** ([fingerprint]), not a weights snapshot, so
 * they only go stale when the model's fingerprint moves.
 */
data class NormParams(
    val signalMean: FloatArray,   // per signal channel [BVP, ACC]
    val signalStd: FloatArray,
    val condMean: FloatArray,     // 8-d conditioning [static(6), context(2)]
    val condStd: FloatArray,
    val fingerprint: String?,
) {
    companion object {
        fun fromJson(o: JSONObject, fingerprint: String?): NormParams {
            val (sigMean, sigStd) = o.inputBlock(TRAIN_SIGNATURE, "signal")
            val (condMean, condStd) = o.inputBlock(TRAIN_SIGNATURE, "cond")
            return NormParams(sigMean, sigStd, condMean, condStd, fingerprint)
        }
    }
}

private fun JSONObject.inputBlock(signature: String, input: String): Pair<FloatArray, FloatArray> {
    val block = getJSONObject(signature).getJSONObject(input)
    return block.floatArray("mean") to block.floatArray("std")
}

private fun JSONObject.floatArray(key: String): FloatArray {
    val arr = getJSONArray(key)
    return FloatArray(arr.length()) { arr.getDouble(it).toFloat() }
}

fun loadNormParams(context: Context, key: String): NormParams? = runCatching {
    val o = JSONObject(normFile(context, key).readText())
    val fp = if (o.isNull("fingerprint")) null else o.getString("fingerprint")
    NormParams.fromJson(o, fp)
}.getOrNull()

/**
 * Pull the model's normalization params and cache them as `norm.json`, stamping the
 * architecture fingerprint from the response header into the body so staleness can be
 * detected on load. The server shape is cached verbatim (plus the fingerprint field).
 */
suspend fun downloadNormParams(context: Context, model: RemoteModel): Result<NormParams> =
    authedRequest(context, model.normEndpoint) { connection ->
        val fingerprint = connection.getHeaderField(FINGERPRINT_HEADER)
        val body = JSONObject(connection.inputStream.bufferedReader().readText())
        body.put("fingerprint", fingerprint ?: JSONObject.NULL)
        modelDir(context, model.key).mkdirs()
        normFile(context, model.key).writeText(body.toString())
        NormParams.fromJson(body, fingerprint)
    }

/** Whether cached norm params are missing, stale, or match [upstream]'s architecture. */
fun normStatus(context: Context, upstream: RemoteModel): NormStatus {
    val local = loadNormParams(context, upstream.key) ?: return NormStatus.MISSING
    return if (local.fingerprint == upstream.fingerprint) NormStatus.CURRENT else NormStatus.OUTDATED
}
