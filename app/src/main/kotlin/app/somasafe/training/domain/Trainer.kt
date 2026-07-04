package app.somasafe.training.domain

import android.content.Context
import app.somasafe.backend.data.StoredWeights
import app.somasafe.backend.data.loadWeights
import app.somasafe.backend.data.saveWeights
import app.somasafe.backend.data.trainableFile
import app.somasafe.capture.data.CaptureRepository
import app.somasafe.capture.domain.leFloats
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

/** A signature's input/output tensors are exposed prefixed with the signature name and
 *  suffixed with a `:0` output index (unique since the models have no name collisions),
 *  e.g. the `train` signature's `signal` input is the tensor `train_signal:0`. */
private fun sigParam(signature: String, param: String) = "${signature}_$param:0"

/**
 * Runs one local training epoch of a model over a processed capture group and writes
 * the trained weights back to `weights.json` (keeping the same base `weights_id`, so
 * the existing quantize/upload flow submits them as a federated update).
 *
 * The autoencoder is self-supervised — its target is the input BVP — so only the model
 * inputs are assembled per window: a raw `[BVP, ACC]` signal frame and the 8-d
 * conditioning vector `[static(6), context(2)]`. Windows without signal or without an
 * activity context are skipped; the score/label is unused. Everything is fed raw — the
 * trainable model z-scores its own inputs in the train/eval signatures.
 */
class Trainer(private val context: Context, private val repository: CaptureRepository) {

    suspend fun trainEpoch(modelKey: String, groupId: Long): TrainResult {
        val weights = loadWeights(context, modelKey)
            ?: error("weights not downloaded for '$modelKey'")
        val static = repository.groupStatic(groupId)?.leFloats()
            ?: error("no demographics for group #$groupId; set the default demographics in the Captures tab")
        require(static.size == N_STATIC) { "expected $N_STATIC static values, got ${static.size}" }

        val samples = repository.samplesForGroup(groupId)
        val windows = samples.mapNotNull { s ->
            val ppg = s.ppg?.leFloats() ?: return@mapNotNull null
            val acc = s.acc?.leFloats() ?: return@mapNotNull null
            val ctx = s.context?.leFloats() ?: return@mapNotNull null
            if (ppg.size != BVP_LEN || ctx.size != N_CONTEXT) return@mapNotNull null
            // cond = [static(6), context(2)], fed raw; the model normalizes it.
            Window(signalFrame(ppg, acc), static + ctx)
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
        val signalName = sigParam(TRAIN_SIGNATURE, SIGNAL_INPUT)
        val batches = windows.size / batchSize            // full batches only; drop the remainder
        var lossSum = 0f
        for (b in 0 until batches) {
            val batch = windows.subList(b * batchSize, (b + 1) * batchSize)
            val signal = flatten(batch.map { it.signal })
            val cond = flatten(batch.map { it.cond })
            val inputs = order.map { if (it == signalName) signal else cond }.toTypedArray()
            lossSum += model.train(inputs, 1)
        }
        val meanLoss = if (batches > 0) lossSum / batches else Float.NaN
        return TrainResult(windows = batches * batchSize, batches = batches, meanLoss = meanLoss)
    }

    /** Batch size and input ordering the train signature declares (the JNI feeds
     *  `Array<FloatArray>` in the signature's input-tensor order). Tensor names are
     *  prefixed with the signature name (`train_signal`, `train_cond`). */
    private fun trainLayout(info: ModelInfo): Pair<Int, List<String>> {
        val sig = info.signatures.firstOrNull { it.key == TRAIN_SIGNATURE }
            ?: error("model has no '$TRAIN_SIGNATURE' signature")
        val signalName = sigParam(TRAIN_SIGNATURE, SIGNAL_INPUT)
        val signal = sig.inputs.firstOrNull { it.name == signalName }
            ?: error("train signature has no '$signalName' input")
        val batch = signal.shape.firstOrNull() ?: -1
        require(batch > 0) { "train signature has a non-fixed batch size" }
        return batch to sig.inputs.map { it.name }
    }

    /** One window frame: interleaved raw `[BVP, ACC]` (ACC resampled to BVP length). */
    private fun signalFrame(bvp: FloatArray, acc: FloatArray): FloatArray {
        val acc512 = interp(acc, BVP_LEN)
        val out = FloatArray(BVP_LEN * N_SIGNALS)
        for (t in 0 until BVP_LEN) {
            out[t * N_SIGNALS] = bvp[t]
            out[t * N_SIGNALS + 1] = acc512[t]
        }
        return out
    }

    private data class Window(val signal: FloatArray, val cond: FloatArray)

    private companion object {
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
