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
 * Idempotent: re-running only fills windows that are still missing a feature vector.
 */
class CapturePipeline(private val repository: CaptureRepository) {

    data class Result(val featuresComputed: Int)

    suspend fun process(groupId: Long): Result = withContext(Dispatchers.Default) {
        val samples = repository.samplesForGroup(groupId)

        var featuresComputed = 0
        for (s in samples) {
            val ppg = s.ppg
            val acc = s.acc
            if (s.features != null || ppg == null || acc == null) continue
            val bvpFloats = ppg.leFloats()
            val accFloats = acc.leFloats()
            if (bvpFloats.size != BVP_WINDOW || accFloats.size != ACC_WINDOW) continue
            repository.storeFeatures(s.id, WindowFeatures.extract(bvpFloats, accFloats).leBytes())
            featuresComputed++
        }

        Result(featuresComputed)
    }

    companion object {
        private const val BVP_WINDOW = PpgService.PPG_PER_SEC * WindowFeatures.WINDOW_SECONDS  // 512
        private const val ACC_WINDOW = PpgService.ACC_PER_SEC * WindowFeatures.WINDOW_SECONDS  // 256
    }
}
