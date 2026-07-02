package app.somasafe.backend.data

import android.content.Context
import app.somasafe.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

const val BACKEND_URL = BuildConfig.BACKEND_URL
const val TRAINABLE_FILENAME = "trainable.tflite"
const val QUANTIZED_FILENAME = "quantized.tflite"
const val WEIGHTS_FILENAME = "weights.json"
const val META_FILENAME = "meta.json"

const val FINGERPRINT_HEADER = "X-Model-Fingerprint"
const val WEIGHTS_ID_HEADER = "X-Weights-ID"
const val WEIGHTS_TIMESTAMP_HEADER = "X-Weights-Timestamp"

fun modelsDir(context: Context): File = File(context.filesDir, "models")

fun modelDir(context: Context, key: String): File = File(modelsDir(context), key)

fun metaFile(context: Context, key: String): File = File(modelDir(context, key), META_FILENAME)

/** Trainable LiteRT model downloaded from the backend (has eval/train/save/restore). */
fun trainableFile(context: Context, key: String): File = File(modelDir(context, key), TRAINABLE_FILENAME)

/** Int8-quantized model produced by the backend from extracted weights; uploaded to the device. */
fun quantizedFile(context: Context, key: String): File = File(modelDir(context, key), QUANTIZED_FILENAME)

/** Local source of truth for the model's weights (`weights.json`): the flat
 *  parameters plus the version metadata (`weights_id`, version, fingerprint) the
 *  /quantize endpoint needs. Pulled from the backend, not extracted from the model. */
fun weightsFile(context: Context, key: String): File = File(modelDir(context, key), WEIGHTS_FILENAME)

data class RemoteModel(
    val key: String,
    val name: String,
    val purpose: String,
    val firmwareId: Int?,
    val appVersion: String,
    val fingerprint: String,        // architecture identity (weight-compatibility key)
    val version: String,            // human-facing display label
    val weightsVersion: String?,    // timestamp of the latest global weights (null if none)
) {
    val trainableEndpoint: String get() = "$BACKEND_URL/model/download/trainable/$key"
    val quantizedEndpoint: String get() = "$BACKEND_URL/model/download/quantized/$key"
    val quantizeEndpoint: String get() = "$BACKEND_URL/model/quantize/$key"
    val weightsEndpoint: String get() = "$BACKEND_URL/model/weights/$key"
    val normEndpoint: String get() = "$BACKEND_URL/model/norm/$key"
    fun resultEndpoint(jobId: String): String = "$BACKEND_URL/model/quantize/result/$jobId"

    fun toJson(): JSONObject = JSONObject().apply {
        put("key", key)
        put("name", name)
        put("purpose", purpose)
        if (firmwareId != null) put("firmware_id", firmwareId) else put("firmware_id", JSONObject.NULL)
        put("app_version", appVersion)
        put("fingerprint", fingerprint)
        put("version", version)
        if (weightsVersion != null) put("weights_version", weightsVersion) else put("weights_version", JSONObject.NULL)
    }

    companion object {
        fun fromJson(obj: JSONObject): RemoteModel = RemoteModel(
            key = obj.getString("key"),
            name = obj.getString("name"),
            purpose = obj.getString("purpose"),
            firmwareId = if (obj.isNull("firmware_id")) null else obj.getInt("firmware_id"),
            appVersion = obj.getString("app_version"),
            fingerprint = obj.getString("fingerprint"),
            version = obj.getString("version"),
            weightsVersion = if (obj.isNull("weights_version")) null else obj.getString("weights_version"),
        )
    }
}

fun saveModelMeta(context: Context, model: RemoteModel) {
    modelDir(context, model.key).mkdirs()
    metaFile(context, model.key).writeText(model.toJson().toString())
}

fun loadModelMeta(context: Context, key: String): RemoteModel? =
    runCatching { RemoteModel.fromJson(JSONObject(metaFile(context, key).readText())) }.getOrNull()

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

suspend fun downloadModel(context: Context, url: String, dest: File): Result<Unit> =
    authedRequest(context, url) { connection ->
        dest.parentFile?.mkdirs()
        connection.inputStream.use { input ->
            dest.outputStream().use { output -> input.copyTo(output) }
        }
    }

const val QUANTIZE_POLL_INTERVAL_MS = 1000L
const val QUANTIZE_POLL_TIMEOUT_MS = 120_000L

/**
 * POST a JSON weights body to the (async) /quantize endpoint and return the
 * job id to poll. The gateway enqueues the work and replies `202 {job_id}`.
 */
suspend fun submitQuantize(context: Context, url: String, jsonBody: ByteArray): Result<String> =
    authedRequest(
        context, url,
        configure = { connection ->
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use { it.write(jsonBody) }
        },
        onSuccess = { connection ->
            JSONObject(connection.inputStream.bufferedReader().readText()).getString("job_id")
        },
    )

/**
 * Poll the result endpoint until the worker has produced the int8 model,
 * streaming it into [dest]. `202` means still pending/running (keep waiting),
 * `200` carries the tflite, anything else (e.g. `422` failed) is an error.
 * A `401` mid-poll triggers a token refresh and the poll continues.
 */
suspend fun pollQuantizeResult(context: Context, url: String, dest: File): Result<Unit> =
    withContext(Dispatchers.IO) {
        runCatching {
            dest.parentFile?.mkdirs()
            var token = AuthStore.accessToken(context) ?: throw NotSignedInException()
            val deadline = System.currentTimeMillis() + QUANTIZE_POLL_TIMEOUT_MS
            var done = false
            while (!done) {
                val connection = URL(url).openConnection() as HttpURLConnection
                val pending = try {
                    connection.setRequestProperty("Authorization", "Bearer $token")
                    when (val code = connection.responseCode) {
                        HttpURLConnection.HTTP_OK -> {
                            connection.inputStream.use { input ->
                                dest.outputStream().use { output -> input.copyTo(output) }
                            }
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
 * Submit the locally stored weights to the backend, then poll for the resulting
 * int8 model and store it as `quantized.tflite`. The submission carries the
 * `weights_id` recorded in `weights.json` (the snapshot the parameters derive
 * from), so aggregation knows the base each update was trained against. Fails if
 * weights have not been downloaded yet.
 */
suspend fun downloadQuantized(context: Context, model: RemoteModel): Result<Unit> =
    withContext(Dispatchers.IO) {
        runCatching {
            val weights = loadWeights(context, model.key) ?: error("weights not downloaded yet")
            val body = JSONObject()
                .put("parameters", JSONArray().apply { weights.parameters.forEach { put(it.toDouble()) } })
                .put("weights_id", weights.weightsId)
                .toString().toByteArray()
            val jobId = submitQuantize(context, model.quantizeEndpoint, body).getOrThrow()
            pollQuantizeResult(context, model.resultEndpoint(jobId), quantizedFile(context, model.key))
                .getOrThrow()
        }
    }
