package app.somasafe.bluetooth.domain

import android.content.Context
import app.somasafe.backend.data.loadSignedModelMeta
import app.somasafe.backend.data.quantizedFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Assemble the signed model payload for the device (BLE interface version 1):
 *
 *     u16 sig_len | sig[sig_len] | u16 contract_version | norm_params | tflite   (LE)
 *
 * The signature covers everything after the header — exactly the canonical bytes
 * the server signed (see shared/docs/model-signing.md) — so the firmware verifies it
 * without reframing. Requires the quantized model and its signed metadata
 * (`quantized.json`) to be stored locally.
 */
fun buildModelPayload(context: Context, key: String): ByteArray {
    val tflite = quantizedFile(context, key).readBytes()
    val meta = loadSignedModelMeta(context, key)
        ?: error("no signed metadata for '$key' — re-download the quantized model")
    val sig = meta.signature ?: error("quantized model for '$key' is unsigned")

    return ByteBuffer.allocate(2 + sig.size + 2 + meta.normParams.size + tflite.size)
        .order(ByteOrder.LITTLE_ENDIAN)
        .putShort(sig.size.toShort())
        .put(sig)
        .putShort(meta.contractVersion.toShort())
        .put(meta.normParams)
        .put(tflite)
        .array()
}
