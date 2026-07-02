package app.somasafe.capture.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import kotlinx.coroutines.launch
import app.somasafe.capture.data.CaptureRepository
import app.somasafe.capture.data.Demographics
import app.somasafe.capture.data.loadDemographics
import app.somasafe.capture.data.saveDemographics

/**
 * Editable form for the default static (demographics) vector stamped onto captures
 * received from the ESP. It is the on-device counterpart of the DaLiA questionnaire:
 * the trainer needs these 6 values to build the autoencoder's conditioning vector.
 * Imported datasets carry their own static and are unaffected by this.
 */
@Composable
fun DemographicsScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repository = remember { CaptureRepository(context.applicationContext) }
    val initial = remember { loadDemographics(context) ?: Demographics.DEFAULT }

    var female by remember { mutableStateOf(initial.female) }
    var age by remember { mutableStateOf(initial.age.toString()) }
    var height by remember { mutableStateOf(initial.height.toString()) }
    var weight by remember { mutableStateOf(initial.weight.toString()) }
    var skin by remember { mutableStateOf(initial.skin.toString()) }
    var sport by remember { mutableStateOf(initial.sport.toString()) }
    var saved by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Demographics", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Default static data applied to captures from the device. Used as the " +
                "conditioning vector for on-device training.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = !female, onClick = { female = false; saved = false }, label = { Text("Male") })
            FilterChip(selected = female, onClick = { female = true; saved = false }, label = { Text("Female") })
        }

        NumberField(age, { age = it; saved = false }, "Age (years)")
        NumberField(height, { height = it; saved = false }, "Height (cm)")
        NumberField(weight, { weight = it; saved = false }, "Weight (kg)")
        NumberField(skin, { skin = it; saved = false }, "Skin type (1–6)")
        NumberField(sport, { sport = it; saved = false }, "Sport level (0–6)")

        val parsed = runCatching {
            Demographics(
                female = female,
                age = age.trim().toFloat(),
                height = height.trim().toFloat(),
                weight = weight.trim().toFloat(),
                skin = skin.trim().toFloat(),
                sport = sport.trim().toFloat(),
            )
        }.getOrNull()

        Button(
            onClick = {
                parsed?.let { d ->
                    saveDemographics(context, d)
                    scope.launch { repository.fillMissingStatic(d.toBytes()) }
                    saved = true
                }
            },
            enabled = parsed != null,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Save") }

        when {
            parsed == null -> Text(
                "Enter valid numbers for every field.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            saved -> Text(
                "Saved.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary,
            )
        }
    }
}

@Composable
private fun NumberField(value: String, onChange: (String) -> Unit, label: String) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
    )
}
