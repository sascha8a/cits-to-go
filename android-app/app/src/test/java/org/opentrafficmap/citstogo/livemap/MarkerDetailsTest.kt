package org.opentrafficmap.citstogo.livemap

import org.junit.Assert.assertEquals
import org.junit.Test

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
        icon = "station-car-arrow",
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
    fun durationsGrowTheirUnits() {
        assertEquals("0 s", MarkerDetailsFormat.duration(-1_000))
        assertEquals("0 s", MarkerDetailsFormat.duration(999))
        assertEquals("59 s", MarkerDetailsFormat.duration(59_999))
        assertEquals("1 min 0 s", MarkerDetailsFormat.duration(60_000))
        assertEquals("59 min 59 s", MarkerDetailsFormat.duration(3_599_999))
        assertEquals("1 h 0 min", MarkerDetailsFormat.duration(3_600_000))
        assertEquals("25 h 1 min", MarkerDetailsFormat.duration(90_060_000))
    }
}
