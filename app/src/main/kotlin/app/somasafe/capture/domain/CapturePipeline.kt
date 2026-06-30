package app.somasafe.capture.domain

import app.somasafe.bluetooth.domain.PpgService
import app.somasafe.capture.data.CaptureRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * On-device preprocessing for a stored capture group, run on demand from the
 * Captures screen. Two passes over the group's windows:
 *
 *  1. Features — windows the device never returned an ML result for (an error, or a
 *     dropped packet) have raw sensor data but no [WindowFeatures]; compute and store
 *     them so every complete window carries a feature vector, score or not.
 *  2. Context — compute each window's activity context ([WindowContext]) from the ACC
 *     of the prior windows in its trailing two minutes, storing it un-normalized.
 *     Windows with too little context are left without one.
 *
 * Idempotent: re-running only fills windows that are still missing a feature/context.
 */
class CapturePipeline(private val repository: CaptureRepository) {

    data class Result(val featuresComputed: Int, val contextsComputed: Int)

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

        val windows = samples.mapNotNull { s ->
            val acc = s.acc ?: return@mapNotNull null
            val start = s.deviceStartMs ?: return@mapNotNull null
            val end = s.deviceEndMs ?: return@mapNotNull null
            s to ContextWindow(start, end, acc.leFloats())
        }
        val all = windows.map { it.second }

        var contextsComputed = 0
        for ((sample, window) in windows) {
            if (sample.context != null) continue
            val context = WindowContext.contextFor(window, all) ?: continue
            repository.storeContext(sample.id, context.leBytes())
            contextsComputed++
        }

        Result(featuresComputed, contextsComputed)
    }

    companion object {
        private const val BVP_WINDOW = PpgService.PPG_PER_SEC * WindowContext.WINDOW_SECONDS  // 512
        private const val ACC_WINDOW = PpgService.ACC_PER_SEC * WindowContext.WINDOW_SECONDS  // 256
    }
}
