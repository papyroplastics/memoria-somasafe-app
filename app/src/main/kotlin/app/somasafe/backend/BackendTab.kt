package app.somasafe.backend

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import java.io.File

@Composable
fun BackendDownloadScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val states = remember {
        mutableStateMapOf<ModelVariant, DownloadState>().apply {
            ModelVariant.entries.forEach { put(it, DownloadState.Idle) }
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

        ModelVariant.entries.forEach { variant ->
            ModelDownloadCard(
                variant = variant,
                state = states[variant] ?: DownloadState.Idle,
                onDownload = {
                    states[variant] = DownloadState.InProgress
                    scope.launch {
                        val dest = File(modelsDir(context), variant.filename)
                        states[variant] = downloadModel(variant.endpoint, dest).fold(
                            onSuccess = { DownloadState.Done(dest.absolutePath) },
                            onFailure = { DownloadState.Error(it.message ?: "Unknown error") },
                        )
                    }
                },
            )
        }
    }
}

@Composable
private fun ModelDownloadCard(
    variant: ModelVariant,
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
            Text(variant.label, style = MaterialTheme.typography.titleMedium)

            when (state) {
                DownloadState.Idle ->
                    Button(onClick = onDownload) { Text("Download") }

                DownloadState.InProgress ->
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text("Downloading…", style = MaterialTheme.typography.bodyMedium)
                    }

                is DownloadState.Done -> {
                    Text(
                        "Saved: ${state.path}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    OutlinedButton(onClick = onDownload) { Text("Re-download") }
                }

                is DownloadState.Error -> {
                    Text(
                        "Error: ${state.message}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Button(onClick = onDownload) { Text("Retry") }
                }
            }
        }
    }
}
