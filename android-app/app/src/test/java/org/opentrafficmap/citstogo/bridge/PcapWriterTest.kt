package org.opentrafficmap.citstogo.bridge

import java.io.ByteArrayOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import org.opentrafficmap.citstogo.protocol.CitsPacket

class PcapWriterTest {
    @Test
    fun rawPacketIsWrittenAsARadioLinkRecordWithoutRadioInformation() {
        val output = ByteArrayOutputStream()
        val payload = byteArrayOf(0x88.toByte(), 0, 1, 2, 3)

        PcapWriter(output).use {
            it.writeRawPacket(payload, 1_234_567)
            it.flush()
        }

        val bytes = output.toByteArray()
        assertEquals(24 + 16 + EMPTY_HEADER_BYTES + payload.size, bytes.size)
        assertEquals(0xA1B2C3D4.toInt(), intLE(bytes, 0))
        assertEquals(Radiotap.LINK_TYPE, intLE(bytes, 20))
        assertEquals(1, intLE(bytes, 24))
        assertEquals(234_567, intLE(bytes, 28))
        assertEquals(EMPTY_HEADER_BYTES + payload.size, intLE(bytes, 32))
        assertEquals(EMPTY_HEADER_BYTES + payload.size, intLE(bytes, 36))
        // An empty header still says where the frame begins, which is all a reader needs.
        assertEquals(0, bytes[0 + 24 + 16].toInt())
        assertEquals(EMPTY_HEADER_BYTES, u16LE(bytes, 24 + 16 + 2))
        assertEquals(0, intLE(bytes, 24 + 16 + 4))
        assertArrayEquals(payload, bytes.copyOfRange(40 + EMPTY_HEADER_BYTES, bytes.size))
    }

    @Test
    fun receivedPacketKeepsItsSignalStrengthAndChannel() {
        val output = ByteArrayOutputStream()
        val payload = byteArrayOf(0x08, 0x02, 0x00, 0x00)

        PcapWriter(output).use {
            it.writePacket(packet(payload, rssiDbm = -67, frequencyMhz = 5_890))
        }

        val bytes = output.toByteArray()
        assertEquals(Radiotap.LINK_TYPE, intLE(bytes, 20))
        assertEquals(RADIO_HEADER_BYTES + payload.size, intLE(bytes, 32))
        assertEquals(RADIO_HEADER_BYTES + payload.size, intLE(bytes, 36))
        assertEquals(RADIO_HEADER_BYTES, u16LE(bytes, 42))
        assertEquals(
            "channel and signal strength are the only fields present",
            (1 shl 3) or (1 shl 5),
            intLE(bytes, 44),
        )
        assertEquals(5_890, u16LE(bytes, 48))
        assertEquals("5 GHz OFDM channel flags", 0x0140, u16LE(bytes, 50))
        assertEquals(-67, bytes[52].toInt().toByte().toInt())
        assertArrayEquals(payload, bytes.copyOfRange(40 + RADIO_HEADER_BYTES, bytes.size))
    }

    @Test
    fun valuesTheFirmwareDidNotMeasureAreLeftOutOfTheHeader() {
        val output = ByteArrayOutputStream()

        PcapWriter(output).use {
            it.writePacket(packet(byteArrayOf(0x08, 0x02), rssiDbm = 0, frequencyMhz = 0))
        }

        val bytes = output.toByteArray()
        assertEquals(
            "nothing measured means no field at all, not a field claiming zero",
            EMPTY_HEADER_BYTES,
            u16LE(bytes, 42),
        )
        assertEquals(0, intLE(bytes, 44))
    }

    @Test
    fun aFrameTrimmedByTheReceiverKeepsTheLengthItHadOnTheAir() {
        val output = ByteArrayOutputStream()
        val payload = ByteArray(5)

        PcapWriter(output).use {
            it.writePacket(packet(payload, rssiDbm = -70, frequencyMhz = 5_860, originalLength = 9))
        }

        val bytes = output.toByteArray()
        assertEquals(RADIO_HEADER_BYTES + payload.size, intLE(bytes, 32))
        assertEquals(RADIO_HEADER_BYTES + 9, intLE(bytes, 36))
    }

    @Test
    fun captureClockIsMappedToUnixTimeAndPreservesIntervals() {
        val output = ByteArrayOutputStream()
        val unixNowUs = 1_785_788_000_000_000L

        PcapWriter(output) { unixNowUs }.use {
            it.writePacket(packet(byteArrayOf(1), timestampUs = 13_000_000L))
            it.writePacket(packet(byteArrayOf(2), timestampUs = 13_125_000L))
        }

        val bytes = output.toByteArray()
        val secondRecord = 24 + RECORD_HEADER_BYTES + RADIO_HEADER_BYTES + 1
        assertEquals(unixNowUs, recordTimestampUs(bytes, 24))
        assertEquals(unixNowUs + 125_000L, recordTimestampUs(bytes, secondRecord))
    }

    @Test
    fun captureClockWrapIsUnwrapped() {
        val output = ByteArrayOutputStream()
        val unixNowUs = 1_785_788_000_000_000L

        PcapWriter(output) { unixNowUs }.use {
            it.writePacket(packet(byteArrayOf(1), timestampUs = 0xffff_ff00L))
            it.writePacket(packet(byteArrayOf(2), timestampUs = 0x0000_0100L))
        }

        val bytes = output.toByteArray()
        val secondRecord = 24 + RECORD_HEADER_BYTES + RADIO_HEADER_BYTES + 1
        assertEquals(unixNowUs, recordTimestampUs(bytes, 24))
        assertEquals(unixNowUs + 512L, recordTimestampUs(bytes, secondRecord))
    }

    private fun packet(
        payload: ByteArray,
        timestampUs: Long = 1_000L,
        rssiDbm: Int = -50,
        frequencyMhz: Int = 5_900,
        originalLength: Int = payload.size,
    ) = CitsPacket(
        sequence = 1,
        timestampUs = timestampUs,
        frequencyMhz = frequencyMhz,
        rssiDbm = rssiDbm,
        wifiType = 0,
        rxState = 0,
        flags = 0,
        originalLength = originalLength,
        capturedLength = payload.size,
        payload = payload,
    )

    private fun recordTimestampUs(bytes: ByteArray, offset: Int): Long =
        intLE(bytes, offset).toLong() * 1_000_000L + intLE(bytes, offset + 4)

    private fun u16LE(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8)

    private fun intLE(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or
            ((bytes[offset + 1].toInt() and 0xff) shl 8) or
            ((bytes[offset + 2].toInt() and 0xff) shl 16) or
            ((bytes[offset + 3].toInt() and 0xff) shl 24)

    private companion object {
        /** PCAP record header, and the radiotap header with and without the two fields we write. */
        const val RECORD_HEADER_BYTES = 16
        const val EMPTY_HEADER_BYTES = 8
        const val RADIO_HEADER_BYTES = 13
    }
}
