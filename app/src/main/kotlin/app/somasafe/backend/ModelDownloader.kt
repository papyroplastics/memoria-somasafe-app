package app.somasafe.backend

import android.content.Context
import app.somasafe.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

const val BACKEND_URL = BuildConfig.BACKEND_URL
const val MODEL_FILENAME = "model.tflite"
const val META_FILENAME = "meta.json"

fun modelsDir(context: Context): File = File(context.filesDir, "models")

fun modelDir(context: Context, key: String): File = File(modelsDir(context), key)

fun metaFile(context: Context, key: String): File = File(modelDir(context, key), META_FILENAME)

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

sealed interface DownloadState {
    data object Idle : DownloadState
    data object InProgress : DownloadState
    data class Done(val path: String) : DownloadState
    data class Error(val message: String) : DownloadState
}

suspend fun fetchModels(): Result<List<RemoteModel>> =
    withContext(Dispatchers.IO) {
        runCatching {
            val connection = URL("$BACKEND_URL/model/list").openConnection() as HttpURLConnection
            try {
                connection.connect()
                val code = connection.responseCode
                if (code != HttpURLConnection.HTTP_OK) error("HTTP $code")
                val body = connection.inputStream.bufferedReader().readText()
                val array = JSONArray(body)
                List(array.length()) { i -> RemoteModel.fromJson(array.getJSONObject(i)) }
            } finally {
                connection.disconnect()
            }
        }
    }

suspend fun downloadModel(url: String, dest: File): Result<Unit> =
    withContext(Dispatchers.IO) {
        runCatching {
            dest.parentFile?.mkdirs()
            val connection = URL(url).openConnection() as HttpURLConnection
            try {
                connection.connect()
                val code = connection.responseCode
                if (code != HttpURLConnection.HTTP_OK) error("HTTP $code")
                connection.inputStream.use { input ->
                    dest.outputStream().use { output -> input.copyTo(output) }
                }
                Unit
            } finally {
                connection.disconnect()
            }
        }
    }
