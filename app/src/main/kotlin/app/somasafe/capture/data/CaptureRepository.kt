package app.somasafe.capture.data

import android.content.Context
import app.somasafe.bluetooth.domain.MlResult
import app.somasafe.bluetooth.domain.PpgSample
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Storage layer for capture data: owns the [CaptureDatabase] and all reads/writes
 * against it. Raw PPG windows and ML results arrive in either order and are merged
 * by sequence number into the same row; imported datasets land here as fully
 * populated rows indistinguishable from captured ones.
 */
class CaptureRepository(private val context: Context) {
    private val dao = CaptureDatabase.get(context).captureDao()
    private val dbMutex = Mutex()

    /** Per-group rollup for the history UI; updates live as rows are written. */
    fun groupSummaries(): Flow<List<GroupSummary>> = dao.groupSummaries()

    /** Open a capture group, stamping it with the default static (demographics) vector
     *  in effect when the capture starts. */
    suspend fun startGroup(startedAt: Long, static: ByteArray? = null): Long =
        dao.insertGroup(SampleGroup(startedAt = startedAt, static = static))

    suspend fun endGroup(groupId: Long, endedAt: Long) = dao.endGroup(groupId, endedAt)

    /** The group's static (demographics) conditioning vector. Groups recorded before
     *  demographics were set carry no static of their own; those fall back to the current
     *  default. Null only when the group has none and no default has been set either. */
    suspend fun groupStatic(groupId: Long): ByteArray? =
        dao.findGroup(groupId)?.static ?: loadDemographics(context)?.toBytes()

    /** Backfill the given default static onto every group that never got one. Called when
     *  the demographics form is saved so pre-existing captures become trainable. */
    suspend fun fillMissingStatic(static: ByteArray) = dao.fillMissingStatic(static)

    suspend fun deleteGroup(groupId: Long) = dao.deleteGroup(groupId)

    suspend fun mergePpg(groupId: Long, sample: PpgSample) = dbMutex.withLock {
        val existing = dao.findSample(groupId, sample.sequenceN)
        if (existing == null) {
            dao.insertSample(
                Sample(
                    groupId = groupId,
                    sequenceN = sample.sequenceN,
                    receivedAt = System.currentTimeMillis(),
                    deviceStartMs = sample.deviceStartMs,
                    deviceEndMs = sample.deviceEndMs,
                    ppg = sample.ppg,
                    acc = sample.acc,
                )
            )
        } else {
            dao.updateSample(
                existing.copy(
                    deviceStartMs = sample.deviceStartMs,
                    deviceEndMs = sample.deviceEndMs,
                    ppg = sample.ppg,
                    acc = sample.acc,
                )
            )
        }
    }

    /** All samples in a group, ordered by device time (for the preprocessing pipeline). */
    suspend fun samplesForGroup(groupId: Long): List<Sample> = dao.samplesForGroup(groupId)

    /** Store features for a window that has no inference result (and thus no score). */
    suspend fun storeFeatures(sampleId: Long, features: ByteArray) = dao.setFeatures(sampleId, features)

    /** Store the computed (un-normalized) activity context for a window. */
    suspend fun storeContext(sampleId: Long, context: ByteArray) = dao.setContext(sampleId, context)

    suspend fun mergeResult(groupId: Long, result: MlResult) = dbMutex.withLock {
        val existing = dao.findSample(groupId, result.sequenceN)
        if (existing == null) {
            dao.insertSample(
                Sample(
                    groupId = groupId,
                    sequenceN = result.sequenceN,
                    receivedAt = System.currentTimeMillis(),
                    features = result.features,
                    score = result.score,
                )
            )
        } else {
            dao.updateSample(existing.copy(features = result.features, score = result.score))
        }
    }

    /** Persist an imported dataset as one group of samples shaped exactly like ESP
     *  captures: each window's own sequence number, device timestamps and whichever
     *  data/result halves survived export. Receive time is stamped now for the last
     *  window and back-dated 8 s per sequence step, so gaps from dropped windows widen
     *  the receive timeline just as real loss would. */
    suspend fun importDataset(dataset: ImportedDataset, fallbackStatic: ByteArray? = null): Long {
        val now = System.currentTimeMillis()
        val windows = dataset.windows
        val maxSeq = windows.maxOfOrNull { it.sequenceN } ?: 0L
        val minSeq = windows.minOfOrNull { it.sequenceN } ?: 0L
        return dao.insertGroupWithSamples(
            SampleGroup(
                startedAt = now - (maxSeq - minSeq) * WINDOW_MS,
                endedAt = now,
                static = dataset.static ?: fallbackStatic,
            ),
        ) { groupId ->
            windows.map { window ->
                Sample(
                    groupId = groupId,
                    sequenceN = window.sequenceN,
                    receivedAt = now - (maxSeq - window.sequenceN) * WINDOW_MS,
                    deviceStartMs = window.deviceStartMs,
                    deviceEndMs = window.deviceEndMs,
                    ppg = window.ppg,
                    acc = window.acc,
                    features = window.features,
                    score = window.score,
                    context = window.context,
                )
            }
        }
    }

    private companion object {
        const val WINDOW_MS = 8_000L
    }
}
