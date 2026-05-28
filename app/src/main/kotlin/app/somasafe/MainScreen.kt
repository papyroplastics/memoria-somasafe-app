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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import app.somasafe.backend.BackendDownloadScreen
import app.somasafe.bluetooth.BluetoothPermissionBox
import app.somasafe.bluetooth.ConnectDeviceScreen
import app.somasafe.bluetooth.FindDevicesScreen
import app.somasafe.model.ModelDetailScreen
import app.somasafe.model.ModelListScreen
import java.io.File

private sealed interface Screen {
  data object DeviceList : Screen
  data class DeviceDetail(val device: BluetoothDevice) : Screen
  data object Backend : Screen
  data object ModelList : Screen
  data class ModelDetail(val model: File) : Screen
}

private enum class AppTab(val title: String, val icon: ImageVector) {
  BLUETOOTH("BLE Scanner", Icons.Rounded.Bluetooth),
  BACKEND("Backend", Icons.Rounded.Hub),
  MODEL("Model", Icons.Rounded.Memory),
}

private fun Screen.tab(): AppTab = when (this) {
  Screen.DeviceList, is Screen.DeviceDetail -> AppTab.BLUETOOTH
  Screen.Backend -> AppTab.BACKEND
  Screen.ModelList, is Screen.ModelDetail -> AppTab.MODEL
}

private fun Screen.parent(): Screen? = when (this) {
  is Screen.DeviceDetail -> Screen.DeviceList
  is Screen.ModelDetail -> Screen.ModelList
  else -> null
}

private fun AppTab.defaultScreen(): Screen = when (this) {
  AppTab.BLUETOOTH -> Screen.DeviceList
  AppTab.BACKEND -> Screen.Backend
  AppTab.MODEL -> Screen.ModelList
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen() {
  var screen by remember { mutableStateOf<Screen>(Screen.DeviceList) }
  val tab = screen.tab()
  val parent = screen.parent()

  BackHandler(enabled = parent != null) {
    parent?.let { screen = it }
  }

  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text(tab.title) },
        navigationIcon = {
          if (parent != null) {
            IconButton(onClick = { screen = parent }) {
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
            onClick = { screen = entry.defaultScreen() },
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
        FindDevicesScreen(modifier) { screen = Screen.DeviceDetail(it) }
      }
      is Screen.DeviceDetail -> BluetoothPermissionBox {
        ConnectDeviceScreen(current.device, modifier)
      }
      Screen.Backend -> BackendDownloadScreen(modifier)
      Screen.ModelList -> ModelListScreen(modifier) { screen = Screen.ModelDetail(it) }
      is Screen.ModelDetail -> ModelDetailScreen(current.model, modifier)
    }
  }
}
