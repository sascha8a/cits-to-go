package org.opentrafficmap.citstogo.cam

import java.security.SecureRandom
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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
        val counters = ItsG5FrameBuilder.SequenceCounters(initialWlanSequence = 0x025, initialGbcSequenceId = 0)
        val frame = ItsG5FrameBuilder.camFrame(
            identity, StationType.PASSENGER_CAR, position, timestamp, counters)
        assertEquals(119, frame.size)
        assertEquals(0x88, frame[0].toInt() and 0xff)
        assertArrayEquals(ByteArray(6) { 0xff.toByte() }, frame.copyOfRange(4, 10))
        assertArrayEquals(identity.macAddress, frame.copyOfRange(10, 16))
        val sequenceControl = 0x025 shl 4
        assertEquals(sequenceControl and 0xff, frame[22].toInt() and 0xff)
        assertEquals((sequenceControl ushr 8) and 0xff, frame[23].toInt() and 0xff)
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
        val frame = ItsG5FrameBuilder.camFrame(
            identity,
            StationType.ROAD_SIDE_UNIT,
            position,
            timestamp,
            ItsG5FrameBuilder.SequenceCounters(initialWlanSequence = 0, initialGbcSequenceId = 0),
        )
        assertEquals(104, frame.size)
        assertEquals(0, frame[41].toInt()) // Stationary flag in GeoNetworking Common header.
    }

    @Test
    fun camFramesAdvanceTheWlanSequenceCounterAndWrapAtTwelveBits() {
        val counters = ItsG5FrameBuilder.SequenceCounters(initialWlanSequence = 0x0ffe, initialGbcSequenceId = 0)
        val sequences = List(3) {
            val frame = ItsG5FrameBuilder.camFrame(
                identity, StationType.PASSENGER_CAR, position, timestamp, counters)
            val sequenceControl = (frame[22].toInt() and 0xff) or ((frame[23].toInt() and 0xff) shl 8)
            sequenceControl ushr 4
        }
        assertEquals(listOf(0x0ffe, 0x0fff, 0x0000), sequences)
    }

    @Test
    fun randomizedPrivacyIdentitiesAreLocalUnicastAndDistinct() {
        val random = SecureRandom()
        val first = randomizedCamIdentity(random)
        val second = randomizedCamIdentity(random)
        assertEquals(6, first.macAddress.size)
        // Bit 0 clear (unicast) and bit 1 set (locally administered), matching stored identities.
        assertEquals(0x02, first.macAddress[0].toInt() and 0x03)
        assertNotEquals(second.macAddress.toList(), first.macAddress.toList())
        assertNotEquals(second.stationId, first.stationId)
    }
}
