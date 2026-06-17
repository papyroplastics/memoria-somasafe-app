package app.somasafe.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothProfile
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.somasafe.backend.LocalModel
import app.somasafe.backend.listLocalModels
import app.somasafe.capture.CaptureController
import app.somasafe.capture.CaptureState
import app.somasafe.capture.GroupSummary
import app.somasafe.capture.ModelState
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

@SuppressLint("MissingPermission")
@Composable
fun ConnectDeviceScreen(device: BluetoothDevice, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val connection = rememberBleConnection(device)
    val controller = remember(connection) {
        CaptureController(context.applicationContext, connection, scope)
    }

    val connectionState by connection.connectionState.collectAsStateWithLifecycle()
    val mtu by connection.mtu.collectAsStateWithLifecycle()
    val services by connection.services.collectAsStateWithLifecycle()
    val modelState by controller.model.collectAsStateWithLifecycle()
    val captureState by controller.capture.collectAsStateWithLifecycle()
    val status by controller.status.collectAsStateWithLifecycle()
    val groups by controller.groupSummaries.collectAsStateWithLifecycle(initialValue = emptyList())

    val connected = connectionState == BluetoothProfile.STATE_CONNECTED && services.isNotEmpty()

    var localModels by remember { mutableStateOf<List<LocalModel>>(emptyList()) }
    var selectedKey by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        localModels = withContext(Dispatchers.IO) { listLocalModels(context) }
        if (selectedKey == null) selectedKey = localModels.firstOrNull()?.key
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Device", style = MaterialTheme.typography.headlineSmall)
        Text("Name: ${device.name ?: "N/A"} (${device.address})")
        Text("Status: ${connectionState.toConnectionString()}")
        Text("MTU: ${if (mtu >= 0) mtu.toString() else "N/A"}")

        ModelCard(
            models = localModels,
            selectedKey = selectedKey,
            onSelect = { selectedKey = it },
            modelState = modelState,
            connected = connected,
            onLoad = { selectedKey?.let { controller.loadModel(it) } },
        )

        CaptureCard(
            captureState = captureState,
            modelLoaded = modelState is ModelState.Loaded,
            connected = connected,
            onStart = controller::startCapture,
            onStop = controller::stopCapture,
        )

        status?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        GroupsCard(groups)

        if (services.isNotEmpty()) {
            Text("GATT services", style = MaterialTheme.typography.titleMedium)
            services.forEach { ServiceItem(it) }
        }
    }
}

@Composable
private fun ModelCard(
    models: List<LocalModel>,
    selectedKey: String?,
    onSelect: (String) -> Unit,
    modelState: ModelState,
    connected: Boolean,
    onLoad: () -> Unit,
) {
    Card {
        Column(
            modifier = Modifier.padding(16.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Model", style = MaterialTheme.typography.titleMedium)

            if (models.isEmpty()) {
                Text(
                    "No quantized models — download and quantize one on the Backend/Model tabs.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                models.forEach { model ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(model.key) },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        RadioButton(selected = model.key == selectedKey, onClick = { onSelect(model.key) })
                        Text(model.displayName, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }

            Button(
                onClick = onLoad,
                enabled = connected && selectedKey != null && modelState !is ModelState.Loading,
            ) {
                Text(if (modelState is ModelState.Loading) "Loading…" else "Load model")
            }

            val (text, color) = when (val s = modelState) {
                ModelState.None -> "Not loaded" to MaterialTheme.colorScheme.onSurfaceVariant
                ModelState.Loading -> "Uploading to device…" to MaterialTheme.colorScheme.onSurfaceVariant
                is ModelState.Loaded -> "Loaded: ${s.key} (in ${s.featuresLen} B, out ${s.scoreLen} B)" to MaterialTheme.colorScheme.primary
                is ModelState.Error -> "Error: ${s.message}" to MaterialTheme.colorScheme.error
            }
            Text(text, style = MaterialTheme.typography.bodySmall, color = color)
        }
    }
}

@Composable
private fun CaptureCard(
    captureState: CaptureState,
    modelLoaded: Boolean,
    connected: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    Card {
        Column(
            modifier = Modifier.padding(16.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Capture", style = MaterialTheme.typography.titleMedium)
            Text(
                "Records PPG windows and matching inference results into a sample group.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            when (captureState) {
                is CaptureState.Running -> Button(
                    onClick = onStop,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) { Text("Stop capture") }

                CaptureState.Idle -> Button(
                    onClick = onStart,
                    enabled = connected && modelLoaded,
                ) { Text("Start capture") }
            }
        }
    }
}

@Composable
private fun GroupsCard(groups: List<GroupSummary>) {
    if (groups.isEmpty()) return
    Card {
        Column(
            modifier = Modifier.padding(16.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("Capture history", style = MaterialTheme.typography.titleMedium)
            groups.forEach { group ->
                val started = timeFormat.format(Date(group.startedAt))
                val ended = group.endedAt?.let { timeFormat.format(Date(it)) } ?: "active"
                Text(
                    "#${group.groupId}  $started → $ended   ${group.sampleCount} samples, ${group.resultCount} results",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun ServiceItem(service: BluetoothGattService) {
    Column(
        modifier = Modifier.padding(start = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            "Service: ${service.uuid}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
            fontFamily = FontFamily.Monospace,
        )
        service.characteristics.forEach { chr -> CharacteristicItem(chr) }
    }
}

@Composable
private fun CharacteristicItem(characteristic: BluetoothGattCharacteristic) {
    Text(
        "Chr: ${characteristic.uuid} [${characteristic.properties.toPropertiesString()}]",
        modifier = Modifier.padding(start = 16.dp),
        style = MaterialTheme.typography.bodySmall,
        fontFamily = FontFamily.Monospace,
    )
}
