package app.somasafe.device

import android.content.Context
import android.util.Log
import app.somasafe.backend.completeAttestation
import app.somasafe.backend.fetchOwnedDevices
import app.somasafe.backend.requestChallenge
import app.somasafe.bluetooth.BleConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private const val TAG = "Attestation"

sealed interface AttestState {
    data object Idle : AttestState
    data object InProgress : AttestState
    data class Attested(val serial: String) : AttestState
    data class Error(val message: String) : AttestState
}

/**
 * Device-ownership attestation: read the device serial, have it sign a backend
 * challenge, and register ownership. Caches the backend's owned-serial list and
 * refreshes it on attestation rather than re-fetching on every read.
 */
class Attestation(
    private val context: Context,
    private val connection: BleConnection,
    private val scope: CoroutineScope,
) {
    private val _attest = MutableStateFlow<AttestState>(AttestState.Idle)
    val attest = _attest.asStateFlow()

    private val _ownedDevices = MutableStateFlow<List<String>>(emptyList())
    val ownedDevices = _ownedDevices.asStateFlow()

    init {
        refreshOwnership()
    }

    /** Pull the set of devices the backend still considers this client to own. */
    fun refreshOwnership() {
        scope.launch {
            fetchOwnedDevices(context)
                .onSuccess { _ownedDevices.value = it }
                .onFailure { Log.e(TAG, "failed to fetch owned devices", it) }
        }
    }

    /**
     * Prove ownership of the connected device: read its serial, request a
     * challenge from the backend, have the device sign the canonical payload,
     * and submit the signature. Refreshes the cached owned-device list on success.
     */
    fun attestDevice(onStatus: (String) -> Unit) {
        if (_attest.value is AttestState.InProgress) return
        scope.launch {
            _attest.value = AttestState.InProgress
            try {
                val device = DeviceService(connection)
                val serial = device.readSerial()
                val challenge = requestChallenge(context, serial).getOrThrow()
                val signature = device.sign(challenge.payload(serial))
                completeAttestation(context, challenge.instanceId, signature).getOrThrow()
                _attest.value = AttestState.Attested(serial)
                onStatus("Device \"$serial\" attested")
                refreshOwnership()
            } catch (e: Exception) {
                Log.e(TAG, "attestation failed", e)
                _attest.value = AttestState.Error(e.message ?: "attestation failed")
            }
        }
    }
}
