package org.opentrafficmap.citstogo.cam

import android.location.Location
import android.os.Build
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.math.roundToLong

data class CamIdentity(
    val stationId: Long,
    val macAddress: ByteArray,
) {
    init {
        require(stationId in 0..0xffff_ffffL)
        require(macAddress.size == 6)
    }
}

data class CamPosition(
    val latitude: Int,
    val longitude: Int,
    val semiMajorConfidenceCm: Int,
    val semiMinorConfidenceCm: Int,
    val semiMajorOrientation: Int,
    val altitudeCm: Int,
    val altitudeConfidence: Int,
    val heading: Int,
    val headingConfidence: Int,
    val speedCms: Int,
    val speedConfidence: Int,
    val positionAccurate: Boolean,
) {
    companion object {
        fun unavailable(): CamPosition = CamPosition(
            latitude = 900_000_001,
            longitude = 1_800_000_001,
            semiMajorConfidenceCm = 4_095,
            semiMinorConfidenceCm = 4_095,
            semiMajorOrientation = 3_601,
            altitudeCm = 800_001,
            altitudeConfidence = 15,
            heading = 3_601,
            headingConfidence = 127,
            speedCms = 16_383,
            speedConfidence = 127,
            positionAccurate = false,
        )

        fun fromLocation(location: Location?): CamPosition {
            if (location == null) return unavailable()
            val horizontalCm = if (location.hasAccuracy()) {
                val measured = ceil(location.accuracy * 100.0).roundToInt().coerceAtLeast(1)
                if (measured <= 4_093) measured else 4_094
            } else {
                4_095
            }
            val bearing = if (location.hasBearing()) {
                ((location.bearing.mod(360f)) * 10f).roundToInt().coerceIn(0, 3_600)
            } else {
                3_601
            }
            val bearingConfidence = if (
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && location.hasBearingAccuracy()
            ) {
                val measured = ceil(location.bearingAccuracyDegrees * 10.0).roundToInt().coerceAtLeast(1)
                if (measured <= 125) measured else 126
            } else {
                127
            }
            val speed = if (location.hasSpeed()) {
                (location.speed * 100f).roundToInt().coerceIn(0, 16_382)
            } else {
                16_383
            }
            val speedConfidence = if (
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && location.hasSpeedAccuracy()
            ) {
                val measured = ceil(location.speedAccuracyMetersPerSecond * 100.0).roundToInt().coerceAtLeast(1)
                if (measured <= 125) measured else 126
            } else {
                127
            }
            val altitude = if (location.hasAltitude()) {
                (location.altitude * 100.0).roundToInt().coerceIn(-100_000, 800_000)
            } else {
                800_001
            }
            val altitudeConfidence = if (
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && location.hasVerticalAccuracy()
            ) {
                altitudeConfidence(location.verticalAccuracyMeters)
            } else {
                15
            }
            return CamPosition(
                latitude = (location.latitude * 10_000_000.0).roundToInt().coerceIn(-900_000_000, 900_000_000),
                longitude = (location.longitude * 10_000_000.0).roundToLong()
                    .coerceIn(-1_800_000_000L, 1_800_000_000L).toInt(),
                semiMajorConfidenceCm = horizontalCm,
                semiMinorConfidenceCm = horizontalCm,
                semiMajorOrientation = if (horizontalCm == 4_095) 3_601 else 0,
                altitudeCm = altitude,
                altitudeConfidence = altitudeConfidence,
                heading = bearing,
                headingConfidence = bearingConfidence,
                speedCms = speed,
                speedConfidence = speedConfidence,
                positionAccurate = horizontalCm <= 500,
            )
        }

        private fun altitudeConfidence(meters: Float): Int = when {
            meters <= 0.01f -> 0
            meters <= 0.02f -> 1
            meters <= 0.05f -> 2
            meters <= 0.1f -> 3
            meters <= 0.2f -> 4
            meters <= 0.5f -> 5
            meters <= 1f -> 6
            meters <= 2f -> 7
            meters <= 5f -> 8
            meters <= 10f -> 9
            meters <= 20f -> 10
            meters <= 50f -> 11
            meters <= 100f -> 12
            meters <= 200f -> 13
            else -> 14
        }
    }
}

object CamUperEncoder {
    const val ITS_EPOCH_UNIX_MS = 1_072_915_200_000L
    // TAI-UTC increased from 32 s at the ITS epoch to 37 s in 2017.
    private const val LEAP_MILLISECONDS_SINCE_ITS_EPOCH = 5_000L

    fun timestampIts(nowUnixMs: Long): Long =
        nowUnixMs - ITS_EPOCH_UNIX_MS + LEAP_MILLISECONDS_SINCE_ITS_EPOCH

    fun encode(
        identity: CamIdentity,
        stationType: StationType,
        position: CamPosition,
        nowUnixMs: Long,
    ): ByteArray {
        val out = UperBitWriter()

        // ItsPduHeader (CAM Release 1 uses protocolVersion 2 and messageID 2).
        out.constrained(2, 0, 255)
        out.constrained(2, 0, 255)
        out.constrained(identity.stationId, 0, 0xffff_ffffL)

        val generationDeltaTime = timestampIts(nowUnixMs).mod(65_536L)
        out.constrained(generationDeltaTime, 0, 65_535)

        // CamParameters extension marker, lowFrequency absent, specialVehicle absent.
        out.bit(false)
        out.bit(false)
        out.bit(false)

        // BasicContainer extension marker and station type.
        out.bit(false)
        out.constrained(stationType.code.toLong(), 0, 255)
        writeReferencePosition(out, position)

        // HighFrequencyContainer: root CHOICE, vehicle=0, RSU=1.
        out.bit(false)
        val rsu = stationType == StationType.ROAD_SIDE_UNIT
        out.bit(rsu)
        if (rsu) writeRsuHighFrequency(out) else writeVehicleHighFrequency(out, position)

        return out.toByteArray()
    }

    private fun writeReferencePosition(out: UperBitWriter, p: CamPosition) {
        out.constrained(p.latitude.toLong(), -900_000_000, 900_000_001)
        out.constrained(p.longitude.toLong(), -1_800_000_000, 1_800_000_001)
        out.constrained(p.semiMajorConfidenceCm.toLong(), 0, 4_095)
        out.constrained(p.semiMinorConfidenceCm.toLong(), 0, 4_095)
        out.constrained(p.semiMajorOrientation.toLong(), 0, 3_601)
        out.constrained(p.altitudeCm.toLong(), -100_000, 800_001)
        out.constrained(p.altitudeConfidence.toLong(), 0, 15)
    }

    private fun writeVehicleHighFrequency(out: UperBitWriter, p: CamPosition) {
        repeat(7) { out.bit(false) } // All optional sensor/vehicle fields unavailable.
        out.constrained(p.heading.toLong(), 0, 3_601)
        out.constrained(p.headingConfidence.toLong(), 1, 127)
        out.constrained(p.speedCms.toLong(), 0, 16_383)
        out.constrained(p.speedConfidence.toLong(), 1, 127)
        out.constrained((if (p.speedCms == 16_383) 2 else 0).toLong(), 0, 2) // DriveDirection.
        out.constrained(1_023, 1, 1_023) // VehicleLengthValue unavailable.
        out.constrained(4, 0, 4) // VehicleLengthConfidence unavailable.
        out.constrained(62, 1, 62) // VehicleWidth unavailable.
        out.constrained(161, -160, 161) // LongitudinalAcceleration unavailable.
        out.constrained(102, 0, 102)
        out.constrained(1_023, -1_023, 1_023) // Curvature unavailable.
        out.constrained(7, 0, 7)
        out.bit(false) // CurvatureCalculationMode root value.
        out.constrained(2, 0, 2) // unavailable.
        out.constrained(32_767, -32_766, 32_767) // YawRate unavailable.
        out.constrained(8, 0, 8)
    }

    private fun writeRsuHighFrequency(out: UperBitWriter) {
        out.bit(false) // Extension marker.
        out.bit(false) // protectedCommunicationZonesRSU absent.
    }
}
