package app.somasafe.backend.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.somasafe.backend.data.DownloadState
import app.somasafe.backend.data.RemoteModel
import app.somasafe.backend.data.TRAINABLE_FILENAME
import app.somasafe.backend.data.WeightsStatus
import app.somasafe.backend.data.downloadModel
import app.somasafe.backend.data.downloadQuantized
import app.somasafe.backend.data.downloadWeights
import app.somasafe.backend.data.fetchModels
import app.somasafe.backend.data.loadModelMeta
import app.somasafe.backend.data.modelDir
import app.somasafe.backend.data.quantizedFile
import app.somasafe.backend.data.saveModelMeta
import app.somasafe.backend.data.weightsFile
import app.somasafe.backend.data.weightsStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

sealed interface ModelListState {
    data object Loading : ModelListState
    data class Loaded(val models: List<RemoteModel>) : ModelListState
    data class Error(val message: String) : ModelListState
}

/**
 * Holds the Backend tab's model list and per-model download/weights/quantize
 * state. Scoped to the Backend destination's nav entry so the `/model/list`
 * fetch runs once per launch (retained across tab switches) rather than on every
 * re-entry; [refresh] reloads on demand. The list is intentionally left stale
 * until refreshed after new downloads land.
 */
class BackendModelsViewModel(app: Application) : AndroidViewModel(app) {
    private val context get() = getApplication<Application>()

    var listState by mutableStateOf<ModelListState>(ModelListState.Loading)
        private set
    var refreshing by mutableStateOf(false)
        private set

    val downloadStates = mutableStateMapOf<String, DownloadState>()
    val weightsStates = mutableStateMapOf<String, DownloadState>()
    val quantizeStates = mutableStateMapOf<String, DownloadState>()
    val localMetas = mutableStateMapOf<String, RemoteModel>()
    val weightsStatuses = mutableStateMapOf<String, WeightsStatus>()

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            refreshing = true
            val result = fetchModels(context).fold(
                onSuccess = { ModelListState.Loaded(it) },
                onFailure = { ModelListState.Error(it.message ?: "Unknown error") },
            )
            listState = result
            (result as? ModelListState.Loaded)?.let { loaded ->
                withContext(Dispatchers.IO) {
                    loaded.models.forEach { model ->
                        loadModelMeta(context, model.key)?.let { localMetas[model.key] = it }
                        weightsStatuses[model.key] = weightsStatus(context, model)
                    }
                }
            }
            refreshing = false
        }
    }

    fun download(model: RemoteModel) {
        viewModelScope.launch {
            downloadStates[model.key] = DownloadState.InProgress
            val dest = File(modelDir(context, model.key), TRAINABLE_FILENAME)
            val result = downloadModel(context, model.trainableEndpoint, dest)
            if (result.isSuccess) {
                withContext(Dispatchers.IO) {
                    saveModelMeta(context, model)
                    weightsStatuses[model.key] = weightsStatus(context, model)
                }
                localMetas[model.key] = model
                downloadStates[model.key] = DownloadState.Done(dest.absolutePath)
            } else {
                downloadStates[model.key] = DownloadState.Error(
                    result.exceptionOrNull()?.message ?: "Unknown error",
                )
            }
        }
    }

    fun downloadWeightsFor(model: RemoteModel) {
        viewModelScope.launch {
            weightsStates[model.key] = DownloadState.InProgress
            weightsStates[model.key] = downloadWeights(context, model).fold(
                onSuccess = {
                    withContext(Dispatchers.IO) {
                        loadModelMeta(context, model.key)?.let { localMetas[model.key] = it }
                        weightsStatuses[model.key] = weightsStatus(context, model)
                    }
                    DownloadState.Done(weightsFile(context, model.key).absolutePath)
                },
                onFailure = { DownloadState.Error(it.message ?: "Unknown error") },
            )
        }
    }

    fun quantize(model: RemoteModel) {
        viewModelScope.launch {
            quantizeStates[model.key] = DownloadState.InProgress
            quantizeStates[model.key] = downloadQuantized(context, model).fold(
                onSuccess = { DownloadState.Done(quantizedFile(context, model.key).absolutePath) },
                onFailure = { DownloadState.Error(it.message ?: "Unknown error") },
            )
        }
    }
}
