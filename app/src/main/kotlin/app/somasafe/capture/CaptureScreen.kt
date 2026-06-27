package app.somasafe.capture

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val timeFormat = SimpleDateFormat("MMM d HH:mm:ss", Locale.getDefault())

/**
 * Manages stored captures: lists sample groups (captured or imported alike),
 * deletes them, and imports a subject dataset exported by
 * backend/scripts/export_subject_data.py. Independent of any BLE connection.
 */
@Composable
fun CaptureScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repository = remember { CaptureRepository(context.applicationContext) }

    val groups by repository.groupSummaries().collectAsStateWithLifecycle(initialValue = emptyList())
    var status by remember { mutableStateOf<String?>(null) }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        status = "Importing…"
        scope.launch {
            try {
                val dataset = withContext(Dispatchers.IO) {
                    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: error("could not open file")
                    DatasetImport.parse(bytes)
                }
                repository.importDataset(dataset)
                status = "Imported S${dataset.subject}: ${dataset.windows.size} windows"
            } catch (e: Exception) {
                status = "Import failed: ${e.message}"
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Captures", style = MaterialTheme.typography.headlineSmall)

        Button(onClick = { importLauncher.launch(arrayOf("*/*")) }) {
            Text("Import dataset…")
        }

        status?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        if (groups.isEmpty()) {
            Text(
                "No captures yet. Record one on the device screen, or import a subject dataset.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            groups.forEach { group ->
                GroupCard(group, onDelete = { scope.launch { repository.deleteGroup(group.groupId) } })
            }
        }
    }
}

@Composable
private fun GroupCard(group: GroupSummary, onDelete: () -> Unit) {
    var confirming by remember { mutableStateOf(false) }
    Card {
        Row(
            modifier = Modifier.padding(16.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                val started = timeFormat.format(Date(group.startedAt))
                val ended = group.endedAt?.let { timeFormat.format(Date(it)) } ?: "active"
                Text("Group #${group.groupId}", style = MaterialTheme.typography.titleMedium)
                Text(
                    "$started → $ended",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "${group.sampleCount} samples, ${group.resultCount} results",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (confirming) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { confirming = false }) { Text("Cancel") }
                    TextButton(onClick = { confirming = false; onDelete() }) {
                        Text("Delete", color = MaterialTheme.colorScheme.error)
                    }
                }
            } else {
                TextButton(onClick = { confirming = true }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}
