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
    /** Name the signature declares the tensor under (`signal`, `error`, ...). Only the
     *  inputs carry it in [name] too; an output's graph name is the call op that
     *  produces it (`StatefulPartitionedCall:0`), so match on this, never on [name]. */
    val paramName: String,
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
