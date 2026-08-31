package app.somasafe.capture.domain

import app.somasafe.bluetooth.domain.PpgService
import app.somasafe.capture.data.CaptureRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * On-device preprocessing for a stored capture group, run on demand from the Captures
 * screen: the device may not return an ML result for every window (an error, or a
 * dropped packet), leaving raw sensor data but no [WindowFeatures]; compute and store
 * them so every complete window carries a feature vector, score or not.
 *
 * The same pass derives the wearer's z-score parameters from the group's own windows —
 * per-feature over every raw feature vector, and one pair over the raw BVP stream — since
 * no model normalizes its own input: the app supplies the parameters when it stages a
 * model on the device, and applies them itself when it trains.
 *
 * Idempotent: re-running only fills windows that are still missing a feature vector, and
 * recomputes the group's parameters over whatever it holds.
 */
class CapturePipeline(private val repository: CaptureRepository) {

    data class Result(val featuresComputed: Int, val hasNormParams: Boolean)

    suspend fun process(groupId: Long): Result {
        val samples = repository.samplesForGroup(groupId)

        val featureRows = mutableListOf<FloatArray>()
        val signalWindows = mutableListOf<FloatArray>()
        val computed = mutableListOf<Pair<Long, FloatArray>>()

        withContext(Dispatchers.Default) {
            for (s in samples) {
                val bvp = s.ppg?.leFloats()?.takeIf { it.size == BVP_WINDOW }
                if (bvp != null) signalWindows += bvp

                var features = s.features?.leFloats()
                if (features == null && bvp != null) {
                    val acc = s.acc?.leFloats()?.takeIf { it.size == ACC_WINDOW }
                    if (acc != null) {
                        features = WindowFeatures.extract(bvp, acc)
                        computed += s.id to features
                    }
                }
                if (features != null) featureRows += features
            }
        }

        for ((sampleId, features) in computed) {
            repository.storeFeatures(sampleId, features.leBytes())
        }

        val featureStats = columnStats(featureRows, WindowFeatures.N_FEATURES)
        repository.storeNormParams(groupId, featureStats, sampleStats(signalWindows))

        return Result(computed.size, featureStats != null)
    }

    companion object {
        private const val BVP_WINDOW = PpgService.PPG_PER_SEC * WindowFeatures.WINDOW_SECONDS  // 512
        private const val ACC_WINDOW = PpgService.ACC_PER_SEC * WindowFeatures.WINDOW_SECONDS  // 256
    }
}
