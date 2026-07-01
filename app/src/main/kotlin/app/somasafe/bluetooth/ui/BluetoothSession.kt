package app.somasafe.bluetooth.ui

import android.bluetooth.BluetoothDevice
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import app.somasafe.bluetooth.data.BleConnection
import app.somasafe.bluetooth.domain.DeviceSession

/**
 * The live Bluetooth session: the selected [device] and, once one is picked, its
 * [connection] and [controller]. Obtained via [rememberBluetoothSession] high in
 * the tree so it outlives the screens that use it; screens read it through
 * [LocalBluetoothSession] and drive it with [select] / [disconnect].
 */
class BluetoothSession internal constructor(
    val device: BluetoothDevice?,
    val connection: BleConnection?,
    val controller: DeviceSession?,
    private val onSelect: (BluetoothDevice) -> Unit,
    private val onDisconnect: () -> Unit,
) {
    val isConnected: Boolean get() = device != null

    fun select(device: BluetoothDevice) = onSelect(device)

    fun disconnect() = onDisconnect()
}

/**
 * Own the device/connection/controller trio for the calling composition. Keeps
 * the connection (and its capture session) alive until the device changes or is
 * cleared via [BluetoothSession.disconnect]; it does not survive configuration
 * changes (the GATT is closed by [rememberBleConnection]).
 */
@Composable
fun rememberBluetoothSession(): BluetoothSession {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var device by remember { mutableStateOf<BluetoothDevice?>(null) }
    val connection = rememberBleConnection(device)
    val controller = remember(connection) {
        connection?.let { DeviceSession(context.applicationContext, it, scope) }
    }

    return remember(device, connection, controller) {
        BluetoothSession(
            device = device,
            connection = connection,
            controller = controller,
            onSelect = { device = it },
            onDisconnect = { device = null },
        )
    }
}

val LocalBluetoothSession = staticCompositionLocalOf<BluetoothSession> {
    error("No BluetoothSession provided; wrap the tree in ProvideBluetoothSession")
}

@Composable
fun ProvideBluetoothSession(content: @Composable () -> Unit) =
    CompositionLocalProvider(LocalBluetoothSession provides rememberBluetoothSession(), content = content)
