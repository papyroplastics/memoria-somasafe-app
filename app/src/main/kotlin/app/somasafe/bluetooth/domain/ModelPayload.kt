package app.somasafe.bluetooth.domain

import android.content.Context
import app.somasafe.backend.data.loadSignedModelMeta
import app.somasafe.backend.data.readQuantizedBytes
import app.somasafe.capture.domain.NormStats
import app.somasafe.capture.domain.paramBlock
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Assemble the signed model payload for the device (BLE interface version 1):
 *
 *     u16 sig_len | sig[sig_len] | u16 norm_len | norm_params | u16 contract_version | tflite  (LE)
 *
 * The signature covers `contract_version ‖ tflite` — exactly the canonical bytes the
 * server signed (see shared/docs/model-signing.md) — and those sit last so the firmware
 * verifies them in place. The wearer's normalization parameters are the client's own
 * (never signed, never served) and ride ahead of the signed region with their own length.
 * Requires the quantized model and its signed metadata (`quantized.json`) to be stored
 * locally.
 */
fun buildModelPayload(context: Context, key: String, norm: NormStats): ByteArray {
    val tflite = readQuantizedBytes(context, key)
    val meta = loadSignedModelMeta(context, key)
        ?: error("no signed metadata for '$key' — re-download the quantized model")
    val sig = meta.signature ?: error("quantized model for '$key' is unsigned")
    val normParams = norm.paramBlock()

    return ByteBuffer.allocate(2 + sig.size + 2 + normParams.size + 2 + tflite.size)
        .order(ByteOrder.LITTLE_ENDIAN)
        .putShort(sig.size.toShort())
        .put(sig)
        .putShort(normParams.size.toShort())
        .put(normParams)
        .putShort(meta.contractVersion.toShort())
        .put(tflite)
        .array()
}
