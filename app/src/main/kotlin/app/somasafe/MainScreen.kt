package app.somasafe

import android.bluetooth.BluetoothDevice
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import app.somasafe.backend.BackendDownloadScreen
import app.somasafe.bluetooth.BluetoothPermissionBox
import app.somasafe.bluetooth.ConnectDeviceScreen
import app.somasafe.bluetooth.FindDevicesScreen
import app.somasafe.bluetooth.rememberBleConnection
import app.somasafe.capture.CaptureController
import app.somasafe.model.ModelDetailScreen
import app.somasafe.model.ModelListScreen

private sealed interface Screen {
    data object DeviceList : Screen
    data object DeviceDetail : Screen
    data object Backend : Screen
    data object ModelList : Screen
    data class ModelDetail(val key: String) : Screen
}

private enum class AppTab(val title: String, val icon: ImageVector) {
    BLUETOOTH("BLE Scanner", Icons.Rounded.Bluetooth),
    BACKEND("Backend", Icons.Rounded.Hub),
    MODEL("Model", Icons.Rounded.Memory),
}

private fun Screen.tab(): AppTab = when (this) {
    Screen.DeviceList, Screen.DeviceDetail -> AppTab.BLUETOOTH
    Screen.Backend -> AppTab.BACKEND
    Screen.ModelList, is Screen.ModelDetail -> AppTab.MODEL
}

private fun Screen.parent(): Screen? = when (this) {
    Screen.DeviceDetail -> Screen.DeviceList
    is Screen.ModelDetail -> Screen.ModelList
    else -> null
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var screen by remember { mutableStateOf<Screen>(Screen.DeviceList) }
    val tab = screen.tab()
    val parent = screen.parent()

    // Hoisted here so the connection (and its capture session) survives tab
    // switches; it lives until the device changes or we leave the detail screen.
    var device by remember { mutableStateOf<BluetoothDevice?>(null) }
    val connection = rememberBleConnection(device)
    val controller = remember(connection) {
        connection?.let { CaptureController(context.applicationContext, it, scope) }
    }

    fun goBack() {
        if (screen == Screen.DeviceDetail) device = null
        parent?.let { screen = it }
    }

    BackHandler(enabled = parent != null) { goBack() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(tab.title) },
                navigationIcon = {
                    if (parent != null) {
                        IconButton(onClick = { goBack() }) {
                            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                ),
            )
        },
        bottomBar = {
            NavigationBar {
                AppTab.entries.forEach { entry ->
                    NavigationBarItem(
                        selected = tab == entry,
                        onClick = {
                            screen = when (entry) {
                                // Return to the live device rather than the scanner
                                // when a connection is still open.
                                AppTab.BLUETOOTH ->
                                    if (device != null) Screen.DeviceDetail else Screen.DeviceList
                                AppTab.BACKEND -> Screen.Backend
                                AppTab.MODEL -> Screen.ModelList
                            }
                        },
                        icon = { Icon(entry.icon, contentDescription = entry.title) },
                        label = { Text(entry.title) },
                    )
                }
            }
        },
    ) { innerPadding ->
        val modifier = Modifier.padding(innerPadding)
        when (val current = screen) {
            Screen.DeviceList -> BluetoothPermissionBox {
                FindDevicesScreen(modifier) {
                    device = it
                    screen = Screen.DeviceDetail
                }
            }
            Screen.DeviceDetail -> BluetoothPermissionBox {
                val d = device
                val conn = connection
                val ctrl = controller
                if (d != null && conn != null && ctrl != null) {
                    ConnectDeviceScreen(d, conn, ctrl, modifier)
                } else {
                    // Device was cleared out from under us; fall back to the list.
                    LaunchedEffect(Unit) { screen = Screen.DeviceList }
                }
            }
            Screen.Backend -> BackendDownloadScreen(modifier)
            Screen.ModelList -> ModelListScreen(modifier) { screen = Screen.ModelDetail(it) }
            is Screen.ModelDetail -> ModelDetailScreen(current.key, modifier) { screen = Screen.ModelList }
        }
    }
}
