package app.somasafe.backend

import android.content.Context
import app.somasafe.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

const val BACKEND_URL = BuildConfig.BACKEND_URL

fun modelsDir(context: Context): File = File(context.filesDir, "models")

enum class ModelVariant(
    val label: String,
    val endpoint: String,
    val filename: String,
) {
    TRAINABLE("Trainable", "$BACKEND_URL/model/trainable", "trainable.tflite"),
    QUANTIZED("Quantized", "$BACKEND_URL/model/quantized", "quantized.tflite"),
}

sealed interface DownloadState {
    data object Idle : DownloadState
    data object InProgress : DownloadState
    data class Done(val path: String) : DownloadState
    data class Error(val message: String) : DownloadState
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
