package org.opentrafficmap.citstogo.livemap

import org.opentrafficmap.citstogo.station.DiscoveredStation

/** A station of the discovery table that the map was asked to show, as a pin at its GeoNetworking position. */
data class FocusMarker(
    val macAddress: String,
    val latitude: Double,
    val longitude: Double,
) {
    companion object {
        const val ICON = "focus-pin"

        /** The pin of [station], `null` without a position. */
        fun of(station: DiscoveredStation): FocusMarker? {
            val latitude = station.latitudeE7 ?: return null
            val longitude = station.longitudeE7 ?: return null
            return FocusMarker(station.macAddress, latitude / 10_000_000.0, longitude / 10_000_000.0)
        }
    }
}
