package app.somasafe

import java.io.Closeable

class LiteRtModel(modelPath: String) : Closeable {

    private val handle: Long = nativeCreate(modelPath)

    // Runs the float eval signature. Input and output are flat arrays whose
    // length must be a multiple of the model's batch size.
    fun runEval(input: FloatArray): FloatArray = nativeRunEval(handle, input)

    // Quantizes the input, runs the int8 eval signature, and dequantizes the
    // output. Quantization parameters are read from the model itself.
    fun runEvalQuantized(input: FloatArray): FloatArray = nativeRunEvalQuantized(handle, input)

    // Runs one training pass over the provided data/labels for the given number
    // of epochs. Returns the final batch-averaged loss.
    fun train(data: FloatArray, labels: FloatArray, epochs: Int): Float =
        nativeTrain(handle, data, labels, epochs)

    // Extracts the current model weights as a flat float array.
    fun saveWeights(): FloatArray = nativeSaveWeights(handle)

    // Restores previously saved weights into the model.
    fun restoreWeights(weights: FloatArray) = nativeRestoreWeights(handle, weights)

    override fun close() = nativeDestroy(handle)

    private external fun nativeCreate(modelPath: String): Long
    private external fun nativeDestroy(handle: Long)
    private external fun nativeRunEval(handle: Long, input: FloatArray): FloatArray
    private external fun nativeRunEvalQuantized(handle: Long, input: FloatArray): FloatArray
    private external fun nativeTrain(handle: Long, data: FloatArray, labels: FloatArray, epochs: Int): Float
    private external fun nativeSaveWeights(handle: Long): FloatArray
    private external fun nativeRestoreWeights(handle: Long, weights: FloatArray)

    companion object {
        init { System.loadLibrary("somasafe_ml") }
    }
}
