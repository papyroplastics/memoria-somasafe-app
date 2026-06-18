package app.somasafe.bluetooth

import android.bluetooth.BluetoothDevice
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch

/**
 * Hold a [BleConnection] for [device], scoped to the calling composition: it
 * connects when [device] becomes non-null and closes when it changes or the
 * composable leaves the tree. Call it high enough in the tree (e.g. above the
 * tab switcher) for the connection to outlive the screen that uses it.
 */
@Composable
fun rememberBleConnection(device: BluetoothDevice?): BleConnection? {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val connection = remember(device) {
        device?.let { BleConnection(context.applicationContext, it) }
    }

    DisposableEffect(connection) {
        val job = connection?.let { conn -> scope.launch { runCatching { conn.connect() } } }
        onDispose {
            job?.cancel()
            connection?.close()
        }
    }

    return connection
}
