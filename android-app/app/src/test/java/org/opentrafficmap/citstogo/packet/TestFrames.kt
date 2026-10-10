package org.opentrafficmap.citstogo.packet

import org.opentrafficmap.citstogo.cam.CamIdentity
import org.opentrafficmap.citstogo.cam.CamPosition
import org.opentrafficmap.citstogo.cam.ItsG5FrameBuilder
import org.opentrafficmap.citstogo.cam.StationType

/** Complete ITS-G5 frames around ITS PDUs from decoder tests. */
internal object TestFrames {
    const val DENM_PORT = 2_002

    private const val CAM_PORT = 2_001
    private const val ITS_PDU_OFFSET = 78
    private const val GN_PAYLOAD_LENGTH_OFFSET = 42
    private const val BTP_DESTINATION_PORT_OFFSET = 74
    private const val BTP_HEADER_LENGTH = 4

    fun hex(value: String): ByteArray =
        ByteArray(value.length / 2) { value.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    /**
     * Puts [pdu] behind the 802.11, LLC/SNAP, GeoNetworking SHB and BTP-B headers of an encoded CAM frame, with the
     * BTP [destinationPort].
     */
    fun shbFrame(pdu: ByteArray, destinationPort: Int = CAM_PORT): ByteArray {
        val headers = ItsG5FrameBuilder.camFrame(
            CamIdentity(stationId = 0x0102_0304, macAddress = byteArrayOf(0x02, 1, 2, 3, 4, 5)),
            StationType.PASSENGER_CAR,
            CamPosition.unavailable(),
            1_785_242_510_349L,
            ItsG5FrameBuilder.SequenceCounters(initialWlanSequence = 0, initialGbcSequenceId = 0),
        ).copyOf(ITS_PDU_OFFSET)
        val payloadLength = BTP_HEADER_LENGTH + pdu.size
        headers[GN_PAYLOAD_LENGTH_OFFSET] = (payloadLength ushr 8).toByte()
        headers[GN_PAYLOAD_LENGTH_OFFSET + 1] = payloadLength.toByte()
        headers[BTP_DESTINATION_PORT_OFFSET] = (destinationPort ushr 8).toByte()
        headers[BTP_DESTINATION_PORT_OFFSET + 1] = destinationPort.toByte()
        return headers + pdu
    }
}
