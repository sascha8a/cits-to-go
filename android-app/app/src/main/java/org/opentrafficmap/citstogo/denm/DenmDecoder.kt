package org.opentrafficmap.citstogo.denm

import org.opentrafficmap.citstogo.intersection.IntersectionDecodeException
import org.opentrafficmap.citstogo.intersection.ItsPacket
import org.opentrafficmap.citstogo.intersection.UperBitReader

enum class DenmTermination {
    Cancellation,
    Negation,
}

/** The information quality and event type at the start of the SituationContainer. */
data class DenmSituation(
    val informationQuality: Int,
    val causeCode: Int,
    val subCauseCode: Int,
)

/**
 * A received DENM in transmitted units, with times as TimestampIts. [stationId] is the sender in the ITS PDU header,
 * which can differ from the [originatingStationId] of the actionId, for example for road side units. Distance and
 * traffic direction are the ENUMERATED values. The event position is `null` where unavailable.
 */
data class ReceivedDenm(
    val stationId: Long,
    val originatingStationId: Long,
    val sequenceNumber: Int,
    val detectionTimeIts: Long,
    val referenceTimeIts: Long,
    val termination: DenmTermination?,
    val latitude: Int?,
    val longitude: Int?,
    val awarenessDistance: Int?,
    val awarenessTrafficDirection: Int?,
    val validityDurationSeconds: Int,
    val transmissionIntervalMs: Int?,
    val stationType: Int,
    /** `null` without a SituationContainer and when a ManagementContainer extension precedes it. */
    val situation: DenmSituation?,
)

object DenmDecoder {
    const val MESSAGE_ID_DENM = 1
    private const val DEFAULT_VALIDITY_DURATION_SECONDS = 600

    private const val LATITUDE_UNAVAILABLE = 900_000_001
    private const val LONGITUDE_UNAVAILABLE = 1_800_000_001
    private const val TIMESTAMP_ITS_MAXIMUM = 4_398_046_511_103L

    /**
     * Decodes the ManagementContainer of a protocolVersion 2 DENM and the event type of its SituationContainer.
     * Returns `null` for other messages and versions.
     */
    fun decode(packet: ItsPacket): ReceivedDenm? {
        if (packet.protocolVersion != 2 || packet.messageId != MESSAGE_ID_DENM) return null
        val reader = UperBitReader(packet.payload, packet.bodyOffset)
        return reader.denm(packet.stationId)
    }

    private fun UperBitReader.denm(stationId: Long): ReceivedDenm {
        val hasSituation = bit()
        skipBits(2) // Presence of the location and alacarte containers, which follow the situation container.
        val managementExtended = bit()
        val hasTermination = bit()
        val hasAwarenessDistance = bit()
        val hasAwarenessTrafficDirection = bit()
        val hasValidityDuration = bit()
        val hasTransmissionInterval = bit()
        val originatingStationId = constrained(0, 0xffff_ffffL)
        val sequenceNumber = constrained(0, 65_535).toInt()
        val detectionTime = constrained(0, TIMESTAMP_ITS_MAXIMUM)
        val referenceTime = constrained(0, TIMESTAMP_ITS_MAXIMUM)
        val termination = if (hasTermination) termination() else null
        val (latitude, longitude) = referencePosition()
        val awarenessDistance = if (hasAwarenessDistance) constrained(0, 7).toInt() else null
        val awarenessTrafficDirection = if (hasAwarenessTrafficDirection) constrained(0, 3).toInt() else null
        val validityDuration =
            if (hasValidityDuration) inRange(0, 86_400).toInt() else DEFAULT_VALIDITY_DURATION_SECONDS
        val transmissionInterval = if (hasTransmissionInterval) inRange(1, 10_000).toInt() else null
        val stationType = constrained(0, 255).toInt()
        // ManagementContainer extension additions would precede the situation container.
        val situation = if (hasSituation && !managementExtended) situation() else null
        return ReceivedDenm(
            stationId = stationId,
            originatingStationId = originatingStationId,
            sequenceNumber = sequenceNumber,
            detectionTimeIts = detectionTime,
            referenceTimeIts = referenceTime,
            termination = termination,
            latitude = latitude.takeUnless { it == LATITUDE_UNAVAILABLE },
            longitude = longitude.takeUnless { it == LONGITUDE_UNAVAILABLE },
            awarenessDistance = awarenessDistance,
            awarenessTrafficDirection = awarenessTrafficDirection,
            validityDurationSeconds = validityDuration,
            transmissionIntervalMs = transmissionInterval,
            stationType = stationType,
            situation = situation,
        )
    }

    private fun UperBitReader.termination(): DenmTermination =
        if (bit()) DenmTermination.Negation else DenmTermination.Cancellation

    private fun UperBitReader.referencePosition(): Pair<Int, Int> {
        val latitude = inRange(-900_000_000, 900_000_001).toInt()
        val longitude = inRange(-1_800_000_000, 1_800_000_001).toInt()
        constrained(0, 4_095) // semiMajorConfidence
        constrained(0, 4_095) // semiMinorConfidence
        constrained(0, 3_601) // semiMajorOrientation
        constrained(-100_000, 800_001) // altitudeValue
        constrained(0, 15) // altitudeConfidence
        return latitude to longitude
    }

    private fun UperBitReader.situation(): DenmSituation {
        // Extension bit and presence of linkedCause and eventZone (eventHistory in DENM v1.3.1), which all follow.
        skipBits(3)
        val informationQuality = constrained(0, 7).toInt()
        // The event type: extension bit, causeCode and subCauseCode. Additions follow the subcause, so a set extension
        // bit leaves both valid. The CauseCodeChoice of the CDD v2 CauseCodeV2 is encoded the same way.
        skipBits(1)
        val causeCode = constrained(0, 255).toInt()
        val subCauseCode = constrained(0, 255).toInt()
        return DenmSituation(informationQuality, causeCode, subCauseCode)
    }

    private fun UperBitReader.inRange(minimum: Long, maximum: Long): Long {
        val value = constrained(minimum, maximum)
        if (value > maximum) throw IntersectionDecodeException("DENM value $value is outside $minimum..$maximum")
        return value
    }
}
