package app.somasafe.bluetooth.ui

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
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.somasafe.backend.data.LocalFirmware
import app.somasafe.backend.data.listLocalFirmware
import app.somasafe.bluetooth.domain.DeviceSession
import app.somasafe.bluetooth.domain.OtaState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun FirmwareInstallScreen(
    controller: DeviceSession,
    modifier: Modifier = Modifier,
    onDone: () -> Unit = {},
) {
    val context = LocalContext.current

    var firmwares by remember { mutableStateOf<List<LocalFirmware>>(emptyList()) }
    var running by remember { mutableStateOf<String?>(null) }
    val ota by controller.ota.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        firmwares = withContext(Dispatchers.IO) { listLocalFirmware(context) }
        running = runCatching { controller.readFirmwareVersion() }
            .fold(
                onSuccess = { (interfaceVersion, version) -> "$version (interface $interfaceVersion)" },
                onFailure = { null },
            )
    }

    LaunchedEffect(ota) {
        if (ota is OtaState.Done) onDone()
    }

    val busy = ota is OtaState.Sending || ota is OtaState.Verifying

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Install Firmware", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Running: ${running ?: "unknown"}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        OtaProgress(ota)

        if (firmwares.isEmpty()) {
            Text(
                "No firmware images — download one from the Backend tab.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            firmwares.forEach { firmware ->
                InstallCard(
                    firmware = firmware,
                    enabled = !busy && firmware.signature != null,
                    onInstall = { controller.installFirmware(firmware) },
                )
            }
        }
    }
}

@Composable
private fun OtaProgress(ota: OtaState) {
    when (ota) {
        is OtaState.Sending -> {
            LinearProgressIndicator(
                progress = { ota.sent.toFloat() / ota.total },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "Sending ${ota.sent / 1024} / ${ota.total / 1024} KB",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        OtaState.Verifying ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                Text("Verifying on device…", style = MaterialTheme.typography.bodyMedium)
            }

        is OtaState.Error ->
            Text(
                "Error: ${ota.message}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )

        else -> {}
    }
}

@Composable
private fun InstallCard(
    firmware: LocalFirmware,
    enabled: Boolean,
    onInstall: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(firmware.version, style = MaterialTheme.typography.titleMedium)
            Text(
                "Interface ${firmware.interfaceVersion}" +
                    "  ·  contracts ${firmware.supportedContracts.joinToString(", ")}" +
                    "  ·  ${firmware.size / 1024} KB",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (firmware.signature == null) {
                Text(
                    "Unsigned image — the device will reject it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Button(onClick = onInstall, enabled = enabled) { Text("Install") }
        }
    }
}
