package app.somasafe.training.data

import android.content.Context
import app.somasafe.backend.data.FINGERPRINT_HEADER
import app.somasafe.backend.data.RemoteModel
import app.somasafe.backend.data.authedRequest
import app.somasafe.backend.data.loadModelMeta
import app.somasafe.backend.data.modelDir
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

const val NORM_FILENAME = "norm.json"

/** Local normalization-params state relative to the model's upstream architecture. */
enum class NormStatus { MISSING, OUTDATED, CURRENT }

fun normFile(context: Context, key: String): File = File(modelDir(context, key), NORM_FILENAME)

/**
 * The dataset-global normalization params the on-device trainer applies at load time,
 * pulled from `/model/norm/{key}` and cached as `norm.json`. Each block is a per-dimension
 * `(mean, std)`; the std already carries the load-time EPS, so the trainer applies
 * `(x - mean) / std` verbatim, matching the backend.
 *
 * These are keyed to the **architecture** ([fingerprint]), not a weights snapshot, so
 * they only go stale when the model's fingerprint moves.
 */
data class NormParams(
    val signalMean: FloatArray,   // [BVP, ACC]
    val signalStd: FloatArray,
    val contextMean: FloatArray,  // 2-d activity context
    val contextStd: FloatArray,
    val staticMean: FloatArray,   // 6-d demographics
    val staticStd: FloatArray,
    val fingerprint: String?,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("signal_mean", signalMean.toJsonArray())
        put("signal_std", signalStd.toJsonArray())
        put("context_mean", contextMean.toJsonArray())
        put("context_std", contextStd.toJsonArray())
        put("static_mean", staticMean.toJsonArray())
        put("static_std", staticStd.toJsonArray())
        put("fingerprint", fingerprint ?: JSONObject.NULL)
    }

    companion object {
        fun fromJson(o: JSONObject, fingerprint: String?): NormParams = NormParams(
            signalMean = o.floatArray("signal_mean"),
            signalStd = o.floatArray("signal_std"),
            contextMean = o.floatArray("context_mean"),
            contextStd = o.floatArray("context_std"),
            staticMean = o.floatArray("static_mean"),
            staticStd = o.floatArray("static_std"),
            fingerprint = fingerprint,
        )
    }
}

private fun FloatArray.toJsonArray(): JSONArray = JSONArray().apply { forEach { put(it.toDouble()) } }

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
 * Pull the model's normalization params and cache them as `norm.json`, recording the
 * architecture fingerprint from the response header so staleness can be detected.
 */
suspend fun downloadNormParams(context: Context, model: RemoteModel): Result<NormParams> =
    authedRequest(context, model.normEndpoint) { connection ->
        val fingerprint = connection.getHeaderField(FINGERPRINT_HEADER)
        val body = JSONObject(connection.inputStream.bufferedReader().readText())
        val params = NormParams.fromJson(body, fingerprint)
        modelDir(context, model.key).mkdirs()
        normFile(context, model.key).writeText(params.toJson().toString())
        params
    }

/** Whether cached norm params are missing, stale, or match [upstream]'s architecture. */
fun normStatus(context: Context, upstream: RemoteModel): NormStatus {
    val local = loadNormParams(context, upstream.key) ?: return NormStatus.MISSING
    return if (local.fingerprint == upstream.fingerprint) NormStatus.CURRENT else NormStatus.OUTDATED
}
