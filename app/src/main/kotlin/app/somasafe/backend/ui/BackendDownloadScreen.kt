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
                            quantizedState = vm.quantizedStates[model.key] ?: DownloadState.Idle,
                            uploadState = vm.uploadStates[model.key] ?: DownloadState.Idle,
                            submitState = vm.submitStates[model.key] ?: DownloadState.Idle,
                            weightsStatus = vm.weightsStatuses[model.key] ?: WeightsStatus.MISSING,
                            onDownload = { vm.download(model) },
                            onDownloadQuantized = { vm.downloadQuantizedFor(model) },
                            onUploadQuantize = { vm.uploadQuantize(model) },
                            onSubmit = { vm.submit(model) },
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
    quantizedState: DownloadState,
    uploadState: DownloadState,
    submitState: DownloadState,
    weightsStatus: WeightsStatus,
    onDownload: () -> Unit,
    onDownloadQuantized: () -> Unit,
    onUploadQuantize: () -> Unit,
    onSubmit: () -> Unit,
) {
    // Up to date only if the version (architecture) and the weights snapshot match upstream.
    val isUpToDate = localMeta != null &&
        localMeta.version == model.version &&
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

            if (!model.appCompatible) {
                Text(
                    "Requires app ≥ ${model.minAppVersion} — update the app to use this model.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                return@Column
            }

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

            if (localMeta != null) {
                QuantizedRow(quantizedState, onDownloadQuantized)
                UploadSection(uploadState, submitState, weightsStatus, onUploadQuantize, onSubmit)
            }
        }
    }
}

@Composable
private fun QuantizedRow(state: DownloadState, onDownload: () -> Unit) {
    when (state) {
        DownloadState.InProgress ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                Text("Downloading quantized…", style = MaterialTheme.typography.bodyMedium)
            }

        is DownloadState.Error -> {
            Text(
                "Quantized error: ${state.message}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            OutlinedButton(onClick = onDownload) { Text("Retry quantized") }
        }

        else ->
            OutlinedButton(onClick = onDownload) {
                Text(if (state is DownloadState.Done) "Re-download quantized" else "Download quantized")
            }
    }
}

/** The federated upload actions, available once on-device training produced weights. */
@Composable
private fun UploadSection(
    uploadState: DownloadState,
    submitState: DownloadState,
    weightsStatus: WeightsStatus,
    onUploadQuantize: () -> Unit,
    onSubmit: () -> Unit,
) {
    val (statusText, statusColor) = when (weightsStatus) {
        WeightsStatus.MISSING -> "No locally trained update" to MaterialTheme.colorScheme.onSurfaceVariant
        WeightsStatus.OUTDATED -> "Trained update: based on older weights" to MaterialTheme.colorScheme.primary
        WeightsStatus.CURRENT -> "Trained update: ready" to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text(statusText, style = MaterialTheme.typography.bodySmall, color = statusColor)

    val enabled = weightsStatus != WeightsStatus.MISSING
    val busy = uploadState is DownloadState.InProgress || submitState is DownloadState.InProgress

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = onUploadQuantize, enabled = enabled && !busy) {
            Text("Upload & quantize")
        }
        OutlinedButton(onClick = onSubmit, enabled = enabled && !busy) {
            Text("Submit only")
        }
    }

    when {
        uploadState is DownloadState.InProgress ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                Text("Uploading & quantizing…", style = MaterialTheme.typography.bodyMedium)
            }
        submitState is DownloadState.InProgress ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                Text("Submitting…", style = MaterialTheme.typography.bodyMedium)
            }
        uploadState is DownloadState.Error ->
            Text(
                "Upload error: ${uploadState.message}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        submitState is DownloadState.Error ->
            Text(
                "Submit error: ${submitState.message}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        submitState is DownloadState.Done ->
            Text(
                "Submitted (${submitState.path})",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary,
            )
    }
}
