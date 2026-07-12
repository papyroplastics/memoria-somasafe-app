package app.somasafe.bluetooth.domain

import android.bluetooth.BluetoothGattCharacteristic
import android.util.Log
import app.somasafe.backend.data.LocalFirmware
import app.somasafe.backend.data.readFirmwareImage
import app.somasafe.bluetooth.data.BleConnection
import app.somasafe.bluetooth.data.SomaSafeUuids
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

private const val TAG = "FirmwareUpdate"
private const val VERIFY_TIMEOUT_MS = 30_000L

private const val OTA_STATE_IDLE = 0
private const val OTA_STATE_RECEIVING = 1
private const val OTA_STATE_VERIFYING = 2
private const val OTA_STATE_ERROR = 0xFF

sealed interface OtaState {
    data object Idle : OtaState
    data class Sending(val sent: Int, val total: Int) : OtaState
    data object Verifying : OtaState
    data class Done(val version: String) : OtaState
    data class Error(val message: String) : OtaState
}

/**
 * Client side of the firmware's BLE OTA service: streams a downloaded image and
 * its server signature to the device, which verifies it against its factory
 * srv_pub, switches the boot partition and restarts. The connection drops when
 * the device reboots — the phone is authoritative and performs no version
 * checks beyond having downloaded the image for its own interface version.
 */
class FirmwareUpdate(
    private val connection: BleConnection,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow<OtaState>(OtaState.Idle)
    val state = _state.asStateFlow()

    /** Read the device's running interface version and firmware version string. */
    suspend fun readVersion(): Pair<Int, String> {
        val chr = connection.characteristic(SomaSafeUuids.OTA_SVC, SomaSafeUuids.OTA_VERSION_CHR)
            ?: error("OTA version characteristic not found")
        val data = connection.readCharacteristic(chr)
        check(data.size >= 2) { "short OTA version read (${data.size} bytes)" }
        val interfaceVersion = (data[0].toInt() and 0xFF) or ((data[1].toInt() and 0xFF) shl 8)
        return interfaceVersion to data.copyOfRange(2, data.size).decodeToString()
    }

    fun install(firmware: LocalFirmware, onStatus: (String) -> Unit) {
        if (_state.value is OtaState.Sending || _state.value is OtaState.Verifying) return
        val signature = firmware.signature ?: run {
            _state.value = OtaState.Error("Image is unsigned; the device would reject it")
            return
        }

        scope.launch {
            val dataChr = connection.characteristic(SomaSafeUuids.OTA_SVC, SomaSafeUuids.OTA_DATA_CHR)
            val stateChr = connection.characteristic(SomaSafeUuids.OTA_SVC, SomaSafeUuids.OTA_STATE_CHR)
            val signatureChr = connection.characteristic(SomaSafeUuids.OTA_SVC, SomaSafeUuids.OTA_SIGNATURE_CHR)
            if (dataChr == null || stateChr == null || signatureChr == null) {
                _state.value = OtaState.Error("OTA service not found on the device")
                return@launch
            }

            val deviceState = MutableStateFlow(OTA_STATE_IDLE)
            try {
                val image = withContext(Dispatchers.IO) { readFirmwareImage(firmware) }
                _state.value = OtaState.Sending(0, image.size)

                connection.enableNotifications(stateChr) { data ->
                    if (data.isNotEmpty()) deviceState.value = data[0].toInt() and 0xFF
                }
                connection.writeCharacteristic(stateChr, u8(OTA_STATE_RECEIVING))

                write(signatureChr, signature)
                write(dataChr, image) { sent -> _state.value = OtaState.Sending(sent, image.size) }

                connection.writeCharacteristic(stateChr, u8(OTA_STATE_VERIFYING))
                _state.value = OtaState.Verifying
                val outcome = withTimeout(VERIFY_TIMEOUT_MS) {
                    deviceState.first { it == OTA_STATE_VERIFYING || it == OTA_STATE_ERROR }
                }
                if (outcome == OTA_STATE_ERROR) error("device rejected the update")

                _state.value = OtaState.Done(firmware.version)
                onStatus("Firmware ${firmware.version} verified — device is rebooting")
            } catch (e: Exception) {
                Log.e(TAG, "firmware update failed", e)
                runCatching { connection.writeCharacteristic(stateChr, u8(OTA_STATE_IDLE)) }
                _state.value = OtaState.Error(e.message ?: "update failed")
            } finally {
                runCatching { connection.disableNotifications(stateChr) }
            }
        }
    }

    private suspend fun write(
        chr: BluetoothGattCharacteristic,
        data: ByteArray,
        onProgress: (Int) -> Unit = {},
    ) {
        val chunk = connection.maxPayload.coerceAtLeast(20)
        var pos = 0
        while (pos < data.size) {
            val end = minOf(pos + chunk, data.size)
            connection.writeCharacteristic(chr, data.copyOfRange(pos, end), withResponse = true)
            pos = end
            onProgress(pos)
        }
    }
}
