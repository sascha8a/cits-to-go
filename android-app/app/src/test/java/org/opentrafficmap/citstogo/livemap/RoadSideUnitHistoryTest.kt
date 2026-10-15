package org.opentrafficmap.citstogo.livemap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.opentrafficmap.citstogo.station.DiscoveredStation
import org.opentrafficmap.citstogo.station.ItsPacketType

class RoadSideUnitHistoryTest {
    private val nowMs = 1_785_242_510_000L
    private val trafficLight = DiscoveredStation(
        macAddress = "02:42:42:00:00:01",
        firstSeenMs = nowMs - 10 * DAY_MS,
        lastSeenMs = nowMs - 2 * DAY_MS,
        typeCounts = mapOf(ItsPacketType.MAPEM to 30, ItsPacketType.DENM to 1),
        lastRssiDbm = -71,
        bestRssiDbm = -58,
        latitudeE7 = 481_234_567,
        longitudeE7 = 162_345_678,
        positionTimeMs = nowMs - 2 * DAY_MS,
        geoNetworkingStationType = 15,
    )
    private val beaconOnly = trafficLight.copy(
        macAddress = "02:42:42:00:00:02",
        typeCounts = emptyMap(),
        lastSeenMs = nowMs - 1_000L,
    )

    @Test
    fun showsRoadSideUnitsWithAPositionAtThatPosition() {
        val markers = RoadSideUnitHistory.build(listOf(trafficLight, beaconOnly), nowMs, MapHistoryDays.ALL)

        assertEquals(
            RoadSideUnitMarker(
                macAddress = "02:42:42:00:00:01",
                latitude = 48.1234567,
                longitude = 16.2345678,
                trafficLight = true,
                icon = RoadSideUnitHistory.TRAFFIC_LIGHT_ICON,
                firstSeenMs = nowMs - 10 * DAY_MS,
                lastSeenMs = nowMs - 2 * DAY_MS,
                messageCounts = mapOf(ItsPacketType.MAPEM to 30, ItsPacketType.DENM to 1),
                lastRssiDbm = -71,
                bestRssiDbm = -58,
            ),
            markers[0],
        )
        assertFalse(markers[1].trafficLight)
        assertEquals(RoadSideUnitHistory.ROAD_SIDE_UNIT_ICON, markers[1].icon)
    }

    @Test
    fun anyIntersectionMessageMakesATrafficLight() {
        for (type in listOf(ItsPacketType.MAPEM, ItsPacketType.SPATEM, ItsPacketType.SSEM)) {
            val station = beaconOnly.copy(typeCounts = mapOf(type to 1))
            assertTrue("$type", RoadSideUnitHistory.build(listOf(station), nowMs, MapHistoryDays.ALL).single().trafficLight)
        }
        for (type in listOf(ItsPacketType.CAM, ItsPacketType.DENM, ItsPacketType.IVIM, ItsPacketType.SREM)) {
            val station = beaconOnly.copy(typeCounts = mapOf(type to 1))
            assertFalse("$type", RoadSideUnitHistory.build(listOf(station), nowMs, MapHistoryDays.ALL).single().trafficLight)
        }
    }

    @Test
    fun leavesOutOtherStationsAndStationsWithoutPosition() {
        val stations = listOf(
            trafficLight.copy(geoNetworkingStationType = 5),
            trafficLight.copy(geoNetworkingStationType = null),
            trafficLight.copy(latitudeE7 = null),
            trafficLight.copy(longitudeE7 = null),
        )

        assertTrue(RoadSideUnitHistory.build(stations, nowMs, MapHistoryDays.ALL).isEmpty())
    }

    @Test
    fun limitsTheHistoryToTheLastDays() {
        val atLimit = trafficLight.copy(lastSeenMs = nowMs - 2 * DAY_MS)
        val beyond = trafficLight.copy(macAddress = "02:42:42:00:00:03", lastSeenMs = nowMs - 2 * DAY_MS - 1)

        assertEquals(
            listOf(atLimit.macAddress),
            RoadSideUnitHistory.build(listOf(atLimit, beyond), nowMs, 2).map { it.macAddress },
        )
        assertEquals(2, RoadSideUnitHistory.build(listOf(atLimit, beyond), nowMs, MapHistoryDays.ALL).size)
        assertEquals(2, RoadSideUnitHistory.build(listOf(atLimit, beyond), nowMs, 3).size)
    }

    private companion object {
        const val DAY_MS = 86_400_000L
    }
}
