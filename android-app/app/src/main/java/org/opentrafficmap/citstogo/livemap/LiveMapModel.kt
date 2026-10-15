package org.opentrafficmap.citstogo.livemap

/** A station on the map, in display units. */
data class StationMarker(
    val stationId: Long,
    val stationType: Int,
    val typeLabel: String,
    val latitude: Double,
    val longitude: Double,
    /** Degrees clockwise from north. Without a heading the icon has no pointer. */
    val headingDeg: Double?,
    val speedMps: Double?,
    val icon: String,
    val ageMs: Long,
    val stale: Boolean,
)

/** A DENM event on the map, at its event position. */
data class DenmMarker(
    /** The actionId as `<originatingStationId>/<sequenceNumber>`. */
    val id: String,
    val originatingStationId: Long,
    val stationType: Int,
    val stationTypeLabel: String,
    val latitude: Double,
    val longitude: Double,
    val label: String,
    val icon: String,
    val causeCode: Int?,
    val subCauseCode: Int?,
    val informationQuality: Int?,
    /**
     * Infrastructure resets the detection time with every update, so [sinceDetectionMs] reads "updated n s ago"
     * instead of "since n s".
     */
    val infrastructure: Boolean,
    val sinceDetectionMs: Long,
    val sinceReceivedMs: Long,
    val expiresAtMs: Long,
)

data class LiveMap(
    val stations: List<StationMarker>,
    val denms: List<DenmMarker>,
)

/** What the map shows of a [LiveMapSnapshot] at a given time. */
object LiveMapModel {
    const val DEFAULT_STATION_TIMEOUT_MS = 10_000L
    const val MIN_STATION_TIMEOUT_MS = 5_000L
    const val MAX_STATION_TIMEOUT_MS = 300_000L

    /** A single lost CAM at 1 Hz leaves a gap of 2 s. */
    const val STALE_AFTER_MS = 3_000L

    /** Road side units do not move, and no specification limits their CAM interval. */
    const val MIN_ROAD_SIDE_UNIT_TIMEOUT_MS = 30_000L

    // Trailers and special vehicles of the road operator, and road side units: C-Roads infrastructure DENM senders.
    private val INFRASTRUCTURE_STATION_TYPES = setOf(9, 10, 15)

    /**
     * Stations heard within [stationTimeoutMs], at least [MIN_ROAD_SIDE_UNIT_TIMEOUT_MS] for road side units, and the
     * DENM events that have not expired at [nowMs]. Ages count from [nowMs] and are never negative, should the clock
     * jump back.
     */
    fun build(snapshot: LiveMapSnapshot, nowMs: Long, stationTimeoutMs: Long): LiveMap {
        val timeoutMs = stationTimeoutMs.coerceIn(MIN_STATION_TIMEOUT_MS, MAX_STATION_TIMEOUT_MS)
        return LiveMap(
            stations = snapshot.stations.mapNotNull { station(it, nowMs, timeoutMs) },
            denms = snapshot.denms.filter { it.expiresAtMs > nowMs }.map { denm(it, nowMs) },
        )
    }

    private fun station(station: LiveMapStation, nowMs: Long, timeoutMs: Long): StationMarker? {
        val ageMs = (nowMs - station.receivedAtMs).coerceAtLeast(0L)
        val stationTimeoutMs = if (station.roadSideUnit) maxOf(timeoutMs, MIN_ROAD_SIDE_UNIT_TIMEOUT_MS) else timeoutMs
        if (ageMs > stationTimeoutMs) return null
        return StationMarker(
            stationId = station.stationId,
            stationType = station.stationType,
            typeLabel = StationTypes.label(station.stationType),
            latitude = station.latitude.degrees(),
            longitude = station.longitude.degrees(),
            headingDeg = station.heading?.let { it / 10.0 },
            speedMps = station.speedCms?.let { it / 100.0 },
            icon = StationTypes.iconGroup(station.stationType).icon,
            ageMs = ageMs,
            stale = ageMs >= STALE_AFTER_MS,
        )
    }

    private fun denm(denm: LiveMapDenm, nowMs: Long): DenmMarker {
        val causeCode = denm.causeCode
        val subCauseCode = denm.subCauseCode
        return DenmMarker(
            id = "${denm.originatingStationId}/${denm.sequenceNumber}",
            originatingStationId = denm.originatingStationId,
            stationType = denm.stationType,
            stationTypeLabel = StationTypes.label(denm.stationType),
            latitude = denm.latitude.degrees(),
            longitude = denm.longitude.degrees(),
            label = if (causeCode == null || subCauseCode == null) {
                "Unknown cause"
            } else {
                DenmCauses.label(causeCode, subCauseCode)
            },
            icon = (causeCode?.let(DenmCauses::iconGroup) ?: DenmIconGroup.Hazard).icon,
            causeCode = causeCode,
            subCauseCode = subCauseCode,
            informationQuality = denm.informationQuality,
            infrastructure = denm.stationType in INFRASTRUCTURE_STATION_TYPES,
            sinceDetectionMs = (nowMs - denm.detectedAtMs).coerceAtLeast(0L),
            sinceReceivedMs = (nowMs - denm.receivedAtMs).coerceAtLeast(0L),
            expiresAtMs = denm.expiresAtMs,
        )
    }

    private fun Int.degrees(): Double = this / 10_000_000.0
}
