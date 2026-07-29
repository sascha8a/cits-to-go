package org.opentrafficmap.citstogo.bridge

import java.io.ByteArrayOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

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

    private fun intLE(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or
            ((bytes[offset + 1].toInt() and 0xff) shl 8) or
            ((bytes[offset + 2].toInt() and 0xff) shl 16) or
            ((bytes[offset + 3].toInt() and 0xff) shl 24)
}
