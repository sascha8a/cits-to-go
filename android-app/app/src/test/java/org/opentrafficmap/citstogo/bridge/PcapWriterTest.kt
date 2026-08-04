package org.opentrafficmap.citstogo.bridge

import java.io.ByteArrayOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import org.opentrafficmap.citstogo.protocol.CitsPacket

class PcapWriterTest {
    @Test
    fun rawPacketIsWrittenAsIeee80211Record() {
        val output = ByteArrayOutputStream()
        val payload = byteArrayOf(0x88.toByte(), 0, 1, 2, 3)

        PcapWriter(output).use {
            it.writeRawPacket(payload, 1_234_567)
            it.flush()
        }

        val bytes = output.toByteArray()
        assertEquals(24 + 16 + payload.size, bytes.size)
        assertEquals(0xA1B2C3D4.toInt(), intLE(bytes, 0))
        assertEquals(105, intLE(bytes, 20))
        assertEquals(1, intLE(bytes, 24))
        assertEquals(234_567, intLE(bytes, 28))
        assertEquals(payload.size, intLE(bytes, 32))
        assertEquals(payload.size, intLE(bytes, 36))
        assertArrayEquals(payload, bytes.copyOfRange(40, 40 + payload.size))
    }

    @Test
    fun captureClockIsMappedToUnixTimeAndPreservesIntervals() {
        val output = ByteArrayOutputStream()
        val unixNowUs = 1_785_788_000_000_000L

        PcapWriter(output) { unixNowUs }.use {
            it.writePacket(packet(timestampUs = 13_000_000L, payload = byteArrayOf(1)))
            it.writePacket(packet(timestampUs = 13_125_000L, payload = byteArrayOf(2)))
        }

        val bytes = output.toByteArray()
        assertEquals(unixNowUs, recordTimestampUs(bytes, 24))
        assertEquals(unixNowUs + 125_000L, recordTimestampUs(bytes, 41))
    }

    @Test
    fun captureClockWrapIsUnwrapped() {
        val output = ByteArrayOutputStream()
        val unixNowUs = 1_785_788_000_000_000L

        PcapWriter(output) { unixNowUs }.use {
            it.writePacket(packet(timestampUs = 0xffff_ff00L, payload = byteArrayOf(1)))
            it.writePacket(packet(timestampUs = 0x0000_0100L, payload = byteArrayOf(2)))
        }

        val bytes = output.toByteArray()
        assertEquals(unixNowUs, recordTimestampUs(bytes, 24))
        assertEquals(unixNowUs + 512L, recordTimestampUs(bytes, 41))
    }

    private fun packet(timestampUs: Long, payload: ByteArray) = CitsPacket(
        sequence = 1,
        timestampUs = timestampUs,
        frequencyMhz = 5_900,
        rssiDbm = -50,
        wifiType = 0,
        rxState = 0,
        flags = 0,
        originalLength = payload.size,
        capturedLength = payload.size,
        payload = payload,
    )

    private fun recordTimestampUs(bytes: ByteArray, offset: Int): Long =
        intLE(bytes, offset).toLong() * 1_000_000L + intLE(bytes, offset + 4)

    private fun intLE(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or
            ((bytes[offset + 1].toInt() and 0xff) shl 8) or
            ((bytes[offset + 2].toInt() and 0xff) shl 16) or
            ((bytes[offset + 3].toInt() and 0xff) shl 24)
}
