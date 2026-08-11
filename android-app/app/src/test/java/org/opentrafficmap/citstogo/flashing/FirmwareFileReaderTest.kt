package org.opentrafficmap.citstogo.flashing

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException

class FirmwareFileReaderTest {
    @Test
    fun readsCompleteFirmwareAndReportsBytes() {
        val firmware = ByteArray(4097) { it.toByte() }
        var bytesRead = 0L

        val result = FirmwareFileReader.read(ByteArrayInputStream(firmware)) { bytesRead = it }

        assertArrayEquals(firmware, result)
        assertEquals(firmware.size.toLong(), bytesRead)
    }

    @Test
    fun rejectsEmptyFirmware() {
        assertThrows(IOException::class.java) {
            FirmwareFileReader.read(ByteArrayInputStream(byteArrayOf()))
        }
    }

    @Test
    fun rejectsFirmwareLargerThanLimit() {
        assertThrows(IOException::class.java) {
            FirmwareFileReader.read(ByteArrayInputStream(ByteArray(9)), maximumBytes = 8)
        }
    }
}
