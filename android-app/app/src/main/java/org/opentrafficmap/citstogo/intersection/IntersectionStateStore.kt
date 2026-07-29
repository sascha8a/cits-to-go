package org.opentrafficmap.citstogo.intersection

import android.location.Location

class IntersectionStateStore {
    private val maps = LinkedHashMap<IntersectionKey, MapIntersection>()
    private val spats = LinkedHashMap<IntersectionKey, SpatIntersection>()
    private var lastKey: IntersectionKey? = null

    fun accept(packet: ByteArray, receivedAtMs: Long = System.currentTimeMillis()): IntersectionSnapshot? {
        val its = ItsFrameExtractor.extract(packet) ?: return null
        val updatedKeys = when (its.messageId) {
            MapSpatDecoder.MESSAGE_ID_MAPEM -> MapSpatDecoder.decodeMap(its, receivedAtMs).map { map ->
                maps[map.key] = map
                map.key
            }
            MapSpatDecoder.MESSAGE_ID_SPATEM -> MapSpatDecoder.decodeSpat(its, receivedAtMs).map { spat ->
                spats[spat.key] = spat
                spat.key
            }
            else -> emptyList()
        }
        lastKey = updatedKeys.lastOrNull() ?: lastKey
        return closest(null)
    }

    fun closest(location: Location?): IntersectionSnapshot? {
        val key = location?.let { nearestKey(it) } ?: lastKey ?: maps.keys.lastOrNull() ?: spats.keys.lastOrNull()
        return key?.let {
            IntersectionSnapshot(
                map = maps[it],
                spat = spats[it],
                source = if (location == null) SelectionSource.LatestObserved else SelectionSource.DeviceLocation,
                updatedAtMs = maxOf(maps[it]?.receivedAtMs ?: 0L, spats[it]?.receivedAtMs ?: 0L),
            )
        }
    }

    private fun nearestKey(location: Location): IntersectionKey? {
        val latitude = (location.latitude * 10_000_000.0).toInt()
        val longitude = (location.longitude * 10_000_000.0).toInt()
        return maps.values.minByOrNull { it.distanceTo(latitude, longitude) }?.key
    }
}
