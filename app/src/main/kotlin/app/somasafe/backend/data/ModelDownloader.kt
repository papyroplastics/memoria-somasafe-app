package app.somasafe.backend.data

import android.content.Context
import app.somasafe.BuildConfig
import app.somasafe.capture.domain.leBytes
import com.github.luben.zstd.ZstdInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64

const val BACKEND_URL = BuildConfig.BACKEND_URL
const val TRAINABLE_FILENAME = "trainable.tflite"
const val QUANTIZED_FILENAME = "quantized.tflite"
const val QUANTIZED_META_FILENAME = "quantized.json"
const val BASE_WEIGHTS_FILENAME = "base_weights.bin"
const val TRAINED_WEIGHTS_FILENAME = "trained_weights.bin"
const val META_FILENAME = "meta.json"

const val FINGERPRINT_HEADER = "X-Model-Fingerprint"
const val MODEL_VERSION_HEADER = "X-Model-Version"
const val WEIGHTS_ID_HEADER = "X-Weights-ID"
const val WEIGHTS_TIMESTAMP_HEADER = "X-Weights-Timestamp"
const val SIGNATURE_HEADER = "X-Model-Signature"
const val CONTRACT_VERSION_HEADER = "X-Contract-Version"
const val NORM_PARAMS_HEADER = "X-Norm-Params"

fun modelsDir(context: Context): File = File(context.filesDir, "models")

fun modelDir(context: Context, key: String): File = File(modelsDir(context), key)

fun metaFile(context: Context, key: String): File = File(modelDir(context, key), META_FILENAME)

/** Trainable LiteRT model downloaded from the backend (has eval/train/save/restore).
 *  Carries the current global weights baked in — it is the weights source. */
fun trainableFile(context: Context, key: String): File = File(modelDir(context, key), TRAINABLE_FILENAME)

/** Int8-quantized model downloaded or produced by the backend; uploaded to the device. */
fun quantizedFile(context: Context, key: String): File = File(modelDir(context, key), QUANTIZED_FILENAME)

/** Signed fields delivered alongside the quantized model (`quantized.json`): the
 *  contract version, the norm params, and the server's ECDSA signature over the
 *  canonical bytes. Needed to assemble the device payload. */
fun quantizedMetaFile(context: Context, key: String): File = File(modelDir(context, key), QUANTIZED_META_FILENAME)

/** Baseline weights (`base_weights.bin`): the global snapshot on-device training
 *  started from, a raw LE float32 blob. Written only by on-device training. */
fun baseWeightsFile(context: Context, key: String): File = File(modelDir(context, key), BASE_WEIGHTS_FILENAME)

/** Absolute trained weights (`trained_weights.bin`), a raw LE float32 blob written
 *  only by on-device training; the upload delta is `trained − base`. */
fun trainedWeightsFile(context: Context, key: String): File = File(modelDir(context, key), TRAINED_WEIGHTS_FILENAME)

/** Downloaded artifacts are stored zstd-compressed exactly as the backend serves
 *  them (signatures cover the raw bytes); consumers decompress through the readers
 *  below rather than reading the files directly. */
internal fun zstdDecompress(bytes: ByteArray): ByteArray =
    ZstdInputStream(ByteArrayInputStream(bytes)).use { it.readBytes() }

/** Raw trainable `.tflite` bytes, decompressed. The only way to read the model. */
fun readTrainableBytes(context: Context, key: String): ByteArray =
    zstdDecompress(trainableFile(context, key).readBytes())

/** Raw quantized `.tflite` bytes, decompressed. The only way to read the model. */
fun readQuantizedBytes(context: Context, key: String): ByteArray =
    zstdDecompress(quantizedFile(context, key).readBytes())

/** Whether this app satisfies a model's `min_app_version` (dot-separated numeric
 *  parts, missing parts count as 0 — so "1.0" satisfies "1.0.0"). */
fun versionAtLeast(local: String, required: String): Boolean {
    val a = local.split('.').map { it.toIntOrNull() ?: 0 }
    val b = required.split('.').map { it.toIntOrNull() ?: 0 }
    for (i in 0 until maxOf(a.size, b.size)) {
        val x = a.getOrElse(i) { 0 }
        val y = b.getOrElse(i) { 0 }
        if (x != y) return x > y
    }
    return true
}

data class RemoteModel(
    val key: String,
    val name: String,
    val firmwareId: Int?,
    val minAppVersion: String,
    val fingerprint: String,        // architecture identity (tripwire for the version)
    val version: Int,               // hand-bumped model version; a move invalidates local state
    val contractVersion: Int,       // how the model is fed (norm-param layout + I/O signatures)
    val weightCount: Int,
    val submissionType: String,     // "raw" | "quantize": which upload path this model accepts
    val weightsVersion: String?,    // timestamp of the latest global weights (null if none)
    val weightsId: Long? = null,    // base snapshot id; set from headers when the trainable is downloaded
) {
    val trainableEndpoint: String get() = "$BACKEND_URL/model/download/trainable/$key"
    val quantizedEndpoint: String get() = "$BACKEND_URL/model/download/quantized/$key"
    fun quantizeSubmitEndpoint(weightsId: Long): String = "$BACKEND_URL/model/submit/quantize/$key/$weightsId"
    fun submitEndpoint(weightsId: Long): String = "$BACKEND_URL/model/submit/raw/$key/$weightsId"
    fun resultEndpoint(jobId: String): String = "$BACKEND_URL/model/quantize/result/$jobId"

    val appCompatible: Boolean get() = versionAtLeast(BuildConfig.VERSION_NAME, minAppVersion)

    /** Whether the quantize upload path applies; a `raw` model 404s on it. */
    val supportsQuantizeSubmit: Boolean get() = submissionType == "quantize"

    fun toJson(): JSONObject = JSONObject().apply {
        put("key", key)
        put("name", name)
        if (firmwareId != null) put("firmware_id", firmwareId) else put("firmware_id", JSONObject.NULL)
        put("min_app_version", minAppVersion)
        put("fingerprint", fingerprint)
        put("version", version)
        put("contract_version", contractVersion)
        put("weight_count", weightCount)
        put("submission_type", submissionType)
        if (weightsVersion != null) put("weights_version", weightsVersion) else put("weights_version", JSONObject.NULL)
        if (weightsId != null) put("weights_id", weightsId)
    }

    companion object {
        fun fromJson(obj: JSONObject): RemoteModel = RemoteModel(
            key = obj.getString("key"),
            name = obj.getString("name"),
            firmwareId = if (obj.isNull("firmware_id")) null else obj.getInt("firmware_id"),
            minAppVersion = obj.getString("min_app_version"),
            fingerprint = obj.getString("fingerprint"),
            version = obj.getInt("version"),
            contractVersion = obj.getInt("contract_version"),
            weightCount = obj.getInt("weight_count"),
            submissionType = obj.getString("submission_type"),
            weightsVersion = if (obj.isNull("weights_version")) null else obj.getString("weights_version"),
            weightsId = if (obj.has("weights_id") && !obj.isNull("weights_id")) obj.getLong("weights_id") else null,
        )
    }
}

fun saveModelMeta(context: Context, model: RemoteModel) {
    modelDir(context, model.key).mkdirs()
    metaFile(context, model.key).writeText(model.toJson().toString())
}

fun loadModelMeta(context: Context, key: String): RemoteModel? =
    runCatching { RemoteModel.fromJson(JSONObject(metaFile(context, key).readText())) }.getOrNull()

/** The signed fields the backend delivers with a quantized model, persisted as
 *  `quantized.json`. [signature] is null only when the server ran without a key. */
data class SignedModelMeta(
    val contractVersion: Int,
    val normParams: ByteArray,
    val signature: ByteArray?,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("contract_version", contractVersion)
        put("norm_params", Base64.getEncoder().encodeToString(normParams))
        if (signature != null) put("signature", Base64.getEncoder().encodeToString(signature))
        else put("signature", JSONObject.NULL)
    }

    companion object {
        fun fromJson(o: JSONObject): SignedModelMeta = SignedModelMeta(
            contractVersion = o.getInt("contract_version"),
            normParams = Base64.getDecoder().decode(o.getString("norm_params")),
            signature = if (o.isNull("signature")) null else Base64.getDecoder().decode(o.getString("signature")),
        )

        fun fromHeaders(connection: HttpURLConnection): SignedModelMeta = SignedModelMeta(
            contractVersion = connection.getHeaderField(CONTRACT_VERSION_HEADER)?.toInt()
                ?: error("missing $CONTRACT_VERSION_HEADER header"),
            normParams = Base64.getDecoder().decode(
                connection.getHeaderField(NORM_PARAMS_HEADER) ?: error("missing $NORM_PARAMS_HEADER header")),
            signature = connection.getHeaderField(SIGNATURE_HEADER)?.let { Base64.getDecoder().decode(it) },
        )
    }
}

fun saveSignedModelMeta(context: Context, key: String, meta: SignedModelMeta) {
    modelDir(context, key).mkdirs()
    quantizedMetaFile(context, key).writeText(meta.toJson().toString())
}

fun loadSignedModelMeta(context: Context, key: String): SignedModelMeta? =
    runCatching { SignedModelMeta.fromJson(JSONObject(quantizedMetaFile(context, key).readText())) }.getOrNull()

/** A model whose quantized variant is available locally, ready to upload to a device. */
data class LocalModel(val key: String, val displayName: String, val modelFile: File)

/** List models that have a quantized variant (the int8 model uploaded to the device). */
fun listLocalModels(context: Context): List<LocalModel> =
    modelsDir(context).listFiles()
        ?.filter { it.isDirectory }
        ?.sortedBy { it.name }
        ?.mapNotNull { dir ->
            val quantized = File(dir, QUANTIZED_FILENAME)
            if (!quantized.exists()) return@mapNotNull null
            LocalModel(dir.name, loadModelMeta(context, dir.name)?.name ?: dir.name, quantized)
        }
        ?: emptyList()

sealed interface DownloadState {
    data object Idle : DownloadState
    data object InProgress : DownloadState
    data class Done(val path: String) : DownloadState
    data class Error(val message: String) : DownloadState
}

/** Raised when the backend rejects a request because no session is present. */
class NotSignedInException : Exception("Not signed in")

private fun rateLimitMessage(connection: HttpURLConnection): String {
    val retry = connection.getHeaderField("Retry-After")
    return if (retry != null) "Rate limited; retry in ${retry}s" else "Rate limited; try again later"
}

/**
 * Run an authenticated request against [url], attaching the stored bearer token.
 * On a `401` it refreshes the access token once and retries. [configure] sets
 * the method/body (re-run per attempt); [onSuccess] reads the 2xx response.
 * `429` is surfaced as a rate-limit error with the Retry-After hint.
 */
internal suspend fun <T> authedRequest(
    context: Context,
    url: String,
    configure: (HttpURLConnection) -> Unit = {},
    onSuccess: (HttpURLConnection) -> T,
): Result<T> = withContext(Dispatchers.IO) {
    runCatching {
        var token = AuthStore.accessToken(context) ?: throw NotSignedInException()
        repeat(2) { attempt ->
            val connection = URL(url).openConnection() as HttpURLConnection
            try {
                connection.setRequestProperty("Authorization", "Bearer $token")
                configure(connection)
                val code = connection.responseCode
                when {
                    code == HttpURLConnection.HTTP_UNAUTHORIZED && attempt == 0 ->
                        token = refreshAccess(context).getOrElse { throw NotSignedInException() }
                    code in 200..299 -> return@runCatching onSuccess(connection)
                    code == 429 -> error(rateLimitMessage(connection))
                    else -> {
                        val err = connection.errorStream?.bufferedReader()?.readText().orEmpty()
                        error("HTTP $code${if (err.isNotBlank()) ": $err" else ""}")
                    }
                }
            } finally {
                connection.disconnect()
            }
        }
        throw NotSignedInException()
    }
}

suspend fun fetchModels(context: Context): Result<List<RemoteModel>> =
    authedRequest(context, "$BACKEND_URL/model/list") { connection ->
        val array = JSONArray(connection.inputStream.bufferedReader().readText())
        List(array.length()) { i -> RemoteModel.fromJson(array.getJSONObject(i)) }
    }

/**
 * Download the model's trainable artifact — the architecture with the current
 * global weights baked in — and record the snapshot it carries (`weights_id` /
 * timestamp headers) in `meta.json`. When the version or fingerprint moved
 * relative to what was stored, the local federated state (trained weights,
 * quantized artifact) is deleted first: it belongs to the superseded version.
 */
suspend fun downloadTrainable(context: Context, model: RemoteModel): Result<Unit> =
    authedRequest(context, model.trainableEndpoint) { connection ->
        val weightsId = connection.getHeaderField(WEIGHTS_ID_HEADER)?.toLong()
            ?: error("missing $WEIGHTS_ID_HEADER header")
        val version = connection.getHeaderField(MODEL_VERSION_HEADER)?.toInt() ?: model.version
        val fingerprint = connection.getHeaderField(FINGERPRINT_HEADER) ?: model.fingerprint

        val previous = loadModelMeta(context, model.key)
        if (previous != null && (previous.version != version || previous.fingerprint != fingerprint)) {
            baseWeightsFile(context, model.key).delete()
            trainedWeightsFile(context, model.key).delete()
            quantizedFile(context, model.key).delete()
            quantizedMetaFile(context, model.key).delete()
        }

        val dest = trainableFile(context, model.key)
        dest.parentFile?.mkdirs()
        connection.inputStream.use { input ->
            dest.outputStream().use { output -> input.copyTo(output) }
        }
        saveModelMeta(context, model.copy(
            version = version,
            fingerprint = fingerprint,
            weightsId = weightsId,
            weightsVersion = connection.getHeaderField(WEIGHTS_TIMESTAMP_HEADER) ?: model.weightsVersion,
        ))
    }

private fun storeSignedModel(context: Context, key: String, connection: HttpURLConnection) {
    val meta = SignedModelMeta.fromHeaders(connection)
    val dest = quantizedFile(context, key)
    dest.parentFile?.mkdirs()
    connection.inputStream.use { input ->
        dest.outputStream().use { output -> input.copyTo(output) }
    }
    saveSignedModelMeta(context, key, meta)
}

/** Download the current global quantized artifact plus its signed fields. */
suspend fun downloadQuantized(context: Context, model: RemoteModel): Result<Unit> =
    authedRequest(context, model.quantizedEndpoint) { connection ->
        storeSignedModel(context, model.key, connection)
    }

const val QUANTIZE_POLL_INTERVAL_MS = 1000L
const val QUANTIZE_POLL_TIMEOUT_MS = 120_000L

private fun HttpURLConnection.sendWeights(body: ByteArray) {
    requestMethod = "POST"
    doOutput = true
    setRequestProperty("Content-Type", "application/octet-stream")
    outputStream.use { it.write(body) }
}

/**
 * Poll the quantize-result endpoint until the worker has produced the int8
 * model, storing it (with its signed header fields) as the model's quantized
 * artifact. `202` means still pending/running (keep waiting), `200` carries the
 * tflite, anything else (e.g. `422` failed) is an error. A `401` mid-poll
 * triggers a token refresh and the poll continues.
 */
suspend fun pollQuantizeResult(context: Context, url: String, key: String): Result<Unit> =
    withContext(Dispatchers.IO) {
        runCatching {
            var token = AuthStore.accessToken(context) ?: throw NotSignedInException()
            val deadline = System.currentTimeMillis() + QUANTIZE_POLL_TIMEOUT_MS
            var done = false
            while (!done) {
                val connection = URL(url).openConnection() as HttpURLConnection
                val pending = try {
                    connection.setRequestProperty("Authorization", "Bearer $token")
                    when (val code = connection.responseCode) {
                        HttpURLConnection.HTTP_OK -> {
                            storeSignedModel(context, key, connection)
                            done = true
                            false
                        }
                        HttpURLConnection.HTTP_ACCEPTED -> true
                        HttpURLConnection.HTTP_UNAUTHORIZED -> {
                            token = refreshAccess(context).getOrElse { throw NotSignedInException() }
                            true
                        }
                        429 -> error(rateLimitMessage(connection))
                        else -> {
                            val err = connection.errorStream?.bufferedReader()?.readText().orEmpty()
                            error("HTTP $code: $err")
                        }
                    }
                } finally {
                    connection.disconnect()
                }
                if (pending) {
                    if (System.currentTimeMillis() >= deadline) error("quantization timed out")
                    delay(QUANTIZE_POLL_INTERVAL_MS)
                }
            }
        }
    }

/**
 * Upload the locally trained update as a weight delta (`trained − global`, LE
 * float32 body, base `weights_id` in the URL) as a federated update, then poll
 * for the personalized signed int8 artifact and store it as the model's
 * quantized artifact. Requires on-device training to have produced the weight blobs.
 */
suspend fun uploadAndQuantize(context: Context, model: RemoteModel): Result<Unit> =
    withContext(Dispatchers.IO) {
        runCatching {
            val delta = loadTrainedDelta(context, model.key)
                ?: error("no locally trained weights for '${model.key}'")
            val weightsId = loadModelMeta(context, model.key)?.weightsId
                ?: error("model '${model.key}' not downloaded")
            val jobId = authedRequest(
                context, model.quantizeSubmitEndpoint(weightsId),
                configure = { it.sendWeights(delta.leBytes()) },
                onSuccess = { JSONObject(it.inputStream.bufferedReader().readText()).getString("job_id") },
            ).getOrThrow()
            pollQuantizeResult(context, model.resultEndpoint(jobId), model.key).getOrThrow()
        }
    }

/**
 * Submit-only federated update: the trained weight delta (`trained − global`) is
 * uploaded for aggregation and nothing comes back (validation happens server-side,
 * silently). Returns the submission id.
 */
suspend fun submitOnly(context: Context, model: RemoteModel): Result<Long> =
    withContext(Dispatchers.IO) {
        runCatching {
            val delta = loadTrainedDelta(context, model.key)
                ?: error("no locally trained weights for '${model.key}'")
            val weightsId = loadModelMeta(context, model.key)?.weightsId
                ?: error("model '${model.key}' not downloaded")
            authedRequest(
                context, model.submitEndpoint(weightsId),
                configure = { it.sendWeights(delta.leBytes()) },
                onSuccess = { JSONObject(it.inputStream.bufferedReader().readText()).getLong("submission_id") },
            ).getOrThrow()
        }
    }
