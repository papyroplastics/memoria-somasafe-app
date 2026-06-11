package app.somasafe.model

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
import app.somasafe.backend.MODEL_FILENAME
import app.somasafe.backend.loadModelMeta
import app.somasafe.backend.modelsDir
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DecimalFormat

private data class LocalModelEntry(val key: String, val file: File, val displayName: String)

@Composable
fun ModelListScreen(modifier: Modifier = Modifier, onModelSelected: (String) -> Unit) {
    val context = LocalContext.current
    var models by remember { mutableStateOf<List<LocalModelEntry>>(emptyList()) }

    LaunchedEffect(Unit) {
        models = withContext(Dispatchers.IO) {
            modelsDir(context).listFiles()
                ?.filter { it.isDirectory }
                ?.sortedBy { it.name }
                ?.mapNotNull { dir ->
                    val tflite = File(dir, MODEL_FILENAME)
                    if (!tflite.exists()) return@mapNotNull null
                    val displayName = loadModelMeta(context, dir.name)?.name ?: dir.name
                    LocalModelEntry(dir.name, tflite, displayName)
                }
                ?: emptyList()
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Downloaded Models", style = MaterialTheme.typography.headlineSmall)

        if (models.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(top = 32.dp),
                contentAlignment = Alignment.TopCenter,
            ) {
                Text(
                    "No models yet — download them from the Backend tab.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            models.forEach { entry ->
                ModelCard(
                    file = entry.file,
                    displayName = entry.displayName,
                    onClick = { onModelSelected(entry.key) },
                )
            }
        }
    }
}

@Composable
private fun ModelCard(file: File, displayName: String, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(displayName, style = MaterialTheme.typography.titleMedium)
                Text(
                    formatFileSize(file.length()),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                "Inspect →",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

internal fun formatFileSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return "${DecimalFormat("0.#").format(kb)} KB"
    val mb = kb / 1024.0
    return "${DecimalFormat("0.#").format(mb)} MB"
}
