package app.somasafe.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothProfile
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

data class GattState(
  val gatt: BluetoothGatt?,
  val connectionState: Int,
  val mtu: Int,
  val services: List<BluetoothGattService> = emptyList(),
) {
  companion object {
    val Empty = GattState(null, -1, -1)
  }
}

fun Int.toConnectionString() = when (this) {
  BluetoothProfile.STATE_CONNECTED -> "Connected"
  BluetoothProfile.STATE_CONNECTING -> "Connecting"
  BluetoothProfile.STATE_DISCONNECTED -> "Disconnected"
  BluetoothProfile.STATE_DISCONNECTING -> "Disconnecting"
  else -> "N/A"
}

fun Int.toPropertiesString(): String = buildList {
  if (this@toPropertiesString and BluetoothGattCharacteristic.PROPERTY_READ != 0) add("READ")
  if (this@toPropertiesString and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) add("WRITE")
  if (this@toPropertiesString and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) add("WRITE_NO_RSP")
  if (this@toPropertiesString and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) add("NOTIFY")
  if (this@toPropertiesString and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) add("INDICATE")
}.joinToString("|")

@SuppressLint("MissingPermission")
@Composable
fun BLEConnectEffect(
  device: BluetoothDevice,
  lifecycleOwner: LifecycleOwner = LocalLifecycleOwner.current,
  onStateChange: (GattState) -> Unit,
) {
  val context = LocalContext.current
  val currentOnStateChange by rememberUpdatedState(onStateChange)

  val stateFlow = remember { MutableStateFlow(GattState.Empty) }

  DisposableEffect(lifecycleOwner, device) {
    val callback = object : BluetoothGattCallback() {
      override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
        stateFlow.update { it.copy(gatt = gatt, connectionState = newState) }
        currentOnStateChange(stateFlow.value)
        if (status != BluetoothGatt.GATT_SUCCESS) {
          Log.e("BLEConnect", "Connection error: $status")
          return
        }
        if (newState == BluetoothProfile.STATE_CONNECTED) {
          gatt.requestMtu(517)
        }
      }

      override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
        stateFlow.update { it.copy(gatt = gatt, mtu = mtu) }
        currentOnStateChange(stateFlow.value)
        gatt.discoverServices()
      }

      override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
        stateFlow.update { it.copy(services = gatt.services) }
        currentOnStateChange(stateFlow.value)
      }
    }

    val observer = LifecycleEventObserver { _, event ->
      if (event == Lifecycle.Event.ON_START) {
        val current = stateFlow.value
        if (current.gatt != null) {
          current.gatt.connect()
        } else {
          stateFlow.update { it.copy(gatt = device.connectGatt(context, false, callback)) }
        }
      } else if (event == Lifecycle.Event.ON_STOP) {
        stateFlow.value.gatt?.disconnect()
      }
    }

    lifecycleOwner.lifecycle.addObserver(observer)

    onDispose {
      lifecycleOwner.lifecycle.removeObserver(observer)
      stateFlow.value.gatt?.close()
      stateFlow.value = GattState.Empty
    }
  }
}
