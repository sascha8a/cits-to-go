package org.opentrafficmap.citstogo.livemap

import org.junit.Assert.assertEquals
import org.junit.Test
import org.opentrafficmap.citstogo.station.DiscoveredStation
import org.opentrafficmap.citstogo.station.ItsPacketType

class MarkerDetailsTest {
    private val nowMs = 1_785_242_510_000L
    private val car = StationMarker(
        stationId = 0xFFFF_FFFEL,
        stationType = 5,
        typeLabel = "Passenger car",
        latitude = 48.2024036,
        longitude = 16.3691773,
        headingDeg = 359.6,
        speedMps = 13.89,
        trailer = false,
        icon = "station-car",
        ageMs = 2_400,
        stale = false,
    )
    private val roadworks = DenmMarker(
        id = "1021000/7",
        originatingStationId = 1_021_000,
        stationType = 15,
        stationTypeLabel = "Road-side unit",
        latitude = 48.1,
        longitude = 16.2,
        label = "Short-term stationary roadworks",
        icon = "denm-roadworks",
        causeCode = 3,
        subCauseCode = 4,
        informationQuality = 2,
        infrastructure = true,
        sinceDetectionMs = 4_000,
        sinceReceivedMs = 1_000,
        expiresAtMs = nowMs + 116_500,
    )

    @Test
    fun stationShowsIdTypeSpeedHeadingAndAge() {
        assertEquals(
            MarkerDetails(
                title = "Passenger car",
                rows = listOf(
                    "Station ID" to "4294967294",
                    "Speed" to "50 km/h",
                    "Heading" to "0°",
                    "Last seen" to "2 s ago",
                ),
            ),
            MarkerDetailsFormat.station(car),
        )
    }

    @Test
    fun stationWithoutHeadingOrSpeed() {
        val rows = MarkerDetailsFormat.station(car.copy(headingDeg = null, speedMps = null)).rows.toMap()

        assertEquals("Unavailable", rows["Speed"])
        assertEquals("Unavailable", rows["Heading"])
    }

    @Test
    fun infrastructureDenmShowsTheLastUpdate() {
        assertEquals(
            MarkerDetails(
                title = "Short-term stationary roadworks",
                rows = listOf(
                    "Cause" to "Roadworks (3)",
                    "Subcause" to "Short-term stationary roadworks (4)",
                    "Information quality" to "2 of 7",
                    "Sender" to "Road-side unit 1021000",
                    "Updated" to "4 s ago",
                    "Last received" to "1 s ago",
                    "Expires" to "in 1 min 56 s",
                ),
            ),
            MarkerDetailsFormat.denm(roadworks, nowMs),
        )
    }

    @Test
    fun vehicleDenmShowsItsDetection() {
        val jam = roadworks.copy(
            stationType = 5,
            stationTypeLabel = "Passenger car",
            label = "Traffic jam",
            causeCode = 1,
            subCauseCode = 0,
            informationQuality = 0,
            infrastructure = false,
            sinceDetectionMs = 65_000,
        )

        val rows = MarkerDetailsFormat.denm(jam, nowMs).rows.toMap()

        assertEquals("Traffic jam (1)", rows["Cause"])
        assertEquals("Unavailable (0)", rows["Subcause"])
        assertEquals("Unavailable", rows["Information quality"])
        assertEquals("1 min 5 s ago", rows["Detected"])
        assertEquals(null, rows["Updated"])
    }

    @Test
    fun unknownAndMissingCauses() {
        val unknown = MarkerDetailsFormat.denm(roadworks.copy(causeCode = 42, subCauseCode = 7), nowMs).rows.toMap()
        val unnamed = MarkerDetailsFormat.denm(roadworks.copy(subCauseCode = 99), nowMs).rows.toMap()
        val missing = MarkerDetailsFormat.denm(
            roadworks.copy(causeCode = null, subCauseCode = null, informationQuality = null),
            nowMs,
        ).rows.toMap()

        assertEquals("Unknown (42)", unknown["Cause"])
        assertEquals("Unknown (7)", unknown["Subcause"])
        assertEquals("Unknown (99)", unnamed["Subcause"])
        assertEquals("Unknown", missing["Cause"])
        assertEquals(null, missing["Subcause"])
        assertEquals(null, missing["Information quality"])
    }

    @Test
    fun roadSideUnitShowsAddressTimesMessagesAndSignal() {
        val unit = RoadSideUnitMarker(
            macAddress = "02:42:42:00:00:01",
            latitude = 48.1,
            longitude = 16.2,
            trafficLight = true,
            icon = RoadSideUnitHistory.TRAFFIC_LIGHT_ICON,
            firstSeenMs = nowMs - 3 * 86_400_000L - 7_200_000L,
            lastSeenMs = nowMs - 90_000L,
            messageCounts = mapOf(ItsPacketType.SPATEM to 1_200, ItsPacketType.MAPEM to 30, ItsPacketType.DENM to 2),
            lastRssiDbm = -71,
            bestRssiDbm = -58,
        )

        assertEquals(
            MarkerDetails(
                title = "Traffic light",
                rows = listOf(
                    "MAC address" to "02:42:42:00:00:01",
                    "Last heard" to "1 min 30 s ago",
                    "First heard" to "3 d 2 h ago",
                    "Messages" to "DENM 2, SPATEM 1200, MAPEM 30",
                    "RSSI" to "-71 dBm, best -58 dBm",
                ),
            ),
            MarkerDetailsFormat.roadSideUnit(unit, nowMs),
        )
        val known = MarkerDetailsFormat.roadSideUnit(
            unit.copy(trafficLight = false, firstSeenMs = 0L, messageCounts = emptyMap(), lastRssiDbm = null, bestRssiDbm = null),
            nowMs,
        )
        assertEquals("Road side unit", known.title)
        assertEquals("Unavailable", known.rows.toMap()["First heard"])
        assertEquals("No ITS messages", known.rows.toMap()["Messages"])
        assertEquals("Unavailable", known.rows.toMap()["RSSI"])
    }

    @Test
    fun discoveredStationIsNamedByItsGeonetworkingStationType() {
        val car = DiscoveredStation(
            macAddress = "02:42:42:00:00:05",
            firstSeenMs = nowMs - 600_000L,
            lastSeenMs = nowMs - 5_000L,
            typeCounts = mapOf(ItsPacketType.CAM to 12),
            lastRssiDbm = -80,
            bestRssiDbm = -70,
            latitudeE7 = 481_234_567,
            longitudeE7 = 162_345_678,
            geoNetworkingStationType = 5,
        )

        assertEquals(
            MarkerDetails(
                title = "Passenger car",
                rows = listOf(
                    "MAC address" to "02:42:42:00:00:05",
                    "Last heard" to "5 s ago",
                    "First heard" to "10 min 0 s ago",
                    "Messages" to "CAM 12",
                    "RSSI" to "-80 dBm, best -70 dBm",
                ),
            ),
            MarkerDetailsFormat.discoveredStation(car, nowMs),
        )
        val title = { station: DiscoveredStation -> MarkerDetailsFormat.discoveredStation(station, nowMs).title }
        assertEquals("Station", title(car.copy(geoNetworkingStationType = null)))
        assertEquals("Road side unit", title(car.copy(geoNetworkingStationType = 15, typeCounts = emptyMap())))
        assertEquals("Traffic light", title(car.copy(geoNetworkingStationType = 15, typeCounts = mapOf(ItsPacketType.SPATEM to 1))))
    }

    @Test
    fun durationsGrowTheirUnits() {
        assertEquals("0 s", MarkerDetailsFormat.duration(-1_000))
        assertEquals("0 s", MarkerDetailsFormat.duration(999))
        assertEquals("59 s", MarkerDetailsFormat.duration(59_999))
        assertEquals("1 min 0 s", MarkerDetailsFormat.duration(60_000))
        assertEquals("59 min 59 s", MarkerDetailsFormat.duration(3_599_999))
        assertEquals("1 h 0 min", MarkerDetailsFormat.duration(3_600_000))
        assertEquals("23 h 59 min", MarkerDetailsFormat.duration(86_399_999))
        assertEquals("1 d 0 h", MarkerDetailsFormat.duration(86_400_000))
        assertEquals("1 d 1 h", MarkerDetailsFormat.duration(90_060_000))
        assertEquals("400 d 23 h", MarkerDetailsFormat.duration(34_646_399_000))
    }
}
