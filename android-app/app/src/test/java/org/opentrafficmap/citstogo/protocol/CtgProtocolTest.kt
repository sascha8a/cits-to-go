package org.opentrafficmap.citstogo.protocol

import java.util.zip.CRC32
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class CtgProtocolTest {
    @Test
    fun cobsRoundTripIncludesZerosAndLongRuns() {
        val input = ByteArray(700) { ((it * 37) and 0xff).toByte() }.also {
            it[0] = 0
            it[254] = 0
            it[699] = 0
        }
        assertArrayEquals(input, Cobs.decode(Cobs.encode(input)))
    }

    @Test
    fun txRequestHasCorrelatedHeaderPayloadAndCrc() {
        val packet = byteArrayOf(0x88.toByte(), 0, 1, 2, 0, 3)
        val wire = CtgFrameEncoder.txRequest(0x1234_5678, packet)
        assertEquals(0, wire.last().toInt())
        val decoded = Cobs.decode(wire, wire.size - 1)
        assertEquals("CTG1", decoded.copyOfRange(0, 4).toString(Charsets.US_ASCII))
        assertEquals(CtgFrameDecoder.TYPE_TX_REQUEST, decoded[5].toInt())
        assertEquals(16, u16(decoded, 6))
        assertEquals(0x1234_5678L, u32(decoded, 8))
        assertEquals(packet.size, u16(decoded, 12))
        assertArrayEquals(packet, decoded.copyOfRange(16, 16 + packet.size))

        val crc = CRC32().apply { update(decoded, 0, decoded.size - 4) }.value
        assertEquals(crc, u32(decoded, decoded.size - 4))
    }

    @Test
    fun txResultCanCarrySuccessfulPayload() {
        val packet = byteArrayOf(0x88.toByte(), 0, 1, 2, 0, 3)
        val decoded = ByteArray(CtgFrameDecoder.TX_RESULT_HEADER_LEN + packet.size + CtgFrameDecoder.CRC_LEN)
        "CTG1".toByteArray(Charsets.US_ASCII).copyInto(decoded, 0)
        decoded[4] = 1
        decoded[5] = CtgFrameDecoder.TYPE_TX_RESULT.toByte()
        putU16(decoded, 6, CtgFrameDecoder.TX_RESULT_HEADER_LEN)
        putU32(decoded, 8, 0x1234_5678L)
        putU32(decoded, 12, 0)
        putU16(decoded, 16, packet.size)
        packet.copyInto(decoded, CtgFrameDecoder.TX_RESULT_HEADER_LEN)
        val crc = CRC32().apply { update(decoded, 0, decoded.size - CtgFrameDecoder.CRC_LEN) }.value
        putU32(decoded, decoded.size - CtgFrameDecoder.CRC_LEN, crc)

        val result = CtgFrameDecoder().decode(Cobs.encode(decoded)) as CtgInboundFrame.TxResult

        assertEquals(0x1234_5678L, result.requestId)
        assertEquals(true, result.successful)
        assertEquals(packet.size, result.packetLength)
        assertArrayEquals(packet, result.packet)
    }

    private fun u16(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8)

    private fun u32(bytes: ByteArray, offset: Int): Long =
        u16(bytes, offset).toLong() or (u16(bytes, offset + 2).toLong() shl 16)

    private fun putU16(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = value.toByte()
        bytes[offset + 1] = (value ushr 8).toByte()
    }

    private fun putU32(bytes: ByteArray, offset: Int, value: Long) {
        putU16(bytes, offset, value.toInt())
        putU16(bytes, offset + 2, (value ushr 16).toInt())
    }
}
