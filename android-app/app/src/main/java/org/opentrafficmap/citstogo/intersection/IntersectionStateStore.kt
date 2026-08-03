package org.opentrafficmap.citstogo.intersection

import android.location.Location

class IntersectionStateStore {
    private val maps = LinkedHashMap<IntersectionKey, MapIntersection>()
    private val spats = LinkedHashMap<IntersectionKey, SpatIntersection>()
    private val firstReceivedAtMs = LinkedHashMap<IntersectionKey, Long>()
    private var lastKey: IntersectionKey? = null

    fun accept(packet: ByteArray, receivedAtMs: Long = System.currentTimeMillis()): IntersectionSnapshot? {
        val its = ItsFrameExtractor.extract(packet) ?: return null
        val updatedKeys = when (its.messageId) {
            MapSpatDecoder.MESSAGE_ID_MAPEM -> MapSpatDecoder.decodeMap(its, receivedAtMs).map { map ->
                firstReceivedAtMs.putIfAbsent(map.key, receivedAtMs)
                maps[map.key] = mergeMap(maps[map.key], map)
                map.key
            }
            MapSpatDecoder.MESSAGE_ID_SPATEM -> MapSpatDecoder.decodeSpat(its, receivedAtMs).map { spat ->
                firstReceivedAtMs.putIfAbsent(spat.key, receivedAtMs)
                val existing = spats[spat.key]
                if (existing == null || spat.isAtLeastAsRecentAs(existing)) {
                    spats[spat.key] = spat
                }
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
                firstReceivedAtMs = firstReceivedAtMs[it] ?: maxOf(maps[it]?.receivedAtMs ?: 0L, spats[it]?.receivedAtMs ?: 0L),
            )
        }
    }

    fun activeSnapshots(nowMs: Long, maxAgeMs: Long): List<IntersectionSnapshot> {
        val cutoffMs = nowMs - maxAgeMs
        val knownKeys = LinkedHashSet<IntersectionKey>().apply {
            addAll(maps.keys)
            addAll(spats.keys)
        }
        val activeKeys = knownKeys.filter { key ->
            maxOf(maps[key]?.receivedAtMs ?: 0L, spats[key]?.receivedAtMs ?: 0L) >= cutoffMs
        }
        maps.keys.removeAll { key -> key !in activeKeys }
        spats.keys.removeAll { key -> key !in activeKeys }
        firstReceivedAtMs.keys.removeAll { key -> key !in activeKeys }
        if (lastKey !in activeKeys) lastKey = activeKeys.lastOrNull()

        val snapshots = activeKeys.map { key ->
            IntersectionSnapshot(
                map = maps[key],
                spat = spats[key],
                source = SelectionSource.LatestObserved,
                updatedAtMs = maxOf(maps[key]?.receivedAtMs ?: 0L, spats[key]?.receivedAtMs ?: 0L),
                firstReceivedAtMs = firstReceivedAtMs[key] ?: maxOf(maps[key]?.receivedAtMs ?: 0L, spats[key]?.receivedAtMs ?: 0L),
            )
        }
        return snapshots.sortedBy { snapshot ->
            snapshot.firstReceivedAtMs
        }
    }

    private fun nearestKey(location: Location): IntersectionKey? {
        val latitude = (location.latitude * 10_000_000.0).toInt()
        val longitude = (location.longitude * 10_000_000.0).toInt()
        return maps.values.minByOrNull { it.distanceTo(latitude, longitude) }?.key
    }

    private fun mergeMap(existing: MapIntersection?, incoming: MapIntersection): MapIntersection {
        if (existing == null || existing.revision != incoming.revision) return incoming
        val lanesById = LinkedHashMap<Int, MapLane>()
        existing.lanes.forEach { lanesById[it.id] = it }
        incoming.lanes.forEach { lanesById[it.id] = it }
        return incoming.copy(
            name = incoming.name ?: existing.name,
            laneWidthCm = incoming.laneWidthCm ?: existing.laneWidthCm,
            lanes = lanesById.values.toList(),
            receivedAtMs = maxOf(existing.receivedAtMs, incoming.receivedAtMs),
        )
    }
}
