package org.opentrafficmap.citstogo.livemap

import org.opentrafficmap.citstogo.station.DiscoveredStation
import org.opentrafficmap.citstogo.station.ItsPacketType

/** A road side unit from the discovery table, at the last GeoNetworking position it sent. */
data class RoadSideUnitMarker(
    val macAddress: String,
    val latitude: Double,
    val longitude: Double,
    /** It sent intersection messages itself: MAPEM, SPATEM or SSEM. */
    val trafficLight: Boolean,
    val icon: String,
    val firstSeenMs: Long,
    val lastSeenMs: Long,
    val messageCounts: Map<ItsPacketType, Int>,
    val lastRssiDbm: Int?,
    val bestRssiDbm: Int?,
)

/** The road side units the map shows from history: every one the discovery table holds a position of. */
object RoadSideUnitHistory {
    const val TRAFFIC_LIGHT_ICON = "history-traffic-light"
    const val ROAD_SIDE_UNIT_ICON = "history-road-side-unit"

    private const val DAY_MS = 86_400_000L
    private val INTERSECTION_MESSAGES = setOf(ItsPacketType.MAPEM, ItsPacketType.SPATEM, ItsPacketType.SSEM)

    /** Whether a road side unit sent intersection messages itself: MAPEM, SPATEM or SSEM. */
    fun trafficLight(messageCounts: Map<ItsPacketType, Int>): Boolean = messageCounts.keys.any { it in INTERSECTION_MESSAGES }

    /** The road side units of [stations] last heard within [maxAgeDays] before [nowMs], all of them for 0 days. */
    fun build(stations: List<DiscoveredStation>, nowMs: Long, maxAgeDays: Int): List<RoadSideUnitMarker> =
        stations.mapNotNull { station ->
            val latitude = station.latitudeE7 ?: return@mapNotNull null
            val longitude = station.longitudeE7 ?: return@mapNotNull null
            if (!station.roadSideUnit) return@mapNotNull null
            if (maxAgeDays > 0 && nowMs - station.lastSeenMs > maxAgeDays * DAY_MS) return@mapNotNull null
            val trafficLight = trafficLight(station.typeCounts)
            RoadSideUnitMarker(
                macAddress = station.macAddress,
                latitude = latitude / 10_000_000.0,
                longitude = longitude / 10_000_000.0,
                trafficLight = trafficLight,
                icon = if (trafficLight) TRAFFIC_LIGHT_ICON else ROAD_SIDE_UNIT_ICON,
                firstSeenMs = station.firstSeenMs,
                lastSeenMs = station.lastSeenMs,
                messageCounts = station.typeCounts,
                lastRssiDbm = station.lastRssiDbm,
                bestRssiDbm = station.bestRssiDbm,
            )
        }
}
