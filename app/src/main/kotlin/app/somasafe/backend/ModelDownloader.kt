package app.somasafe.backend

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

fun modelsDir(context: Context): File = File(context.filesDir, "models")

fun modelDir(context: Context, key: String): File = File(modelsDir(context), key)

fun metaFile(context: Context, key: String): File = File(modelDir(context, key), META_FILENAME)

/** Trainable LiteRT model downloaded from the backend (has eval/train/save/restore). */
fun trainableFile(context: Context, key: String): File = File(modelDir(context, key), TRAINABLE_FILENAME)

/** Int8-quantized model produced by the backend from extracted weights; uploaded to the device. */
fun quantizedFile(context: Context, key: String): File = File(modelDir(context, key), QUANTIZED_FILENAME)

/** Trainable weights extracted on-device, in the JSON shape the /quantize endpoint expects. */
fun weightsFile(context: Context, key: String): File = File(modelDir(context, key), WEIGHTS_FILENAME)

data class RemoteModel(
    val key: String,
    val name: String,
    val lastUpdated: String,
    val purpose: String,
    val firmwareId: Int?,
    val appVersion: String,
    val modelId: Int,
) {
    val trainableEndpoint: String get() = "$BACKEND_URL/model/trainable/$key/$modelId"
    val quantizedEndpoint: String get() = "$BACKEND_URL/model/quantized/$key/$modelId"
    val quantizeEndpoint: String get() = "$BACKEND_URL/model/quantize/$key/$modelId"
    fun resultEndpoint(jobId: String): String = "$BACKEND_URL/model/quantize/result/$jobId"

    fun toJson(): JSONObject = JSONObject().apply {
        put("key", key)
        put("name", name)
        put("last_updated", lastUpdated)
        put("purpose", purpose)
        if (firmwareId != null) put("firmware_id", firmwareId) else put("firmware_id", JSONObject.NULL)
        put("app_version", appVersion)
        put("model_id", modelId)
    }

    companion object {
        fun fromJson(obj: JSONObject): RemoteModel = RemoteModel(
            key = obj.getString("key"),
            name = obj.getString("name"),
            lastUpdated = obj.getString("last_updated"),
            purpose = obj.getString("purpose"),
            firmwareId = if (obj.isNull("firmware_id")) null else obj.getInt("firmware_id"),
            appVersion = obj.getString("app_version"),
            modelId = obj.getInt("model_id"),
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
private suspend fun <T> authedRequest(
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
 * Send the already-extracted `weights.json` to the backend, then poll for the
 * resulting int8 model and store it as `quantized.tflite`. Fails if weights
 * have not been extracted yet (weight extraction needs the LiteRT runtime and
 * lives in the model module).
 */
suspend fun downloadQuantized(context: Context, model: RemoteModel): Result<Unit> =
    withContext(Dispatchers.IO) {
        runCatching {
            val weights = weightsFile(context, model.key)
            require(weights.exists()) { "weights not extracted yet" }
            val jobId = submitQuantize(context, model.quantizeEndpoint, weights.readBytes()).getOrThrow()
            pollQuantizeResult(context, model.resultEndpoint(jobId), quantizedFile(context, model.key))
                .getOrThrow()
        }
    }
