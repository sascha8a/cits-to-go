package org.opentrafficmap.citstogo.bridge

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PcapReplayTest {
    @Test
    fun readsLittleEndianMicrosecondPcap() {
        val reader = PcapReader(ByteArrayInputStream(pcap(littleEndian = true, nanoseconds = false)))

        val first = requireNotNull(reader.nextFrame())
        val second = requireNotNull(reader.nextFrame())

        assertEquals(10_250_000L, first.timestampUs)
        assertEquals(3, first.originalLength)
        assertArrayEquals(byteArrayOf(1, 2, 3), first.payload)
        assertEquals(20_750_000L, second.timestampUs)
        assertNull(reader.nextFrame())
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
