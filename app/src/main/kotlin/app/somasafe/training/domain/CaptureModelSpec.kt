package app.somasafe.training.domain

import app.somasafe.capture.data.GroupNormParams
import app.somasafe.capture.data.Sample

/** One window's per-tensor inputs, keyed by the signature's own input param names
 *  ("signal" / "features" / "labels"). */
typealias WindowInputs = Map<String, FloatArray>

/**
 * The model-specific half of on-device training: how to pull this model's windows out
 * of a capture group, and how to score its eval outputs into one headline number.
 * Mirrors the backend's `Trainer` (backend/ml/models/common.py), scoped to a single
 * capture group instead of a whole dataset.
 */
interface CaptureModelSpec {
    /** Reused from the backend's `Trainer.primary_metric` strings ("recon_error",
     *  "accuracy", ...) so the two sides speak the same vocabulary. */
    val statName: String

    /** One capture group's samples -> per-window tensors, dropping windows missing the
     *  columns or norm params this model needs. */
    fun windows(samples: List<Sample>, norm: GroupNormParams): List<WindowInputs>

    /** One summary number from an eval call's outputs (output tensor name -> one value
     *  per window), aligned to the [windows] that produced them. */
    fun stat(windows: List<WindowInputs>, outputs: Map<String, FloatArray>): Float
}
