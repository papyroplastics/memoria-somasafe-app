package app.somasafe.capture.data

import app.somasafe.capture.proto.CaptureDataset
import com.google.protobuf.ByteString
import com.google.protobuf.InvalidProtocolBufferException

/** One imported window in the byte layout the capture schema stores. Fields are
 *  nullable to mirror real loss: a window may have signal data but no ML result
 *  (features/score null) or a result with no signal (ppg/acc and device timestamps
 *  null, like a result-only row). [context] is set only when the export embedded it. */
data class ImportedWindow(
    val sequenceN: Long,
    val deviceStartMs: Long?,
    val deviceEndMs: Long?,
    val ppg: ByteArray?,
    val acc: ByteArray?,
    val features: ByteArray?,
    val score: ByteArray?,
    val context: ByteArray?,
)

data class ImportedDataset(
    val subject: Int,
    val windows: List<ImportedWindow>,
    val static: ByteArray?,   // raw little-endian float32 demographics (6-d); null if absent
)

/**
 * Parses the `.ssds` subject export written by backend/scripts/export_subject_data.py,
 * a `somasafe.capture.CaptureDataset` protobuf (schema in shared/dataset.proto).
 *
 * Each window carries the recording-intrinsic metadata an ESP sample has (sequence
 * number, on-device start/end timestamps) plus whichever halves survived export:
 * raw little-endian float32 PPG/ACC/features/context and the int8 score. An empty
 * payload field means absent; missing windows simply leave a gap in the sequence
 * numbers, exactly like dropped captures.
 */
object DatasetImport {
    private const val FORMAT_VERSION = 4

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
                acc = w.acc.bytesOrNull(),
                features = w.features.bytesOrNull(),
                score = w.score.bytesOrNull(),
                context = w.context.bytesOrNull(),
            )
        }
        return ImportedDataset(dataset.subject, windows, dataset.static.bytesOrNull())
    }

    private fun ByteString.bytesOrNull(): ByteArray? = if (isEmpty) null else toByteArray()
}
