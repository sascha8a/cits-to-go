package org.opentrafficmap.citstogo.protocol

import java.util.zip.CRC32

sealed interface CtgInboundFrame {
    data class Capture(val packet: CitsPacket) : CtgInboundFrame
    data class TxResult(
        val requestId: Long,
        val status: Long,
        val packetLength: Int,
        val packet: ByteArray? = null,
    ) : CtgInboundFrame {
        val successful: Boolean get() = status == 0L
    }
}

class CtgFrameDecoder {
    fun decode(encoded: ByteArray, length: Int = encoded.size): CtgInboundFrame {
        val decoded = Cobs.decode(encoded, length)
        if (decoded.size < MIN_HEADER_LEN + CRC_LEN) throw ProtocolException("Frame too short")
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
        if (headerLen < MIN_HEADER_LEN || decoded.size < headerLen + CRC_LEN) {
            throw ProtocolException("Invalid CTG header length $headerLen")
        }

        val expectedCrc = u32(decoded, decoded.size - CRC_LEN)
        val crc = CRC32()
        crc.update(decoded, 0, decoded.size - CRC_LEN)
        val actualCrc = crc.value
        if (actualCrc != expectedCrc) {
            throw ProtocolException("CRC mismatch")
        }

        return when (type) {
            TYPE_CAPTURE -> decodeCapture(decoded, headerLen)
            TYPE_TX_RESULT -> decodeTxResult(decoded, headerLen)
            else -> throw ProtocolException("Unsupported CTG frame type $type")
        }
    }

    private fun decodeCapture(decoded: ByteArray, headerLen: Int): CtgInboundFrame.Capture {
        if (headerLen != CAPTURE_HEADER_LEN) throw ProtocolException("Unexpected capture header length $headerLen")
        val capturedLen = u16(decoded, 26)
        val totalLen = headerLen + capturedLen + CRC_LEN
        if (decoded.size != totalLen) {
            throw ProtocolException("Frame length ${decoded.size} does not match captured length $capturedLen")
        }
        val payload = decoded.copyOfRange(headerLen, headerLen + capturedLen)
        return CtgInboundFrame.Capture(CitsPacket(
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
        ))
    }

    private fun decodeTxResult(decoded: ByteArray, headerLen: Int): CtgInboundFrame.TxResult {
        if (headerLen != TX_RESULT_HEADER_LEN || decoded.size < headerLen + CRC_LEN) {
            throw ProtocolException("Malformed TX result")
        }
        val packetLength = u16(decoded, 16)
        val payloadLength = decoded.size - headerLen - CRC_LEN
        if (payloadLength != 0 && payloadLength != packetLength) {
            throw ProtocolException("TX result payload length $payloadLength does not match packet length $packetLength")
        }
        return CtgInboundFrame.TxResult(
            requestId = u32(decoded, 8),
            status = u32(decoded, 12),
            packetLength = packetLength,
            packet = if (payloadLength > 0) decoded.copyOfRange(headerLen, headerLen + payloadLength) else null,
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
        const val TYPE_CAPTURE = 1
        const val TYPE_TX_REQUEST = 2
        const val TYPE_TX_RESULT = 3
        const val CAPTURE_HEADER_LEN = 32
        const val TX_REQUEST_HEADER_LEN = 16
        const val TX_RESULT_HEADER_LEN = 20
        const val MIN_HEADER_LEN = 8
        const val CRC_LEN = 4
    }
}

class ProtocolException(message: String) : Exception(message)
