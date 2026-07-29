package org.opentrafficmap.citstogo.protocol

import java.util.zip.CRC32

object CtgFrameEncoder {
    const val FLAG_EN_SYS_SEQ = 0x0001
    const val MAX_PACKET_BYTES = 2352

    fun txRequest(requestId: Long, packet: ByteArray, flags: Int = FLAG_EN_SYS_SEQ): ByteArray {
        require(packet.isNotEmpty()) { "Packet must not be empty" }
        require(packet.size <= MAX_PACKET_BYTES) { "Packet exceeds $MAX_PACKET_BYTES bytes" }

        val headerLen = CtgFrameDecoder.TX_REQUEST_HEADER_LEN
        val decoded = ByteArray(headerLen + packet.size + CtgFrameDecoder.CRC_LEN)
        decoded[0] = 'C'.code.toByte()
        decoded[1] = 'T'.code.toByte()
        decoded[2] = 'G'.code.toByte()
        decoded[3] = '1'.code.toByte()
        decoded[4] = 1
        decoded[5] = CtgFrameDecoder.TYPE_TX_REQUEST.toByte()
        putU16(decoded, 6, headerLen)
        putU32(decoded, 8, requestId)
        putU16(decoded, 12, packet.size)
        putU16(decoded, 14, flags)
        packet.copyInto(decoded, headerLen)

        val crc = CRC32()
        crc.update(decoded, 0, decoded.size - CtgFrameDecoder.CRC_LEN)
        putU32(decoded, decoded.size - CtgFrameDecoder.CRC_LEN, crc.value)
        return Cobs.encode(decoded) + byteArrayOf(0)
    }

    private fun putU16(buffer: ByteArray, offset: Int, value: Int) {
        buffer[offset] = value.toByte()
        buffer[offset + 1] = (value ushr 8).toByte()
    }

    private fun putU32(buffer: ByteArray, offset: Int, value: Long) {
        buffer[offset] = value.toByte()
        buffer[offset + 1] = (value ushr 8).toByte()
        buffer[offset + 2] = (value ushr 16).toByte()
        buffer[offset + 3] = (value ushr 24).toByte()
    }
}
