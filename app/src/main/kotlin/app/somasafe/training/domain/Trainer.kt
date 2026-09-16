package app.somasafe.training.domain

import android.content.Context
import app.somasafe.backend.data.loadBaseWeights
import app.somasafe.backend.data.loadModelMeta
import app.somasafe.backend.data.loadTrainedWeights
import app.somasafe.backend.data.saveTrainedWeights
import app.somasafe.backend.data.readTrainableBytes
import app.somasafe.capture.data.CaptureRepository
import app.somasafe.training.domain.models.CAPTURE_MODELS
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
    val statName: String,
    val statBefore: Float,
    val statAfter: Float,
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
    val statChange: Float get() =
        if (statBefore > 0f) (statAfter - statBefore) / statBefore * 100f else Float.NaN
}

private const val TRAIN_SIGNATURE = "train"
private const val EVAL_SIGNATURE = "eval"
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
 * Which capture columns feed the model, how they're normalized, and what the headline
 * eval stat means are all model-specific — delegated to a [CaptureModelSpec] looked up
 * by [CAPTURE_MODELS]; this class only owns the batch/train/score/save mechanics that
 * are the same for every model.
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

        val spec = CAPTURE_MODELS[modelKey]
            ?: error("training not supported for model '$modelKey'")
        checkNotNull(loadModelMeta(context, modelKey)?.weightsId) { "model '$modelKey' not downloaded" }
        val prevTrained = loadTrainedWeights(context, modelKey)
        val prevBase = loadBaseWeights(context, modelKey)

        val norm = repository.normParams(groupId)
            ?: error("capture group #$groupId has no normalization parameters — Process it first")

        val samples = repository.samplesForGroup(groupId)
        val windows = spec.windows(samples, norm)

        return withContext(Dispatchers.Default) {
            LiteRtModel(readTrainableBytes(context, modelKey)).use { model ->
                val info = model.describe()
                val trainSig = signature(info, TRAIN_SIGNATURE)
                val evalSig = signature(info, EVAL_SIGNATURE)
                val batchSize = batchSizeOf(trainSig)
                val batches = windows.size / batchSize
                require(batches > 0) {
                    "no full batch of windows to train on (${windows.size} usable, batch size $batchSize)"
                }

                val baseline = prevBase ?: model.saveWeights()
                if (prevTrained != null) model.restoreWeights(prevTrained)

                val scored = (0 until batches).shuffled().take(SCORE_BATCHES).sorted()
                val scoreWindows = scored.flatMap { b -> batchAt(windows, b, batchSize) }
                val total = 2 * scored.size + batches
                val prepareMs = lap()

                currentCoroutineContext().ensureActive()
                _state.value = TrainState.Running(TrainPhase.SCORING_BASELINE, 0, total, 0, batches)
                val statBefore = scoreStat(model, evalSig, spec, scoreWindows)
                val scoreBeforeMs = lap()

                var lossSum = 0f
                var lastLoss = Float.NaN
                val trainParams = trainSig.inputs.map { it.paramName }
                for (b in 0 until batches) {
                    currentCoroutineContext().ensureActive()
                    _state.value = TrainState.Running(
                        TrainPhase.TRAINING, scored.size + b, total, b + 1, batches,
                    )
                    lastLoss = model.train(inputsFor(batchAt(windows, b, batchSize), trainParams), 1)
                    lossSum += lastLoss
                }
                val trainMs = lap()

                currentCoroutineContext().ensureActive()
                _state.value = TrainState.Running(
                    TrainPhase.SCORING_RESULT, scored.size + batches, total, batches, batches,
                )
                val statAfter = scoreStat(model, evalSig, spec, scoreWindows)
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
                    statName = spec.statName,
                    statBefore = statBefore,
                    statAfter = statAfter,
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

    private fun scoreStat(
        model: LiteRtModel, evalSig: SignatureInfo, spec: CaptureModelSpec, windows: List<WindowInputs>,
    ): Float {
        val inputs = inputsFor(windows, evalSig.inputs.map { it.paramName })
        val outputs = evalSig.outputs.map { it.paramName }.zip(model.runEval(inputs).toList()).toMap()
        return spec.stat(windows, outputs)
    }

    private fun signature(info: ModelInfo, key: String): SignatureInfo =
        info.signatures.firstOrNull { it.key == key } ?: error("model has no '$key' signature")

    private fun batchSizeOf(sig: SignatureInfo): Int {
        val batch = sig.inputs.firstOrNull()?.shape?.firstOrNull() ?: -1
        require(batch > 0) { "'${sig.key}' signature has a non-fixed batch size" }
        return batch
    }

    private companion object {
        fun batchAt(windows: List<WindowInputs>, batch: Int, batchSize: Int): List<WindowInputs> =
            windows.subList(batch * batchSize, (batch + 1) * batchSize)

        fun inputsFor(windows: List<WindowInputs>, paramNames: List<String>): Array<FloatArray> =
            paramNames.map { name -> flatten(windows.map { it.getValue(name) }) }.toTypedArray()

        fun flatten(rows: List<FloatArray>): FloatArray {
            val width = rows.first().size
            val out = FloatArray(rows.size * width)
            rows.forEachIndexed { i, row -> row.copyInto(out, i * width) }
            return out
        }
    }
}
