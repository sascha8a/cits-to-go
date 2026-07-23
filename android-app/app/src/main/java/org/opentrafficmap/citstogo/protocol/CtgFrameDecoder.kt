package org.opentrafficmap.citstogo.protocol

import java.util.zip.CRC32

class CtgFrameDecoder {
    fun decode(encoded: ByteArray, length: Int = encoded.size): CitsPacket {
        val decoded = Cobs.decode(encoded, length)
        if (decoded.size < HEADER_LEN + CRC_LEN) throw ProtocolException("Frame too short")
        if (decoded[0] != 'C'.code.toByte() ||
            decoded[1] != 'T'.code.toByte() ||
            decoded[2] != 'G'.code.toByte() ||
            decoded[3] != '1'.code.toByte()
        ) {
            throw ProtocolException("Bad CTG1 magic")
        }
        val version = decoded[4].toInt() and 0xff
        val type = decoded[5].toInt() and 0xff
        val headerLen = u16(decoded, 6)
        if (version != 1) throw ProtocolException("Unsupported CTG version $version")
        if (type != 1) throw ProtocolException("Unsupported CTG frame type $type")
        if (headerLen != HEADER_LEN) throw ProtocolException("Unexpected header length $headerLen")

        val capturedLen = u16(decoded, 26)
        val totalLen = headerLen + capturedLen + CRC_LEN
        if (decoded.size != totalLen) {
            throw ProtocolException("Frame length ${decoded.size} does not match captured length $capturedLen")
        }

        val expectedCrc = u32(decoded, totalLen - CRC_LEN)
        val crc = CRC32()
        crc.update(decoded, 0, totalLen - CRC_LEN)
        val actualCrc = crc.value
        if (actualCrc != expectedCrc) {
            throw ProtocolException("CRC mismatch")
        }

        val payload = decoded.copyOfRange(headerLen, headerLen + capturedLen)
        return CitsPacket(
            sequence = u32(decoded, 10),
            timestampUs = u64(decoded, 14),
            frequencyMhz = u16(decoded, 22),
            rssiDbm = decoded[28].toInt(),
            wifiType = decoded[29].toInt() and 0xff,
            rxState = decoded[30].toInt() and 0xff,
            flags = u16(decoded, 8),
            originalLength = u16(decoded, 24),
            capturedLength = capturedLen,
            payload = payload,
        )
    }

    private fun u16(buf: ByteArray, offset: Int): Int =
        (buf[offset].toInt() and 0xff) or ((buf[offset + 1].toInt() and 0xff) shl 8)

    private fun u32(buf: ByteArray, offset: Int): Long =
        (u16(buf, offset).toLong()) or (u16(buf, offset + 2).toLong() shl 16)

    private fun u64(buf: ByteArray, offset: Int): Long {
        val low = u32(buf, offset)
        val high = u32(buf, offset + 4)
        return low or (high shl 32)
    }

    companion object {
        const val HEADER_LEN = 32
        const val CRC_LEN = 4
    }
}

class ProtocolException(message: String) : Exception(message)
