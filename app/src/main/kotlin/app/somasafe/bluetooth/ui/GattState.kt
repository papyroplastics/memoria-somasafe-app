package app.somasafe.bluetooth.ui

import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothProfile

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
