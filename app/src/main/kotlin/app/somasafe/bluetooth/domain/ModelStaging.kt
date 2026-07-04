package app.somasafe.bluetooth.domain

import android.content.Context
import android.util.Log
import app.somasafe.backend.data.quantizedFile
import app.somasafe.backend.data.trainableFile
import app.somasafe.bluetooth.data.BleConnection
import app.somasafe.training.domain.LiteRtModel
import app.somasafe.training.domain.TensorInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import app.somasafe.bluetooth.data.SomaSafeUuids

private const val TAG = "ModelStaging"

sealed interface ModelState {
    data object None : ModelState
    data object Loading : ModelState
    data class Loaded(val key: String, val featuresLen: Int, val scoreLen: Int) : ModelState
    data class Error(val message: String) : ModelState
}

/**
 * Reads a stored quantized model, introspects its input/output tensor sizes, and
 * stages it onto the connected device over the ML client-buffer service.
 */
class ModelStaging(
    private val context: Context,
    private val connection: BleConnection,
    private val scope: CoroutineScope,
) {
    private val _model = MutableStateFlow<ModelState>(ModelState.None)
    val model = _model.asStateFlow()

    /** Read, introspect and stage the model stored under [key], then mark it ready. */
    fun loadModel(key: String, onStatus: (String) -> Unit) {
        if (_model.value is ModelState.Loading) return
        scope.launch {
            _model.value = ModelState.Loading
            try {
                // The staged file is an opaque signed payload (norm params + tflite),
                // uploaded verbatim; introspect the trainable model for the tensor sizes.
                val bytes = withContext(Dispatchers.IO) { quantizedFile(context, key).readBytes() }
                val (featuresLen, scoreLen) =
                    withContext(Dispatchers.Default) { introspect(trainableFile(context, key).absolutePath) }

                val buffer = ClientBuffer(connection, SomaSafeUuids.ML_SVC)
                buffer.start()
                try {
                    buffer.upload(bytes)
                    buffer.ready()
                } finally {
                    buffer.stop()
                }

                _model.value = ModelState.Loaded(key, featuresLen, scoreLen)
                onStatus("Model \"$key\" loaded (${bytes.size} bytes)")
            } catch (e: Exception) {
                Log.e(TAG, "model load failed", e)
                _model.value = ModelState.Error(e.message ?: "load failed")
            }
        }
    }

    private fun introspect(path: String): Pair<Int, Int> = LiteRtModel(path).use { model ->
        // The device echoes the raw feature vector + int8 score; the trainable model's
        // `eval` signature declares the same input/output shapes as the staged int8 model.
        val signature = model.describe().signatures.firstOrNull { it.key == "eval" }
            ?: error("model has no 'eval' signature")
        val input = signature.inputs.firstOrNull() ?: error("model has no input tensor")
        val output = signature.outputs.firstOrNull() ?: error("model has no output tensor")
        // Features stream back as float32; the score stays int8 (1 byte/element).
        elementCount(input) * Float.SIZE_BYTES to elementCount(output)
    }

    private fun elementCount(tensor: TensorInfo): Int {
        require(tensor.shape.isNotEmpty() && tensor.shape.all { it > 0 }) {
            "tensor '${tensor.name}' has a dynamic or unknown shape"
        }
        return tensor.shape.fold(1) { acc, dim -> acc * dim }
    }
}
