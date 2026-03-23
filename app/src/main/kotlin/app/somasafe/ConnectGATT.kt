package app.somasafe

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.random.Random

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
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var state by remember(device) { mutableStateOf(GattState.Empty) }

    val discoveredServices = state.services
    val firstCharacteristic = discoveredServices
        .flatMap { it.characteristics }
        .firstOrNull()

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
        Text("MTU: ${state.mtu}")
        Text("Services: ${discoveredServices.joinToString { it.uuid.toString() }}")
        Text("Message sent: ${state.messageSent}")
        Text("Message received: ${state.messageReceived}")

        Button(onClick = {
            scope.launch(Dispatchers.IO) {
                if (state.connectionState == BluetoothProfile.STATE_DISCONNECTED) {
                    state.gatt?.connect()
                }
                state.gatt?.requestMtu(Random.nextInt(27, 190))
            }
        }) { Text("Request MTU") }

        Button(
            enabled = state.gatt != null,
            onClick = {
                scope.launch(Dispatchers.IO) { state.gatt?.discoverServices() }
            },
        ) { Text("Discover Services") }

        Button(
            enabled = state.gatt != null && firstCharacteristic != null,
            onClick = {
                scope.launch(Dispatchers.IO) {
                    writeCharacteristic(context, state.gatt!!, firstCharacteristic!!)
                }
            },
        ) { Text("Write to Device") }

        Button(
            enabled = state.gatt != null && firstCharacteristic != null,
            onClick = {
                scope.launch(Dispatchers.IO) {
                    state.gatt?.readCharacteristic(firstCharacteristic)
                }
            },
        ) { Text("Read Characteristic") }

        Button(onClick = onClose) { Text("Close") }
    }
}

private fun writeCharacteristic(
    context: android.content.Context,
    gatt: BluetoothGatt,
    characteristic: BluetoothGattCharacteristic,
) {
    val appName = context.applicationInfo.loadLabel(context.packageManager)
    val data = "Hello from $appName!".toByteArray()
    gatt.writeCharacteristic(
        characteristic,
        data,
        BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
    )
}

private data class GattState(
    val gatt: BluetoothGatt?,
    val connectionState: Int,
    val mtu: Int,
    val services: List<BluetoothGattService> = emptyList(),
    val messageSent: Boolean = false,
    val messageReceived: String = "",
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
                }
            }

            override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
                state = state.copy(gatt = gatt, mtu = mtu)
                currentOnStateChange(state)
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                state = state.copy(services = gatt.services)
                currentOnStateChange(state)
            }

            override fun onCharacteristicWrite(
                gatt: BluetoothGatt?,
                characteristic: BluetoothGattCharacteristic?,
                status: Int,
            ) {
                state = state.copy(messageSent = status == BluetoothGatt.GATT_SUCCESS)
                currentOnStateChange(state)
            }

            override fun onCharacteristicRead(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray,
                status: Int,
            ) {
                handleRead(value)
            }

            private fun handleRead(value: ByteArray) {
                state = state.copy(messageReceived = value.decodeToString())
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
