package app.somasafe.capture.domain

import java.nio.ByteOrder
import java.nio.ByteBuffer

/** Little-endian float32 <-> ByteArray, the on-disk layout the capture schema uses
 *  for ppg/features. */

internal fun ByteArray.leFloats(): FloatArray {
    val buf = ByteBuffer.wrap(this).order(ByteOrder.LITTLE_ENDIAN)
    return FloatArray(size / Float.SIZE_BYTES) { buf.float }
}

internal fun FloatArray.leBytes(): ByteArray {
    val buf = ByteBuffer.allocate(size * Float.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
    for (v in this) buf.putFloat(v)
    return buf.array()
}
