package app.somasafe.training.domain

data class QuantizationInfo(
    val type: String,           // "none" | "per_tensor" | "per_channel" | "block_wise" | "unknown"
    val scale: Float,           // per_tensor
    val zeroPoint: Int,         // per_tensor
    val quantizedDim: Int,      // per_channel
    val scales: FloatArray,     // per_channel
    val zeroPoints: IntArray,   // per_channel
)

data class TensorInfo(
    val name: String,
    val elementType: String,
    val ranked: Boolean,
    val shape: IntArray,        // -1 indicates a dynamic dimension; empty for scalars
    val quantization: QuantizationInfo,
)

data class SignatureInfo(
    val key: String,
    val inputs: List<TensorInfo>,
    val outputs: List<TensorInfo>,
)

data class ModelInfo(
    val name: String,
    val signatures: List<SignatureInfo>,
)

/** Bare param name a tensor was declared with in its signature, stripping the
 *  `${signature}_` prefix and the `:0` output index Trainer.kt's `sigParam` adds
 *  (e.g. `train_signal:0` under signature `train` is param `signal`). */
fun TensorInfo.paramName(signature: String): String =
    name.substringBeforeLast(':').removePrefix("${signature}_")
