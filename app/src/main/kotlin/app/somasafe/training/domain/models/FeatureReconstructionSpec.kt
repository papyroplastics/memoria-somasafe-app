package app.somasafe.training.domain.models

import app.somasafe.capture.data.GroupNormParams
import app.somasafe.capture.data.Sample
import app.somasafe.capture.domain.WindowFeatures
import app.somasafe.capture.domain.leFloats
import app.somasafe.capture.domain.normalize
import app.somasafe.training.domain.CaptureModelSpec
import app.somasafe.training.domain.WindowInputs

/** Reconstructs the hand-crafted feature vector (feature-ae, feature-ae-secure — same
 *  architecture, the submission path they differ on doesn't affect training). */
object FeatureReconstructionSpec : CaptureModelSpec {

    override val statName = "recon_error"

    override fun windows(samples: List<Sample>, norm: GroupNormParams): List<WindowInputs> {
        val features = norm.features ?: return emptyList()
        return samples.mapNotNull { s ->
            val values = s.features?.leFloats()?.takeIf { it.size == WindowFeatures.N_FEATURES }
                ?: return@mapNotNull null
            mapOf("features" to features.normalize(values))
        }
    }

    override fun stat(windows: List<WindowInputs>, outputs: Map<String, FloatArray>): Float =
        outputs.getValue("error").average().toFloat()
}
