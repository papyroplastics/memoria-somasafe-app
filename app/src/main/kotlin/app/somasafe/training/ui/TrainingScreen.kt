package app.somasafe.training.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import app.somasafe.backend.data.loadModelMeta
import app.somasafe.capture.data.CaptureRepository
import app.somasafe.capture.data.GroupSummary
import app.somasafe.training.domain.TrainMetrics
import app.somasafe.training.domain.TrainPhase
import app.somasafe.training.domain.TrainState
import app.somasafe.training.domain.Trainer
import app.somasafe.training.domain.models.CAPTURE_MODELS

/**
 * On-device training for one model: pick a processed capture group and run a local
 * epoch, writing the trained weights to `trained_weights.bin` (plus the starting
 * baseline in `base_weights.bin`). Requires the model to be downloaded (the trainable
 * artifact carries the global weights baked in) and the capture group to have been
 * processed, since the windows are z-scored with the parameters preprocessing derived
 * from that group. After training, the model detail screen's upload actions submit the
 * delta `trained − base` as the federated update.
 */
@Composable
fun TrainingScreen(modelKey: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repository = remember { CaptureRepository(context.applicationContext) }
    val trainer = remember { Trainer(context.applicationContext, repository) }

    val groups by repository.groupSummaries().collectAsStateWithLifecycle(initialValue = emptyList())
    val meta = remember(modelKey) { loadModelMeta(context, modelKey) }
    val state by trainer.state.collectAsStateWithLifecycle()

    var job by remember(modelKey) { mutableStateOf<Job?>(null) }

    val supported = CAPTURE_MODELS.containsKey(modelKey)
    val ready = meta?.weightsId != null
    val busy = state is TrainState.Preparing || state is TrainState.Running

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Train ${meta?.name ?: modelKey}", style = MaterialTheme.typography.headlineSmall)

        PrereqCard(hasModel = ready)

        if (!supported) {
            Text(
                "Training not yet supported for this model.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }

        TrainProgress(state, onCancel = { job?.cancel() })

        if (groups.isEmpty()) {
            Text(
                "No captures to train on. Record or import one under the Captures tab, then Process it.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text("Capture groups", style = MaterialTheme.typography.titleMedium)
            groups.forEach { group ->
                GroupTrainCard(group, enabled = supported && ready && !busy && group.hasNormParams) {
                    job = scope.launch { runCatching { trainer.trainEpoch(modelKey, group.groupId) } }
                }
            }
        }
    }
}

@Composable
private fun TrainProgress(state: TrainState, onCancel: () -> Unit) {
    when (state) {
        TrainState.Idle -> {}

        TrainState.Preparing ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                Text("Preparing windows…", style = MaterialTheme.typography.bodyMedium)
            }

        is TrainState.Running -> {
            LinearProgressIndicator(
                progress = { state.done.toFloat() / state.total },
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    when (state.phase) {
                        TrainPhase.SCORING_BASELINE -> "Scoring baseline…"
                        TrainPhase.TRAINING -> "Training batch ${state.batch} / ${state.batches}"
                        TrainPhase.SCORING_RESULT -> "Scoring result…"
                        TrainPhase.SAVING -> "Saving weights…"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = onCancel) { Text("Cancel") }
            }
        }

        is TrainState.Error ->
            Text(
                "Training failed: ${state.message}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )

        is TrainState.Done -> MetricsCard(state.metrics)
    }
}

@Composable
private fun MetricsCard(metrics: TrainMetrics) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                "Trained in ${formatMs(metrics.totalMs)}",
                style = MaterialTheme.typography.titleMedium,
            )
            MetricRow(
                metrics.statName,
                "%.4f → %.4f  (%+.1f%%)".format(
                    metrics.statBefore, metrics.statAfter, metrics.statChange,
                ),
            )
            MetricRow(
                "Train loss",
                "mean %.4f · last batch %.4f".format(metrics.meanLoss, metrics.lastLoss),
            )
            MetricRow(
                "Windows",
                "${metrics.batches * metrics.batchSize} trained in ${metrics.batches} batches of " +
                    "${metrics.batchSize} · ${metrics.samples} samples, ${metrics.dropped} without " +
                    "signal, ${metrics.remainder} left over",
            )
            MetricRow(
                "Update",
                "‖Δ‖ %.4f · max %.4f · %d weights".format(
                    metrics.updateNorm, metrics.updateMaxAbs, metrics.weightCount,
                ),
            )
            MetricRow(
                "Timing",
                "prepare ${formatMs(metrics.prepareMs)} · train ${formatMs(metrics.trainMs)} " +
                    "(${metrics.msPerBatch} ms/batch) · scoring ${formatMs(metrics.scoreMs)} · " +
                    "save ${formatMs(metrics.saveMs)}",
            )
            Text(
                "Scored on ${metrics.scoredBatches} of the ${metrics.batches} batches, drawn at random",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun MetricRow(label: String, value: String) {
    Column {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

private fun formatMs(ms: Long): String =
    if (ms < 1_000) "$ms ms" else "%.1f s".format(ms / 1_000f)

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
                    "${group.sampleCount} samples · ${group.signalCount} with signal",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (!group.hasNormParams) {
                    Text(
                        "Not processed — no normalization parameters",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            Button(onClick = onTrain, enabled = enabled) { Text("Train") }
        }
    }
}
