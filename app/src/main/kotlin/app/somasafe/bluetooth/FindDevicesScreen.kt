package app.somasafe.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.os.ParcelUuid
import android.util.Log
import java.util.UUID
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

private val ML_SERVICE_UUID = UUID.fromString("a4523840-7543-2492-fe43-b7dad4432738")
private val PPG_SERVICE_UUID = UUID.fromString("c7e4f210-3a8b-4d56-9c2f-1e7b0a5d3c8e")

@SuppressLint("MissingPermission")
@Composable
fun FindDevicesScreen(modifier: Modifier = Modifier, onDeviceSelected: (BluetoothDevice) -> Unit) {
  val context = LocalContext.current
  val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return

  var scanning by remember { mutableStateOf(true) }
  val devices = remember { mutableStateListOf<BluetoothDevice>() }
  val pairedDevices = remember {
    mutableStateListOf(*adapter.bondedDevices.toTypedArray())
  }

  val scanSettings = ScanSettings.Builder()
  .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
  .setScanMode(ScanSettings.SCAN_MODE_BALANCED)
  .setLegacy(false)
  .setPhy(ScanSettings.PHY_LE_ALL_SUPPORTED)
  .build()

  if (scanning) {
    BLEScanEffect(
      scanSettings = scanSettings,
      onScanFailed = {
        scanning = false
        Log.w("FindBLEDevices", "Scan failed: $it")
      },
      onDeviceFound = { result ->
        if (!devices.contains(result.device)) {
          devices.add(result.device)
        }
      },
    )
    LaunchedEffect(true) {
      delay(15_000)
      scanning = false
    }
  }

  LazyColumn(
    modifier = modifier
    .fillMaxSize()
    .padding(16.dp),
    verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    item {
      Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text("Available devices", style = MaterialTheme.typography.titleLarge)
        if (scanning) {
          CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
        } else {
          IconButton(onClick = {
            devices.clear()
            scanning = true
          }) {
            Icon(Icons.Rounded.Refresh, contentDescription = "Rescan")
          }
        }
      }
    }

    if (devices.isEmpty()) {
      item {
        Text(
          "No devices found",
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
    items(devices) { device ->
      DeviceItem(device, onClick = { onDeviceSelected(device) })
    }

    if (pairedDevices.isNotEmpty()) {
      item {
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
      }
      item {
        Text("Paired devices", style = MaterialTheme.typography.titleLarge)
      }
      items(pairedDevices) { device ->
        DeviceItem(device, onClick = { onDeviceSelected(device) })
      }
    }
  }
}

@SuppressLint("MissingPermission")
@Composable
private fun DeviceItem(device: BluetoothDevice, onClick: () -> Unit) {
  Card(
    onClick = onClick,
    modifier = Modifier.fillMaxWidth(),
  ) {
    Row(
      modifier = Modifier
      .fillMaxWidth()
      .padding(12.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(
        text = device.name ?: "Unknown",
        modifier = Modifier.weight(4f),
        style = MaterialTheme.typography.bodyMedium,
      )
      Text(
        text = device.address,
        modifier = Modifier.weight(4f),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      Text(
        text = when (device.bondState) {
          BluetoothDevice.BOND_BONDED -> "Paired"
          BluetoothDevice.BOND_BONDING -> "Pairing"
          else -> "None"
        },
        modifier = Modifier.weight(2f),
        style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.End,
      )
    }
  }
}

@SuppressLint("MissingPermission")
@Composable
private fun BLEScanEffect(
  scanSettings: ScanSettings,
  onScanFailed: (Int) -> Unit,
  onDeviceFound: (ScanResult) -> Unit,
) {
  val context = LocalContext.current
  val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
  if (adapter == null) {
    onScanFailed(ScanCallback.SCAN_FAILED_INTERNAL_ERROR)
    return
  }

  val currentOnDeviceFound by rememberUpdatedState(onDeviceFound)

  DisposableEffect(scanSettings) {
    val callback = object : ScanCallback() {
      override fun onScanResult(callbackType: Int, result: ScanResult) {
        currentOnDeviceFound(result)
      }

      override fun onScanFailed(errorCode: Int) {
        onScanFailed(errorCode)
      }
    }

    val filters = listOf(
      ScanFilter.Builder().setServiceUuid(ParcelUuid(ML_SERVICE_UUID)).build(),
      ScanFilter.Builder().setServiceUuid(ParcelUuid(PPG_SERVICE_UUID)).build()
    )
    adapter.bluetoothLeScanner?.startScan(filters, scanSettings, callback)

    onDispose {
      adapter.bluetoothLeScanner?.stopScan(callback)
    }
  }
}
