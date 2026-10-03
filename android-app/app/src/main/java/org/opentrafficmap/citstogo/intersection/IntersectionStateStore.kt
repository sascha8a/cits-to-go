package org.opentrafficmap.citstogo.intersection

import android.location.Location

class IntersectionStateStore {
    private val maps = LinkedHashMap<IntersectionKey, MapIntersection>()
    private val spats = LinkedHashMap<IntersectionKey, SpatIntersection>()
    private val firstReceivedAtMs = LinkedHashMap<IntersectionKey, Long>()
    private var lastKey: IntersectionKey? = null
    private var diagnostics = IntersectionDiagnostics()

    fun accept(packet: ByteArray, receivedAtMs: Long = System.currentTimeMillis()): IntersectionSnapshot? {
        diagnostics = diagnostics.copy(framesInspected = diagnostics.framesInspected + 1)
        val its = when (val extraction = ItsFrameExtractor.extractDetailed(packet)) {
            is ItsExtractionResult.Success -> {
                diagnostics = diagnostics.copy(
                    itsPacketsExtracted = diagnostics.itsPacketsExtracted + 1,
                    securedItsPackets = diagnostics.securedItsPackets + if (extraction.packet.geonetworkingSecured) 1 else 0,
                    securedGeoNetworkingFrames = diagnostics.securedGeoNetworkingFrames +
                        if (extraction.packet.geonetworkingSecured) 1 else 0,
                )
                extraction.packet
            }
            ItsExtractionResult.NotGeoNetworking -> return null
            is ItsExtractionResult.Malformed -> {
                diagnostics = diagnostics.copy(
                    malformedGeoNetworkingFrames = diagnostics.malformedGeoNetworkingFrames + 1,
                    securedGeoNetworkingFrames = diagnostics.securedGeoNetworkingFrames +
                        if (extraction.secured) 1 else 0,
                    lastExtractionIssue = extraction.reason,
                )
                return null
            }
            is ItsExtractionResult.Unsupported -> {
                diagnostics = diagnostics.copy(
                    unsupportedGeoNetworkingFrames = diagnostics.unsupportedGeoNetworkingFrames + 1,
                    lastExtractionIssue = extraction.reason,
                )
                return null
            }
        }

        val updatedKeys = when {
            its.destinationPort == MapSpatDecoder.BTP_PORT_MAPEM &&
                its.messageId == MapSpatDecoder.MESSAGE_ID_MAPEM -> decodeMap(its, receivedAtMs)
            its.destinationPort == MapSpatDecoder.BTP_PORT_SPATEM &&
                its.messageId == MapSpatDecoder.MESSAGE_ID_SPATEM -> decodeSpat(its, receivedAtMs)
            else -> emptyList()
        }
        lastKey = updatedKeys.lastOrNull() ?: lastKey
        return closest(null)
    }

    fun diagnostics(): IntersectionDiagnostics = diagnostics

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
        return snapshots.sortedBy { snapshot -> snapshot.firstReceivedAtMs }
    }

    private fun decodeMap(packet: ItsPacket, receivedAtMs: Long): List<IntersectionKey> {
        diagnostics = diagnostics.copy(mapemSeen = diagnostics.mapemSeen + 1)
        return try {
            val decoded = MapSpatDecoder.decodeMap(packet, receivedAtMs)
            decoded.forEach { map ->
                firstReceivedAtMs.putIfAbsent(map.key, receivedAtMs)
                maps[map.key] = mergeMap(maps[map.key], map)
            }
            diagnostics = diagnostics.copy(mapemDecoded = diagnostics.mapemDecoded + 1)
            decoded.map { it.key }
        } catch (e: Exception) {
            diagnostics = diagnostics.copy(
                mapemDecodeFailures = diagnostics.mapemDecodeFailures + 1,
                lastDecodeError = "MAPEM: ${e.message ?: e.javaClass.simpleName}",
            )
            emptyList()
        }
    }

    private fun decodeSpat(packet: ItsPacket, receivedAtMs: Long): List<IntersectionKey> {
        diagnostics = diagnostics.copy(spatemSeen = diagnostics.spatemSeen + 1)
        return try {
            val decoded = MapSpatDecoder.decodeSpat(packet, receivedAtMs)
            decoded.forEach { spat ->
                firstReceivedAtMs.putIfAbsent(spat.key, receivedAtMs)
                val existing = spats[spat.key]
                if (existing == null || spat.isAtLeastAsRecentAs(existing)) {
                    spats[spat.key] = spat
                }
            }
            diagnostics = diagnostics.copy(spatemDecoded = diagnostics.spatemDecoded + 1)
            decoded.map { it.key }
        } catch (e: Exception) {
            diagnostics = diagnostics.copy(
                spatemDecodeFailures = diagnostics.spatemDecodeFailures + 1,
                lastDecodeError = "SPATEM: ${e.message ?: e.javaClass.simpleName}",
            )
            emptyList()
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
