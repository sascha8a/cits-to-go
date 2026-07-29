package org.opentrafficmap.citstogo.cam

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class CamEncodingTest {
    private val identity = CamIdentity(
        stationId = 0x0102_0304,
        macAddress = byteArrayOf(0x02, 1, 2, 3, 4, 5),
    )
    private val position = CamPosition.unavailable()
    private val timestamp = CamUperEncoder.ITS_EPOCH_UNIX_MS + 123_456L

    @Test
    fun vehicleCamHasExpectedHeaderAndReleaseOneBitLength() {
        val cam = CamUperEncoder.encode(identity, StationType.PASSENGER_CAR, position, timestamp)
        assertEquals(41, cam.size) // 322 meaningful UPER bits, padded to 41 octets.
        assertArrayEquals(
            byteArrayOf(2, 2, 1, 2, 3, 4),
            cam.copyOfRange(0, 6),
        )
        val expectedGdt = CamUperEncoder.timestampIts(timestamp).mod(65_536L).toInt()
        assertEquals(expectedGdt ushr 8, cam[6].toInt() and 0xff)
        assertEquals(expectedGdt and 0xff, cam[7].toInt() and 0xff)
    }

    @Test
    fun completeFrameContainsQosSnapGeoShbBtpAndCam() {
        val frame = ItsG5FrameBuilder.camFrame(
            identity, StationType.PASSENGER_CAR, position, timestamp)
        assertEquals(119, frame.size)
        assertEquals(0x88, frame[0].toInt() and 0xff)
        assertArrayEquals(ByteArray(6) { 0xff.toByte() }, frame.copyOfRange(4, 10))
        assertArrayEquals(identity.macAddress, frame.copyOfRange(10, 16))
        assertEquals(0x89, frame[32].toInt() and 0xff)
        assertEquals(0x47, frame[33].toInt() and 0xff)
        assertEquals(0x11, frame[34].toInt() and 0xff)
        assertEquals(0x20, frame[38].toInt() and 0xff)
        assertEquals(0x50, frame[39].toInt() and 0xff)
        assertEquals(0x02, frame[40].toInt() and 0xff)
        assertEquals(45, ((frame[42].toInt() and 0xff) shl 8) or (frame[43].toInt() and 0xff))
        assertEquals(0x07, frame[74].toInt() and 0xff)
        assertEquals(0xd1, frame[75].toInt() and 0xff)
        assertArrayEquals(byteArrayOf(2, 2, 1, 2, 3, 4), frame.copyOfRange(78, 84))
    }

    @Test
    fun rsuUsesCompactRsuHighFrequencyContainer() {
        val cam = CamUperEncoder.encode(identity, StationType.ROAD_SIDE_UNIT, position, timestamp)
        assertEquals(26, cam.size)
        val frame = ItsG5FrameBuilder.camFrame(identity, StationType.ROAD_SIDE_UNIT, position, timestamp)
        assertEquals(104, frame.size)
        assertEquals(0, frame[41].toInt()) // Stationary flag in GeoNetworking Common header.
    }
}
