package org.opentrafficmap.citstogo.cam

internal class UperBitWriter {
    private var buffer = ByteArray(64)
    private var bitCount = 0

    fun bit(value: Boolean) = bits(if (value) 1 else 0, 1)

    fun constrained(value: Long, minimum: Long, maximum: Long) {
        require(value in minimum..maximum) { "$value outside $minimum..$maximum" }
        val range = maximum - minimum + 1
        val width = if (range <= 1) 0 else 64 - java.lang.Long.numberOfLeadingZeros(range - 1)
        bits(value - minimum, width)
    }

    fun bits(value: Long, width: Int) {
        require(width in 0..64)
        ensure(bitCount + width)
        for (shift in width - 1 downTo 0) {
            if (((value ushr shift) and 1L) != 0L) {
                val byteIndex = bitCount / 8
                val bitIndex = 7 - bitCount % 8
                buffer[byteIndex] = (buffer[byteIndex].toInt() or (1 shl bitIndex)).toByte()
            }
            bitCount += 1
        }
    }

    fun toByteArray(): ByteArray = buffer.copyOf((bitCount + 7) / 8)

    private fun ensure(bits: Int) {
        val bytes = (bits + 7) / 8
        if (bytes > buffer.size) buffer = buffer.copyOf(maxOf(bytes, buffer.size * 2))
    }
}
