package app.somasafe.backend.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Local weights state relative to the upstream global-weights snapshot. */
enum class WeightsStatus { MISSING, OUTDATED, CURRENT }

/** State of a model's quantized artifact relative to its local weights. */
enum class QuantStatus { MISSING, OUTDATED, CURRENT }

/**
 * The weights the client holds for a model — the local source of truth, persisted
 * as `weights.json`. The trainable `.tflite` is never assumed to carry usable
 * weights (it may be randomly initialized); these are pulled from the backend and,
 * once on-device training exists, overwritten in place by the trained parameters.
 *
 * Alongside the flat parameter vector it keeps the version metadata `/quantize`
 * needs: [weightsId] is the `GlobalWeights` snapshot the parameters derive from
 * (echoed back on submit so aggregation knows the base), and [weightsVersion] /
 * [fingerprint] record which upstream snapshot / architecture they came from so
 * staleness can be detected.
 */
data class StoredWeights(
    val parameters: FloatArray,
    val weightsId: Long,
    val weightsVersion: String?,
    val fingerprint: String?,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("parameters", JSONArray().apply { parameters.forEach { put(it.toDouble()) } })
        put("weights_id", weightsId)
        put("weights_version", weightsVersion ?: JSONObject.NULL)
        put("fingerprint", fingerprint ?: JSONObject.NULL)
    }

    companion object {
        fun fromJson(o: JSONObject): StoredWeights {
            val arr = o.getJSONArray("parameters")
            return StoredWeights(
                parameters = FloatArray(arr.length()) { arr.getDouble(it).toFloat() },
                weightsId = o.getLong("weights_id"),
                weightsVersion = if (o.isNull("weights_version")) null else o.getString("weights_version"),
                fingerprint = if (o.isNull("fingerprint")) null else o.getString("fingerprint"),
            )
        }
    }
}

fun loadWeights(context: Context, key: String): StoredWeights? =
    runCatching { StoredWeights.fromJson(JSONObject(weightsFile(context, key).readText())) }.getOrNull()

/** Persist [weights] as the model's `weights.json` (called after a pull or, later, training). */
fun saveWeights(context: Context, key: String, weights: StoredWeights) {
    modelDir(context, key).mkdirs()
    weightsFile(context, key).writeText(weights.toJson().toString())
}

/**
 * Pull the model's latest global weights from `/model/weights/{key}` and store them
 * as the local source of truth. The body is the raw little-endian float32 buffer;
 * the snapshot id / timestamp / architecture travel in the response headers.
 *
 * `meta.json`'s upstream weights pointer is advanced to match, so the version we
 * hold reads consistently across the app (weights.json stays the source of truth).
 */
suspend fun downloadWeights(context: Context, model: RemoteModel): Result<StoredWeights> =
    authedRequest(context, model.weightsEndpoint) { connection ->
        val id = connection.getHeaderField(WEIGHTS_ID_HEADER)?.toLong()
            ?: error("missing $WEIGHTS_ID_HEADER header")
        val floats = ByteBuffer.wrap(connection.inputStream.use { it.readBytes() })
            .order(ByteOrder.LITTLE_ENDIAN)
            .asFloatBuffer()
        val parameters = FloatArray(floats.remaining()).also { floats.get(it) }

        val weights = StoredWeights(
            parameters = parameters,
            weightsId = id,
            weightsVersion = connection.getHeaderField(WEIGHTS_TIMESTAMP_HEADER),
            fingerprint = connection.getHeaderField(FINGERPRINT_HEADER),
        )
        saveWeights(context, model.key, weights)
        loadModelMeta(context, model.key)?.let {
            saveModelMeta(context, it.copy(weightsVersion = weights.weightsVersion))
        }
        weights
    }

/** Whether the local weights are missing, stale, or match [upstream]'s snapshot. */
fun weightsStatus(context: Context, upstream: RemoteModel): WeightsStatus {
    val local = loadWeights(context, upstream.key) ?: return WeightsStatus.MISSING
    val current = local.fingerprint == upstream.fingerprint &&
        local.weightsVersion == upstream.weightsVersion
    return if (current) WeightsStatus.CURRENT else WeightsStatus.OUTDATED
}

/** Whether the quantized artifact is missing, present, or stale vs the weights file. */
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
