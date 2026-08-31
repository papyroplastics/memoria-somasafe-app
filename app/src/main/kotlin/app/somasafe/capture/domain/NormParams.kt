package app.somasafe.capture.domain

import kotlin.math.sqrt

const val NORM_EPS = 1e-6f

/** Per-wearer z-score parameters: one entry per input coordinate, or a single
 *  entry when the whole signal shares one mean/std (see [normalize]). */
data class NormStats(val mean: FloatArray, val std: FloatArray)

/** Column-wise stats over equally-sized rows (the 20-feature family). */
fun columnStats(rows: List<FloatArray>, width: Int): NormStats? {
    val used = rows.filter { it.size == width }
    if (used.isEmpty()) return null

    val sum = DoubleArray(width)
    val sumSq = DoubleArray(width)
    for (row in used) {
        for (i in 0 until width) {
            val v = row[i].toDouble()
            sum[i] += v
            sumSq[i] += v * v
        }
    }

    val mean = FloatArray(width)
    val std = FloatArray(width)
    for (i in 0 until width) {
        val m = sum[i] / used.size
        mean[i] = m.toFloat()
        std[i] = sqrt((sumSq[i] / used.size - m * m).coerceAtLeast(0.0)).toFloat() + NORM_EPS
    }
    return NormStats(mean, std)
}

/** One mean/std over every sample of every chunk (the raw BVP signal family). */
fun sampleStats(chunks: List<FloatArray>): NormStats? {
    var n = 0L
    var sum = 0.0
    var sumSq = 0.0
    for (chunk in chunks) {
        for (v in chunk) {
            n++
            sum += v
            sumSq += v.toDouble() * v
        }
    }
    if (n == 0L) return null

    val mean = sum / n
    val std = sqrt((sumSq / n - mean * mean).coerceAtLeast(0.0))
    return NormStats(floatArrayOf(mean.toFloat()), floatArrayOf(std.toFloat() + NORM_EPS))
}

/** Z-scores [values], broadcasting a single-entry [NormStats] across all of them. */
fun NormStats.normalize(values: FloatArray): FloatArray {
    require(mean.size == 1 || mean.size == values.size) {
        "norm params cover ${mean.size} coordinates, input has ${values.size}"
    }
    return FloatArray(values.size) { i ->
        val j = if (mean.size == 1) 0 else i
        (values[i] - mean[j]) / std[j]
    }
}

/** The device payload's norm block: `mean[n]` then `std[n]`, LE float32. */
fun NormStats.paramBlock(): ByteArray = mean.leBytes() + std.leBytes()
