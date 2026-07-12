package app.somasafe.training.domain

import java.io.Closeable

class LiteRtModel(modelBytes: ByteArray) : Closeable {

    private val handle: Long = nativeCreate(modelBytes)

    fun runEval(inputs: Array<FloatArray>): Array<FloatArray> = nativeRunEval(handle, inputs)

    fun runEvalQuantized(input: FloatArray): FloatArray = nativeRunEvalQuantized(handle, input)

    fun train(inputs: Array<FloatArray>, epochs: Int): Float = nativeTrain(handle, inputs, epochs)

    fun saveWeights(): FloatArray = nativeSaveWeights(handle)

    fun restoreWeights(weights: FloatArray) = nativeRestoreWeights(handle, weights)

    fun describe(name: String = ""): ModelInfo = nativeDescribe(handle, name)

    override fun close() = nativeDestroy(handle)

    private external fun nativeCreate(modelBytes: ByteArray): Long
    private external fun nativeDestroy(handle: Long)
    private external fun nativeRunEval(handle: Long, inputs: Array<FloatArray>): Array<FloatArray>
    private external fun nativeRunEvalQuantized(handle: Long, input: FloatArray): FloatArray
    private external fun nativeTrain(handle: Long, inputs: Array<FloatArray>, epochs: Int): Float
    private external fun nativeSaveWeights(handle: Long): FloatArray
    private external fun nativeRestoreWeights(handle: Long, weights: FloatArray)
    private external fun nativeDescribe(handle: Long, name: String): ModelInfo

    companion object {
        init { System.loadLibrary("somasafe_ml") }
    }
}
