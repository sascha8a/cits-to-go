package org.opentrafficmap.citstogo.station

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StationDiscoveryStoreTest {
    private val store = StationDiscoveryStore()

    @Test
    fun foldsPacketsMessageTypesSignalsAndPositionIntoOneStation() {
        store.observe(
            MAC_A,
            observation(
                type = ItsPacketType.CAM,
                rssi = -80,
                latitude = 482_024_036,
                longitude = 163_691_773,
            ),
            1_000L,
        )
        store.observe(
            MAC_A,
            observation(type = ItsPacketType.CAM, rssi = -61, latitude = 482_024_100, longitude = 163_691_800),
            2_000L,
        )
        store.observe(MAC_A, observation(type = ItsPacketType.MAPEM, rssi = -70), 3_000L)

        val station = store.snapshot().single()
        assertEquals(1_000L, station.firstSeenMs)
        assertEquals(3_000L, station.lastSeenMs)
        assertEquals(
            mapOf(ItsPacketType.CAM to 2, ItsPacketType.MAPEM to 1),
            station.typeCounts,
        )
        assertEquals(-70, station.lastRssiDbm)
        assertEquals(-61, station.bestRssiDbm)
        assertEquals(482_024_100, station.latitudeE7)
        assertEquals(163_691_800, station.longitudeE7)
        assertEquals(2_000L, station.positionTimeMs)
    }

    @Test
    fun keepsTheLastPositionWhenLaterPacketsCarryNone() {
        store.observe(MAC_A, observation(type = ItsPacketType.CAM, latitude = 482_000_000, longitude = 163_000_000), 1_000L)
        store.observe(MAC_A, observation(type = ItsPacketType.CAM), 2_000L)

        val station = store.snapshot().single()
        assertEquals(482_000_000, station.latitudeE7)
        assertEquals(1_000L, station.positionTimeMs)
        assertEquals(2_000L, station.lastSeenMs)
    }

    @Test
    fun ignoresZeroedAndUnavailablePositionVectors() {
        store.observe(MAC_A, observation(type = ItsPacketType.CAM, latitude = 0, longitude = 0), 1_000L)
        store.observe(
            MAC_A,
            observation(type = ItsPacketType.CAM, latitude = 900_000_001, longitude = 1_800_000_001),
            2_000L,
        )

        val station = store.snapshot().single()
        assertNull(station.latitudeE7)
        assertNull(station.longitudeE7)
        assertEquals(0L, station.positionTimeMs)
    }

    @Test
    fun tracksStationsWhoseFramesItCannotDecode() {
        store.observe(MAC_A, observation(), 1_000L)

        val station = store.snapshot().single()
        assertTrue(station.typeCounts.isEmpty())
        assertNull(station.lastRssiDbm)
        assertNull(station.latitudeE7)
        assertEquals(1_000L, station.firstSeenMs)
        assertEquals(1_000L, station.lastSeenMs)
    }

    @Test
    fun keepsTheMostRecentStationsWithinTheCapacity() {
        val bounded = StationDiscoveryStore(maxStations = 3)
        listOf(MAC_A, MAC_B, MAC_C, MAC_D).forEachIndexed { index, mac ->
            bounded.observe(mac, observation(type = ItsPacketType.CAM), (index + 1) * 1_000L)
        }

        assertEquals(listOf(MAC_D, MAC_C, MAC_B), bounded.snapshot().map { it.macAddress })
    }

    @Test
    fun snapshotStartsWithTheMostRecentlyActiveStation() {
        store.observe(MAC_A, observation(type = ItsPacketType.CAM), 1_000L)
        store.observe(MAC_B, observation(type = ItsPacketType.CAM), 5_000L)

        assertEquals(listOf(MAC_B, MAC_A), store.snapshot().map { it.macAddress })
        assertEquals(listOf(MAC_B), store.snapshot(limit = 1).map { it.macAddress })
    }

    @Test
    fun seedsLegacyAddressesWithoutInventingActivity() {
        store.observe(MAC_A, observation(type = ItsPacketType.CAM), 4_000L)
        store.seedMacAddresses(listOf(MAC_A, MAC_B, ""))

        val stations = store.snapshot()
        assertEquals(2, stations.size)
        assertEquals(listOf(MAC_A, MAC_B), stations.map { it.macAddress })
        val legacy = stations.first { it.macAddress == MAC_B }
        assertTrue(legacy.typeCounts.isEmpty())
        assertNull(legacy.lastRssiDbm)
        assertEquals(0L, legacy.lastSeenMs)
        assertEquals(1, stations.first { it.macAddress == MAC_A }.typeCounts.getValue(ItsPacketType.CAM))
    }

    @Test
    fun snapshotSurvivesTheIntentSerializationItTravelsIn() {
        store.observe(
            MAC_A,
            observation(type = ItsPacketType.CAM, rssi = -61, latitude = 482_024_036, longitude = 163_691_773),
            2_000L,
        )
        store.observe(MAC_B, observation(type = ItsPacketType.MAPEM), 3_000L)
        store.seedMacAddresses(listOf(MAC_C))
        val table = DiscoveredStationList(store.snapshot())

        // The status broadcast writes this with ObjectOutputStream through Parcel.writeSerializable,
        // so every row and every field type it carries has to be serializable.
        val written = ByteArrayOutputStream()
        ObjectOutputStream(written).use { it.writeObject(table) }
        val restored = ObjectInputStream(ByteArrayInputStream(written.toByteArray())).use { it.readObject() }

        assertEquals(table, restored)
    }

    @Test
    fun restoresTheStoredTableAndSurvivesARoundTrip() {
        store.observe(
            MAC_A,
            observation(
                type = ItsPacketType.CAM,
                rssi = -66,
                latitude = 482_024_036,
                longitude = 163_691_773,
            ),
            1_000L,
        )
        store.observe(MAC_A, observation(type = ItsPacketType.SREM, rssi = -59), 2_000L)
        store.observe(MAC_B, observation(type = ItsPacketType.SPATEM, rssi = -71), 3_000L)
        store.seedMacAddresses(listOf(MAC_C))

        val restored = StationDiscoveryStore().apply { restore(store.encode()) }

        assertEquals(store.snapshot(), restored.snapshot())
        assertEquals(store.stationCount, restored.stationCount)
    }

    @Test
    fun readsTablesWrittenByAnOlderApp() {
        // Versions before the MAC/seen/position/message-count layout also stored a station ID, a
        // station type and a packet total; those fields are ignored, the rest of the row is not.
        val writtenByAnOlderApp = """
            {"version":1,"stations":[{"mac":"$MAC_A","ids":[[11,4],[12,1]],"type":15,"first":1000,"last":3000,
            "packets":9,"types":{"2":3,"5":6},"lastRssi":-70,"bestRssi":-55,"lat":482024036,"lon":163691773,
            "positionTime":2000}]}
        """.trimIndent()

        store.restore(writtenByAnOlderApp)

        val station = store.snapshot().single()
        assertEquals(MAC_A, station.macAddress)
        assertEquals(1_000L, station.firstSeenMs)
        assertEquals(3_000L, station.lastSeenMs)
        assertEquals(mapOf(ItsPacketType.CAM to 3, ItsPacketType.MAPEM to 6), station.typeCounts)
        assertEquals(-70, station.lastRssiDbm)
        assertEquals(-55, station.bestRssiDbm)
        assertEquals(482_024_036, station.latitudeE7)
        assertEquals(2_000L, station.positionTimeMs)
    }

    @Test
    fun restorePrefersWhatThisProcessAlreadyObserved() {
        store.observe(MAC_A, observation(type = ItsPacketType.CAM), 9_000L)
        val stored = StationDiscoveryStore().apply {
            observe(MAC_A, observation(type = ItsPacketType.MAPEM), 1_000L)
            observe(MAC_A, observation(type = ItsPacketType.MAPEM), 2_000L)
        }

        store.restore(stored.encode())

        val station = store.snapshot().single()
        assertEquals(mapOf(ItsPacketType.CAM to 1), station.typeCounts)
        assertEquals(9_000L, station.lastSeenMs)
    }

    @Test
    fun ignoresUnusableStoredTables() {
        store.restore(null)
        store.restore("   ")
        store.restore("not json at all")
        store.restore("""{"stations":[{"noMac":true},42]}""")

        assertEquals(0, store.stationCount)
    }

    @Test
    fun clearingResetsGenerationAndContent() {
        store.observe(MAC_A, observation(type = ItsPacketType.CAM), 1_000L)
        val generationBeforeClear = store.generation
        store.clear()

        assertTrue(generationBeforeClear > 0L)
        assertEquals(0, store.stationCount)
        assertTrue(store.snapshot().isEmpty())
        assertTrue(store.generation > generationBeforeClear)

        val empty = StationDiscoveryStore()
        empty.clear()
        assertEquals(0L, empty.generation)
    }

    private fun observation(
        type: ItsPacketType? = null,
        rssi: Int? = null,
        latitude: Int? = null,
        longitude: Int? = null,
    ) = StationObservation(
        packetType = type,
        rssiDbm = rssi,
        latitudeE7 = latitude,
        longitudeE7 = longitude,
    )

    private companion object {
        const val MAC_A = "02:00:00:00:00:0a"
        const val MAC_B = "02:00:00:00:00:0b"
        const val MAC_C = "02:00:00:00:00:0c"
        const val MAC_D = "02:00:00:00:00:0d"
    }
}
