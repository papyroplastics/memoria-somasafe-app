package app.somasafe.model

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
