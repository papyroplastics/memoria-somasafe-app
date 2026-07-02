package app.somasafe.training.domain

import android.content.Context
import app.somasafe.backend.data.StoredWeights
import app.somasafe.backend.data.loadWeights
import app.somasafe.backend.data.saveWeights
import app.somasafe.backend.data.trainableFile
import app.somasafe.capture.data.CaptureRepository
import app.somasafe.capture.domain.leFloats
import app.somasafe.training.data.NormParams
import app.somasafe.training.data.loadNormParams
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Outcome of one on-device training epoch. */
data class TrainResult(val windows: Int, val batches: Int, val meanLoss: Float)

private const val BVP_LEN = 512
private const val N_SIGNALS = 2
private const val N_STATIC = 6
private const val N_CONTEXT = 2
private const val TRAIN_SIGNATURE = "train"
private const val SIGNAL_INPUT = "signal"

/**
 * Runs one local training epoch of a model over a processed capture group and writes
 * the trained weights back to `weights.json` (keeping the same base `weights_id`, so
 * the existing quantize/upload flow submits them as a federated update).
 *
 * The autoencoder is self-supervised — its target is the input BVP — so only the model
 * inputs are assembled per window: a z-scored `[BVP, ACC]` signal frame and the 8-d
 * conditioning vector `[static(6), context(2)]`. Windows without signal or without an
 * activity context are skipped; the score/label is unused. Everything is normalized
 * with the model's params (`/model/norm`) exactly as the backend does at load time.
 */
class Trainer(private val context: Context, private val repository: CaptureRepository) {

    suspend fun trainEpoch(modelKey: String, groupId: Long): TrainResult {
        val weights = loadWeights(context, modelKey)
            ?: error("weights not downloaded for '$modelKey'")
        val norm = loadNormParams(context, modelKey)
            ?: error("normalization params not downloaded for '$modelKey'")
        val static = repository.groupStatic(groupId)?.leFloats()
            ?: error("no demographics for group #$groupId; set the default demographics in the Captures tab")
        require(static.size == N_STATIC) { "expected $N_STATIC static values, got ${static.size}" }

        val samples = repository.samplesForGroup(groupId)
        val windows = samples.mapNotNull { s ->
            val ppg = s.ppg?.leFloats() ?: return@mapNotNull null
            val acc = s.acc?.leFloats() ?: return@mapNotNull null
            val ctx = s.context?.leFloats() ?: return@mapNotNull null
            if (ppg.size != BVP_LEN || ctx.size != N_CONTEXT) return@mapNotNull null
            // cond = [static(6), context(2)], normalized as one 8-d vector (backend window_cond_vectors).
            val cond = normalize(static + ctx, norm.condMean, norm.condStd)
            Window(signalFrame(ppg, acc, norm), cond)
        }

        return withContext(Dispatchers.Default) {
            LiteRtModel(trainableFile(context, modelKey).absolutePath).use { model ->
                model.restoreWeights(weights.parameters)
                val result = runEpoch(model, windows)
                saveWeights(context, modelKey, weights.copy(parameters = model.saveWeights()))
                result
            }
        }
    }

    private fun runEpoch(model: LiteRtModel, windows: List<Window>): TrainResult {
        val (batchSize, order) = trainLayout(model.describe())
        val batches = windows.size / batchSize            // full batches only; drop the remainder
        var lossSum = 0f
        for (b in 0 until batches) {
            val batch = windows.subList(b * batchSize, (b + 1) * batchSize)
            val signal = flatten(batch.map { it.signal })
            val cond = flatten(batch.map { it.cond })
            val inputs = order.map { if (it == SIGNAL_INPUT) signal else cond }.toTypedArray()
            lossSum += model.train(inputs, 1)
        }
        val meanLoss = if (batches > 0) lossSum / batches else Float.NaN
        return TrainResult(windows = batches * batchSize, batches = batches, meanLoss = meanLoss)
    }

    /** Batch size and input ordering the train signature declares (the JNI feeds
     *  `Array<FloatArray>` in the signature's input-tensor order). */
    private fun trainLayout(info: ModelInfo): Pair<Int, List<String>> {
        val sig = info.signatures.firstOrNull { it.key == TRAIN_SIGNATURE }
            ?: error("model has no '$TRAIN_SIGNATURE' signature")
        val signal = sig.inputs.firstOrNull { it.name == SIGNAL_INPUT }
            ?: error("train signature has no '$SIGNAL_INPUT' input")
        val batch = signal.shape.firstOrNull() ?: -1
        require(batch > 0) { "train signature has a non-fixed batch size" }
        return batch to sig.inputs.map { it.name }
    }

    /** One window frame: interleaved z-scored `[BVP, ACC]` (ACC resampled to BVP length). */
    private fun signalFrame(bvp: FloatArray, acc: FloatArray, norm: NormParams): FloatArray {
        val acc512 = interp(acc, BVP_LEN)
        val out = FloatArray(BVP_LEN * N_SIGNALS)
        val (bMean, aMean) = norm.signalMean[0] to norm.signalMean[1]
        val (bStd, aStd) = norm.signalStd[0] to norm.signalStd[1]
        for (t in 0 until BVP_LEN) {
            out[t * N_SIGNALS] = (bvp[t] - bMean) / bStd
            out[t * N_SIGNALS + 1] = (acc512[t] - aMean) / aStd
        }
        return out
    }

    private data class Window(val signal: FloatArray, val cond: FloatArray)

    private companion object {
        fun normalize(x: FloatArray, mean: FloatArray, std: FloatArray): FloatArray =
            FloatArray(x.size) { (x[it] - mean[it]) / std[it] }

        /** Linear resample matching numpy.interp over `linspace(0,1,·)` grids. */
        fun interp(src: FloatArray, target: Int): FloatArray {
            if (src.size == target) return src
            val n = src.size
            return FloatArray(target) { j ->
                val pos = if (target == 1) 0f else j.toFloat() / (target - 1) * (n - 1)
                val lo = pos.toInt().coerceIn(0, n - 1)
                val hi = minOf(lo + 1, n - 1)
                val frac = pos - lo
                src[lo] * (1 - frac) + src[hi] * frac
            }
        }

        fun flatten(rows: List<FloatArray>): FloatArray {
            val width = rows.first().size
            val out = FloatArray(rows.size * width)
            rows.forEachIndexed { i, row -> row.copyInto(out, i * width) }
            return out
        }
    }
}
