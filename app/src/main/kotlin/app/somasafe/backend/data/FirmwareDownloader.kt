package app.somasafe.backend.data

import android.content.Context
import app.somasafe.bluetooth.data.BLE_INTERFACE_VERSION
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Base64

const val FIRMWARE_SIGNATURE_HEADER = "X-Firmware-Signature"
const val FIRMWARE_IMAGE_FILENAME = "firmware.bin"
const val FIRMWARE_META_FILENAME = "meta.json"

fun firmwareDir(context: Context): File = File(context.filesDir, "firmware")

fun firmwareVersionDir(context: Context, version: String): File =
    File(firmwareDir(context), version)

/** A firmware build published by the backend for this app's BLE interface version. */
data class RemoteFirmware(
    val version: String,
    val interfaceVersion: Int,
    val supportedContracts: List<Int>,
    val size: Long,
    val createdAt: String,
) {
    val downloadEndpoint: String get() = "$BACKEND_URL/ota/download/$interfaceVersion/$version"

    fun toJson(): JSONObject = JSONObject().apply {
        put("version", version)
        put("interface_version", interfaceVersion)
        put("supported_contracts", JSONArray(supportedContracts))
        put("size", size)
        put("created_at", createdAt)
    }

    companion object {
        fun fromJson(obj: JSONObject): RemoteFirmware = RemoteFirmware(
            version = obj.getString("version"),
            interfaceVersion = obj.getInt("interface_version"),
            supportedContracts = obj.getJSONArray("supported_contracts")
                .let { array -> List(array.length()) { array.getInt(it) } },
            size = obj.getLong("size"),
            createdAt = obj.getString("created_at"),
        )
    }
}

/** A downloaded firmware image, ready to install on a device over the OTA
 *  service. [signature] is the server's ECDSA over the raw image bytes (null
 *  only when the server ran without a key — the device will reject it). */
data class LocalFirmware(
    val version: String,
    val interfaceVersion: Int,
    val supportedContracts: List<Int>,
    val size: Long,
    val createdAt: String,
    val signature: ByteArray?,
    val file: File,
)

suspend fun fetchFirmwareVersions(context: Context): Result<List<RemoteFirmware>> =
    authedRequest(context, "$BACKEND_URL/ota/versions/$BLE_INTERFACE_VERSION") { connection ->
        val array = JSONArray(connection.inputStream.bufferedReader().readText())
        List(array.length()) { i -> RemoteFirmware.fromJson(array.getJSONObject(i)) }
    }

suspend fun downloadFirmware(context: Context, firmware: RemoteFirmware): Result<Unit> =
    authedRequest(context, firmware.downloadEndpoint) { connection ->
        val signature = connection.getHeaderField(FIRMWARE_SIGNATURE_HEADER)
        val dir = firmwareVersionDir(context, firmware.version)
        dir.mkdirs()
        connection.inputStream.use { input ->
            File(dir, FIRMWARE_IMAGE_FILENAME).outputStream().use { input.copyTo(it) }
        }
        File(dir, FIRMWARE_META_FILENAME).writeText(firmware.toJson().apply {
            if (signature != null) put("signature", signature)
            else put("signature", JSONObject.NULL)
        }.toString())
    }

fun listLocalFirmware(context: Context): List<LocalFirmware> =
    firmwareDir(context).listFiles()
        ?.filter { it.isDirectory }
        ?.mapNotNull { dir ->
            runCatching {
                val image = File(dir, FIRMWARE_IMAGE_FILENAME)
                if (!image.exists()) return@mapNotNull null
                val meta = JSONObject(File(dir, FIRMWARE_META_FILENAME).readText())
                val remote = RemoteFirmware.fromJson(meta)
                LocalFirmware(
                    version = remote.version,
                    interfaceVersion = remote.interfaceVersion,
                    supportedContracts = remote.supportedContracts,
                    size = image.length(),
                    createdAt = remote.createdAt,
                    signature = if (meta.isNull("signature")) null
                                else Base64.getDecoder().decode(meta.getString("signature")),
                    file = image,
                )
            }.getOrNull()
        }
        ?.sortedByDescending { it.createdAt }
        ?: emptyList()

fun deleteFirmware(context: Context, version: String) {
    firmwareVersionDir(context, version).deleteRecursively()
}

/** Raw firmware image bytes, decompressed. Stored zstd-compressed as served (the
 *  signature covers the raw image); consumers stream it through here. */
fun readFirmwareImage(firmware: LocalFirmware): ByteArray =
    zstdDecompress(firmware.file.readBytes())
