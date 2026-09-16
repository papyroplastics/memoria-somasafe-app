package app.somasafe.capture.data

import android.content.Context
import app.somasafe.bluetooth.domain.MlResult
import app.somasafe.bluetooth.domain.PpgSample
import app.somasafe.capture.domain.NormStats
import app.somasafe.capture.domain.leBytes
import app.somasafe.capture.domain.leFloats
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One group's stored z-score parameters: the per-feature pair a staged model carries,
 *  and the single BVP pair on-device training normalizes its windows with. */
data class GroupNormParams(val groupId: Long, val features: NormStats?, val signal: NormStats?)

/**
 * Storage layer for capture data: owns the [CaptureDatabase] and all reads/writes
 * against it. Raw PPG windows and ML results arrive in either order and are merged
 * by sequence number into the same row; imported datasets land here as fully
 * populated rows indistinguishable from captured ones.
 */
class CaptureRepository(context: Context) {
    private val dao = CaptureDatabase.get(context).captureDao()
    private val dbMutex = Mutex()

    /** Per-group rollup for the history UI; updates live as rows are written. */
    fun groupSummaries(): Flow<List<GroupSummary>> = dao.groupSummaries()

    suspend fun startGroup(startedAt: Long): Long =
        dao.insertGroup(SampleGroup(startedAt = startedAt))

    suspend fun endGroup(groupId: Long, endedAt: Long) = dao.endGroup(groupId, endedAt)

    suspend fun deleteGroup(groupId: Long) = dao.deleteGroup(groupId)

    /** Store the z-score parameters preprocessing derived from a group's own windows. */
    suspend fun storeNormParams(groupId: Long, features: NormStats?, signal: NormStats?) =
        dao.setNormParams(
            groupId,
            features?.mean?.leBytes(), features?.std?.leBytes(),
            signal?.mean?.leBytes(), signal?.std?.leBytes(),
        )

    suspend fun normParams(groupId: Long): GroupNormParams? = dao.group(groupId)?.normParams()

    /** The parameters a model gets staged with: the picked group's, or — when nothing is
     *  picked — the most recent preprocessed group's. Null when no group has any. */
    suspend fun stagingNormParams(): GroupNormParams? = dao.stagingGroup()?.normParams()

    suspend fun pickForStaging(groupId: Long) =
        dao.pickGroup(groupId, System.currentTimeMillis())

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
                )
            )
        } else {
            dao.updateSample(
                existing.copy(
                    deviceStartMs = sample.deviceStartMs,
                    deviceEndMs = sample.deviceEndMs,
                    ppg = sample.ppg,
                )
            )
        }
    }

    /** All samples in a group, ordered by device time (for the preprocessing pipeline). */
    suspend fun samplesForGroup(groupId: Long): List<Sample> = dao.samplesForGroup(groupId)

    /** Store features for a window that has no inference result (and thus no score). */
    suspend fun storeFeatures(sampleId: Long, features: ByteArray) = dao.setFeatures(sampleId, features)

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
    suspend fun importDataset(dataset: ImportedDataset): Long {
        val now = System.currentTimeMillis()
        val windows = dataset.windows
        val maxSeq = windows.maxOfOrNull { it.sequenceN } ?: 0L
        val minSeq = windows.minOfOrNull { it.sequenceN } ?: 0L
        return dao.insertGroupWithSamples(
            SampleGroup(
                startedAt = now - (maxSeq - minSeq) * WINDOW_MS,
                endedAt = now,
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
                    features = window.features,
                    score = window.score,
                )
            }
        }
    }

    private fun SampleGroup.normParams() = GroupNormParams(
        groupId = id,
        features = stats(featureMean, featureStd),
        signal = stats(signalMean, signalStd),
    )

    private fun stats(mean: ByteArray?, std: ByteArray?): NormStats? =
        if (mean == null || std == null) null else NormStats(mean.leFloats(), std.leFloats())

    private companion object {
        const val WINDOW_MS = 8_000L
    }
}
