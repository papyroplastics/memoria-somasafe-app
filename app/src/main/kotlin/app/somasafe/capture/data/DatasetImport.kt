package app.somasafe.capture.data

import app.somasafe.bluetooth.domain.PpgService
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** One imported window, already in the byte layout the capture schema stores.
 *  Carries the recording-intrinsic metadata an ESP sample has (sequence number,
 *  on-device start/end timestamps); [context] is present only when the export
 *  embedded it (ctxLen > 0). */
data class ImportedWindow(
    val sequenceN: Long,
    val deviceStartMs: Long,
    val deviceEndMs: Long,
    val ppg: ByteArray,
    val acc: ByteArray,
    val features: ByteArray,
    val score: ByteArray,
    val context: ByteArray? = null,
)

data class ImportedDataset(
    val subject: Int,
    val windows: List<ImportedWindow>,
)

/**
 * Parses the flat `.ssds` export written by backend/scripts/export_subject_data.py:
 *
 *   header: magic "SSDS" | version(u8) | subject(u16) | ppgLen(u16) | accLen(u16)
 *           | featLen(u16) | ctxLen(u16) | scoreLen(u16) | count(u32)   (little-endian)
 *   body:   count × ( seqN(u32) | deviceStartMs(u32) | deviceEndMs(u32)
 *                     | ppg[ppgLen] | acc[accLen] | features[featLen]
 *                     | context[ctxLen] | score[scoreLen] )
 *
 * PPG/ACC, features and context are raw little-endian float32; score is int8.
 * seqN/device timestamps fake the metadata an ESP sample carries (8 s device-time grid).
 * ctxLen is 0 when the export did not embed context (the phone computes it itself).
 */
object DatasetImport {
    private val MAGIC = byteArrayOf('S'.code.toByte(), 'S'.code.toByte(), 'D'.code.toByte(), 'S'.code.toByte())
    private const val VERSION = 2
    private const val HEADER_LEN = 4 + 1 + 2 + 2 + 2 + 2 + 2 + 2 + 4 // 21 bytes
    private const val META_LEN = 4 + 4 + 4 // seqN + deviceStartMs + deviceEndMs

    // Expected raw window sizes for the model's 8-second windows.
    private const val WINDOW_SECONDS = 8
    private val PPG_BYTES = PpgService.PPG_PER_SEC * WINDOW_SECONDS * Float.SIZE_BYTES // 2048
    private val ACC_BYTES = PpgService.ACC_PER_SEC * WINDOW_SECONDS * Float.SIZE_BYTES // 1024

    fun parse(bytes: ByteArray): ImportedDataset {
        require(bytes.size >= HEADER_LEN) { "file too small (${bytes.size} bytes)" }
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

        val magic = ByteArray(4).also { buf.get(it) }
        require(magic.contentEquals(MAGIC)) { "bad magic (not an .ssds file)" }
        val version = buf.get().toInt() and 0xFF
        require(version == VERSION) { "unsupported version $version" }

        val subject = buf.short.toInt() and 0xFFFF
        val ppgLen = buf.short.toInt() and 0xFFFF
        val accLen = buf.short.toInt() and 0xFFFF
        val featLen = buf.short.toInt() and 0xFFFF
        val ctxLen = buf.short.toInt() and 0xFFFF
        val scoreLen = buf.short.toInt() and 0xFFFF
        val count = buf.int

        require(ppgLen == PPG_BYTES) { "ppg window is $ppgLen B, expected $PPG_BYTES" }
        require(accLen == ACC_BYTES) { "acc window is $accLen B, expected $ACC_BYTES" }
        require(count >= 0) { "negative window count" }

        val stride = META_LEN + ppgLen + accLen + featLen + ctxLen + scoreLen
        val expected = HEADER_LEN + count.toLong() * stride
        require(bytes.size.toLong() == expected) {
            "length ${bytes.size}, expected $expected for $count windows"
        }

        val windows = ArrayList<ImportedWindow>(count)
        repeat(count) {
            val sequenceN = buf.int.toLong() and 0xFFFFFFFFL
            val deviceStartMs = buf.int.toLong() and 0xFFFFFFFFL
            val deviceEndMs = buf.int.toLong() and 0xFFFFFFFFL
            val ppg = ByteArray(ppgLen).also { buf.get(it) }
            val acc = ByteArray(accLen).also { buf.get(it) }
            val features = ByteArray(featLen).also { buf.get(it) }
            val context = if (ctxLen > 0) ByteArray(ctxLen).also { buf.get(it) } else null
            val score = ByteArray(scoreLen).also { buf.get(it) }
            windows.add(ImportedWindow(sequenceN, deviceStartMs, deviceEndMs, ppg, acc, features, score, context))
        }
        return ImportedDataset(subject, windows)
    }
}
