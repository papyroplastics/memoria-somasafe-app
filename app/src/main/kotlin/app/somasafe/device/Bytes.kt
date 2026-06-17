package app.somasafe.device

import java.nio.ByteBuffer
import java.nio.ByteOrder

internal fun u8(value: Int): ByteArray = byteArrayOf(value.toByte())

internal fun u32le(value: Int): ByteArray =
    ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()

/** Read a little-endian uint32 as a non-negative Long. */
internal fun ByteArray.u32leAt(offset: Int): Long =
    (this[offset].toLong() and 0xFF) or
        ((this[offset + 1].toLong() and 0xFF) shl 8) or
        ((this[offset + 2].toLong() and 0xFF) shl 16) or
        ((this[offset + 3].toLong() and 0xFF) shl 24)
