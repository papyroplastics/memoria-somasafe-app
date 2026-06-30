package app.somasafe.capture.domain

import kotlin.math.sqrt

/** One window's ACC and on-device timestamps, the inputs to context computation. */
data class ContextWindow(
    val deviceStartMs: Long,
    val deviceEndMs: Long,
    val acc: FloatArray,
)

/**
 * Per-window activity context, the on-device counterpart of the causal activity
 * context in backend/ml/data.py. A capture can be discontinuous (windows may be
 * missing), so this takes the lenient view the firmware path can't: the context of
 * a window is the mean/std of the raw ACC magnitude over every *prior* window that
 * ended within [CONTEXT_SECONDS] of this window's start (device time). The target
 * window's own ACC is not included.
 *
 * Stored un-normalized; normalization (with the dataset's ACC params) happens later
 * at training-input assembly. A window needs at least [MIN_CONTEXT_WINDOWS] of the
 * [EXPECTED_CONTEXT_WINDOWS] context windows present, otherwise it has too little
 * context and is left without one (excluded from training).
 */
object WindowContext {
    const val WINDOW_SECONDS = 8
    const val CONTEXT_SECONDS = 120
    private const val CONTEXT_MS = CONTEXT_SECONDS * 1000L

    val EXPECTED_CONTEXT_WINDOWS = CONTEXT_SECONDS / WINDOW_SECONDS          // 15
    val MIN_CONTEXT_WINDOWS = (EXPECTED_CONTEXT_WINDOWS * 2 + 2) / 3         // ceil(2/3 * 15) = 10

    /** Raw 2-d context `[mean, std]` of the ACC magnitude over the prior windows in
     *  [target]'s trailing [CONTEXT_SECONDS], or null when too few exist. */
    fun contextFor(target: ContextWindow, windows: List<ContextWindow>): FloatArray? {
        val lowerBound = target.deviceStartMs - CONTEXT_MS
        var sum = 0.0
        var sumSq = 0.0
        var count = 0L
        var priors = 0
        for (w in windows) {
            if (w === target) continue
            if (w.deviceEndMs > target.deviceStartMs || w.deviceEndMs < lowerBound) continue
            priors++
            for (v in w.acc) {
                sum += v
                sumSq += v.toDouble() * v
                count++
            }
        }
        if (priors < MIN_CONTEXT_WINDOWS || count == 0L) return null

        val mean = sum / count
        val variance = (sumSq / count - mean * mean).coerceAtLeast(0.0)
        return floatArrayOf(mean.toFloat(), sqrt(variance).toFloat())
    }
}
