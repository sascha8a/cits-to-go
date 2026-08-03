package org.opentrafficmap.citstogo.srem

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import org.opentrafficmap.citstogo.cam.CamIdentity
import org.opentrafficmap.citstogo.cam.ItsG5FrameBuilder
import org.opentrafficmap.citstogo.intersection.ItsFrameExtractor

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
    }

    @Test
    fun sremPayloadStartsWithItsPduHeader() {
        val payload = SremUperEncoder.encode(
            SremIdentity(identity.stationId, identity.macAddress),
            request,
        )

        assertArrayEquals(byteArrayOf(2, 9, 1, 2, 3, 4), payload.copyOfRange(0, 6))
    }
}
