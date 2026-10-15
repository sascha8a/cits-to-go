package org.opentrafficmap.citstogo.station

import java.io.Serializable

/**
 * Everything the receiver has heard from one C-ITS station, keyed by the IEEE source address of
 * its frames. Timestamps are wall-clock milliseconds; a zero timestamp means "never observed",
 * which happens for addresses carried over from the earlier counter-only discovery store.
 *
 * Rows travel to the interface inside a [DiscoveredStationList] in a status broadcast intent, which
 * writes it with [java.io.ObjectOutputStream], so this class and every field type it holds have to
 * stay serializable.
 */
data class DiscoveredStation(
    val macAddress: String,
    val firstSeenMs: Long = 0L,
    val lastSeenMs: Long = 0L,
    val typeCounts: Map<ItsPacketType, Int> = emptyMap(),
    val lastRssiDbm: Int? = null,
    val bestRssiDbm: Int? = null,
    val latitudeE7: Int? = null,
    val longitudeE7: Int? = null,
    val positionTimeMs: Long = 0L,
    /** The ITS station type in the GeoNetworking address of the packets this station created. */
    val geoNetworkingStationType: Int? = null,
) : Serializable {
    val roadSideUnit: Boolean get() = geoNetworkingStationType == ROAD_SIDE_UNIT_STATION_TYPE

    companion object {
        const val ROAD_SIDE_UNIT_STATION_TYPE = 15
    }
}

/** A single received packet's contribution to the picture of its sender. */
data class StationObservation(
    val packetType: ItsPacketType? = null,
    val rssiDbm: Int? = null,
    val latitudeE7: Int? = null,
    val longitudeE7: Int? = null,
    /** The ITS station type in the GeoNetworking address of the station that created the packet. */
    val stationType: Int? = null,
    /**
     * The link-layer address of the station that created the packet, when another radio forwarded it. The
     * position and station type are then that station's, not those of the radio that sent the frame.
     */
    val forwardedFrom: String? = null,
)

/**
 * Serializable wrapper so a snapshot can travel in a status broadcast intent, following
 * [org.opentrafficmap.citstogo.intersection.IntersectionSnapshotList].
 */
data class DiscoveredStationList(val stations: List<DiscoveredStation>) : Serializable {
    companion object {
        /**
         * Most rows of the table one status broadcast carries, which keeps the intent well clear of
         * the binder buffer. More may be stored, so this also caps what the table can show. The road
         * side units the map shows from history travel next to them, at most
         * [StationDiscoveryStore.MAX_ROAD_SIDE_UNITS].
         */
        const val BROADCAST_LIMIT = 250
    }
}
