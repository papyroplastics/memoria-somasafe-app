package app.somasafe.capture.data

import app.somasafe.capture.proto.CaptureDataset
import com.google.protobuf.ByteString
import com.google.protobuf.InvalidProtocolBufferException

/** One imported window in the byte layout the capture schema stores. Fields are
 *  nullable to mirror real loss: a window may have signal data but no ML result
 *  (features/score null) or a result with no signal (ppg and device timestamps
 *  null, like a result-only row). */
data class ImportedWindow(
    val sequenceN: Long,
    val deviceStartMs: Long?,
    val deviceEndMs: Long?,
    val ppg: ByteArray?,
    val features: ByteArray?,
    val score: ByteArray?,
)

data class ImportedDataset(
    val subject: Int,
    val windows: List<ImportedWindow>,
)

/**
 * Parses the `.ssds` subject export written by backend/scripts/system/export_subject_data.py,
 * a `somasafe.capture.CaptureDataset` protobuf (schema in shared/dataset.proto).
 *
 * Each window carries the recording-intrinsic metadata an ESP sample has (sequence
 * number, on-device start/end timestamps) plus whichever halves survived export:
 * raw little-endian float32 PPG/features and the int8 score. An empty payload
 * field means absent; missing windows simply leave a gap in the sequence numbers,
 * exactly like dropped captures.
 */
object DatasetImport {
    private const val FORMAT_VERSION = 1

    fun parse(bytes: ByteArray): ImportedDataset {
        val dataset = try {
            CaptureDataset.parseFrom(bytes)
        } catch (e: InvalidProtocolBufferException) {
            throw IllegalArgumentException("not a valid .ssds capture dataset", e)
        }
        require(dataset.formatVersion == FORMAT_VERSION) {
            "unsupported .ssds version ${dataset.formatVersion}"
        }

        val windows = dataset.windowsList.map { w ->
            ImportedWindow(
                sequenceN = w.sequenceN.toUInt().toLong(),
                deviceStartMs = if (w.hasDeviceStartMs()) w.deviceStartMs.toUInt().toLong() else null,
                deviceEndMs = if (w.hasDeviceEndMs()) w.deviceEndMs.toUInt().toLong() else null,
                ppg = w.ppg.bytesOrNull(),
                features = w.features.bytesOrNull(),
                score = w.score.bytesOrNull(),
            )
        }
        return ImportedDataset(dataset.subject, windows)
    }

    private fun ByteString.bytesOrNull(): ByteArray? = if (isEmpty) null else toByteArray()
}
