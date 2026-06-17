package app.somasafe.capture

import android.content.Context
import android.util.Log
import app.somasafe.backend.MODEL_FILENAME
import app.somasafe.backend.modelDir
import app.somasafe.bluetooth.BleConnection
import app.somasafe.device.ClientBuffer
import app.somasafe.device.ML_ERROR_NAMES
import app.somasafe.device.MlResult
import app.somasafe.device.MlService
import app.somasafe.device.PpgSample
import app.somasafe.device.PpgService
import app.somasafe.model.LiteRtModel
import app.somasafe.model.TensorInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

private const val TAG = "CaptureController"

sealed interface ModelState {
    data object None : ModelState
    data object Loading : ModelState
    data class Loaded(val key: String, val featuresLen: Int, val scoreLen: Int) : ModelState
    data class Error(val message: String) : ModelState
}

sealed interface CaptureState {
    data object Idle : CaptureState
    data class Running(val groupId: Long) : CaptureState
}

/**
 * Drives a capture session against a connected device: stage a model, then
 * start/stop collecting PPG windows and ML results, merging them by sequence
 * number into the [CaptureDatabase]. Scoped to a screen via [scope]; not
 * retained across configuration changes.
 */
class CaptureController(
    private val context: Context,
    private val connection: BleConnection,
    private val scope: kotlinx.coroutines.CoroutineScope,
) {
    private val dao = CaptureDatabase.get(context).captureDao()

    /** Per-group rollup for the history UI; updates live as rows are written. */
    val groupSummaries = dao.groupSummaries()

    private val _model = MutableStateFlow<ModelState>(ModelState.None)
    val model = _model.asStateFlow()

    private val _capture = MutableStateFlow<CaptureState>(CaptureState.Idle)
    val capture = _capture.asStateFlow()

    private val _status = MutableStateFlow<String?>(null)
    val status = _status.asStateFlow()

    private val dbMutex = Mutex()
    private var captureJob: Job? = null

    /** Read, introspect and stage the model stored under [key], then mark it ready. */
    fun loadModel(key: String) {
        if (_model.value is ModelState.Loading) return
        scope.launch {
            _model.value = ModelState.Loading
            try {
                val file = File(modelDir(context, key), MODEL_FILENAME)
                val bytes = withContext(Dispatchers.IO) { file.readBytes() }
                val (featuresLen, scoreLen) =
                    withContext(Dispatchers.Default) { introspect(file.absolutePath) }

                val buffer = ClientBuffer(connection)
                buffer.upload(bytes)
                buffer.ready()

                _model.value = ModelState.Loaded(key, featuresLen, scoreLen)
                _status.value = "Model \"$key\" loaded (${bytes.size} bytes)"
            } catch (e: Exception) {
                Log.e(TAG, "model load failed", e)
                _model.value = ModelState.Error(e.message ?: "load failed")
            }
        }
    }

    fun startCapture() {
        if (_capture.value is CaptureState.Running) return
        val loaded = _model.value as? ModelState.Loaded ?: run {
            _status.value = "Load a model before capturing"
            return
        }

        captureJob = scope.launch {
            val ppg: PpgService
            val ml: MlService
            try {
                ppg = PpgService(connection)
                ml = MlService(connection, loaded.featuresLen, loaded.scoreLen)
            } catch (e: Exception) {
                Log.e(TAG, "failed to open device services", e)
                _status.value = e.message ?: "failed to open device services"
                return@launch
            }

            val groupId = dao.insertGroup(SampleGroup(startedAt = System.currentTimeMillis()))
            _capture.value = CaptureState.Running(groupId)
            _status.value = "Capturing…"

            try {
                launch { ppg.samples().collect { applyPpg(groupId, it) } }
                launch { ml.results().collect { applyResult(groupId, it) } }
                launch { ml.errors().collect { onDeviceError(it) } }
                awaitCancellation()
            } finally {
                withContext(NonCancellable) {
                    dao.endGroup(groupId, System.currentTimeMillis())
                }
                _capture.value = CaptureState.Idle
                _status.value = "Capture stopped"
            }
        }
    }

    fun stopCapture() {
        captureJob?.cancel()
        captureJob = null
    }

    private suspend fun applyPpg(groupId: Long, sample: PpgSample) = dbMutex.withLock {
        val existing = dao.findSample(groupId, sample.sequenceN)
        if (existing == null) {
            dao.insertSample(
                Sample(
                    groupId = groupId,
                    sequenceN = sample.sequenceN,
                    receivedAt = System.currentTimeMillis(),
                    deviceStartMs = sample.deviceStartMs,
                    deviceEndMs = sample.deviceEndMs,
                    ppg = sample.ppg,
                    acc = sample.acc,
                )
            )
        } else {
            dao.updateSample(
                existing.copy(
                    deviceStartMs = sample.deviceStartMs,
                    deviceEndMs = sample.deviceEndMs,
                    ppg = sample.ppg,
                    acc = sample.acc,
                )
            )
        }
    }

    private suspend fun applyResult(groupId: Long, result: MlResult) = dbMutex.withLock {
        val existing = dao.findSample(groupId, result.sequenceN)
        if (existing == null) {
            dao.insertSample(
                Sample(
                    groupId = groupId,
                    sequenceN = result.sequenceN,
                    receivedAt = System.currentTimeMillis(),
                    features = result.features,
                    score = result.score,
                )
            )
        } else {
            dao.updateSample(existing.copy(features = result.features, score = result.score))
        }
    }

    private fun onDeviceError(code: Int) {
        val name = ML_ERROR_NAMES[code] ?: "UNKNOWN"
        _status.value = "Device ML error: $name ($code)"
    }

    private fun introspect(path: String): Pair<Int, Int> = LiteRtModel(path).use { model ->
        val signature = model.describe().signatures.firstOrNull()
            ?: error("model exposes no signatures")
        val input = signature.inputs.firstOrNull() ?: error("model has no input tensor")
        val output = signature.outputs.firstOrNull() ?: error("model has no output tensor")
        tensorBytes(input) to tensorBytes(output)
    }

    // The device sends int8 tensors, so element count == byte count.
    private fun tensorBytes(tensor: TensorInfo): Int {
        require(tensor.shape.isNotEmpty() && tensor.shape.all { it > 0 }) {
            "tensor '${tensor.name}' has a dynamic or unknown shape"
        }
        return tensor.shape.fold(1) { acc, dim -> acc * dim }
    }
}
