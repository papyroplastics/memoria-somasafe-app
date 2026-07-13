package app.somasafe.backend.ui

import androidx.compose.foundation.clickable
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
import app.somasafe.backend.data.RemoteFirmware
import app.somasafe.backend.data.RemoteModel
import app.somasafe.backend.data.WeightsStatus
import app.somasafe.backend.data.logout
import app.somasafe.bluetooth.data.BLE_INTERFACE_VERSION
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackendDownloadScreen(
    vm: BackendModelsViewModel,
    modifier: Modifier = Modifier,
    onLogout: () -> Unit = {},
    onOpenLocalModels: () -> Unit = {},
    onOpenLocalFirmware: () -> Unit = {},
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
            Text("Backend", style = MaterialTheme.typography.headlineSmall)
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
            )

            HorizontalDivider()

            SectionHeader("Models", onOpen = onOpenLocalModels)

            when (val state = vm.listState) {
                ModelListState.Loading -> LoadingRow("Loading models…")

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

            HorizontalDivider()

            SectionHeader("Firmware", onOpen = onOpenLocalFirmware)

            when (val state = vm.firmwareListState) {
                FirmwareListState.Loading -> LoadingRow("Loading firmware…")

                is FirmwareListState.Error ->
                    Text(
                        "Failed to load firmware: ${state.message}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )

                is FirmwareListState.Loaded ->
                    if (state.versions.isEmpty()) {
                        Text(
                            "No firmware published for interface $BLE_INTERFACE_VERSION.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        state.versions.forEach { firmware ->
                            FirmwareDownloadCard(
                                firmware = firmware,
                                downloaded = firmware.version in vm.localFirmwareVersions,
                                state = vm.firmwareDownloadStates[firmware.version] ?: DownloadState.Idle,
                                onDownload = { vm.downloadFirmwareFor(firmware) },
                            )
                        }
                    }
            }
        }
    }
}

@Composable
private fun SessionHeader(
    username: String,
    onLogout: () -> Unit,
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
        TextButton(onClick = onLogout) { Text("Log out") }
    }
}

/** A section title with a trailing arrow opening its local-management screen. */
@Composable
private fun SectionHeader(title: String, onOpen: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(
            "Downloaded →",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun LoadingRow(text: String) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun FirmwareDownloadCard(
    firmware: RemoteFirmware,
    downloaded: Boolean,
    state: DownloadState,
    onDownload: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Firmware ${firmware.version}", style = MaterialTheme.typography.titleMedium)
            Text(
                "Contracts: ${firmware.supportedContracts.joinToString(", ")}" +
                    "  ·  ${formatFileSize(firmware.size)}" +
                    "  ·  ${firmware.createdAt.take(10)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (downloaded) {
                Text(
                    "Downloaded",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            when (state) {
                DownloadState.InProgress -> LoadingRow("Downloading…")

                is DownloadState.Error -> {
                    Text(
                        "Error: ${state.message}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Button(onClick = onDownload) { Text("Retry") }
                }

                else ->
                    if (downloaded) {
                        Button(
                            onClick = onDownload,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            ),
                        ) { Text("Download") }
                    } else {
                        Button(onClick = onDownload) { Text("Download") }
                    }
            }
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
                UploadSection(
                    uploadState, submitState, weightsStatus,
                    model.supportsQuantizeSubmit, onUploadQuantize, onSubmit,
                )
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
    supportsQuantizeSubmit: Boolean,
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
        // "Upload & quantize" only applies to quantize-type models; a raw model
        // 404s on that endpoint, so only "Submit only" is offered for it.
        if (supportsQuantizeSubmit) {
            OutlinedButton(onClick = onUploadQuantize, enabled = enabled && !busy) {
                Text("Upload & quantize")
            }
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
