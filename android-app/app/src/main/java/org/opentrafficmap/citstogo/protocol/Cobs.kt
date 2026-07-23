package org.opentrafficmap.citstogo.protocol

object Cobs {
    fun decode(input: ByteArray, length: Int = input.size): ByteArray {
        require(length >= 0 && length <= input.size)
        val out = ByteArray(length)
        var read = 0
        var write = 0

        while (read < length) {
            val code = input[read++].toInt() and 0xff
            if (code == 0 || read + code - 1 > length) {
                throw IllegalArgumentException("Malformed COBS record")
            }
            repeat(code - 1) {
                out[write++] = input[read++]
            }
            if (code < 0xff && read < length) {
                out[write++] = 0
            }
        }
        return out.copyOf(write)
    }
}
