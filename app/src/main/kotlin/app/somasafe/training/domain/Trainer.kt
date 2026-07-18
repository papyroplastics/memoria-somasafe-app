package app.somasafe.training.domain

import android.content.Context
import app.somasafe.backend.data.loadBaseWeights
import app.somasafe.backend.data.loadModelMeta
import app.somasafe.backend.data.loadTrainedWeights
import app.somasafe.backend.data.saveTrainedWeights
import app.somasafe.backend.data.readTrainableBytes
import app.somasafe.capture.data.CaptureRepository
import app.somasafe.capture.domain.leFloats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Outcome of one on-device training epoch. */
data class TrainResult(val windows: Int, val batches: Int, val meanLoss: Float)

private const val BVP_LEN = 512
private const val TRAIN_SIGNATURE = "train"
private const val SIGNAL_INPUT = "signal"

/** A signature's input/output tensors are exposed prefixed with the signature name and
 *  suffixed with a `:0` output index (unique since the models have no name collisions),
 *  e.g. the `train` signature's `signal` input is the tensor `train_signal:0`. */
private fun sigParam(signature: String, param: String) = "${signature}_$param:0"

/**
 * Runs one local training epoch of a model over a processed capture group and writes
 * the absolute trained weights to `trained_weights.bin` and the global snapshot they
 * derive from to `base_weights.bin` (the upload flows submit the delta `trained −
 * base`, pinned to the base `weights_id` in `trainable.json`). The first epoch trains
 * straight on the weights baked into the trainable model — the global snapshot, also
 * saved as the baseline; later epochs restore the locally trained weights and carry
 * the original baseline forward.
 *
 * The autoencoder is self-supervised — its target is the input BVP — so the only model
 * input assembled per window is its raw BVP frame. Windows without signal are skipped;
 * the score/label is unused. The signal is fed raw — the trainable model z-scores it in
 * the train/eval signatures.
 */
class Trainer(private val context: Context, private val repository: CaptureRepository) {

    suspend fun trainEpoch(modelKey: String, groupId: Long): TrainResult {
        checkNotNull(loadModelMeta(context, modelKey)?.weightsId) { "model '$modelKey' not downloaded" }
        val prevTrained = loadTrainedWeights(context, modelKey)
        val prevBase = loadBaseWeights(context, modelKey)

        val samples = repository.samplesForGroup(groupId)
        val windows = samples.mapNotNull { s ->
            val ppg = s.ppg?.leFloats() ?: return@mapNotNull null
            if (ppg.size != BVP_LEN) return@mapNotNull null
            ppg
        }

        return withContext(Dispatchers.Default) {
            LiteRtModel(readTrainableBytes(context, modelKey)).use { model ->
                val baseline = prevBase ?: model.saveWeights()
                if (prevTrained != null) model.restoreWeights(prevTrained)
                val result = runEpoch(model, windows)
                saveTrainedWeights(context, modelKey, baseline, model.saveWeights())
                result
            }
        }
    }

    private fun runEpoch(model: LiteRtModel, windows: List<FloatArray>): TrainResult {
        val batchSize = trainBatchSize(model.describe())
        val batches = windows.size / batchSize            // full batches only; drop the remainder
        var lossSum = 0f
        for (b in 0 until batches) {
            val batch = windows.subList(b * batchSize, (b + 1) * batchSize)
            lossSum += model.train(arrayOf(flatten(batch)), 1)
        }
        val meanLoss = if (batches > 0) lossSum / batches else Float.NaN
        return TrainResult(windows = batches * batchSize, batches = batches, meanLoss = meanLoss)
    }

    /** Batch size the train signature declares. Its sole input is the signal tensor,
     *  named after the signature (`train_signal`). */
    private fun trainBatchSize(info: ModelInfo): Int {
        val sig = info.signatures.firstOrNull { it.key == TRAIN_SIGNATURE }
            ?: error("model has no '$TRAIN_SIGNATURE' signature")
        val signalName = sigParam(TRAIN_SIGNATURE, SIGNAL_INPUT)
        val signal = sig.inputs.singleOrNull()?.takeIf { it.name == signalName }
            ?: error("train signature must take exactly one '$signalName' input")
        val batch = signal.shape.firstOrNull() ?: -1
        require(batch > 0) { "train signature has a non-fixed batch size" }
        return batch
    }

    private companion object {
        fun flatten(rows: List<FloatArray>): FloatArray {
            val width = rows.first().size
            val out = FloatArray(rows.size * width)
            rows.forEachIndexed { i, row -> row.copyInto(out, i * width) }
            return out
        }
    }
}
