package org.opentrafficmap.citstogo.bridge

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.opentrafficmap.citstogo.protocol.CitsPacket

class PcapReplayTest {
    @Test
    fun readsLittleEndianMicrosecondPcap() {
        val reader = PcapReader(ByteArrayInputStream(pcap(littleEndian = true, nanoseconds = false)))

        val first = requireNotNull(reader.nextFrame())
        val second = requireNotNull(reader.nextFrame())

        assertEquals(10_250_000L, first.timestampUs)
        assertEquals(3, first.originalLength)
        assertArrayEquals(byteArrayOf(1, 2, 3), first.payload)
        // A plain IEEE 802.11 capture never recorded radio conditions.
        assertNull(first.rssiDbm)
        assertNull(first.frequencyMhz)
        assertEquals(20_750_000L, second.timestampUs)
        assertNull(reader.nextFrame())
    }

    @Test
    fun roundTripsSignalStrengthAndChannelThroughTheRadioHeader() {
        val output = ByteArrayOutputStream()
        PcapWriter(output).use { writer ->
            writer.writePacket(received(byteArrayOf(0x08, 0x02, 0x00), rssiDbm = -72, frequencyMhz = 5_860))
            writer.writePacket(received(byteArrayOf(0x08, 0x02, 0x01), rssiDbm = -41, frequencyMhz = 5_875))
        }

        val reader = PcapReader(ByteArrayInputStream(output.toByteArray()))

        val first = requireNotNull(reader.nextFrame())
        assertEquals(-72, first.rssiDbm)
        assertEquals(5_860, first.frequencyMhz)
        assertArrayEquals(byteArrayOf(0x08, 0x02, 0x00), first.payload)
        assertEquals(3, first.originalLength)

        val second = requireNotNull(reader.nextFrame())
        assertEquals(-41, second.rssiDbm)
        assertEquals(5_875, second.frequencyMhz)
        assertArrayEquals(byteArrayOf(0x08, 0x02, 0x01), second.payload)
        assertNull(reader.nextFrame())
    }

    @Test
    fun readsAMonitorModeHeaderWithFieldsThisAppNeverWrites() {
        // An interface that recorded timestamp, flags, rate, channel, signal, noise and antenna:
        // seven fields, each aligned to its own width, so only walking the bitmap finds the signal.
        val header = byteArrayOf(0, 0, 33, 0) +
            intLE(0x8000_086F.toInt()) + // the seven fields above, plus one extension word
            intLE(0) +
            ByteArray(4) + // padding the eight byte timestamp demands
            ByteArray(8) + byteArrayOf(0x10, 0x0c) + // flags, rate
            intLE(5_954 or (0x0140 shl 16)) + // frequency and channel flags
            byteArrayOf(-88, -95, 1) // signal, noise, antenna
        val frame = byteArrayOf(0x08, 0x02, 0x00, 0x00, 0x11)

        val reader = PcapReader(ByteArrayInputStream(radioFile(header + frame)))

        val record = requireNotNull(reader.nextFrame())
        assertEquals(-88, record.rssiDbm)
        assertEquals(5_954, record.frequencyMhz)
        assertArrayEquals(frame, record.payload)
        assertEquals(frame.size, record.originalLength)
    }

    @Test
    fun aFieldNewerThanThisReaderEndsTheWalkWithoutHidingTheFrame() {
        // Channel and signal, then a field numbered beyond anything this reader knows about.
        val header = byteArrayOf(0, 0, 17, 0) +
            intLE((1 shl 3) or (1 shl 5) or (1 shl 20)) +
            intLE(5_936 or (0x0140 shl 16)) + byteArrayOf(-60) + ByteArray(4)
        val frame = byteArrayOf(0x08, 0x02, 0x00, 0x00)

        val reader = PcapReader(ByteArrayInputStream(radioFile(header + frame)))

        val record = requireNotNull(reader.nextFrame())
        assertEquals(-60, record.rssiDbm)
        assertEquals(5_936, record.frequencyMhz)
        assertArrayEquals(frame, record.payload)
    }

    @Test
    fun rejectsAFileWhoseLinkTypeIsNotIeee80211() {
        val ethernet = radioFile(byteArrayOf(0, 0, 8, 0, 0, 0, 0, 0)).also { file ->
            file[20] = 1
            file[21] = 0
            file[22] = 0
            file[23] = 0
        }

        val failure = runCatching { PcapReader(ByteArrayInputStream(ethernet)) }.exceptionOrNull()

        assertTrue("expected the link type to be refused", failure is IllegalArgumentException)
        assertTrue(failure!!.message!!.contains("link type"))
    }

    @Test
    fun rejectsARadioHeaderThatIsNotRadiotap() {
        val header = byteArrayOf(1, 0, 13, 0, 0, 0, 0, 0) + ByteArray(5)

        val failure = runCatching {
            PcapReader(ByteArrayInputStream(radioFile(header + byteArrayOf(8, 2)))).nextFrame()
        }.exceptionOrNull()

        assertTrue("expected the radiotap version to be refused", failure is IllegalArgumentException)
        assertTrue(failure!!.message!!.contains("radiotap version"))
    }

    @Test
    fun readsBigEndianNanosecondPcap() {
        val reader = PcapReader(ByteArrayInputStream(pcap(littleEndian = false, nanoseconds = true)))

        assertEquals(10_250_000L, requireNotNull(reader.nextFrame()).timestampUs)
        assertEquals(20_750_000L, requireNotNull(reader.nextFrame()).timestampUs)
    }

    @Test
    fun replaysUsingCaptureIntervalsWithoutAccumulatingProcessingTime() {
        var nowUs = 5_000_000L
        val deliveredAt = mutableListOf<Long>()
        val reader = PcapReader(ByteArrayInputStream(pcap(littleEndian = true, nanoseconds = false)))
        val replay = PcapReplay(
            elapsedRealtimeUs = { nowUs },
            sleepMs = { nowUs += it * 1_000L },
        )

        val count = replay.run(reader, active = { true }) {
            deliveredAt += nowUs
            nowUs += 2_000_000L
        }

        assertEquals(2L, count)
        assertEquals(listOf(5_000_000L, 15_500_000L), deliveredAt)
    }

    private fun received(payload: ByteArray, rssiDbm: Int, frequencyMhz: Int) = CitsPacket(
        sequence = 1,
        timestampUs = 1_000_000L,
        frequencyMhz = frequencyMhz,
        rssiDbm = rssiDbm,
        wifiType = 0,
        rxState = 0,
        flags = 0,
        originalLength = payload.size,
        capturedLength = payload.size,
        payload = payload,
    )

    /** One little-endian microsecond PCAP of link type 127 holding [record] as its only packet. */
    private fun radioFile(record: ByteArray): ByteArray {
        val output = ByteArrayOutputStream()
        output.write(byteArrayOf(0xd4.toByte(), 0xc3.toByte(), 0xb2.toByte(), 0xa1.toByte()))
        write16(output, 2, littleEndian = true)
        write16(output, 4, littleEndian = true)
        repeat(2) { write32(output, 0, littleEndian = true) }
        write32(output, 65_535, littleEndian = true)
        write32(output, Radiotap.LINK_TYPE, littleEndian = true)
        write32(output, 10, littleEndian = true)
        write32(output, 250_000, littleEndian = true)
        write32(output, record.size, littleEndian = true)
        write32(output, record.size, littleEndian = true)
        output.write(record)
        return output.toByteArray()
    }

    private fun intLE(value: Int) = byteArrayOf(
        (value and 0xff).toByte(),
        ((value ushr 8) and 0xff).toByte(),
        ((value ushr 16) and 0xff).toByte(),
        ((value ushr 24) and 0xff).toByte(),
    )

    private fun pcap(littleEndian: Boolean, nanoseconds: Boolean): ByteArray {
        val output = ByteArrayOutputStream()
        val magic = when {
            littleEndian && !nanoseconds -> byteArrayOf(0xd4.toByte(), 0xc3.toByte(), 0xb2.toByte(), 0xa1.toByte())
            littleEndian -> byteArrayOf(0x4d, 0x3c, 0xb2.toByte(), 0xa1.toByte())
            !nanoseconds -> byteArrayOf(0xa1.toByte(), 0xb2.toByte(), 0xc3.toByte(), 0xd4.toByte())
            else -> byteArrayOf(0xa1.toByte(), 0xb2.toByte(), 0x3c, 0x4d)
        }
        output.write(magic)
        write16(output, 2, littleEndian)
        write16(output, 4, littleEndian)
        repeat(2) { write32(output, 0, littleEndian) }
        write32(output, 65_535, littleEndian)
        write32(output, 105, littleEndian)
        writePacket(output, 10, if (nanoseconds) 250_000_000 else 250_000, byteArrayOf(1, 2, 3), littleEndian)
        writePacket(output, 20, if (nanoseconds) 750_000_000 else 750_000, byteArrayOf(4), littleEndian)
        return output.toByteArray()
    }

    private fun writePacket(output: ByteArrayOutputStream, seconds: Int, fraction: Int, payload: ByteArray, littleEndian: Boolean) {
        write32(output, seconds, littleEndian)
        write32(output, fraction, littleEndian)
        write32(output, payload.size, littleEndian)
        write32(output, payload.size, littleEndian)
        output.write(payload)
    }

    private fun write16(output: ByteArrayOutputStream, value: Int, littleEndian: Boolean) {
        val shifts = if (littleEndian) listOf(0, 8) else listOf(8, 0)
        shifts.forEach { output.write(value ushr it and 0xff) }
    }

    private fun write32(output: ByteArrayOutputStream, value: Int, littleEndian: Boolean) {
        val shifts = if (littleEndian) listOf(0, 8, 16, 24) else listOf(24, 16, 8, 0)
        shifts.forEach { output.write(value ushr it and 0xff) }
    }
}
