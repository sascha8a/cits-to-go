package org.opentrafficmap.citstogo.cam

import java.io.Serializable
import kotlin.math.roundToInt
import org.opentrafficmap.citstogo.srem.SremProfile

/**
 * The size of the own vehicle as a CAM carries it in its BasicVehicleContainerHighFrequency (ETSI EN 302 637-2,
 * CDD TS 102 894-2 V1.3.1), in 0.1 m. A `null` size is sent as unavailable.
 */
data class VehicleDimensions(
    /** The vehicle length, with the trailer when [trailer] is [Trailer.KNOWN_LENGTH]; at most [MAX_LENGTH_DM]. */
    val lengthDm: Int?,
    /** At most [MAX_WIDTH_DM]. */
    val widthDm: Int?,
    val trailer: Trailer,
) : Serializable {
    /** The VehicleLengthConfidenceIndication values that a profile can choose. */
    enum class Trailer(val code: Int) {
        NONE(0),
        KNOWN_LENGTH(1),
        UNKNOWN_LENGTH(2),
        ;

        companion object {
            fun fromCode(code: Int): Trailer = entries.firstOrNull { it.code == code } ?: NONE
        }
    }

    /** VehicleLengthValue: 1..1022, 1023 for unavailable. */
    val lengthValue: Int get() = lengthDm?.coerceIn(1, MAX_LENGTH_DM) ?: LENGTH_UNAVAILABLE

    /** VehicleLengthConfidenceIndication: the trailer, or unavailable (4) without a length. */
    val lengthConfidenceIndication: Int get() = if (lengthDm == null) LENGTH_CONFIDENCE_UNAVAILABLE else trailer.code

    /** Whether CAMs with this size report a trailer. Without a length they leave the trailer unavailable. */
    val reportsTrailer: Boolean get() = lengthDm != null && trailer != Trailer.NONE

    /** VehicleWidth: 1..61, 62 for unavailable. */
    val widthValue: Int get() = widthDm?.coerceIn(1, MAX_WIDTH_DM) ?: WIDTH_UNAVAILABLE

    companion object {
        const val MAX_LENGTH_DM = 1_022
        const val MAX_WIDTH_DM = 61
        const val LENGTH_UNAVAILABLE = 1_023
        const val LENGTH_CONFIDENCE_UNAVAILABLE = 4
        const val WIDTH_UNAVAILABLE = 62

        val UNAVAILABLE = VehicleDimensions(lengthDm = null, widthDm = null, trailer = Trailer.NONE)

        /**
         * Whether CAMs of [profile] carry a size. In ETSI C-ITS Release 1, pedestrians and cyclists have no vehicle
         * size; the VAM of Release 2 describes them.
         */
        fun applies(profile: SremProfile): Boolean =
            profile != SremProfile.PEDESTRIAN && profile != SremProfile.BICYCLE

        /** The defaults proposed in issue #11; widths of 2.55 m round up to 2.6 m, the next 0.1 m step. */
        fun defaults(profile: SremProfile): VehicleDimensions = when (profile) {
            SremProfile.PEDESTRIAN, SremProfile.BICYCLE -> UNAVAILABLE
            SremProfile.MOPED, SremProfile.MOTORCYCLE -> VehicleDimensions(20, 8, Trailer.NONE)
            SremProfile.PASSENGER_CAR -> VehicleDimensions(45, 18, Trailer.NONE)
            SremProfile.PUBLIC_TRANSPORT_BUS -> VehicleDimensions(120, 26, Trailer.NONE)
            SremProfile.LIGHT_TRUCK -> VehicleDimensions(60, 22, Trailer.NONE)
            SremProfile.HEAVY_TRUCK -> VehicleDimensions(165, 26, Trailer.NONE)
            SremProfile.SPECIAL_VEHICLE -> VehicleDimensions(80, 26, Trailer.NONE)
            SremProfile.TRAM -> VehicleDimensions(340, 24, Trailer.NONE)
        }

        /** Reads a settings field in metres, decimal point or comma: rounded to 0.1 m and limited; else unavailable. */
        fun parseMeters(text: String, maxDm: Int): Int? = text.trim()
            .replace(',', '.')
            .toDoubleOrNull()
            ?.takeIf { it.isFinite() && it > 0.0 }
            ?.let { (it * 10).roundToInt().coerceIn(1, maxDm) }

        /** The settings field of a size: metres with one decimal, empty when unavailable. */
        fun formatMeters(dm: Int?): String = dm?.let { "${it / 10}.${it % 10}" }.orEmpty()
    }
}
