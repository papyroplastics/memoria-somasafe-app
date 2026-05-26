package app.somasafe

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothProfile
import android.util.Log
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner

@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("MissingPermission")
@Composable
fun BLEApp() {
  BluetoothPermissionBox {
    var selectedDevice by remember { mutableStateOf<BluetoothDevice?>(null) }

    Scaffold(
      topBar = {
        TopAppBar(
          title = { Text("BLE Scanner") },
          colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
          ),
        )
      },
    ) { innerPadding ->
      AnimatedContent(
        targetState = selectedDevice,
        label = "device",
        modifier = Modifier.padding(innerPadding),
      ) { device ->
        if (device == null) {
          FindDevicesScreen { selectedDevice = it }
        } else {
          ConnectDeviceScreen(device) { selectedDevice = null }
        }
      }
    }
  }
}

@SuppressLint("MissingPermission")
@Composable
private fun ConnectDeviceScreen(device: BluetoothDevice, onClose: () -> Unit) {
  var state by remember(device) { mutableStateOf(GattState.Empty) }

  BLEConnectEffect(device) { state = it }

  Column(
    modifier = Modifier
    .fillMaxSize()
    .verticalScroll(rememberScrollState())
    .padding(16.dp),
    verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    Text("Device Details", style = MaterialTheme.typography.headlineSmall)
    Text("Name: ${device.name ?: "N/A"} (${device.address})")
    Text("Status: ${state.connectionState.toConnectionString()}")
    Text("MTU: ${if (state.mtu >= 0) state.mtu.toString() else "N/A"}")

    if (state.services.isNotEmpty()) {
      Text("Services:", style = MaterialTheme.typography.titleMedium)
      state.services.forEach { svc ->
        ServiceItem(svc)
      }
    }

    Button(onClick = onClose) { Text("Close") }
  }
}

@Composable
private fun ServiceItem(service: BluetoothGattService) {
  Column(
    modifier = Modifier.padding(start = 8.dp),
    verticalArrangement = Arrangement.spacedBy(2.dp),
  ) {
    Text(
      "Service: ${service.uuid}",
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.primary,
    )
    service.characteristics.forEach { chr ->
      CharacteristicItem(chr)
    }
  }
}

@Composable
private fun CharacteristicItem(characteristic: BluetoothGattCharacteristic) {
  Column(
    modifier = Modifier.padding(start = 16.dp),
    verticalArrangement = Arrangement.spacedBy(2.dp),
  ) {
    Text(
      "Chr: ${characteristic.uuid} [${characteristic.properties.toPropertiesString()}]",
      style = MaterialTheme.typography.bodySmall,
    )
    characteristic.descriptors.forEach { dsc ->
      DescriptorItem(dsc)
    }
  }
}

@Composable
private fun DescriptorItem(descriptor: BluetoothGattDescriptor) {
  Text(
    "Dsc: ${descriptor.uuid}",
    modifier = Modifier.padding(start = 24.dp),
    style = MaterialTheme.typography.bodySmall,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
  )
}

private fun Int.toPropertiesString(): String = buildList {
  if (this@toPropertiesString and BluetoothGattCharacteristic.PROPERTY_READ != 0) add("READ")
  if (this@toPropertiesString and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) add("WRITE")
  if (this@toPropertiesString and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) add("WRITE_NO_RSP")
  if (this@toPropertiesString and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) add("NOTIFY")
  if (this@toPropertiesString and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) add("INDICATE")
}.joinToString("|")

private data class GattState(
  val gatt: BluetoothGatt?,
  val connectionState: Int,
  val mtu: Int,
  val services: List<BluetoothGattService> = emptyList(),
) {
  companion object {
    val Empty = GattState(null, -1, -1)
  }
}

private fun Int.toConnectionString() = when (this) {
  BluetoothProfile.STATE_CONNECTED -> "Connected"
  BluetoothProfile.STATE_CONNECTING -> "Connecting"
  BluetoothProfile.STATE_DISCONNECTED -> "Disconnected"
  BluetoothProfile.STATE_DISCONNECTING -> "Disconnecting"
  else -> "N/A"
}

@SuppressLint("MissingPermission")
@Composable
private fun BLEConnectEffect(
  device: BluetoothDevice,
  lifecycleOwner: LifecycleOwner = LocalLifecycleOwner.current,
  onStateChange: (GattState) -> Unit,
) {
  val context = LocalContext.current
  val currentOnStateChange by rememberUpdatedState(onStateChange)

  var state by remember { mutableStateOf(GattState.Empty) }

  DisposableEffect(lifecycleOwner, device) {
    val callback = object : BluetoothGattCallback() {
      override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
        state = state.copy(gatt = gatt, connectionState = newState)
        currentOnStateChange(state)
        if (status != BluetoothGatt.GATT_SUCCESS) {
          Log.e("BLEConnect", "Connection error: $status")
          return
        }
        if (newState == BluetoothProfile.STATE_CONNECTED) {
          gatt.requestMtu(517)
        }
      }

      override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
        state = state.copy(gatt = gatt, mtu = mtu)
        currentOnStateChange(state)
        gatt.discoverServices()
      }

      override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
        state = state.copy(services = gatt.services)
        currentOnStateChange(state)
      }
    }

    val observer = LifecycleEventObserver { _, event ->
      if (event == Lifecycle.Event.ON_START) {
        if (state.gatt != null) {
          state.gatt?.connect()
        } else {
          state = state.copy(gatt = device.connectGatt(context, false, callback))
        }
      } else if (event == Lifecycle.Event.ON_STOP) {
        state.gatt?.disconnect()
      }
    }

    lifecycleOwner.lifecycle.addObserver(observer)

    onDispose {
      lifecycleOwner.lifecycle.removeObserver(observer)
      state.gatt?.close()
      state = GattState.Empty
    }
  }
}
