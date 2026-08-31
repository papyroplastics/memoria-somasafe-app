package app.somasafe.capture.domain

import com.github.psambit9791.jdsp.transform.DiscreteFourier
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * On-device replica of `extract_features` (backend/ml/preprocessing.py) and
 * `ml_extract_features` (firmware/main/ml/features.c): the 20 raw (un-normalized)
 * features from one 8-second window — BVP (512 samples @ 64 Hz) and ACC (256
 * samples @ 32 Hz). Feature order:
 *   [0..6]  BVP: mean, std, min, max, range, rms, mean-abs-diff
 *   [7..13] ACC: mean, std, min, max, range, rms, mean-abs-diff
 *   [14]    BVP zero-crossing rate (mean-centred)
 *   [15]    BVP dominant frequency (Hz)
 *   [16]    BVP HR-band power ratio (0.7–3.5 Hz)
 *   [17]    BVP pulse-band spectral centroid (Hz, 0.5–4.0 Hz)
 *   [18]    BVP pulse-band spectral spread (Hz)
 *   [19]    BVP log power ratio above the pulse band
 *
 * JDSP supplies the FFT for the spectral features; the peak bin, the band ratios and
 * the band moments are all scale-invariant, so any magnitude scaling JDSP applies
 * cancels out.
 */
object WindowFeatures {
    const val N_FEATURES = 20
    const val WINDOW_SECONDS = 8
    private const val BVP_RATE = 64
    private const val HR_BAND_LO_BIN = 6   // first bin >= 0.7 Hz (0.75 Hz at 0.125 Hz/bin)
    private const val HR_BAND_HI_BIN = 28  // 3.50 Hz

    // Pulse band for the three shape features, 0.5-4.0 Hz = 30-240 bpm. Wider than the
    // HR band above so a slowed rhythm still falls inside it.
    private const val PULSE_BAND_LO_BIN = 4   // 0.50 Hz
    private const val PULSE_BAND_HI_BIN = 32  // 4.00 Hz
    private const val FEATURE_EPS = 1e-9

    fun extract(bvp: FloatArray, acc: FloatArray): FloatArray {
        val f = FloatArray(N_FEATURES)

        val bvpMean = channelStats(bvp, f, 0)
        channelStats(acc, f, 7)

        var zcr = 0
        for (i in 1 until bvp.size) {
            val prevNeg = (bvp[i - 1] - bvpMean) < 0f
            val currNeg = (bvp[i] - bvpMean) < 0f
            if (prevNeg != currNeg) zcr++
        }
        f[14] = zcr.toFloat() / (bvp.size - 1)

        val n = bvp.size
        val windowed = DoubleArray(n) { i ->
            val hann = 0.5 - 0.5 * cos(2.0 * PI * i / (n - 1))
            (bvp[i] - bvpMean).toDouble() * hann
        }
        val ft = DiscreteFourier(windowed)
        ft.transform()
        val mag = ft.getMagnitude(true)            // positive frequencies, length n/2 + 1

        val binHz = BVP_RATE.toDouble() / n
        var total = 0.0
        var band = 0.0
        var pulsePower = 0.0
        var pulseWSum = 0.0
        var pulseWSum2 = 0.0
        var highPower = 0.0
        var peakBin = 1
        var peakPower = 0.0
        for (k in mag.indices) {
            val p = mag[k] * mag[k]
            total += p
            if (k in HR_BAND_LO_BIN..HR_BAND_HI_BIN) band += p
            if (k in PULSE_BAND_LO_BIN..PULSE_BAND_HI_BIN) {
                val freq = k * binHz
                pulsePower += p
                pulseWSum += p * freq
                pulseWSum2 += p * freq * freq
            } else if (k > PULSE_BAND_HI_BIN) {
                highPower += p
            }
            if (k > 0 && p > peakPower) { peakPower = p; peakBin = k }
        }
        f[15] = (peakBin * binHz).toFloat()
        f[16] = (band / (total + 1e-8)).toFloat()

        // Pulse-band shape. The centroid is the power-weighted mean in-band frequency and
        // the spread its standard deviation, computed from the raw moments as
        // sqrt(E[f²] - E[f]²) so the loop above needs only one pass.
        val pulseTotal = pulsePower + FEATURE_EPS
        val centroid = pulseWSum / pulseTotal
        val variance = pulseWSum2 / pulseTotal - centroid * centroid
        f[17] = centroid.toFloat()
        f[18] = sqrt(variance.coerceAtLeast(0.0)).toFloat()
        f[19] = ln(highPower / (total + FEATURE_EPS) + FEATURE_EPS).toFloat()
        return f
    }

    /** Writes mean, std, min, max, range, rms, mean-abs-diff into f[base..base+6] and
     *  returns the mean (reused for BVP centring). */
    private fun channelStats(x: FloatArray, f: FloatArray, base: Int): Float {
        var mn = x[0]
        var mx = x[0]
        var sum = 0.0
        var sumSq = 0.0
        for (v in x) {
            sum += v
            sumSq += v.toDouble() * v
            if (v < mn) mn = v
            if (v > mx) mx = v
        }
        val n = x.size
        val mean = sum / n
        val variance = (sumSq / n - mean * mean).coerceAtLeast(0.0)
        var diffSum = 0.0
        for (i in 1 until n) diffSum += abs(x[i] - x[i - 1]).toDouble()

        f[base] = mean.toFloat()
        f[base + 1] = sqrt(variance).toFloat()
        f[base + 2] = mn
        f[base + 3] = mx
        f[base + 4] = mx - mn
        f[base + 5] = sqrt(sumSq / n).toFloat()
        f[base + 6] = (diffSum / (n - 1)).toFloat()
        return mean.toFloat()
    }
}
