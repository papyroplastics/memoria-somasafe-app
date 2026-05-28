package app.somasafe.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@SuppressLint("MissingPermission")
@Composable
fun ConnectDeviceScreen(device: BluetoothDevice, modifier: Modifier = Modifier) {
  var state by remember(device) { mutableStateOf(GattState.Empty) }

  BLEConnectEffect(device) { state = it }

  Column(
    modifier = modifier
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
