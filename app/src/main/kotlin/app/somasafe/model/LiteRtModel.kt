package app.somasafe.model

import java.io.Closeable

class LiteRtModel(modelPath: String) : Closeable {

    private val handle: Long = nativeCreate(modelPath)

    fun runEval(input: FloatArray): FloatArray = nativeRunEval(handle, input)

    fun runEvalQuantized(input: FloatArray): FloatArray = nativeRunEvalQuantized(handle, input)

    fun train(data: FloatArray, labels: FloatArray, epochs: Int): Float =
        nativeTrain(handle, data, labels, epochs)

    fun saveWeights(): FloatArray = nativeSaveWeights(handle)

    fun restoreWeights(weights: FloatArray) = nativeRestoreWeights(handle, weights)

    fun describe(name: String = ""): ModelInfo = nativeDescribe(handle, name)

    override fun close() = nativeDestroy(handle)

    private external fun nativeCreate(modelPath: String): Long
    private external fun nativeDestroy(handle: Long)
    private external fun nativeRunEval(handle: Long, input: FloatArray): FloatArray
    private external fun nativeRunEvalQuantized(handle: Long, input: FloatArray): FloatArray
    private external fun nativeTrain(handle: Long, data: FloatArray, labels: FloatArray, epochs: Int): Float
    private external fun nativeSaveWeights(handle: Long): FloatArray
    private external fun nativeRestoreWeights(handle: Long, weights: FloatArray)
    private external fun nativeDescribe(handle: Long, name: String): ModelInfo

    companion object {
        init { System.loadLibrary("somasafe_ml") }
    }
}
