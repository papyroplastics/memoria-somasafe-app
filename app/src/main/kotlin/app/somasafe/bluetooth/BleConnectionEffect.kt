package app.somasafe.bluetooth

import android.bluetooth.BluetoothDevice
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch

/**
 * Create a [BleConnection] for [device] scoped to the current composition: it
 * connects when first composed and closes when the composable leaves the tree.
 */
@Composable
fun rememberBleConnection(device: BluetoothDevice): BleConnection {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val connection = remember(device) { BleConnection(context.applicationContext, device) }

    DisposableEffect(device) {
        val job = scope.launch { runCatching { connection.connect() } }
        onDispose {
            job.cancel()
            connection.close()
        }
    }

    return connection
}
