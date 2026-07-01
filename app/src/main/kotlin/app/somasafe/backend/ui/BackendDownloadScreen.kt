package app.somasafe.backend.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.somasafe.backend.data.AuthStore
import app.somasafe.backend.data.BACKEND_URL
import app.somasafe.backend.data.DownloadState
import app.somasafe.backend.data.RemoteModel
import app.somasafe.backend.data.WeightsStatus
import app.somasafe.backend.data.logout
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackendDownloadScreen(
    vm: BackendModelsViewModel,
    modifier: Modifier = Modifier,
    onLogout: () -> Unit = {},
    onOpenLocalModels: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val username = remember { AuthStore.username(context).orEmpty() }

    PullToRefreshBox(
        isRefreshing = vm.refreshing,
        onRefresh = { vm.refresh() },
        modifier = modifier.fillMaxSize(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
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

            SessionHeader(
                username = username,
                onLogout = {
                    scope.launch {
                        logout(context)
                        onLogout()
                    }
                },
                onOpenLocalModels = onOpenLocalModels,
            )

            HorizontalDivider()

            when (val state = vm.listState) {
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
                            localMeta = vm.localMetas[model.key],
                            state = vm.downloadStates[model.key] ?: DownloadState.Idle,
                            weightsState = vm.weightsStates[model.key] ?: DownloadState.Idle,
                            quantizeState = vm.quantizeStates[model.key] ?: DownloadState.Idle,
                            showWeights = vm.localMetas[model.key] != null,
                            weightsStatus = vm.weightsStatuses[model.key] ?: WeightsStatus.MISSING,
                            quantizeEnabled = (vm.weightsStatuses[model.key] ?: WeightsStatus.MISSING) != WeightsStatus.MISSING,
                            onDownload = { vm.download(model) },
                            onDownloadWeights = { vm.downloadWeightsFor(model) },
                            onQuantize = { vm.quantize(model) },
                        )
                    }
            }
        }
    }
}

@Composable
private fun SessionHeader(
    username: String,
    onLogout: () -> Unit,
    onOpenLocalModels: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "Signed in as $username",
            style = MaterialTheme.typography.bodyMedium,
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onOpenLocalModels) { Text("Downloaded models") }
            TextButton(onClick = onLogout) { Text("Log out") }
        }
    }
}

@Composable
private fun ModelDownloadCard(
    model: RemoteModel,
    localMeta: RemoteModel?,
    state: DownloadState,
    weightsState: DownloadState,
    quantizeState: DownloadState,
    showWeights: Boolean,
    weightsStatus: WeightsStatus,
    quantizeEnabled: Boolean,
    onDownload: () -> Unit,
    onDownloadWeights: () -> Unit,
    onQuantize: () -> Unit,
) {
    // Up to date only if both the architecture and the weights match upstream.
    val isUpToDate = localMeta != null &&
        localMeta.fingerprint == model.fingerprint &&
        localMeta.weightsVersion == model.weightsVersion

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
                    "Downloaded: v${localMeta.version}  ·  ${localMeta.fingerprint.take(8)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (!isUpToDate) {
                val upstream = buildString {
                    append("Upstream: v${model.version}  ·  ${model.fingerprint.take(8)}")
                    model.weightsVersion?.let { append("  ·  ${it.take(10)}") }
                }
                Text(
                    upstream,
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

            if (showWeights) {
                WeightsRow(weightsState, weightsStatus, onDownloadWeights)
                QuantizeRow(quantizeState, quantizeEnabled, onQuantize)
            }
        }
    }
}

@Composable
private fun WeightsRow(state: DownloadState, status: WeightsStatus, onDownload: () -> Unit) {
    when (state) {
        DownloadState.InProgress ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                Text("Downloading weights…", style = MaterialTheme.typography.bodyMedium)
            }

        is DownloadState.Error -> {
            Text(
                "Weights error: ${state.message}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            OutlinedButton(onClick = onDownload) { Text("Retry weights") }
        }

        else -> {
            val (statusText, statusColor) = when (status) {
                WeightsStatus.MISSING -> "Weights: not downloaded" to MaterialTheme.colorScheme.onSurfaceVariant
                WeightsStatus.OUTDATED -> "Weights: outdated" to MaterialTheme.colorScheme.primary
                WeightsStatus.CURRENT -> "Weights: up to date" to MaterialTheme.colorScheme.onSurfaceVariant
            }
            Text(statusText, style = MaterialTheme.typography.bodySmall, color = statusColor)
            OutlinedButton(onClick = onDownload) {
                Text(if (status == WeightsStatus.CURRENT) "Re-download weights" else "Download weights")
            }
        }
    }
}

@Composable
private fun QuantizeRow(state: DownloadState, enabled: Boolean, onQuantize: () -> Unit) {
    when (state) {
        DownloadState.InProgress ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                Text("Quantizing…", style = MaterialTheme.typography.bodyMedium)
            }

        is DownloadState.Error -> {
            Text(
                "Quantize error: ${state.message}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            OutlinedButton(onClick = onQuantize, enabled = enabled) { Text("Retry quantize") }
        }

        is DownloadState.Done ->
            OutlinedButton(onClick = onQuantize, enabled = enabled) { Text("Re-quantize") }

        DownloadState.Idle -> {
            OutlinedButton(onClick = onQuantize, enabled = enabled) { Text("Quantize") }
            if (!enabled) {
                Text(
                    "Download weights first.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
