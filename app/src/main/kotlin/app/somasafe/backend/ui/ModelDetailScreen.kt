package app.somasafe.backend.ui

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
import app.somasafe.backend.data.QuantStatus
import app.somasafe.backend.data.RemoteModel
import app.somasafe.backend.data.WeightsStatus
import app.somasafe.backend.data.downloadQuantized
import app.somasafe.backend.data.loadModelMeta
import app.somasafe.backend.data.modelDir
import app.somasafe.backend.data.quantStatus
import app.somasafe.backend.data.submitOnly
import app.somasafe.backend.data.readTrainableBytes
import app.somasafe.backend.data.trainableFile
import app.somasafe.backend.data.uploadAndQuantize
import app.somasafe.backend.data.weightsStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import app.somasafe.training.domain.LiteRtModel
import app.somasafe.training.domain.ModelInfo
import app.somasafe.training.domain.QuantizationInfo
import app.somasafe.training.domain.SignatureInfo
import app.somasafe.training.domain.TensorInfo
import app.somasafe.training.domain.paramName

// Bare param names produced by the model's eval/train/save/restore signatures
// (see backend/ml/models/feature_mlp.py, cnn_autoencoder.py and common.py).
private val KNOWN_ROLES: Map<String, String> = mapOf(
    "features"        to "feature vector",
    "labels"          to "labels",
    "logits"          to "logit",
    "signal"          to "raw signal",
    "cond"            to "condition vector",
    "reconstruction"  to "reconstruction",
    "error"           to "reconstruction error",
    "loss"            to "training loss",
    "weights"         to "model weights",
)

@Composable
fun ModelDetailScreen(modelKey: String, modifier: Modifier = Modifier,
                      onOpenTraining: () -> Unit = {}, onDeleted: () -> Unit = {}) {
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
                LiteRtModel(readTrainableBytes(context, modelKey)).use { it.describe(modelKey) }
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

        ModelWeightsSection(modelKey = modelKey, meta = storedMeta)

        Button(onClick = onOpenTraining, modifier = Modifier.fillMaxWidth()) {
            Text("Train on capture…")
        }

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
private fun ModelWeightsSection(modelKey: String, meta: RemoteModel?) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var weights by remember(modelKey) { mutableStateOf(WeightsStatus.MISSING) }
    var quant by remember(modelKey) { mutableStateOf(QuantStatus.MISSING) }
    var busy by remember(modelKey) { mutableStateOf(false) }
    var message by remember(modelKey) { mutableStateOf<String?>(null) }

    suspend fun refresh() = withContext(Dispatchers.IO) {
        weights = weightsStatus(context, modelKey)
        quant = quantStatus(context, modelKey)
    }

    LaunchedEffect(modelKey, meta) { refresh() }

    fun run(action: suspend () -> String) {
        scope.launch {
            busy = true
            message = action()
            refresh()
            busy = false
        }
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        if (meta != null) run {
                            downloadQuantized(context, meta).fold(
                                onSuccess = { "Quantized model downloaded" },
                                onFailure = { "Download failed: ${it.message}" },
                            )
                        }
                    },
                    enabled = !busy && meta != null,
                ) { Text("Download quantized") }
            }

            // The federated upload paths, available once training produced weights.
            // "Upload & quantize" only applies to quantize-type models; a raw model
            // 404s on that endpoint, so only "Submit only" is offered for it.
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (meta?.supportsQuantizeSubmit == true) {
                    OutlinedButton(
                        onClick = {
                            run {
                                uploadAndQuantize(context, meta).fold(
                                    onSuccess = { "Update uploaded; personalized quantized model stored" },
                                    onFailure = { "Upload failed: ${it.message}" },
                                )
                            }
                        },
                        enabled = !busy && weights != WeightsStatus.MISSING,
                    ) { Text("Upload & quantize") }
                }

                OutlinedButton(
                    onClick = {
                        if (meta != null) run {
                            submitOnly(context, meta).fold(
                                onSuccess = { "Update submitted (#$it)" },
                                onFailure = { "Submit failed: ${it.message}" },
                            )
                        }
                    },
                    enabled = !busy && meta != null && weights != WeightsStatus.MISSING,
                ) { Text("Submit only") }
            }

            val weightsText = when (weights) {
                WeightsStatus.MISSING -> "Trained update: none"
                WeightsStatus.OUTDATED -> "Trained update: based on older weights"
                WeightsStatus.CURRENT -> "Trained update: ready"
            }
            val quantText = when (quant) {
                QuantStatus.MISSING -> "Quantized model: none"
                QuantStatus.OUTDATED -> "Quantized model: outdated"
                QuantStatus.CURRENT -> "Quantized model: up to date"
            }
            Text(
                "$weightsText  ·  $quantText",
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
            if (meta.firmwareId != null) {
                Text(
                    "Firmware: ≥ v${meta.firmwareId}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                "App: ≥ ${meta.minAppVersion}  ·  Contract: v${meta.contractVersion}",
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
            sig.inputs.forEach { TensorCard(it, sig.key) }
        }

        if (sig.outputs.isNotEmpty()) {
            Text(
                "Outputs",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
            sig.outputs.forEach { TensorCard(it, sig.key) }
        }
    }
}

@Composable
private fun TensorCard(tensor: TensorInfo, signature: String) {
    val paramName = tensor.paramName(signature)
    val role = KNOWN_ROLES[paramName]
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(paramName, style = MaterialTheme.typography.bodyMedium)
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
