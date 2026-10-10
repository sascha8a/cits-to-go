package org.opentrafficmap.citstogo.livemap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveMapModelTest {
    private val nowMs = 1_785_242_510_000L
    private val car = LiveMapStation(
        stationId = 0x8000_0001L,
        stationType = 5,
        roadSideUnit = false,
        latitude = 482_024_036,
        longitude = -163_691_773,
        heading = 1_355,
        speedCms = 1_389,
        receivedAtMs = nowMs - 1_000,
    )
    private val jam = LiveMapDenm(
        originatingStationId = 0x8000_0001L,
        sequenceNumber = 4_711,
        stationType = 5,
        latitude = 482_024_036,
        longitude = 163_691_773,
        causeCode = 1,
        subCauseCode = 0,
        informationQuality = 1,
        detectedAtMs = nowMs - 5_000,
        receivedAtMs = nowMs - 1_000,
        expiresAtMs = nowMs + 55_000,
    )

    @Test
    fun stationIsShownUntilTheTimeout() {
        for (timeoutMs in listOf(5_000L, 10_000L, 300_000L)) {
            assertEquals("$timeoutMs", 1, stationsAtAge(timeoutMs, timeoutMs).size)
            assertEquals("$timeoutMs", 0, stationsAtAge(timeoutMs + 1, timeoutMs).size)
        }
    }

    @Test
    fun timeoutIsLimitedToTheSettingRange() {
        assertEquals(1, stationsAtAge(5_000, timeoutMs = 1_000).size)
        assertEquals(0, stationsAtAge(5_001, timeoutMs = 1_000).size)
        assertEquals(1, stationsAtAge(300_000, timeoutMs = 3_600_000).size)
        assertEquals(0, stationsAtAge(300_001, timeoutMs = 3_600_000).size)
    }

    @Test
    fun stationTurnsStaleAfterThreeSeconds() {
        assertFalse(stationsAtAge(2_999).single().stale)
        assertTrue(stationsAtAge(3_000).single().stale)
    }

    @Test
    fun roadSideUnitsStayAtLeastThirtySeconds() {
        // What counts is the road side unit container of the CAM, not the station type.
        val roadSideUnit = car.copy(roadSideUnit = true, heading = null, speedCms = null)
        val roadSideUnitStationType = car.copy(stationType = 15)

        assertEquals(1, stationsAtAge(30_000, 10_000, roadSideUnit).size)
        assertEquals(0, stationsAtAge(30_001, 10_000, roadSideUnit).size)
        assertEquals(1, stationsAtAge(60_000, 60_000, roadSideUnit).size)
        assertEquals(0, stationsAtAge(60_001, 60_000, roadSideUnit).size)
        assertEquals(0, stationsAtAge(10_001, 10_000, roadSideUnitStationType).size)
    }

    @Test
    fun ageIsNeverNegative() {
        val station = stationsAtAge(-5_000).single()

        assertEquals(0, station.ageMs)
        assertFalse(station.stale)
    }

    @Test
    fun stationIsInDisplayUnits() {
        val station = stationsAtAge(1_000).single()

        assertEquals(
            StationMarker(
                stationId = 0x8000_0001L,
                stationType = 5,
                typeLabel = "Passenger car",
                latitude = 48.2024036,
                longitude = -16.3691773,
                headingDeg = 135.5,
                speedMps = 13.89,
                icon = "station-car-arrow",
                ageMs = 1_000,
                stale = false,
            ),
            station,
        )
    }

    @Test
    fun stationWithoutHeadingIsADot() {
        val dot = car.copy(stationType = 42, heading = null, speedCms = null)

        val station = stationsAtAge(1_000, station = dot).single()

        assertEquals(null, station.headingDeg)
        assertEquals(null, station.speedMps)
        assertEquals("station-unknown-dot", station.icon)
        assertEquals("Unknown (42)", station.typeLabel)
    }

    @Test
    fun denmIsShownUntilItExpires() {
        assertEquals(1, denmsAt(jam.expiresAtMs - 1).size)
        assertEquals(0, denmsAt(jam.expiresAtMs).size)
    }

    @Test
    fun denmIsReadyForDisplay() {
        assertEquals(
            DenmMarker(
                id = "2147483649/4711",
                originatingStationId = 0x8000_0001L,
                stationType = 5,
                stationTypeLabel = "Passenger car",
                latitude = 48.2024036,
                longitude = 16.3691773,
                label = "Traffic jam",
                icon = "denm-trafficjam",
                causeCode = 1,
                subCauseCode = 0,
                informationQuality = 1,
                infrastructure = false,
                sinceDetectionMs = 5_000,
                sinceReceivedMs = 1_000,
                expiresAtMs = nowMs + 55_000,
            ),
            denmsAt(nowMs).single(),
        )
    }

    @Test
    fun infrastructureUpdatesTheDetectionTime() {
        for (stationType in 0..255) {
            val denm = denmsAt(nowMs, jam.copy(stationType = stationType)).single()
            assertEquals("$stationType", stationType in setOf(9, 10, 15), denm.infrastructure)
        }
    }

    @Test
    fun unknownAndMissingCausesHaveFallbacks() {
        val unknown = denmsAt(nowMs, jam.copy(causeCode = 42, subCauseCode = 7)).single()
        val missing = denmsAt(nowMs, jam.copy(causeCode = null, subCauseCode = null)).single()

        assertEquals("Cause 42 / sub 7", unknown.label)
        assertEquals("denm-hazard", unknown.icon)
        assertEquals("Unknown cause", missing.label)
        assertEquals("denm-hazard", missing.icon)
    }

    @Test
    fun denmAgesAreNeverNegative() {
        val denm = denmsAt(nowMs, jam.copy(detectedAtMs = nowMs + 2_000, receivedAtMs = nowMs + 1_000)).single()

        assertEquals(0, denm.sinceDetectionMs)
        assertEquals(0, denm.sinceReceivedMs)
    }

    private fun stationsAtAge(
        ageMs: Long,
        timeoutMs: Long = LiveMapModel.DEFAULT_STATION_TIMEOUT_MS,
        station: LiveMapStation = car,
    ): List<StationMarker> =
        LiveMapModel.build(LiveMapSnapshot(listOf(station), emptyList()), station.receivedAtMs + ageMs, timeoutMs)
            .stations

    private fun denmsAt(nowMs: Long, denm: LiveMapDenm = jam): List<DenmMarker> =
        LiveMapModel.build(LiveMapSnapshot(emptyList(), listOf(denm)), nowMs, LiveMapModel.DEFAULT_STATION_TIMEOUT_MS)
            .denms
}
