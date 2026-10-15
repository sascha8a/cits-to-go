package org.opentrafficmap.citstogo.station

import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicLong

/**
 * Accumulates what the receiver has heard from each C-ITS station, keyed by the IEEE source
 * address of that station's frames.
 *
 * The store keeps at most [maxStations] records of other stations and [maxRoadSideUnits] of road
 * side units, evicting the longest-unseen address of either kind first, so that the road side
 * units the map shows from history are not pushed out by passing traffic. It can serialise itself
 * to JSON so the discovery table survives app restarts. All methods are safe to
 * call from the serial read thread and the service's handler thread.
 */
class StationDiscoveryStore(
    private val maxStations: Int = MAX_STATIONS,
    private val maxRoadSideUnits: Int = MAX_ROAD_SIDE_UNITS,
) {
    private val records = LinkedHashMap<String, Record>()

    private val generationCounter = AtomicLong()

    /** Bumped on every change so callers can skip redundant persistence and broadcasts. */
    val generation: Long get() = generationCounter.get()

    val stationCount: Int get() = synchronized(this) { records.size }

    /**
     * Folds one received packet into the record of [macAddress]. [seenAtMs] is the wall-clock time
     * the frame was received, so a PCAP replay records the times of the original capture.
     *
     * The position and station type of a packet that another radio forwarded belong to the station
     * that created it, so they go to that station's record, which is added if the receiver has not
     * heard it itself. Message counts and signal strength stay with the radio that sent the frame.
     */
    fun observe(macAddress: String, observation: StationObservation, seenAtMs: Long) {
        synchronized(this) {
            val record = records.getOrPut(macAddress) { Record(macAddress) }
            observation.packetType?.let { type ->
                record.typeCounts[type] = (record.typeCounts[type] ?: 0) + 1
            }
            observation.rssiDbm?.let { rssi ->
                record.lastRssiDbm = rssi
                record.bestRssiDbm = maxOf(record.bestRssiDbm ?: rssi, rssi)
            }
            record.seen(seenAtMs)
            val source = when (val forwardedFrom = observation.forwardedFrom) {
                null, macAddress -> record
                else -> forwardedFrom.takeIf(::isUnicastAddress)?.let { address ->
                    records.getOrPut(address) { Record(address) }.also { it.seen(seenAtMs) }
                }
            }
            if (source != null) observation.stationType?.let { source.geoNetworkingStationType = it }
            val latitude = observation.latitudeE7
            val longitude = observation.longitudeE7
            if (source != null && latitude != null && longitude != null && isUsablePosition(latitude, longitude)) {
                source.latitudeE7 = latitude
                source.longitudeE7 = longitude
                source.positionTimeMs = seenAtMs
            }
            generationCounter.incrementAndGet()
            evictLeastRecentlySeen()
        }
    }

    /**
     * Adds address-only records for stations seen before per-station tracking existed, so that
     * every address the home screen counter includes has a row. Such a record has no timestamps or
     * counts.
     */
    fun seedMacAddresses(macAddresses: Collection<String>) {
        synchronized(this) {
            val missing = macAddresses.filter { it.isNotBlank() && !records.containsKey(it) }
            if (missing.isEmpty()) return
            missing.forEach { records[it] = Record(it) }
            generationCounter.incrementAndGet()
            evictLeastRecentlySeen()
        }
    }

    /** Snapshot of every record, newest activity first. */
    fun snapshot(limit: Int = Int.MAX_VALUE): List<DiscoveredStation> = synchronized(this) {
        records.values.sortedByDescending { it.lastSeenMs }.take(maxOf(limit, 0)).map { it.toStation() }
    }

    /** Forgets every record, or every record except the road side units with [keepRoadSideUnits]. */
    fun clear(keepRoadSideUnits: Boolean = false) {
        synchronized(this) {
            val removed = if (keepRoadSideUnits) {
                records.values.removeAll { !it.roadSideUnit }
            } else {
                records.isNotEmpty().also { records.clear() }
            }
            if (!removed) return
            generationCounter.incrementAndGet()
        }
    }

    /** The road side units with a position, newest activity first. */
    fun roadSideUnits(): List<DiscoveredStation> = synchronized(this) {
        records.values
            .filter { it.roadSideUnit && it.latitudeE7 != null && it.longitudeE7 != null }
            .sortedByDescending { it.lastSeenMs }
            .map { it.toStation() }
    }

    /** JSON for durable storage; the counterpart of [restore]. */
    fun encode(): String = synchronized(this) {
        val stations = JSONArray()
        records.values.forEach { stations.put(it.toJson()) }
        JSONObject()
            .put(KEY_VERSION, JSON_VERSION)
            .put(KEY_STATIONS, stations)
            .toString()
    }

    /**
     * Loads records written by [encode]. Addresses already in the store win, because they carry
     * everything observed since this process started. Malformed entries are skipped.
     */
    fun restore(json: String?) {
        if (json.isNullOrBlank()) return
        synchronized(this) {
            val stations = runCatching { JSONObject(json).getJSONArray(KEY_STATIONS) }.getOrNull() ?: return
            var restored = 0
            for (index in 0 until stations.length()) {
                val record = runCatching { Record.fromJson(stations.getJSONObject(index)) }.getOrNull() ?: continue
                if (records.containsKey(record.macAddress)) continue
                records[record.macAddress] = record
                restored += 1
            }
            if (restored == 0) return
            generationCounter.incrementAndGet()
            evictLeastRecentlySeen()
        }
    }

    private fun evictLeastRecentlySeen() {
        if (records.size <= minOf(maxStations, maxRoadSideUnits)) return
        evictLeastRecentlySeen(roadSideUnits = false, limit = maxStations)
        evictLeastRecentlySeen(roadSideUnits = true, limit = maxRoadSideUnits)
    }

    private fun evictLeastRecentlySeen(roadSideUnits: Boolean, limit: Int) {
        val excess = records.values.count { it.roadSideUnit == roadSideUnits } - limit
        if (excess <= 0) return
        // One pass, also when seeding thousands of addresses at once. Ties go in the order of the map.
        records.values
            .filter { it.roadSideUnit == roadSideUnits }
            .sortedBy { it.lastSeenMs }
            .take(excess)
            .forEach { records.remove(it.macAddress) }
    }

    /**
     * Keeps only real coordinates: senders leave the GeoNetworking position vector zeroed and the
     * ITS ranges reserve their extreme values for "unavailable".
     */
    private fun isUsablePosition(latitudeE7: Int, longitudeE7: Int): Boolean =
        (latitudeE7 != 0 || longitudeE7 != 0) &&
            latitudeE7 > MIN_LATITUDE_E7 && latitudeE7 < MAX_LATITUDE_E7 &&
            longitudeE7 > MIN_LONGITUDE_E7 && longitudeE7 < MAX_LONGITUDE_E7

    /**
     * An address as [org.opentrafficmap.citstogo.protocol.Ieee80211Mac.sourceAddress] writes it,
     * neither zero nor a group address.
     */
    private fun isUnicastAddress(address: String): Boolean {
        val octets = address.split(':').map { it.toIntOrNull(16) ?: return false }
        return octets.size == MAC_ADDRESS_LEN && octets.all { it in 0..0xff } && octets.any { it != 0 } &&
            octets[0] and 0x01 == 0
    }

    /**
     * One address as it is accumulated and stored. Fields are added here in the same order the
     * discovery table shows them; [fromJson] tolerates records written by an older app, which also
     * carried the station ID, station type and packet total no column shows any more.
     */
    private class Record(val macAddress: String) {
        val typeCounts = LinkedHashMap<ItsPacketType, Int>()
        var firstSeenMs: Long = 0L
        var lastSeenMs: Long = 0L
        var lastRssiDbm: Int? = null
        var bestRssiDbm: Int? = null
        var latitudeE7: Int? = null
        var longitudeE7: Int? = null
        var positionTimeMs: Long = 0L
        var geoNetworkingStationType: Int? = null

        val roadSideUnit: Boolean
            get() = geoNetworkingStationType == DiscoveredStation.ROAD_SIDE_UNIT_STATION_TYPE

        fun seen(atMs: Long) {
            firstSeenMs = if (firstSeenMs == 0L) atMs else minOf(firstSeenMs, atMs)
            lastSeenMs = maxOf(lastSeenMs, atMs)
        }

        fun toStation(): DiscoveredStation = DiscoveredStation(
            macAddress = macAddress,
            firstSeenMs = firstSeenMs,
            lastSeenMs = lastSeenMs,
            typeCounts = typeCounts.toMap(),
            lastRssiDbm = lastRssiDbm,
            bestRssiDbm = bestRssiDbm,
            latitudeE7 = latitudeE7,
            longitudeE7 = longitudeE7,
            positionTimeMs = positionTimeMs,
            geoNetworkingStationType = geoNetworkingStationType,
        )

        fun toJson(): JSONObject = JSONObject()
            .put("mac", macAddress)
            .put("first", firstSeenMs)
            .put("last", lastSeenMs)
            .put("types", JSONObject().apply {
                typeCounts.forEach { (type, count) -> put(type.messageId.toString(), count) }
            })
            .put("lastRssi", lastRssiDbm ?: JSONObject.NULL)
            .put("bestRssi", bestRssiDbm ?: JSONObject.NULL)
            .put("lat", latitudeE7 ?: JSONObject.NULL)
            .put("lon", longitudeE7 ?: JSONObject.NULL)
            .put("positionTime", positionTimeMs)
            .put(KEY_GEONETWORKING_STATION_TYPE, geoNetworkingStationType ?: JSONObject.NULL)

        companion object {
            fun fromJson(json: JSONObject): Record {
                val mac = json.getString("mac")
                require(mac.isNotBlank()) { "Missing MAC address" }
                return Record(mac).apply {
                    firstSeenMs = json.optLong("first")
                    lastSeenMs = json.optLong("last")
                    json.optJSONObject("types")?.let { types ->
                        val keys = types.keys()
                        while (keys.hasNext()) {
                            val key = keys.next()
                            val type = ItsPacketType.fromMessageId(key.toIntOrNull() ?: continue) ?: continue
                            typeCounts[type] = types.getInt(key)
                        }
                    }
                    if (!json.isNull("lastRssi")) lastRssiDbm = json.getInt("lastRssi")
                    if (!json.isNull("bestRssi")) bestRssiDbm = json.getInt("bestRssi")
                    if (!json.isNull("lat") && !json.isNull("lon")) {
                        latitudeE7 = json.getInt("lat")
                        longitudeE7 = json.getInt("lon")
                    }
                    positionTimeMs = json.optLong("positionTime")
                    if (json.has(KEY_GEONETWORKING_STATION_TYPE) && !json.isNull(KEY_GEONETWORKING_STATION_TYPE)) {
                        geoNetworkingStationType = json.getInt(KEY_GEONETWORKING_STATION_TYPE)
                    }
                }
            }
        }
    }

    companion object {
        /** Enough addresses for a long drive through a city while bounding storage. */
        const val MAX_STATIONS = 500

        /** Road side units are kept apart from [MAX_STATIONS], so the map can show them from history. */
        const val MAX_ROAD_SIDE_UNITS = 500

        private const val MIN_LATITUDE_E7 = -900_000_000
        private const val MAX_LATITUDE_E7 = 900_000_000
        private const val MIN_LONGITUDE_E7 = -1_800_000_000
        private const val MAX_LONGITUDE_E7 = 1_800_000_000
        private const val MAC_ADDRESS_LEN = 6

        private const val JSON_VERSION = 1
        private const val KEY_VERSION = "version"
        private const val KEY_STATIONS = "stations"

        // Not "type": older versions stored the station type of the ITS payload there.
        private const val KEY_GEONETWORKING_STATION_TYPE = "gnStationType"
    }
}
