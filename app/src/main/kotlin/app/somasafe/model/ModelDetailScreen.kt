package app.somasafe.model

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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.material3.Text
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
import app.somasafe.backend.RemoteModel
import app.somasafe.backend.loadModelMeta
import app.somasafe.backend.modelDir
import app.somasafe.backend.trainableFile
import app.somasafe.backend.weightsFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val KNOWN_ROLES: Map<String, String> = mapOf(
    "feature"         to "feature vector",
    "signal"          to "raw signal",
    "label"           to "labels",
    "score"           to "anomaly score",
    "logit"           to "logit",
    "loss"            to "training loss",
    "parameters"      to "model weights",
    "parameter_count" to "parameter count",
)

private fun TensorInfo.role(): String? = KNOWN_ROLES[name]

@Composable
fun ModelDetailScreen(modelKey: String, modifier: Modifier = Modifier, onDeleted: () -> Unit = {}) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val modelFile = trainableFile(context, modelKey)
    var storedMeta by remember { mutableStateOf<RemoteModel?>(null) }
    var modelInfo by remember { mutableStateOf<ModelInfo?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(modelKey) {
        storedMeta = null
        modelInfo = null
        error = null
        runCatching {
            withContext(Dispatchers.Default) {
                storedMeta = withContext(Dispatchers.IO) { loadModelMeta(context, modelKey) }
                LiteRtModel(modelFile.absolutePath).use { it.describe(modelKey) }
            }
        }.fold(
            onSuccess = { modelInfo = it },
            onFailure = { error = it.message ?: "Unknown error" },
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(storedMeta?.name ?: modelKey, style = MaterialTheme.typography.headlineSmall)

        Text(
            formatFileSize(modelFile.length()),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        ModelPrepSection(modelKey = modelKey, meta = storedMeta)

        storedMeta?.let { ModelMetaCard(it) }

        HorizontalDivider()

        when {
            error != null ->
                Text(
                    "Failed to inspect model: $error",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )

            modelInfo == null ->
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text("Loading model…", style = MaterialTheme.typography.bodyMedium)
                }

            else ->
                ModelInfoContent(modelInfo!!)
        }

        HorizontalDivider()

        Button(
            onClick = {
                scope.launch {
                    withContext(Dispatchers.IO) { modelDir(context, modelKey).deleteRecursively() }
                    onDeleted()
                }
            },
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
            ),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Delete Model")
        }
    }
}

@Composable
private fun ModelPrepSection(modelKey: String, meta: RemoteModel?) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var weightsPresent by remember(modelKey) { mutableStateOf(false) }
    var quant by remember(modelKey) { mutableStateOf(QuantStatus.MISSING) }
    var busy by remember(modelKey) { mutableStateOf(false) }
    var message by remember(modelKey) { mutableStateOf<String?>(null) }

    suspend fun refresh() = withContext(Dispatchers.IO) {
        weightsPresent = weightsFile(context, modelKey).exists()
        quant = ModelPrep.quantStatus(context, modelKey)
    }

    LaunchedEffect(modelKey) { refresh() }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            busy = true
                            message = ModelPrep.extractWeights(context, modelKey).fold(
                                onSuccess = { "Weights extracted" },
                                onFailure = { "Extract failed: ${it.message}" },
                            )
                            refresh()
                            busy = false
                        }
                    },
                    enabled = !busy,
                ) { Text("Extract weights") }

                Button(
                    onClick = {
                        if (meta != null) {
                            scope.launch {
                                busy = true
                                message = ModelPrep.extractAndQuantize(context, meta).fold(
                                    onSuccess = { "Quantized model downloaded" },
                                    onFailure = { "Quantize failed: ${it.message}" },
                                )
                                refresh()
                                busy = false
                            }
                        }
                    },
                    enabled = !busy && meta != null,
                ) { Text("Download quantized") }
            }

            val quantText = when (quant) {
                QuantStatus.MISSING -> "Quantized model: none"
                QuantStatus.OUTDATED -> "Quantized model: outdated (re-quantize)"
                QuantStatus.CURRENT -> "Quantized model: up to date"
            }
            Text(
                "Weights: ${if (weightsPresent) "extracted" else "none"}  ·  $quantText",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            message?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
            }
        }
    }
}

@Composable
private fun ModelMetaCard(meta: RemoteModel) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(meta.name, style = MaterialTheme.typography.titleSmall)
                Text(
                    "v${meta.version}  ·  ${meta.fingerprint.take(8)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                meta.purpose,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (meta.firmwareId != null) {
                Text(
                    "Firmware: ≥ v${meta.firmwareId}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                "App: ≥ ${meta.appVersion}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ModelInfoContent(info: ModelInfo) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        info.signatures.forEach { sig ->
            SignatureSection(sig)
        }
    }
}

@Composable
private fun SignatureSection(sig: SignatureInfo) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "Signature: ${sig.key}",
            style = MaterialTheme.typography.titleMedium,
        )

        if (sig.inputs.isNotEmpty()) {
            Text(
                "Inputs",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
            sig.inputs.forEach { TensorCard(it) }
        }

        if (sig.outputs.isNotEmpty()) {
            Text(
                "Outputs",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
            sig.outputs.forEach { TensorCard(it) }
        }
    }
}

@Composable
private fun TensorCard(tensor: TensorInfo) {
    val role = tensor.role()
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(tensor.name, style = MaterialTheme.typography.bodyMedium)
                if (role != null) {
                    SuggestionChip(
                        onClick = {},
                        label = { Text(role, style = MaterialTheme.typography.labelSmall) },
                        colors = SuggestionChipDefaults.suggestionChipColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                            labelColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        ),
                    )
                }
            }
            Text(
                "${tensor.elementType} • ${tensor.shape.toShapeString(tensor.ranked)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            tensor.quantization.toDisplayString()?.let { quantStr ->
                Text(
                    quantStr,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
        }
    }
}

private fun IntArray.toShapeString(ranked: Boolean): String {
    if (!ranked) return "unranked"
    if (isEmpty()) return "scalar"
    return joinToString(", ", "[", "]") { if (it == -1) "?" else it.toString() }
}

private fun QuantizationInfo.toDisplayString(): String? = when (type) {
    "per_tensor"  -> "Quantized  scale=$scale  zp=$zeroPoint"
    "per_channel" -> "Per-channel  dim=$quantizedDim  ${scales.size} channels"
    "block_wise"  -> "Block-wise quantized"
    "unknown"     -> "Unknown quantization"
    else          -> null
}
