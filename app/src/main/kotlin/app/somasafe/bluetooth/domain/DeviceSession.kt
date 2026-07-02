package app.somasafe.bluetooth.domain

import android.content.Context
import android.util.Log
import app.somasafe.bluetooth.data.BleConnection
import app.somasafe.capture.data.CaptureRepository
import app.somasafe.capture.data.loadDemographics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "DeviceSession"

sealed interface CaptureState {
    data object Idle : CaptureState
    data class Running(val groupId: Long) : CaptureState
}

/**
 * Drives a connected device for the duration of a screen: ownership attestation
 * ([Attestation]), staging a model ([ModelStaging]), and running a capture session
 * — collecting PPG windows + ML results and persisting them via [CaptureRepository].
 * Scoped to a screen via [scope]; not retained across configuration changes.
 */
class DeviceSession(
    private val context: Context,
    private val connection: BleConnection,
    private val scope: CoroutineScope,
) {
    private val repository = CaptureRepository(context)
    private val attestation = Attestation(context, connection, scope)
    private val staging = ModelStaging(context, connection, scope)

    val model = staging.model
    val attest = attestation.attest
    val ownedDevices = attestation.ownedDevices
    val groupSummaries = repository.groupSummaries()

    private val _capture = MutableStateFlow<CaptureState>(CaptureState.Idle)
    val capture = _capture.asStateFlow()

    private val _status = MutableStateFlow<String?>(null)
    val status = _status.asStateFlow()

    private var captureJob: Job? = null

    fun loadModel(key: String) = staging.loadModel(key) { _status.value = it }

    fun attestDevice() = attestation.attestDevice { _status.value = it }

    fun startCapture() {
        if (_capture.value is CaptureState.Running) return
        val loaded = model.value as? ModelState.Loaded ?: run {
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

            val static = loadDemographics(context)?.toBytes()
            val groupId = repository.startGroup(System.currentTimeMillis(), static)
            _capture.value = CaptureState.Running(groupId)
            _status.value = "Capturing…"

            try {
                launch { ppg.samples().collect { repository.mergePpg(groupId, it) } }
                launch { ml.results().collect { repository.mergeResult(groupId, it) } }
                launch { ml.errors().collect { onDeviceError(it) } }
                awaitCancellation()
            } finally {
                withContext(NonCancellable) {
                    repository.endGroup(groupId, System.currentTimeMillis())
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

    private fun onDeviceError(code: Int) {
        val name = ML_ERROR_NAMES[code] ?: "UNKNOWN"
        _status.value = "Device ML error: $name ($code)"
    }
}
