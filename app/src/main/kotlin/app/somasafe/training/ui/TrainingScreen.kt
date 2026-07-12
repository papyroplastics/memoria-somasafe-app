package app.somasafe.training.ui

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
import kotlinx.coroutines.launch
import app.somasafe.backend.data.loadModelMeta
import app.somasafe.capture.data.CaptureRepository
import app.somasafe.capture.data.GroupSummary
import app.somasafe.training.domain.Trainer

/**
 * On-device training for one model: pick a processed capture group and run a local
 * epoch, writing the trained weights to `trained_weights.bin` (plus the starting
 * baseline in `base_weights.bin`). Requires the model to be downloaded (the trainable
 * artifact carries the global weights baked in). The model z-scores its own inputs, so
 * no normalization params are needed here. After training, the model detail screen's
 * upload actions submit the delta `trained − base` as the federated update.
 */
@Composable
fun TrainingScreen(modelKey: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repository = remember { CaptureRepository(context.applicationContext) }
    val trainer = remember { Trainer(context.applicationContext, repository) }

    val groups by repository.groupSummaries().collectAsStateWithLifecycle(initialValue = emptyList())
    val meta = remember(modelKey) { loadModelMeta(context, modelKey) }

    var busy by remember(modelKey) { mutableStateOf(false) }
    var status by remember(modelKey) { mutableStateOf<String?>(null) }

    val ready = meta?.weightsId != null

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Train ${meta?.name ?: modelKey}", style = MaterialTheme.typography.headlineSmall)

        PrereqCard(hasModel = ready)

        status?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary)
        }

        if (groups.isEmpty()) {
            Text(
                "No captures to train on. Record or import one under the Captures tab, then Process it.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text("Capture groups", style = MaterialTheme.typography.titleMedium)
            groups.forEach { group ->
                GroupTrainCard(group, enabled = ready && !busy) {
                    status = "Training on group #${group.groupId}…"
                    scope.launch {
                        busy = true
                        status = runCatching { trainer.trainEpoch(modelKey, group.groupId) }.fold(
                            onSuccess = { r ->
                                if (r.batches == 0) "No full batch of windows with context to train on"
                                else "Trained ${r.windows} windows (${r.batches} batches), loss ${"%.4f".format(r.meanLoss)}"
                            },
                            onFailure = { "Training failed: ${it.message}" },
                        )
                        busy = false
                    }
                }
            }
        }
    }
}

@Composable
private fun PrereqCard(hasModel: Boolean) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                if (hasModel) "Model: downloaded" else "Model: not downloaded (download under Backend)",
                style = MaterialTheme.typography.bodySmall,
                color = if (hasModel) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun GroupTrainCard(group: GroupSummary, enabled: Boolean, onTrain: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(16.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Group #${group.groupId}", style = MaterialTheme.typography.titleMedium)
                Text(
                    "${group.sampleCount} samples · ${group.contextCount} with context",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Button(onClick = onTrain, enabled = enabled) { Text("Train") }
        }
    }
}
