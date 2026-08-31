package app.somasafe.training.domain

import android.content.Context
import app.somasafe.backend.data.loadBaseWeights
import app.somasafe.backend.data.loadModelMeta
import app.somasafe.backend.data.loadTrainedWeights
import app.somasafe.backend.data.saveTrainedWeights
import app.somasafe.backend.data.readTrainableBytes
import app.somasafe.capture.data.CaptureRepository
import app.somasafe.capture.domain.leFloats
import app.somasafe.capture.domain.normalize
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.sqrt

enum class TrainPhase { SCORING_BASELINE, TRAINING, SCORING_RESULT, SAVING }

sealed interface TrainState {
    data object Idle : TrainState
    data object Preparing : TrainState
    data class Running(
        val phase: TrainPhase,
        val done: Int,
        val total: Int,
        val batch: Int,
        val batches: Int,
    ) : TrainState
    data class Done(val metrics: TrainMetrics) : TrainState
    data class Error(val message: String) : TrainState
}

data class TrainMetrics(
    val samples: Int,
    val windows: Int,
    val dropped: Int,
    val remainder: Int,
    val batchSize: Int,
    val batches: Int,
    val scoredBatches: Int,
    val errorBefore: Float,
    val errorAfter: Float,
    val meanLoss: Float,
    val lastLoss: Float,
    val updateNorm: Float,
    val updateMaxAbs: Float,
    val weightCount: Int,
    val prepareMs: Long,
    val scoreBeforeMs: Long,
    val trainMs: Long,
    val scoreAfterMs: Long,
    val saveMs: Long,
) {
    val totalMs: Long get() = prepareMs + scoreBeforeMs + trainMs + scoreAfterMs + saveMs
    val scoreMs: Long get() = scoreBeforeMs + scoreAfterMs
    val msPerBatch: Long get() = if (batches > 0) trainMs / batches else 0
    val errorChange: Float get() =
        if (errorBefore > 0f) (errorAfter - errorBefore) / errorBefore * 100f else Float.NaN
}

private const val BVP_LEN = 512
private const val TRAIN_SIGNATURE = "train"
private const val EVAL_SIGNATURE = "eval"
private const val SIGNAL_INPUT = "signal"
private const val ERROR_OUTPUT = "error"
private const val SCORE_BATCHES = 20

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
 * input assembled per window is its BVP frame. Windows without signal are skipped; the
 * score/label is unused. No model normalizes its own input any more, so each window is
 * z-scored here with the capture group's own signal parameters (derived by preprocessing,
 * so the group has to have been processed first).
 */
class Trainer(private val context: Context, private val repository: CaptureRepository) {

    private val _state = MutableStateFlow<TrainState>(TrainState.Idle)
    val state = _state.asStateFlow()

    suspend fun trainEpoch(modelKey: String, groupId: Long): TrainMetrics {
        _state.value = TrainState.Preparing
        return try {
            runTraining(modelKey, groupId).also { _state.value = TrainState.Done(it) }
        } catch (e: CancellationException) {
            _state.value = TrainState.Idle
            throw e
        } catch (e: Exception) {
            _state.value = TrainState.Error(e.message ?: "training failed")
            throw e
        }
    }

    private suspend fun runTraining(modelKey: String, groupId: Long): TrainMetrics {
        var mark = System.nanoTime()
        fun lap(): Long {
            val now = System.nanoTime()
            val ms = (now - mark) / 1_000_000
            mark = now
            return ms
        }

        checkNotNull(loadModelMeta(context, modelKey)?.weightsId) { "model '$modelKey' not downloaded" }
        val prevTrained = loadTrainedWeights(context, modelKey)
        val prevBase = loadBaseWeights(context, modelKey)

        val norm = repository.normParams(groupId)?.signal
            ?: error("capture group #$groupId has no normalization parameters — Process it first")

        val samples = repository.samplesForGroup(groupId)
        val windows = samples.mapNotNull { s ->
            val ppg = s.ppg?.leFloats() ?: return@mapNotNull null
            if (ppg.size != BVP_LEN) return@mapNotNull null
            norm.normalize(ppg)
        }

        return withContext(Dispatchers.Default) {
            LiteRtModel(readTrainableBytes(context, modelKey)).use { model ->
                val info = model.describe()
                val batchSize = trainBatchSize(info)
                val errorIndex = evalErrorIndex(info)
                val batches = windows.size / batchSize
                require(batches > 0) {
                    "no full batch of windows to train on (${windows.size} usable, batch size $batchSize)"
                }

                val baseline = prevBase ?: model.saveWeights()
                if (prevTrained != null) model.restoreWeights(prevTrained)

                val scored = (0 until batches).shuffled().take(SCORE_BATCHES).sorted()
                val scoreInput = flatten(scored.flatMap { b -> batchAt(windows, b, batchSize) })
                val total = 2 * scored.size + batches
                val prepareMs = lap()

                currentCoroutineContext().ensureActive()
                _state.value = TrainState.Running(TrainPhase.SCORING_BASELINE, 0, total, 0, batches)
                val errorBefore = meanError(model, errorIndex, scoreInput)
                val scoreBeforeMs = lap()

                var lossSum = 0f
                var lastLoss = Float.NaN
                for (b in 0 until batches) {
                    currentCoroutineContext().ensureActive()
                    _state.value = TrainState.Running(
                        TrainPhase.TRAINING, scored.size + b, total, b + 1, batches,
                    )
                    lastLoss = model.train(arrayOf(flatten(batchAt(windows, b, batchSize))), 1)
                    lossSum += lastLoss
                }
                val trainMs = lap()

                currentCoroutineContext().ensureActive()
                _state.value = TrainState.Running(
                    TrainPhase.SCORING_RESULT, scored.size + batches, total, batches, batches,
                )
                val errorAfter = meanError(model, errorIndex, scoreInput)
                val scoreAfterMs = lap()

                _state.value = TrainState.Running(TrainPhase.SAVING, total, total, batches, batches)
                val trained = model.saveWeights()
                saveTrainedWeights(context, modelKey, baseline, trained)
                val saveMs = lap()

                var squares = 0.0
                var maxAbs = 0f
                for (i in trained.indices) {
                    val delta = trained[i] - baseline[i]
                    squares += delta.toDouble() * delta
                    maxAbs = maxOf(maxAbs, abs(delta))
                }

                TrainMetrics(
                    samples = samples.size,
                    windows = windows.size,
                    dropped = samples.size - windows.size,
                    remainder = windows.size - batches * batchSize,
                    batchSize = batchSize,
                    batches = batches,
                    scoredBatches = scored.size,
                    errorBefore = errorBefore,
                    errorAfter = errorAfter,
                    meanLoss = lossSum / batches,
                    lastLoss = lastLoss,
                    updateNorm = sqrt(squares).toFloat(),
                    updateMaxAbs = maxAbs,
                    weightCount = trained.size,
                    prepareMs = prepareMs,
                    scoreBeforeMs = scoreBeforeMs,
                    trainMs = trainMs,
                    scoreAfterMs = scoreAfterMs,
                    saveMs = saveMs,
                )
            }
        }
    }

    private fun meanError(model: LiteRtModel, errorIndex: Int, input: FloatArray): Float =
        model.runEval(arrayOf(input))[errorIndex].average().toFloat()

    /** Batch size the train signature declares. Its sole input is the signal tensor. */
    private fun trainBatchSize(info: ModelInfo): Int {
        val sig = info.signatures.firstOrNull { it.key == TRAIN_SIGNATURE }
            ?: error("model has no '$TRAIN_SIGNATURE' signature")
        val signal = sig.inputs.singleOrNull()?.takeIf { it.paramName == SIGNAL_INPUT }
            ?: error("train signature must take exactly one '$SIGNAL_INPUT' input")
        val batch = signal.shape.firstOrNull() ?: -1
        require(batch > 0) { "train signature has a non-fixed batch size" }
        return batch
    }

    private fun evalErrorIndex(info: ModelInfo): Int {
        val sig = info.signatures.firstOrNull { it.key == EVAL_SIGNATURE }
            ?: error("model has no '$EVAL_SIGNATURE' signature")
        val index = sig.outputs.indexOfFirst { it.paramName == ERROR_OUTPUT }
        require(index >= 0) { "eval signature has no '$ERROR_OUTPUT' output" }
        return index
    }

    private companion object {
        fun batchAt(windows: List<FloatArray>, batch: Int, batchSize: Int): List<FloatArray> =
            windows.subList(batch * batchSize, (batch + 1) * batchSize)

        fun flatten(rows: List<FloatArray>): FloatArray {
            val width = rows.first().size
            val out = FloatArray(rows.size * width)
            rows.forEachIndexed { i, row -> row.copyInto(out, i * width) }
            return out
        }
    }
}
