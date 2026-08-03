package org.opentrafficmap.citstogo.intersection

import org.junit.Assert.assertEquals
import org.junit.Test
import org.opentrafficmap.citstogo.cam.UperBitWriter

class SsemDecoderTest {
    @Test
    fun decodesMinimalGrantedSignalStatus() {
        val packet = ItsPacket(
            destinationPort = SsemDecoder.BTP_PORT_SSEM,
            protocolVersion = 2,
            messageId = SsemDecoder.MESSAGE_ID_SSEM,
            stationId = 0x0000_002a,
            bodyOffset = 6,
            payload = byteArrayOf(2, 10, 0, 0, 0, 42) + minimalSsemPayload(),
            sourceLatitude = null,
            sourceLongitude = null,
        )

        val statuses = SsemDecoder.decode(packet, receivedAtMs = 1_000L)

        assertEquals(1, statuses.size)
        val status = statuses.first()
        assertEquals(IntersectionKey(43, 1039), status.intersectionKey)
        assertEquals(3, status.intersectionSequenceNumber)
        assertEquals(9, status.messageSequenceNumber)
        assertEquals(0x0102_0304L, status.requesterStationId)
        assertEquals(5, status.requestId)
        assertEquals(27, status.requestSequenceNumber)
        assertEquals(31, status.inboundLaneId)
        assertEquals(16, status.outboundLaneId)
        assertEquals(12_346, status.second)
        assertEquals(SsemResponseStatus.Granted, status.responseStatus)
    }

    private fun minimalSsemPayload(): ByteArray {
        val out = UperBitWriter()
        // SignalStatusMessage: extension absent; timestamp absent; sequenceNumber present; regional absent.
        out.bit(false)
        out.bit(false)
        out.bit(true)
        out.bit(false)
        out.constrained(12_345, 0, 65_535)
        out.constrained(9, 0, 127)
        out.constrained(1, 1, 32)

        // SignalStatus: extension absent; regional absent.
        out.bit(false)
        out.bit(false)
        out.constrained(3, 0, 127)
        writeIntersectionReferenceId(out, 43, 1039)
        out.constrained(1, 1, 32)

        // SignalStatusPackage: extension absent; requester, outbound, second present.
        out.bit(false)
        out.bit(true)
        out.bit(true)
        out.bit(false)
        out.bit(true)
        out.bit(false)
        out.bit(false)
        writeSignalRequesterInfo(out)
        writeAccessPointLane(out, 31)
        writeAccessPointLane(out, 16)
        out.constrained(12_346, 0, 65_535)
        writeExtensibleEnum(out, SsemResponseStatus.Granted.code, 8)
        return out.toByteArray()
    }

    private fun writeSignalRequesterInfo(out: UperBitWriter) {
        out.bit(false)
        out.bit(false)
        out.bit(false)
        out.constrained(1, 0, 1)
        out.constrained(0x0102_0304L, 0, 0xffff_ffffL)
        out.constrained(5, 0, 255)
        out.constrained(27, 0, 127)
    }

    private fun writeIntersectionReferenceId(out: UperBitWriter, region: Int, id: Int) {
        out.bit(true)
        out.constrained(region.toLong(), 0, 65_535)
        out.constrained(id.toLong(), 0, 65_535)
    }

    private fun writeAccessPointLane(out: UperBitWriter, laneId: Int) {
        out.bit(false)
        out.constrained(0, 0, 2)
        out.constrained(laneId.toLong(), 0, 255)
    }

    private fun writeExtensibleEnum(out: UperBitWriter, value: Int, rootValues: Int) {
        out.bit(false)
        out.constrained(value.toLong(), 0, (rootValues - 1).toLong())
    }
}

