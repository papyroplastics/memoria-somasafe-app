package app.somasafe.training.domain.models

import app.somasafe.capture.data.GroupNormParams
import app.somasafe.capture.data.Sample
import app.somasafe.capture.domain.leFloats
import app.somasafe.capture.domain.normalize
import app.somasafe.training.domain.CaptureModelSpec
import app.somasafe.training.domain.WindowInputs

/** Reconstructs a raw BVP window (cnn-ae, and any future lstm-ae/gru-ae sharing the
 *  same shape). */
object SignalReconstructionSpec : CaptureModelSpec {

    private const val BVP_LEN = 512

    override val statName = "recon_error"

    override fun windows(samples: List<Sample>, norm: GroupNormParams): List<WindowInputs> {
        val signal = norm.signal ?: return emptyList()
        return samples.mapNotNull { s ->
            val ppg = s.ppg?.leFloats()?.takeIf { it.size == BVP_LEN } ?: return@mapNotNull null
            mapOf("signal" to signal.normalize(ppg))
        }
    }

    override fun stat(windows: List<WindowInputs>, outputs: Map<String, FloatArray>): Float =
        outputs.getValue("error").average().toFloat()
}
