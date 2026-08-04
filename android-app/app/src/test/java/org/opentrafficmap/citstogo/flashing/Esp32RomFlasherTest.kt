package org.opentrafficmap.citstogo.flashing

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException

class Esp32RomFlasherTest {
    @Test
    fun slipEncodingEscapesFrameAndEscapeBytes() {
        assertArrayEquals(
            byteArrayOf(0xc0.toByte(), 1, 0xdb.toByte(), 0xdc.toByte(), 2, 0xdb.toByte(), 0xdd.toByte(), 0xc0.toByte()),
            Esp32RomFlasher.slipEncode(byteArrayOf(1, 0xc0.toByte(), 2, 0xdb.toByte())),
        )
    }

    @Test
    fun checksumUsesEspressifSeedAndUnsignedBytes() {
        assertEquals(0xef xor 0x00 xor 0x80 xor 0xff, Esp32RomFlasher.checksum(byteArrayOf(0, 0x80.toByte(), 0xff.toByte())))
    }

    @Test
    fun rejectsAnImageWithoutAnEsp32C5BootloaderBeforeUsbAccess() {
        val transport = object : EspFlashTransport {
            override fun write(data: ByteArray) = error("USB must not be accessed")
            override fun read(buffer: ByteArray, timeoutMs: Int): Int = error("USB must not be accessed")
            override fun setControlLines(dtr: Boolean, rts: Boolean) = error("USB must not be accessed")
        }

        assertThrows(IOException::class.java) {
            Esp32RomFlasher(transport).flash(ByteArray(1024)) {}
        }
    }
}
