package app.somasafe.training.domain.models

import app.somasafe.training.domain.CaptureModelSpec

/** Model keys the on-device train loop knows how to run, mirroring backend's
 *  `model_list.py`. A key absent here isn't broken, just not wired up yet. */
val CAPTURE_MODELS: Map<String, CaptureModelSpec> = mapOf(
    "cnn-ae" to SignalReconstructionSpec,
    "feature-ae" to FeatureReconstructionSpec,
    "feature-ae-secure" to FeatureReconstructionSpec,
    "feature-mlp" to FeatureClassificationSpec,
)
