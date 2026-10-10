package org.opentrafficmap.citstogo.livemap

import kotlin.math.roundToInt

/** What the detail card of a tapped marker shows. */
data class MarkerDetails(
    val title: String,
    val rows: List<Pair<String, String>>,
)

object MarkerDetailsFormat {
    fun station(station: StationMarker): MarkerDetails = MarkerDetails(
        title = station.typeLabel,
        rows = listOf(
            "Station ID" to station.stationId.toString(),
            "Speed" to (station.speedMps?.let { "${(it * 3.6).roundToInt()} km/h" } ?: UNAVAILABLE),
            "Heading" to (station.headingDeg?.let { "${it.roundToInt() % 360}°" } ?: UNAVAILABLE),
            "Last seen" to "${duration(station.ageMs)} ago",
        ),
    )

    /** Infrastructure sets a new detection time with every update, so its age reads as the last update. */
    fun denm(denm: DenmMarker, nowMs: Long): MarkerDetails {
        val causeCode = denm.causeCode
        val subCauseCode = denm.subCauseCode
        val rows = mutableListOf<Pair<String, String>>()
        rows += "Cause" to (causeCode?.let { "${DenmCauses.causeLabel(it) ?: "Unknown"} ($it)" } ?: "Unknown")
        if (causeCode != null && subCauseCode != null) {
            val label = DenmCauses.subCauseLabel(causeCode, subCauseCode)
                ?: if (subCauseCode == 0) UNAVAILABLE else "Unknown"
            rows += "Subcause" to "$label ($subCauseCode)"
        }
        denm.informationQuality?.let { rows += "Information quality" to if (it == 0) UNAVAILABLE else "$it of 7" }
        rows += "Sender" to "${denm.stationTypeLabel} ${denm.originatingStationId}"
        rows += if (denm.infrastructure) {
            "Updated" to "${duration(denm.sinceDetectionMs)} ago"
        } else {
            "Detected" to "${duration(denm.sinceDetectionMs)} ago"
        }
        rows += "Last received" to "${duration(denm.sinceReceivedMs)} ago"
        rows += "Expires" to "in ${duration(denm.expiresAtMs - nowMs)}"
        return MarkerDetails(title = denm.label, rows = rows)
    }

    /** Whole seconds, minutes from one minute and hours from one hour on. */
    fun duration(ms: Long): String {
        val seconds = ms.coerceAtLeast(0L) / 1_000L
        return when {
            seconds < 60 -> "$seconds s"
            seconds < 3_600 -> "${seconds / 60} min ${seconds % 60} s"
            else -> "${seconds / 3_600} h ${seconds % 3_600 / 60} min"
        }
    }

    private const val UNAVAILABLE = "Unavailable"
}
