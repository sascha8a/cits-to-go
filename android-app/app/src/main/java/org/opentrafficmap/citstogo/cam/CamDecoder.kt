package org.opentrafficmap.citstogo.cam

import org.opentrafficmap.citstogo.intersection.IntersectionDecodeException
import org.opentrafficmap.citstogo.intersection.ItsPacket
import org.opentrafficmap.citstogo.intersection.UperBitReader

enum class CamHighFrequencyContainer {
    BasicVehicle,
    RoadSideUnit,

    /** Behind a BasicContainer extension or an extension of the HighFrequencyContainer CHOICE. */
    Unknown,
}

/** A received CAM in transmitted units. Heading and speed are `null` when unavailable or not decoded. */
data class ReceivedCam(
    val stationId: Long,
    val generationDeltaTime: Int,
    val stationType: Int,
    val latitude: Int,
    val longitude: Int,
    val highFrequencyContainer: CamHighFrequencyContainer,
    val heading: Int?,
    val speedCms: Int?,
    /**
     * Whether the vehicle pulls a trailer, from its VehicleLengthConfidenceIndication. `null` when the CAM leaves it
     * unknown or unavailable, and without a decoded BasicVehicleContainerHighFrequency.
     */
    val trailer: Boolean?,
)

object CamDecoder {
    const val MESSAGE_ID_CAM = 2

    private const val LATITUDE_UNAVAILABLE = 900_000_001
    private const val LONGITUDE_UNAVAILABLE = 1_800_000_001
    private const val HEADING_UNAVAILABLE = 3_601
    private const val SPEED_UNAVAILABLE = 16_383
    private const val NO_TRAILER_PRESENT = 0
    private const val TRAILER_PRESENT_WITH_KNOWN_LENGTH = 1
    private const val TRAILER_PRESENT_WITH_UNKNOWN_LENGTH = 2

    /**
     * Decodes a protocolVersion 2 CAM up to the trailer in the vehicle length. Returns `null` for other messages and
     * versions and for CAMs without a position.
     */
    fun decode(packet: ItsPacket): ReceivedCam? {
        if (packet.protocolVersion != 2 || packet.messageId != MESSAGE_ID_CAM) return null
        val reader = UperBitReader(packet.payload, packet.bodyOffset)
        return reader.cam(packet.stationId)
    }

    private fun UperBitReader.cam(stationId: Long): ReceivedCam? {
        val generationDeltaTime = constrained(0, 65_535).toInt()
        // CamParameters: extension bit, lowFrequencyContainer and specialVehicleContainer presence. All of them
        // only concern data behind the containers read here.
        skipBits(3)
        val basicContainerExtended = bit()
        val stationType = constrained(0, 255).toInt()
        val (latitude, longitude) = referencePosition()
        if (latitude == LATITUDE_UNAVAILABLE || longitude == LONGITUDE_UNAVAILABLE) return null
        // BasicContainer extension additions would precede the high frequency container.
        val container = if (basicContainerExtended) CamHighFrequencyContainer.Unknown else highFrequencyContainer()
        val vehicle = if (container == CamHighFrequencyContainer.BasicVehicle) vehicleHighFrequency() else null
        return ReceivedCam(
            stationId = stationId,
            generationDeltaTime = generationDeltaTime,
            stationType = stationType,
            latitude = latitude,
            longitude = longitude,
            highFrequencyContainer = container,
            heading = vehicle?.heading,
            speedCms = vehicle?.speedCms,
            trailer = vehicle?.trailer,
        )
    }

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

    private fun UperBitReader.highFrequencyContainer(): CamHighFrequencyContainer {
        if (bit()) return CamHighFrequencyContainer.Unknown
        return if (bit()) CamHighFrequencyContainer.RoadSideUnit else CamHighFrequencyContainer.BasicVehicle
    }

    private class VehicleHighFrequency(val heading: Int?, val speedCms: Int?, val trailer: Boolean?)

    private fun UperBitReader.vehicleHighFrequency(): VehicleHighFrequency {
        skipBits(7) // Presence of the optional BasicVehicleContainerHighFrequency fields.
        val heading = inRange(0, 3_601).toInt()
        constrained(1, 127) // headingConfidence
        val speed = constrained(0, 16_383).toInt()
        constrained(1, 127) // speedConfidence
        constrained(0, 2) // driveDirection
        constrained(1, 1_023) // vehicleLengthValue
        // VehicleLengthConfidenceIndication: trailerPresenceIsUnknown (3), unavailable (4) and values out of range
        // leave the trailer open.
        val trailer = when (constrained(0, 4).toInt()) {
            NO_TRAILER_PRESENT -> false
            TRAILER_PRESENT_WITH_KNOWN_LENGTH, TRAILER_PRESENT_WITH_UNKNOWN_LENGTH -> true
            else -> null
        }
        return VehicleHighFrequency(
            heading = heading.takeUnless { it == HEADING_UNAVAILABLE },
            speedCms = speed.takeUnless { it == SPEED_UNAVAILABLE },
            trailer = trailer,
        )
    }

    private fun UperBitReader.inRange(minimum: Long, maximum: Long): Long {
        val value = constrained(minimum, maximum)
        if (value > maximum) throw IntersectionDecodeException("CAM value $value is outside $minimum..$maximum")
        return value
    }
}
