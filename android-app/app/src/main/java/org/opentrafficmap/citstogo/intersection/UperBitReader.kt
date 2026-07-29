package org.opentrafficmap.citstogo.intersection

internal class UperBitReader(private val bytes: ByteArray, byteOffset: Int = 0) {
    private var bitOffset = byteOffset * 8

    val remainingBits: Int get() = bytes.size * 8 - bitOffset

    fun bit(): Boolean = bits(1) != 0L

    fun bits(width: Int): Long {
        require(width in 0..64)
        if (remainingBits < width) throw IntersectionDecodeException("PER payload ended early")
        var value = 0L
        repeat(width) {
            val byte = bytes[bitOffset / 8].toInt() and 0xff
            value = (value shl 1) or ((byte ushr (7 - bitOffset % 8)) and 1).toLong()
            bitOffset += 1
        }
        return value
    }

    fun constrained(minimum: Long, maximum: Long): Long {
        val range = maximum - minimum + 1
        val width = if (range <= 1) 0 else 64 - java.lang.Long.numberOfLeadingZeros(range - 1)
        return minimum + bits(width)
    }

    fun signedConstrained(minimum: Int, maximum: Int): Int =
        constrained(minimum.toLong(), maximum.toLong()).toInt()

    fun sequenceLength(minimum: Int, maximum: Int): Int =
        constrained(minimum.toLong(), maximum.toLong()).toInt()

    fun skipBits(width: Int) {
        bits(width)
    }

    fun skipNormallySmallLength() {
        if (!bit()) {
            skipBits(6)
        } else {
            val octets = bits(7).toInt()
            skipBits(octets * 8)
        }
    }
}

class IntersectionDecodeException(message: String) : Exception(message)
