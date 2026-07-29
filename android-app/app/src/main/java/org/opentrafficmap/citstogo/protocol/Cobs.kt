package org.opentrafficmap.citstogo.protocol

object Cobs {
    fun encode(input: ByteArray): ByteArray {
        val out = ByteArray(input.size + input.size / 254 + 1)
        var read = 0
        var write = 1
        var codeIndex = 0
        var code = 1

        while (read < input.size) {
            val value = input[read++]
            if (value == 0.toByte()) {
                out[codeIndex] = code.toByte()
                codeIndex = write++
                code = 1
            } else {
                out[write++] = value
                code += 1
                if (code == 0xff) {
                    out[codeIndex] = code.toByte()
                    codeIndex = write++
                    code = 1
                }
            }
        }
        out[codeIndex] = code.toByte()
        return out.copyOf(write)
    }

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
