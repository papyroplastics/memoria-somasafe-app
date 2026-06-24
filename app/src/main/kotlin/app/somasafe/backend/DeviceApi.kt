package app.somasafe.backend

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/**
 * Device ownership attestation against the backend `/device` namespace. The
 * device proves control of a serial by signing a server-issued challenge; see
 * backend/api/device.py for the protocol and the canonical payload layout.
 */
data class DeviceChallenge(
    val instanceId: String,
    val nonce: ByteArray,
    val serverTime: Long,
    val userId: Long,
) {
    /**
     * The canonical payload the device signs, matching backend/api/device.py:
     *   nonce(32) || instance_id(16) || server_time(u64 BE) || user_id(u64 BE) || serial(ascii)
     */
    fun payload(serial: String): ByteArray {
        val uuid = UUID.fromString(instanceId)
        return ByteBuffer.allocate(nonce.size + 16 + 8 + 8 + serial.length)
            .order(ByteOrder.BIG_ENDIAN)
            .put(nonce)
            .putLong(uuid.mostSignificantBits)
            .putLong(uuid.leastSignificantBits)
            .putLong(serverTime)
            .putLong(userId)
            .put(serial.toByteArray(Charsets.US_ASCII))
            .array()
    }
}

/** Serials of the devices the backend still considers this client to own. */
suspend fun fetchOwnedDevices(context: Context): Result<List<String>> =
    authedRequest(context, "$BACKEND_URL/device/owned") { connection ->
        val array = JSONArray(connection.inputStream.bufferedReader().readText())
        List(array.length()) { i -> array.getString(i) }
    }

suspend fun requestChallenge(context: Context, serial: String): Result<DeviceChallenge> =
    authedRequest(
        context, "$BACKEND_URL/device/challenge",
        configure = { connection ->
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use {
                it.write(JSONObject().put("serial", serial).toString().toByteArray())
            }
        },
        onSuccess = { connection ->
            val o = JSONObject(connection.inputStream.bufferedReader().readText())
            DeviceChallenge(
                instanceId = o.getString("instance_id"),
                nonce = Base64.decode(o.getString("nonce"), Base64.DEFAULT),
                serverTime = o.getLong("server_time"),
                userId = o.getLong("user_id"),
            )
        },
    )

suspend fun completeAttestation(
    context: Context,
    instanceId: String,
    signature: ByteArray,
): Result<Unit> =
    authedRequest(
        context, "$BACKEND_URL/device/attest",
        configure = { connection ->
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            val body = JSONObject()
                .put("instance_id", instanceId)
                .put("signature", Base64.encodeToString(signature, Base64.NO_WRAP))
            connection.outputStream.use { it.write(body.toString().toByteArray()) }
        },
        onSuccess = { connection ->
            connection.inputStream.close()
        },
    )
