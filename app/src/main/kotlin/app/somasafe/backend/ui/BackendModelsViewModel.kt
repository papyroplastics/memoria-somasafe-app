package app.somasafe.backend.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.somasafe.backend.data.DownloadState
import app.somasafe.backend.data.RemoteFirmware
import app.somasafe.backend.data.RemoteModel
import app.somasafe.backend.data.WeightsStatus
import app.somasafe.backend.data.downloadFirmware
import app.somasafe.backend.data.downloadQuantized
import app.somasafe.backend.data.downloadTrainable
import app.somasafe.backend.data.fetchFirmwareVersions
import app.somasafe.backend.data.fetchModels
import app.somasafe.backend.data.listLocalFirmware
import app.somasafe.backend.data.loadModelMeta
import app.somasafe.backend.data.quantizedFile
import app.somasafe.backend.data.submitOnly
import app.somasafe.backend.data.trainableFile
import app.somasafe.backend.data.uploadAndQuantize
import app.somasafe.backend.data.weightsStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface ModelListState {
    data object Loading : ModelListState
    data class Loaded(val models: List<RemoteModel>) : ModelListState
    data class Error(val message: String) : ModelListState
}

sealed interface FirmwareListState {
    data object Loading : FirmwareListState
    data class Loaded(val versions: List<RemoteFirmware>) : FirmwareListState
    data class Error(val message: String) : FirmwareListState
}

/**
 * Holds the Backend tab's model and firmware lists and their per-item
 * download/upload state. Scoped to the Backend destination's nav entry so the
 * list fetches run once per launch (retained across tab switches) rather than
 * on every re-entry; [refresh] reloads on demand. The lists are intentionally
 * left stale until refreshed after new downloads land.
 */
class BackendModelsViewModel(app: Application) : AndroidViewModel(app) {
    private val context get() = getApplication<Application>()

    var listState by mutableStateOf<ModelListState>(ModelListState.Loading)
        private set
    var firmwareListState by mutableStateOf<FirmwareListState>(FirmwareListState.Loading)
        private set
    var localFirmwareVersions by mutableStateOf<Set<String>>(emptySet())
        private set
    var refreshing by mutableStateOf(false)
        private set

    val firmwareDownloadStates = mutableStateMapOf<String, DownloadState>()
    val downloadStates = mutableStateMapOf<String, DownloadState>()
    val quantizedStates = mutableStateMapOf<String, DownloadState>()
    val uploadStates = mutableStateMapOf<String, DownloadState>()
    val submitStates = mutableStateMapOf<String, DownloadState>()
    val localMetas = mutableStateMapOf<String, RemoteModel>()
    val weightsStatuses = mutableStateMapOf<String, WeightsStatus>()

    init { refresh() }

    private suspend fun refreshLocal(key: String) = withContext(Dispatchers.IO) {
        loadModelMeta(context, key)?.let { localMetas[key] = it }
        weightsStatuses[key] = weightsStatus(context, key)
    }

    private suspend fun refreshLocalFirmware() = withContext(Dispatchers.IO) {
        localFirmwareVersions = listLocalFirmware(context).map { it.version }.toSet()
    }

    fun refresh() {
        viewModelScope.launch {
            refreshing = true
            val result = fetchModels(context).fold(
                onSuccess = { ModelListState.Loaded(it) },
                onFailure = { ModelListState.Error(it.message ?: "Unknown error") },
            )
            listState = result
            (result as? ModelListState.Loaded)?.models?.forEach { refreshLocal(it.key) }
            firmwareListState = fetchFirmwareVersions(context).fold(
                onSuccess = { FirmwareListState.Loaded(it) },
                onFailure = { FirmwareListState.Error(it.message ?: "Unknown error") },
            )
            refreshLocalFirmware()
            refreshing = false
        }
    }

    fun downloadFirmwareFor(firmware: RemoteFirmware) {
        viewModelScope.launch {
            firmwareDownloadStates[firmware.version] = DownloadState.InProgress
            firmwareDownloadStates[firmware.version] = downloadFirmware(context, firmware).fold(
                onSuccess = {
                    refreshLocalFirmware()
                    DownloadState.Done(firmware.version)
                },
                onFailure = { DownloadState.Error(it.message ?: "Unknown error") },
            )
        }
    }

    fun download(model: RemoteModel) {
        viewModelScope.launch {
            downloadStates[model.key] = DownloadState.InProgress
            downloadStates[model.key] = downloadTrainable(context, model).fold(
                onSuccess = {
                    refreshLocal(model.key)
                    DownloadState.Done(trainableFile(context, model.key).absolutePath)
                },
                onFailure = { DownloadState.Error(it.message ?: "Unknown error") },
            )
        }
    }

    fun downloadQuantizedFor(model: RemoteModel) {
        viewModelScope.launch {
            quantizedStates[model.key] = DownloadState.InProgress
            quantizedStates[model.key] = downloadQuantized(context, model).fold(
                onSuccess = { DownloadState.Done(quantizedFile(context, model.key).absolutePath) },
                onFailure = { DownloadState.Error(it.message ?: "Unknown error") },
            )
        }
    }

    fun uploadQuantize(model: RemoteModel) {
        viewModelScope.launch {
            uploadStates[model.key] = DownloadState.InProgress
            uploadStates[model.key] = uploadAndQuantize(context, model).fold(
                onSuccess = { DownloadState.Done(quantizedFile(context, model.key).absolutePath) },
                onFailure = { DownloadState.Error(it.message ?: "Unknown error") },
            )
        }
    }

    fun submit(model: RemoteModel) {
        viewModelScope.launch {
            submitStates[model.key] = DownloadState.InProgress
            submitStates[model.key] = submitOnly(context, model).fold(
                onSuccess = { DownloadState.Done("submission #$it") },
                onFailure = { DownloadState.Error(it.message ?: "Unknown error") },
            )
        }
    }
}
