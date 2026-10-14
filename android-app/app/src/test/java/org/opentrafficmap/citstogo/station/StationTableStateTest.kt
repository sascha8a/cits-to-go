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
        macAddress = "02:00:00:00:00:02",
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
        assertEquals(listOf(newest, older, legacy), StationTableState.DEFAULT.rows(stations))
        assertEquals(StationColumn.lastSeen, StationTableState.DEFAULT.sortColumn)
        assertTrue(StationTableState.DEFAULT.sortDescending)
    }

    @Test
    fun tappingAHeaderSortsByItAndTappingAgainReverses() {
        val byCamCount = StationTableState.DEFAULT.sortedBy(cam)
        assertEquals(cam, byCamCount.sortColumn)
        assertTrue(byCamCount.sortDescending)
        assertEquals(listOf(older, newest, legacy), byCamCount.rows(stations))

        val ascending = byCamCount.sortedBy(cam)
        assertFalse(ascending.sortDescending)
        assertEquals(listOf(legacy, newest, older), ascending.rows(stations))
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
        assertEquals(listOf(newest, older, legacy), strongestFirst.rows(stations))

        val weakestFirst = strongestFirst.sortedBy(StationColumn.lastRssi)
        assertEquals(listOf(older, newest, legacy), weakestFirst.rows(stations))
    }

    @Test
    fun thePositionColumnSortsByHowFreshThePositionIs() {
        assertEquals(
            listOf(newest, older, legacy),
            StationTableState.DEFAULT.sortedBy(StationColumn.position).rows(stations),
        )
    }

    @Test
    fun macFilterMatchesTextCaseInsensitively() {
        val state = StationTableState.DEFAULT.withFilter(StationColumn.macAddress, "0:00:02")
        assertEquals(listOf(newest), state.rows(stations))
    }

    @Test
    fun numericFiltersSupportComparisons() {
        assertEquals(listOf(older), StationTableState.DEFAULT.withFilter(cam, ">=10").rows(stations))
        assertEquals(listOf(newest, legacy), StationTableState.DEFAULT.withFilter(cam, "<10").rows(stations))
        assertEquals(listOf(newest), StationTableState.DEFAULT.withFilter(StationColumn.lastRssi, ">=-60").rows(stations))
    }

    @Test
    fun eachPacketTypeColumnFiltersItsOwnCounter() {
        assertEquals(listOf(newest), StationTableState.DEFAULT.withFilter(spatem, "=2").rows(stations))
        assertEquals(listOf(older, legacy), StationTableState.DEFAULT.withFilter(spatem, "=0").rows(stations))
    }

    @Test
    fun missingValuesCanBeFilteredWithTheDash() {
        val state = StationTableState.DEFAULT.withFilter(StationColumn.firstSeen, StationColumn.MISSING)
        assertEquals(listOf(legacy), state.rows(stations))
    }

    @Test
    fun positionFilterMatchesEitherCoordinate() {
        assertEquals(listOf(newest), StationTableState.DEFAULT.withFilter(StationColumn.position, "16.36").rows(stations))
    }

    @Test
    fun filtersCombineAndAreRememberedPerColumn() {
        val state = StationTableState.DEFAULT
            .withFilter(StationColumn.macAddress, "00:0")
            .withFilter(cam, ">20")
        assertEquals(listOf(older), state.rows(stations))
        assertEquals(2, state.filters.size)
    }

    @Test
    fun clearingAFilterRemovesItFromTheState() {
        val filtered = StationTableState.DEFAULT.withFilter(cam, "  10 ")
        assertEquals("10", filtered.filters[cam])

        assertTrue(filtered.withoutFilter(cam).filters.isEmpty())
        assertTrue(filtered.withFilter(cam, "").filters.isEmpty())
    }

    @Test
    fun viewStateSurvivesEncodingAndDecoding() {
        val state = StationTableState.DEFAULT
            .sortedBy(StationColumn.firstSeen)
            .withFilter(StationColumn.macAddress, "02:00")
            .withFilter(StationColumn.lastRssi, ">=-70")
            .withWidth(StationColumn.position, 220)

        val restored = StationTableState.decode(state.encode())

        assertEquals(state, restored)
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
        assertEquals(mapOf(StationColumn.position to "48.2"), restored.filters)
        assertEquals(mapOf(StationColumn.lastRssi to 110), restored.widths)
    }

    @Test
    fun resetReturnsToTheDefaultView() {
        val used = StationTableState.DEFAULT
            .sortedBy(cam)
            .withFilter(StationColumn.macAddress, "11")
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
        assertEquals(240, widened.withFilter(cam, ">2").widthOf(StationColumn.position))
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
    fun everyColumnIsSortableAndFilterable() {
        assertEquals(ItsPacketType.entries.size, StationColumn.packetTypes.size)
        assertEquals(13, StationColumn.ordered.size)
        assertTrue(StationColumn.ordered.all { StationColumn.fromKey(it.key) == it })
        StationColumn.ordered.forEach { column ->
            assertTrue("${column.title} needs a filter hint", column.filterHint.isNotBlank())
            stations.forEach { station ->
                column.number(station)
                val cell = column.display(station)
                assertTrue("${column.title} needs cell text", cell.isNotBlank())
                if (cell != StationColumn.MISSING) {
                    // A filter matches exactly the text in the cell, so the first word of a cell has
                    // to find that row again.
                    val probe = cell.substringBefore(' ').takeIf { it.length >= 2 } ?: cell
                    assertTrue(
                        "${column.title} should match the text it shows",
                        column.matches(station, probe),
                    )
                }
            }
        }
    }

    private fun columnFor(type: ItsPacketType): StationColumn =
        requireNotNull(StationColumn.packetTypes.firstOrNull { it.packetType == type })
}
