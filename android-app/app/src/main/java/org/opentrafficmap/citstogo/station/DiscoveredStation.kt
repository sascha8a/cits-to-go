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
) : Serializable

/** A single received packet's contribution to the picture of its sender. */
data class StationObservation(
    val packetType: ItsPacketType? = null,
    val rssiDbm: Int? = null,
    val latitudeE7: Int? = null,
    val longitudeE7: Int? = null,
)

/**
 * Serializable wrapper so a snapshot can travel in a status broadcast intent, following
 * [org.opentrafficmap.citstogo.intersection.IntersectionSnapshotList].
 */
data class DiscoveredStationList(val stations: List<DiscoveredStation>) : Serializable {
    companion object {
        /**
         * Most stations one status broadcast carries, which keeps the intent well clear of the
         * binder buffer. More may be stored, so this also caps what the table can show.
         */
        const val BROADCAST_LIMIT = 250
    }
}
