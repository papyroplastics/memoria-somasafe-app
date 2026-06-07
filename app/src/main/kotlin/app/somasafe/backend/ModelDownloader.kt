package app.somasafe.backend

import android.content.Context
import app.somasafe.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

const val BACKEND_URL = BuildConfig.BACKEND_URL

fun modelsDir(context: Context): File = File(context.filesDir, "models")

data class ModelInfo(
    val key: String,
    val name: String,
    val lastUpdated: String,
    val purpose: String,
) {
    val endpoint: String get() = "$BACKEND_URL/model/$key"
    val filename: String get() = "$key.tflite"
}

sealed interface DownloadState {
    data object Idle : DownloadState
    data object InProgress : DownloadState
    data class Done(val path: String) : DownloadState
    data class Error(val message: String) : DownloadState
}

suspend fun fetchModels(): Result<List<ModelInfo>> =
    withContext(Dispatchers.IO) {
        runCatching {
            val connection = URL("$BACKEND_URL/models").openConnection() as HttpURLConnection
            try {
                connection.connect()
                val code = connection.responseCode
                if (code != HttpURLConnection.HTTP_OK) error("HTTP $code")
                val body = connection.inputStream.bufferedReader().readText()
                val array = JSONArray(body)
                List(array.length()) { i ->
                    val obj = array.getJSONObject(i)
                    ModelInfo(
                        key = obj.getString("key"),
                        name = obj.getString("name"),
                        lastUpdated = obj.getString("last_updated"),
                        purpose = obj.getString("purpose"),
                    )
                }
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
