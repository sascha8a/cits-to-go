package org.opentrafficmap.citstogo.station

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StationTableStateTest {
    private val now = 1_760_000_000_000L
    private val older = DiscoveredStation(
        macAddress = "02:00:00:00:00:01",
        firstSeenMs = now - 600_000L,
        lastSeenMs = now - 60_000L,
        typeCounts = mapOf(ItsPacketType.CAM to 40),
        lastRssiDbm = -80,
        bestRssiDbm = -74,
    )
    private val newest = DiscoveredStation(
        macAddress = "02:00:00:00:00:0a",
        firstSeenMs = now - 5_000L,
        lastSeenMs = now,
        typeCounts = mapOf(ItsPacketType.CAM to 1, ItsPacketType.SPATEM to 2),
        lastRssiDbm = -55,
        bestRssiDbm = -52,
        latitudeE7 = 482_024_036,
        longitudeE7 = 163_691_773,
        positionTimeMs = now,
    )
    private val legacy = DiscoveredStation(macAddress = "02:00:00:00:00:03")
    private val stations = listOf(older, newest, legacy)

    private val cam = columnFor(ItsPacketType.CAM)
    private val spatem = columnFor(ItsPacketType.SPATEM)

    @Test
    fun tableShowsTheAgreedColumnsLeftToRight() {
        assertEquals(
            listOf("mac_address", "last_seen", "first_seen", "position") +
                ItsPacketType.entries.map { "type_${it.name.lowercase(Locale.US)}" } +
                listOf("last_rssi", "best_rssi"),
            StationColumn.ordered.map { it.key },
        )
    }

    @Test
    fun defaultOrderIsMostRecentActivityFirst() {
        assertEquals(listOf(newest, older, legacy), StationTableState.DEFAULT.rows(stations, now))
        assertEquals(StationColumn.lastSeen, StationTableState.DEFAULT.sortColumn)
        assertTrue(StationTableState.DEFAULT.sortDescending)
    }

    @Test
    fun tappingAHeaderSortsByItAndTappingAgainReverses() {
        val byCamCount = StationTableState.DEFAULT.sortedBy(cam)
        assertEquals(cam, byCamCount.sortColumn)
        assertTrue(byCamCount.sortDescending)
        assertEquals(listOf(older, newest, legacy), byCamCount.rows(stations, now))

        val ascending = byCamCount.sortedBy(cam)
        assertFalse(ascending.sortDescending)
        assertEquals(listOf(legacy, newest, older), ascending.rows(stations, now))
    }

    @Test
    fun textColumnsStartAtAscendingAndNumbersAtDescending() {
        assertTrue(StationTableState.DEFAULT.sortedBy(StationColumn.macAddress).sortDescending.not())
        assertTrue(StationTableState.DEFAULT.sortedBy(StationColumn.firstSeen).sortDescending)
        assertTrue(StationTableState.DEFAULT.sortedBy(StationColumn.position).sortDescending)
    }

    @Test
    fun stationsWithoutTheSortedValueStayAtTheBottom() {
        val strongestFirst = StationTableState.DEFAULT.sortedBy(StationColumn.lastRssi)
        assertEquals(listOf(newest, older, legacy), strongestFirst.rows(stations, now))

        val weakestFirst = strongestFirst.sortedBy(StationColumn.lastRssi)
        assertEquals(listOf(older, newest, legacy), weakestFirst.rows(stations, now))
    }

    @Test
    fun thePositionColumnSortsByHowFreshThePositionIs() {
        assertEquals(
            listOf(newest, older, legacy),
            StationTableState.DEFAULT.sortedBy(StationColumn.position).rows(stations, now),
        )
    }

    @Test
    fun textFilterOffersContainsStartsWithAndExactIgnoringCase() {
        val contains = StationFilter.Text(query = "0:00:0A")
        assertEquals(listOf(newest), filtered(StationColumn.macAddress, contains).rows(stations, now))

        val startsWith = StationFilter.Text(TextMatchMode.StartsWith, "02:00:00:00:00:0")
        assertEquals(3, filtered(StationColumn.macAddress, startsWith).rows(stations, now).size)
        assertEquals(
            listOf(older),
            filtered(StationColumn.macAddress, StationFilter.Text(TextMatchMode.StartsWith, "02:00:00:00:00:01"))
                .rows(stations, now),
        )

        assertEquals(listOf(newest), filtered(StationColumn.macAddress, StationFilter.Text(TextMatchMode.Exact, "02:00:00:00:00:0A")).rows(stations, now))
        assertTrue(filtered(StationColumn.macAddress, StationFilter.Text(TextMatchMode.Exact, "02:00")).rows(stations, now).isEmpty())
    }

    @Test
    fun countAndSignalColumnsFilterTheirValueAsAnOpenOrClosedRange() {
        assertEquals(listOf(older), filtered(cam, StationFilter.NumberRange(min = 10)).rows(stations, now))
        assertEquals(
            listOf(newest, legacy),
            filtered(cam, StationFilter.NumberRange(max = 9)).rows(stations, now),
        )
        assertEquals(
            listOf(newest),
            filtered(StationColumn.lastRssi, StationFilter.NumberRange(min = -60)).rows(stations, now),
        )
    }

    @Test
    fun eachPacketTypeColumnFiltersItsOwnCounter() {
        assertEquals(
            listOf(newest),
            filtered(spatem, StationFilter.NumberRange(min = 2, max = 2)).rows(stations, now),
        )
        assertEquals(
            listOf(older, legacy),
            filtered(spatem, StationFilter.NumberRange(min = 0, max = 0)).rows(stations, now),
        )
    }

    @Test
    fun missingValuesAreRequestedExplicitly() {
        assertEquals(
            listOf(legacy),
            filtered(StationColumn.firstSeen, StationFilter.TimeRange(missingOnly = true)).rows(stations, now),
        )
        assertEquals(
            listOf(legacy),
            filtered(StationColumn.lastRssi, StationFilter.NumberRange(missingOnly = true)).rows(stations, now),
        )
    }

    @Test
    fun anAbsoluteTimeRangeKeepsOneSidedBounds() {
        assertEquals(
            listOf(newest),
            filtered(StationColumn.lastSeen, StationFilter.TimeRange(startMs = now - 30_000L)).rows(stations, now),
        )
        assertEquals(
            listOf(older),
            filtered(StationColumn.lastSeen, StationFilter.TimeRange(endMs = now - 30_000L)).rows(stations, now),
        )
    }

    @Test
    fun timeShortcutsFollowTheTickingClock() {
        val lastHour = filtered(StationColumn.lastSeen, StationFilter.TimeRange(shortcut = TimeShortcut.LastHour))
        assertEquals(listOf(newest, older), lastHour.rows(stations, now))
        assertTrue(lastHour.rows(stations, now + 7_200_000L).isEmpty())
    }

    @Test
    fun thePositionColumnHasNoFilter() {
        val state = StationTableState.DEFAULT.withFilter(
            StationColumn.position,
            StationFilter.Text(query = "16.36"),
        )
        assertTrue(state.filters.isEmpty())
        assertEquals(stations.size, state.rows(stations, now).size)
    }

    @Test
    fun filtersCombineAndAreRememberedPerColumn() {
        val state = StationTableState.DEFAULT
            .withFilter(StationColumn.macAddress, StationFilter.Text(query = "00:0"))
            .withFilter(cam, StationFilter.NumberRange(min = 21))
        assertEquals(listOf(older), state.rows(stations, now))
        assertEquals(2, state.filters.size)
    }

    @Test
    fun clearingAFilterRemovesItFromTheState() {
        val filteredState = StationTableState.DEFAULT.withFilter(cam, StationFilter.NumberRange(min = 10))
        assertEquals(StationFilter.NumberRange(min = 10), filteredState.filters[cam])

        assertTrue(filteredState.withoutFilter(cam).filters.isEmpty())
        assertTrue(filteredState.withFilter(cam, null).filters.isEmpty())
        assertTrue(filteredState.withFilter(cam, StationFilter.NumberRange()).filters.isEmpty())
    }

    @Test
    fun filterSummariesReadWellOnTheChips() {
        assertEquals("contains \"02:00\"", StationFilter.Text(query = "02:00").summary)
        assertEquals("at least 2", StationFilter.NumberRange(min = 2).summary)
        assertEquals("at most -60", StationFilter.NumberRange(max = -60).summary)
        assertEquals("3 – 9", StationFilter.NumberRange(min = 3, max = 9).summary)
        assertEquals("is 0", StationFilter.NumberRange(min = 0, max = 0).summary)
        assertEquals("Today", StationFilter.TimeRange(shortcut = TimeShortcut.Today).summary)
        assertEquals("never seen", StationFilter.TimeRange(missingOnly = true).summary)
    }

    @Test
    fun viewStateSurvivesEncodingAndDecoding() {
        val state = StationTableState.DEFAULT
            .sortedBy(StationColumn.firstSeen)
            .withFilter(StationColumn.macAddress, StationFilter.Text(TextMatchMode.StartsWith, "02:00"))
            .withFilter(StationColumn.lastRssi, StationFilter.NumberRange(min = -70))
            .withFilter(StationColumn.lastSeen, StationFilter.TimeRange(shortcut = TimeShortcut.Last24Hours))
            .withFilter(StationColumn.firstSeen, StationFilter.TimeRange(startMs = now - 86_400_000L, endMs = now))
            .withWidth(StationColumn.position, 220)

        val restored = StationTableState.decode(state.encode())

        assertEquals(state, restored)
    }

    @Test
    fun queriesFromTheFreeTextBoxesMigrateToTheDedicatedFilters() {
        val stored = """
            {"filters":{"mac_address":"0:00:0A","last_rssi":">=-70","type_cam":">20",
                        "first_seen":"—","position":"16.36","last_seen":"09:4"}}
        """.trimIndent()

        val restored = StationTableState.decode(stored)

        // A comparison became the equivalent bound, the dash the missing-value request; text that
        // no dedicated editor can express is gone, and so is the position nobody filters anymore.
        assertEquals(StationFilter.Text(query = "0:00:0A"), restored.filters[StationColumn.macAddress])
        assertEquals(StationFilter.NumberRange(min = -70), restored.filters[StationColumn.lastRssi])
        assertEquals(StationFilter.NumberRange(min = 21), restored.filters[cam])
        assertEquals(StationFilter.TimeRange(missingOnly = true), restored.filters[StationColumn.firstSeen])
        assertNull(restored.filters[StationColumn.position])
        assertNull(restored.filters[StationColumn.lastSeen])
    }

    @Test
    fun damagedOrUnknownStoredStateFallsBackToTheDefaults() {
        assertEquals(StationTableState.DEFAULT, StationTableState.decode(null))
        assertEquals(StationTableState.DEFAULT, StationTableState.decode("nonsense"))
        assertEquals(
            StationTableState.DEFAULT,
            StationTableState.decode("""{"sort":"who_knows","filters":{"who_knows":"x","last_seen":"  "}}"""),
        )
    }

    @Test
    fun viewStateSavedForADroppedColumnIsForgotten() {
        // Columns the table no longer shows: sorting, filtering and width must not come back for them.
        val retired = """
            {"sort":"station_id","descending":false,
             "filters":{"station_id":"4005","station_type":"tram","position":"48.2"},
             "widths":{"station_id":240,"packets":120,"last_rssi":110}}
        """.trimIndent()

        val restored = StationTableState.decode(retired)

        assertEquals(StationColumn.lastSeen, restored.sortColumn)
        assertTrue(restored.sortDescending)
        assertTrue(restored.filters.isEmpty())
        assertEquals(mapOf(StationColumn.lastRssi to 110), restored.widths)
    }

    @Test
    fun resetReturnsToTheDefaultView() {
        val used = StationTableState.DEFAULT
            .sortedBy(cam)
            .withFilter(StationColumn.macAddress, StationFilter.Text(query = "11"))
            .withWidth(StationColumn.position, 260)

        assertEquals(StationTableState.DEFAULT, used.resetView())
    }

    @Test
    fun draggedWidthsAreRememberedAndClamped() {
        val widened = StationTableState.DEFAULT.withWidth(StationColumn.position, 240)
        assertEquals(240, widened.widthOf(StationColumn.position))
        assertNull(StationTableState.DEFAULT.widthOf(StationColumn.position))

        val tooNarrow = widened.withWidth(StationColumn.position, 10)
        assertEquals(StationColumn.position.minWidthDp, tooNarrow.widthOf(StationColumn.position))
        val tooWide = widened.withWidth(cam, 5_000)
        assertEquals(StationColumn.MAX_WIDTH_DP, tooWide.widthOf(cam))

        // Sorting and filtering must not drop a hand-made width.
        assertEquals(240, widened.sortedBy(cam).widthOf(StationColumn.position))
        assertEquals(240, widened.withFilter(cam, StationFilter.NumberRange(min = 3)).widthOf(StationColumn.position))
        assertNull(widened.withoutWidth(StationColumn.position).widthOf(StationColumn.position))
    }

    @Test
    fun widthsSurviveEncodingAndDecodingAndResetClearsThem() {
        val widened = StationTableState.DEFAULT
            .withWidth(StationColumn.position, 240)
            .withWidth(StationColumn.macAddress, 190)

        val restored = StationTableState.decode(widened.encode())

        assertEquals(widened, restored)
        assertEquals(StationTableState.DEFAULT, restored.resetView())
    }

    @Test
    fun storedWidthsOfUnknownColumnsAreIgnored() {
        val restored = StationTableState.decode("""{"widths":{"who_knows":300,"best_rssi":120}}""")
        assertEquals(mapOf(StationColumn.bestRssi to 120), restored.widths)
    }

    @Test
    fun everyColumnIsSortableAndAllButThePositionIsFilterable() {
        assertEquals(ItsPacketType.entries.size, StationColumn.packetTypes.size)
        assertEquals(13, StationColumn.ordered.size)
        assertTrue(StationColumn.ordered.all { StationColumn.fromKey(it.key) == it })
        assertFalse(StationColumn.position.filterable)
        assertTrue(StationColumn.ordered.filter { it !== StationColumn.position }.all { it.filterable })
        StationColumn.ordered.forEach { column ->
            stations.forEach { station ->
                column.number(station)
                val cell = column.display(station)
                assertTrue("${column.title} needs cell text", cell.isNotBlank())
            }
        }
    }

    private fun filtered(column: StationColumn, filter: StationFilter): StationTableState =
        StationTableState.DEFAULT.withFilter(column, filter)

    private fun columnFor(type: ItsPacketType): StationColumn =
        requireNotNull(StationColumn.packetTypes.firstOrNull { it.packetType == type })
}
