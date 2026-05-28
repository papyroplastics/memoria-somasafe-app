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
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun ModelDetailScreen(model: File, modifier: Modifier = Modifier) {
    var modelInfo by remember { mutableStateOf<ModelInfo?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(model) {
        modelInfo = null
        error = null
        runCatching {
            withContext(Dispatchers.Default) {
                LiteRtModel(model.absolutePath).use { it.describe(model.nameWithoutExtension) }
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
        Text(model.name, style = MaterialTheme.typography.headlineSmall)

        Text(
            formatFileSize(model.length()),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

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
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(tensor.name, style = MaterialTheme.typography.bodyMedium)
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
