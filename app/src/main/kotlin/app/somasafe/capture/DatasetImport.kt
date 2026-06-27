package app.somasafe.capture

import app.somasafe.device.PpgService
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** One imported window, already in the byte layout the capture schema stores. */
data class ImportedWindow(
    val ppg: ByteArray,
    val acc: ByteArray,
    val features: ByteArray,
    val score: ByteArray,
)

data class ImportedDataset(
    val subject: Int,
    val windows: List<ImportedWindow>,
)

/**
 * Parses the flat `.ssds` export written by backend/scripts/export_subject_data.py:
 *
 *   header: magic "SSDS" | version(u8) | subject(u16) | ppgLen(u16) | accLen(u16)
 *           | featLen(u16) | scoreLen(u16) | count(u32)   (little-endian)
 *   body:   count × ( ppg[ppgLen] | acc[accLen] | features[featLen] | score[scoreLen] )
 *
 * PPG/ACC are raw little-endian float32; features/score are int8.
 */
object DatasetImport {
    private val MAGIC = byteArrayOf('S'.code.toByte(), 'S'.code.toByte(), 'D'.code.toByte(), 'S'.code.toByte())
    private const val VERSION = 1
    private const val HEADER_LEN = 4 + 1 + 2 + 2 + 2 + 2 + 2 + 4 // 19 bytes

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
        val scoreLen = buf.short.toInt() and 0xFFFF
        val count = buf.int

        require(ppgLen == PPG_BYTES) { "ppg window is $ppgLen B, expected $PPG_BYTES" }
        require(accLen == ACC_BYTES) { "acc window is $accLen B, expected $ACC_BYTES" }
        require(count >= 0) { "negative window count" }

        val stride = ppgLen + accLen + featLen + scoreLen
        val expected = HEADER_LEN + count.toLong() * stride
        require(bytes.size.toLong() == expected) {
            "length ${bytes.size}, expected $expected for $count windows"
        }

        val windows = ArrayList<ImportedWindow>(count)
        repeat(count) {
            val ppg = ByteArray(ppgLen).also { buf.get(it) }
            val acc = ByteArray(accLen).also { buf.get(it) }
            val features = ByteArray(featLen).also { buf.get(it) }
            val score = ByteArray(scoreLen).also { buf.get(it) }
            windows.add(ImportedWindow(ppg, acc, features, score))
        }
        return ImportedDataset(subject, windows)
    }
}
