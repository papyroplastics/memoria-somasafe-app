package app.somasafe.backend

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private sealed interface ModelListState {
    data object Loading : ModelListState
    data class Loaded(val models: List<RemoteModel>) : ModelListState
    data class Error(val message: String) : ModelListState
}

@Composable
fun BackendDownloadScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var loggedIn by remember { mutableStateOf(AuthStore.isLoggedIn(context)) }
    var username by remember { mutableStateOf(AuthStore.username(context).orEmpty()) }

    var listState by remember { mutableStateOf<ModelListState>(ModelListState.Loading) }
    val downloadStates = remember { mutableStateMapOf<String, DownloadState>() }
    val quantizeStates = remember { mutableStateMapOf<String, DownloadState>() }
    val localMetas = remember { mutableStateMapOf<String, RemoteModel>() }
    val weightsPresent = remember { mutableStateMapOf<String, Boolean>() }

    LaunchedEffect(loggedIn) {
        if (!loggedIn) {
            listState = ModelListState.Loading
            return@LaunchedEffect
        }
        listState = fetchModels(context).fold(
            onSuccess = { ModelListState.Loaded(it) },
            onFailure = { ModelListState.Error(it.message ?: "Unknown error") },
        )
    }

    LaunchedEffect(listState) {
        val loaded = listState as? ModelListState.Loaded ?: return@LaunchedEffect
        withContext(Dispatchers.IO) {
            loaded.models.forEach { model ->
                loadModelMeta(context, model.key)?.let { localMetas[model.key] = it }
                weightsPresent[model.key] = weightsFile(context, model.key).exists()
            }
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

        SessionHeader(
            loggedIn = loggedIn,
            username = username,
            onSignedIn = { user ->
                username = user
                loggedIn = true
            },
            onSignedOut = {
                loggedIn = false
                localMetas.clear()
                weightsPresent.clear()
                downloadStates.clear()
                quantizeStates.clear()
            },
        )

        HorizontalDivider()

        if (!loggedIn) {
            Text(
                "Sign in to browse and download models.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Column
        }

        when (val state = listState) {
            ModelListState.Loading ->
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text("Loading models…", style = MaterialTheme.typography.bodyMedium)
                }

            is ModelListState.Error ->
                Text(
                    "Failed to load models: ${state.message}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )

            is ModelListState.Loaded ->
                state.models.forEach { model ->
                    ModelDownloadCard(
                        model = model,
                        localMeta = localMetas[model.key],
                        state = downloadStates[model.key] ?: DownloadState.Idle,
                        quantizeState = quantizeStates[model.key] ?: DownloadState.Idle,
                        showQuantize = localMetas[model.key] != null,
                        quantizeEnabled = weightsPresent[model.key] == true,
                        onDownload = {
                            scope.launch {
                                downloadStates[model.key] = DownloadState.InProgress
                                val dest = File(modelDir(context, model.key), TRAINABLE_FILENAME)
                                val result = downloadModel(context, model.trainableEndpoint, dest)
                                if (result.isSuccess) {
                                    withContext(Dispatchers.IO) { saveModelMeta(context, model) }
                                    localMetas[model.key] = model
                                    downloadStates[model.key] = DownloadState.Done(dest.absolutePath)
                                } else {
                                    downloadStates[model.key] = DownloadState.Error(
                                        result.exceptionOrNull()?.message ?: "Unknown error"
                                    )
                                }
                            }
                        },
                        onQuantize = {
                            scope.launch {
                                quantizeStates[model.key] = DownloadState.InProgress
                                quantizeStates[model.key] = downloadQuantized(context, model).fold(
                                    onSuccess = { DownloadState.Done(quantizedFile(context, model.key).absolutePath) },
                                    onFailure = { DownloadState.Error(it.message ?: "Unknown error") },
                                )
                            }
                        },
                    )
                }
        }
    }
}

@Composable
private fun SessionHeader(
    loggedIn: Boolean,
    username: String,
    onSignedIn: (String) -> Unit,
    onSignedOut: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    if (loggedIn) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Signed in as $username",
                style = MaterialTheme.typography.bodyMedium,
            )
            TextButton(onClick = {
                scope.launch {
                    logout(context)
                    onSignedOut()
                }
            }) { Text("Log out") }
        }
        return
    }

    var user by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var signingIn by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Sign in", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = user,
            onValueChange = { user = it },
            label = { Text("Username") },
            singleLine = true,
            enabled = !signingIn,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Password") },
            singleLine = true,
            enabled = !signingIn,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
        )
        error?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        Button(
            enabled = !signingIn && user.isNotBlank() && password.isNotBlank(),
            onClick = {
                scope.launch {
                    signingIn = true
                    error = null
                    login(context, user.trim(), password).fold(
                        onSuccess = { onSignedIn(user.trim()) },
                        onFailure = { error = it.message ?: "Sign in failed" },
                    )
                    signingIn = false
                }
            },
        ) {
            if (signingIn) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            } else {
                Text("Sign in")
            }
        }
    }
}

@Composable
private fun ModelDownloadCard(
    model: RemoteModel,
    localMeta: RemoteModel?,
    state: DownloadState,
    quantizeState: DownloadState,
    showQuantize: Boolean,
    quantizeEnabled: Boolean,
    onDownload: () -> Unit,
    onQuantize: () -> Unit,
) {
    // Up to date only if both the architecture and the weights match upstream.
    val isUpToDate = localMeta != null &&
        localMeta.fingerprint == model.fingerprint &&
        localMeta.weightsVersion == model.weightsVersion

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(model.name, style = MaterialTheme.typography.titleMedium)
            Text(
                model.purpose,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (localMeta != null) {
                Text(
                    "Downloaded: v${localMeta.version}  ·  ${localMeta.fingerprint.take(8)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (!isUpToDate) {
                val upstream = buildString {
                    append("Upstream: v${model.version}  ·  ${model.fingerprint.take(8)}")
                    model.weightsVersion?.let { append("  ·  ${it.take(10)}") }
                }
                Text(
                    upstream,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            when (state) {
                DownloadState.InProgress ->
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text("Downloading…", style = MaterialTheme.typography.bodyMedium)
                    }

                is DownloadState.Error -> {
                    Text(
                        "Error: ${state.message}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Button(onClick = onDownload) { Text("Retry") }
                }

                else ->
                    if (isUpToDate) {
                        Button(
                            onClick = onDownload,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            ),
                        ) { Text("Download") }
                    } else {
                        Button(onClick = onDownload) {
                            Text(if (localMeta == null) "Download" else "Update")
                        }
                    }
            }

            if (showQuantize) {
                QuantizeRow(quantizeState, quantizeEnabled, onQuantize)
            }
        }
    }
}

@Composable
private fun QuantizeRow(state: DownloadState, enabled: Boolean, onQuantize: () -> Unit) {
    when (state) {
        DownloadState.InProgress ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                Text("Quantizing…", style = MaterialTheme.typography.bodyMedium)
            }

        is DownloadState.Error -> {
            Text(
                "Quantize error: ${state.message}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            OutlinedButton(onClick = onQuantize, enabled = enabled) { Text("Retry quantize") }
        }

        is DownloadState.Done ->
            OutlinedButton(onClick = onQuantize, enabled = enabled) { Text("Re-quantize") }

        DownloadState.Idle -> {
            OutlinedButton(onClick = onQuantize, enabled = enabled) { Text("Quantize") }
            if (!enabled) {
                Text(
                    "Extract weights on the Model tab first.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
