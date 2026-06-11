package app.somasafe.backend

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private sealed interface ModelListState {
    data object Loading : ModelListState
    data class Loaded(val models: List<RemoteModel>) : ModelListState
    data class Error(val message: String) : ModelListState
}

@Composable
fun BackendDownloadScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var listState by remember { mutableStateOf<ModelListState>(ModelListState.Loading) }
    val downloadStates = remember { mutableStateMapOf<String, DownloadState>() }
    val localMetas = remember { mutableStateMapOf<String, RemoteModel>() }

    LaunchedEffect(Unit) {
        listState = fetchModels().fold(
            onSuccess = { ModelListState.Loaded(it) },
            onFailure = { ModelListState.Error(it.message ?: "Unknown error") },
        )
    }

    LaunchedEffect(listState) {
        val loaded = listState as? ModelListState.Loaded ?: return@LaunchedEffect
        withContext(Dispatchers.IO) {
            loaded.models.forEach { model ->
                loadModelMeta(context, model.key)?.let { localMetas[model.key] = it }
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Download Models", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Backend: $BACKEND_URL",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        HorizontalDivider()

        when (val state = listState) {
            ModelListState.Loading ->
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text("Loading models…", style = MaterialTheme.typography.bodyMedium)
                }

            is ModelListState.Error ->
                Text(
                    "Failed to load models: ${state.message}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )

            is ModelListState.Loaded ->
                state.models.forEach { model ->
                    ModelDownloadCard(
                        model = model,
                        localMeta = localMetas[model.key],
                        state = downloadStates[model.key] ?: DownloadState.Idle,
                        onDownload = {
                            scope.launch {
                                downloadStates[model.key] = DownloadState.InProgress
                                val dest = File(modelDir(context, model.key), MODEL_FILENAME)
                                val result = downloadModel(model.trainableEndpoint, dest)
                                if (result.isSuccess) {
                                    withContext(Dispatchers.IO) { saveModelMeta(context, model) }
                                    localMetas[model.key] = model
                                    downloadStates[model.key] = DownloadState.Done(dest.absolutePath)
                                } else {
                                    downloadStates[model.key] = DownloadState.Error(
                                        result.exceptionOrNull()?.message ?: "Unknown error"
                                    )
                                }
                            }
                        },
                    )
                }
        }
    }
}

@Composable
private fun ModelDownloadCard(
    model: RemoteModel,
    localMeta: RemoteModel?,
    state: DownloadState,
    onDownload: () -> Unit,
) {
    val isUpToDate = localMeta != null && localMeta.modelId == model.modelId

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(model.name, style = MaterialTheme.typography.titleMedium)
            Text(
                model.purpose,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (localMeta != null) {
                Text(
                    "Downloaded: v${localMeta.modelId}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (!isUpToDate) {
                Text(
                    "Upstream: v${model.modelId}  ·  ${model.lastUpdated.take(10)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            when (state) {
                DownloadState.InProgress ->
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text("Downloading…", style = MaterialTheme.typography.bodyMedium)
                    }

                is DownloadState.Error -> {
                    Text(
                        "Error: ${state.message}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Button(onClick = onDownload) { Text("Retry") }
                }

                else ->
                    if (isUpToDate) {
                        Button(
                            onClick = onDownload,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            ),
                        ) { Text("Download") }
                    } else {
                        Button(onClick = onDownload) {
                            Text(if (localMeta == null) "Download" else "Update")
                        }
                    }
            }
        }
    }
}
