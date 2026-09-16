package app.somasafe.training.domain.models

import app.somasafe.capture.data.GroupNormParams
import app.somasafe.capture.data.Sample
import app.somasafe.capture.domain.WindowFeatures
import app.somasafe.capture.domain.int8Floats
import app.somasafe.capture.domain.leFloats
import app.somasafe.capture.domain.normalize
import app.somasafe.training.domain.CaptureModelSpec
import app.somasafe.training.domain.WindowInputs

/**
 * Supervised anomaly classifier over the feature vector (feature-mlp). `Sample.score`
 * doubles as its label: ground truth for a group imported via
 * `export_subject_data.py` (which bakes the synthetic anomaly label into that byte),
 * but for a live capture it's just the currently staged model's own echoed inference —
 * a pseudo-label, known to train poorly, kept only so the loop runs end to end.
 */
object FeatureClassificationSpec : CaptureModelSpec {

    override val statName = "accuracy"

    override fun windows(samples: List<Sample>, norm: GroupNormParams): List<WindowInputs> {
        val features = norm.features ?: return emptyList()
        return samples.mapNotNull { s ->
            val values = s.features?.leFloats()?.takeIf { it.size == WindowFeatures.N_FEATURES }
                ?: return@mapNotNull null
            val label = s.score?.int8Floats()?.takeIf { it.size == 1 } ?: return@mapNotNull null
            mapOf("features" to features.normalize(values), "labels" to label)
        }
    }

    override fun stat(windows: List<WindowInputs>, outputs: Map<String, FloatArray>): Float {
        val logits = outputs.getValue("logits")
        val labels = windows.map { it.getValue("labels")[0] }
        require(logits.size == labels.size) { "logits/labels length mismatch" }
        val correct = logits.indices.count { (logits[it] > 0f) == (labels[it] > 0.5f) }
        return correct.toFloat() / labels.size
    }
}
