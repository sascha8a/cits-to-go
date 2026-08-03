package org.opentrafficmap.citstogo.srem

import org.opentrafficmap.citstogo.cam.UperBitWriter
import java.util.Calendar
import java.util.TimeZone

object SremUperEncoder {
    private const val MESSAGE_ID_SREM = 9
    private const val PROTOCOL_VERSION = 2
    private const val REQUEST_TYPE_PRIORITY = 1
    private const val REQUESTOR_ROLE_PEDESTRIAN = 20
    private val UTC: TimeZone = TimeZone.getTimeZone("UTC")

    fun encode(identity: SremIdentity, request: SremRequest): ByteArray {
        require(request.requestId in 0..255)
        require(request.sequenceNumber in 0..127)
        require(request.intersectionId in 0..65_535)
        request.region?.let { require(it in 0..65_535) }
        require(request.inboundLaneId in 0..255)
        require(request.outboundLaneId in 0..255)

        val out = UperBitWriter()
        writeItsPduHeader(out, identity.stationId)
        writeSignalRequestMessage(out, identity.stationId, request)
        return out.toByteArray()
    }

    private fun writeItsPduHeader(out: UperBitWriter, stationId: Long) {
        out.constrained(PROTOCOL_VERSION.toLong(), 0, 255)
        out.constrained(MESSAGE_ID_SREM.toLong(), 0, 255)
        out.constrained(stationId, 0, 0xffff_ffffL)
    }

    private fun writeSignalRequestMessage(out: UperBitWriter, stationId: Long, request: SremRequest) {
        // SignalRequestMessage extension marker and optionals:
        // timeStamp, second, sequenceNumber present; regional absent.
        out.bit(false)
        out.bit(true)
        out.bit(true)
        out.bit(true)
        out.bit(false)
        out.constrained(minuteOfYear(request.nowUnixMs).toLong(), 0, 527_040)
        out.constrained(dSecond(request.nowUnixMs).toLong(), 0, 65_535)
        out.constrained(request.sequenceNumber.toLong(), 0, 127)
        out.constrained(1, 1, 32)
        writeSignalRequestPackage(out, request)
        writeRequestorDescription(out, stationId, request.position)
    }

    private fun writeSignalRequestPackage(out: UperBitWriter, request: SremRequest) {
        // SignalRequestPackage extension marker and optionals: minute and second present.
        out.bit(false)
        out.bit(true)
        out.bit(true)
        out.bit(false)
        out.bit(false)
        writeSignalRequest(out, request)
        out.constrained(minuteOfYear(request.nowUnixMs).toLong(), 0, 527_040)
        out.constrained(dSecond(request.nowUnixMs).toLong(), 0, 65_535)
    }

    private fun writeSignalRequest(out: UperBitWriter, request: SremRequest) {
        // SignalRequest extension marker and optionals: inbound/outbound lanes present.
        out.bit(false)
        out.bit(true)
        out.bit(true)
        out.bit(false)
        writeIntersectionReferenceId(out, request.region, request.intersectionId)
        out.constrained(request.requestId.toLong(), 0, 255)
        writeRootEnum(out, REQUEST_TYPE_PRIORITY, 4)
        writeIntersectionAccessPointLane(out, request.inboundLaneId)
        writeIntersectionAccessPointLane(out, request.outboundLaneId)
    }

    private fun writeIntersectionReferenceId(out: UperBitWriter, region: Int?, id: Int) {
        out.bit(region != null)
        if (region != null) out.constrained(region.toLong(), 0, 65_535)
        out.constrained(id.toLong(), 0, 65_535)
    }

    private fun writeIntersectionAccessPointLane(out: UperBitWriter, laneId: Int) {
        out.bit(false)
        out.constrained(0, 0, 2)
        out.constrained(laneId.toLong(), 0, 255)
    }

    private fun writeRequestorDescription(out: UperBitWriter, stationId: Long, position: SremPosition) {
        // RequestorDescription extension marker and optionals: type and position present.
        out.bit(false)
        out.bit(true)
        out.bit(true)
        repeat(5) { out.bit(false) }
        out.bit(false)
        writeVehicleIdStationId(out, stationId)
        writeRequestorType(out)
        writeRequestorPositionVector(out, position)
    }

    private fun writeVehicleIdStationId(out: UperBitWriter, stationId: Long) {
        out.bit(true)
        out.constrained(stationId, 0, 0xffff_ffffL)
    }

    private fun writeRequestorType(out: UperBitWriter) {
        out.bit(false)
        repeat(5) { out.bit(false) }
        writeRootEnum(out, REQUESTOR_ROLE_PEDESTRIAN, 32)
    }

    private fun writeRequestorPositionVector(out: UperBitWriter, position: SremPosition) {
        out.bit(false)
        out.bit(false)
        writePosition3D(out, position)
    }

    private fun writePosition3D(out: UperBitWriter, position: SremPosition) {
        out.bit(false)
        out.bit(false)
        out.constrained(position.latitude.toLong(), -900_000_000, 900_000_001)
        out.constrained(position.longitude.toLong(), -1_799_999_999, 1_800_000_001)
    }

    private fun writeRootEnum(out: UperBitWriter, value: Int, rootValues: Int) {
        out.bit(false)
        out.constrained(value.toLong(), 0, (rootValues - 1).toLong())
    }

    private fun minuteOfYear(unixMs: Long): Int {
        val calendar = Calendar.getInstance(UTC)
        calendar.timeInMillis = unixMs
        return (calendar.get(Calendar.DAY_OF_YEAR) - 1) * 1_440 +
            calendar.get(Calendar.HOUR_OF_DAY) * 60 +
            calendar.get(Calendar.MINUTE)
    }

    private fun dSecond(unixMs: Long): Int {
        val calendar = Calendar.getInstance(UTC)
        calendar.timeInMillis = unixMs
        return calendar.get(Calendar.SECOND) * 1_000 + calendar.get(Calendar.MILLISECOND)
    }
}
