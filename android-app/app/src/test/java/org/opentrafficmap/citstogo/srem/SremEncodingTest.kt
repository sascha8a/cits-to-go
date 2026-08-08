package org.opentrafficmap.citstogo.srem

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.opentrafficmap.citstogo.cam.CamIdentity
import org.opentrafficmap.citstogo.cam.ItsG5FrameBuilder
import org.opentrafficmap.citstogo.intersection.ItsFrameExtractor
import org.opentrafficmap.citstogo.intersection.UperBitReader

class SremEncodingTest {
    private val identity = CamIdentity(
        stationId = 0x0102_0304,
        macAddress = byteArrayOf(0x02, 1, 2, 3, 4, 5),
    )
    private val request = SremRequest(
        region = 43,
        intersectionId = 1039,
        requestId = 5,
        sequenceNumber = 27,
        inboundLaneId = 31,
        outboundLaneId = 16,
        position = SremPosition(482_024_036, 163_691_773, 1_357, true),
        nowUnixMs = 1_785_242_510_349L,
        profile = SremProfile.PEDESTRIAN,
        packageRequestUnixMs = 1_785_242_511_987L,
    )

    @Test
    fun sremFrameExtractsAsMessageNineOnSremPort() {
        val frame = ItsG5FrameBuilder.sremFrame(identity, request)
        val packet = ItsFrameExtractor.extract(frame)

        requireNotNull(packet)
        assertEquals(2007, packet.destinationPort)
        assertEquals(2, packet.protocolVersion)
        assertEquals(9, packet.messageId)
        assertEquals(identity.stationId, packet.stationId)
        assertArrayEquals(byteArrayOf(2, 9, 1, 2, 3, 4), packet.payload.copyOfRange(0, 6))
        val snapOffset = frame.indexOfSequence(byteArrayOf(0x89.toByte(), 0x47)) + 2
        assertEquals(0x1a, frame[snapOffset + 2].toInt() and 0xff)
        assertEquals(4, frame[snapOffset + 3].toInt() and 0xff)
        assertEquals(0x40, frame[snapOffset + 5].toInt() and 0xf0)
        assertEquals(request.profile.stationType.code, (frame[snapOffset + 16].toInt() ushr 2) and 0x1f)
        assertEquals(request.position.heading, u16(frame, snapOffset + 38))
        assertEquals(request.position.latitude, i32(frame, snapOffset + 40))
        assertEquals(request.position.longitude, i32(frame, snapOffset + 44))
        assertEquals(1_000, u16(frame, snapOffset + 48))
        assertEquals(2_007, u16(frame, snapOffset + 56))
        assertEquals(0x21, frame[snapOffset - 10].toInt() and 0xff)
    }

    @Test
    fun sremPayloadStartsWithItsPduHeader() {
        val payload = SremUperEncoder.encode(
            SremIdentity(identity.stationId, identity.macAddress),
            request,
        )

        assertArrayEquals(byteArrayOf(2, 9, 1, 2, 3, 4), payload.copyOfRange(0, 6))
    }

    @Test
    fun sremPayloadFollowsSignalRequestMessageUperLayout() {
        val reader = UperBitReader(
            SremUperEncoder.encode(SremIdentity(identity.stationId, identity.macAddress), request),
        )

        assertEquals(2, reader.constrained(0, 255))
        assertEquals(9, reader.constrained(0, 255))
        assertEquals(identity.stationId, reader.constrained(0, 0xffff_ffffL))

        assertFalse(reader.bit()) // SignalRequestMessage extension marker
        assertTrue(reader.bit()) // timeStamp
        assertTrue(reader.bit()) // sequenceNumber
        assertTrue(reader.bit()) // requests
        assertFalse(reader.bit()) // regional
        assertEquals(300_281, reader.constrained(0, 527_040))
        assertEquals(50_349, reader.constrained(0, 65_535))
        assertEquals(request.sequenceNumber.toLong(), reader.constrained(0, 127))
        assertEquals(1, reader.sequenceLength(1, 32))

        assertFalse(reader.bit()) // SignalRequestPackage extension marker
        assertTrue(reader.bit()) // minute
        assertTrue(reader.bit()) // second
        assertFalse(reader.bit()) // duration
        assertFalse(reader.bit()) // regional

        assertFalse(reader.bit()) // SignalRequest extension marker
        assertTrue(reader.bit()) // outBoundLane
        assertFalse(reader.bit()) // regional
        assertTrue(reader.bit()) // IntersectionReferenceID.region
        assertEquals(request.region!!.toLong(), reader.constrained(0, 65_535))
        assertEquals(request.intersectionId.toLong(), reader.constrained(0, 65_535))
        assertEquals(request.requestId.toLong(), reader.constrained(0, 255))
        assertFalse(reader.bit()) // PriorityRequestType extension marker
        assertEquals(1, reader.constrained(0, 3))
        assertLaneAccessPoint(reader, request.inboundLaneId)
        assertLaneAccessPoint(reader, request.outboundLaneId)
        assertEquals(300_281, reader.constrained(0, 527_040))
        assertEquals(51_987, reader.constrained(0, 65_535))

        assertFalse(reader.bit()) // RequestorDescription extension marker
        assertTrue(reader.bit()) // type
        assertTrue(reader.bit()) // position
        repeat(6) { assertFalse(reader.bit()) }
        assertTrue(reader.bit()) // VehicleID.stationID choice
        assertEquals(identity.stationId, reader.constrained(0, 0xffff_ffffL))

        assertFalse(reader.bit()) // RequestorType extension marker
        repeat(5) { assertFalse(reader.bit()) }
        assertFalse(reader.bit()) // BasicVehicleRole extension marker
        assertEquals(20, reader.constrained(0, 22))

        assertFalse(reader.bit()) // RequestorPositionVector extension marker
        assertTrue(reader.bit()) // heading
        assertFalse(reader.bit()) // speed
        assertFalse(reader.bit()) // Position3D extension marker
        assertFalse(reader.bit()) // elevation
        assertFalse(reader.bit()) // regional
        assertEquals(request.position.latitude.toLong(), reader.constrained(-900_000_000, 900_000_001))
        assertEquals(request.position.longitude.toLong(), reader.constrained(-1_800_000_000, 1_800_000_001))
        assertEquals(request.position.heading.toLong(), reader.constrained(0, 28_800))

        assertTrue(reader.remainingBits < 8)
        assertEquals(0, reader.bits(reader.remainingBits))
    }

    private fun assertLaneAccessPoint(reader: UperBitReader, laneId: Int) {
        assertFalse(reader.bit()) // IntersectionAccessPoint extension marker
        assertEquals(0, reader.constrained(0, 2)) // lane choice
        assertEquals(laneId.toLong(), reader.constrained(0, 255))
    }

    @Test
    fun profilesMapToExpectedStationTypesAndBasicVehicleRoles() {
        assertEquals(
            listOf(20, 19, 10, 10, 0, 1, 9, 9, 2),
            SremProfile.entries.dropLast(1).map { it.basicVehicleRole.value },
        )
        assertTrue(SremProfile.TRAM.basicVehicleRole.isExtension)
        assertEquals(0, SremProfile.TRAM.basicVehicleRole.value)
        assertEquals(11, SremProfile.TRAM.stationType.code)
        assertTrue(SremProfile.PUBLIC_TRANSPORT_BUS.hasTransitStatus)
        assertTrue(SremProfile.TRAM.hasTransitStatus)
    }

    private fun ByteArray.indexOfSequence(sequence: ByteArray): Int =
        indices.first { offset ->
            offset + sequence.size <= size && sequence.indices.all { this[offset + it] == sequence[it] }
        }

    private fun u16(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xff) shl 8) or (bytes[offset + 1].toInt() and 0xff)

    private fun i32(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xff) shl 24) or
            ((bytes[offset + 1].toInt() and 0xff) shl 16) or
            ((bytes[offset + 2].toInt() and 0xff) shl 8) or
            (bytes[offset + 3].toInt() and 0xff)
}
