package app.somasafe.backend.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.somasafe.backend.data.LocalFirmware
import app.somasafe.backend.data.deleteFirmware
import app.somasafe.backend.data.listLocalFirmware
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun FirmwareListScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var firmwares by remember { mutableStateOf<List<LocalFirmware>>(emptyList()) }

    LaunchedEffect(Unit) {
        firmwares = withContext(Dispatchers.IO) { listLocalFirmware(context) }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Downloaded Firmware", style = MaterialTheme.typography.headlineSmall)

        if (firmwares.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(top = 32.dp),
                contentAlignment = Alignment.TopCenter,
            ) {
                Text(
                    "No firmware yet — download it from the Backend tab.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            firmwares.forEach { firmware ->
                FirmwareCard(
                    firmware = firmware,
                    onDelete = {
                        scope.launch {
                            withContext(Dispatchers.IO) { deleteFirmware(context, firmware.version) }
                            firmwares = firmwares - firmware
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun FirmwareCard(firmware: LocalFirmware, onDelete: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(firmware.version, style = MaterialTheme.typography.titleMedium)
                Text(
                    "Interface ${firmware.interfaceVersion}" +
                        "  ·  contracts ${firmware.supportedContracts.joinToString(", ")}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "${formatFileSize(firmware.size)}  ·  ${firmware.createdAt.take(10)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SignatureIndicator(signed = firmware.signature != null)
            }
            TextButton(onClick = onDelete) {
                Text("Delete", color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun SignatureIndicator(signed: Boolean) {
    val (text, color) = if (signed) {
        "Signed" to MaterialTheme.colorScheme.primary
    } else {
        "Unsigned — the device will reject it" to MaterialTheme.colorScheme.error
    }
    Text(text, style = MaterialTheme.typography.labelSmall, color = color)
}
