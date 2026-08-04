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
        position = SremPosition(482_024_036, 163_691_773),
        nowUnixMs = 1_785_242_510_349L,
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
        assertEquals(0, (frame[68].toInt() and 0xff) shl 8 or (frame[69].toInt() and 0xff))
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
        assertEquals(50_349, reader.constrained(0, 65_535))

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
        assertFalse(reader.bit()) // heading
        assertFalse(reader.bit()) // speed
        assertFalse(reader.bit()) // Position3D extension marker
        assertFalse(reader.bit()) // elevation
        assertFalse(reader.bit()) // regional
        assertEquals(request.position.latitude.toLong(), reader.constrained(-900_000_000, 900_000_001))
        assertEquals(request.position.longitude.toLong(), reader.constrained(-1_800_000_000, 1_800_000_001))

        assertTrue(reader.remainingBits < 8)
        assertEquals(0, reader.bits(reader.remainingBits))
    }

    private fun assertLaneAccessPoint(reader: UperBitReader, laneId: Int) {
        assertFalse(reader.bit()) // IntersectionAccessPoint extension marker
        assertEquals(0, reader.constrained(0, 2)) // lane choice
        assertEquals(laneId.toLong(), reader.constrained(0, 255))
    }
}
