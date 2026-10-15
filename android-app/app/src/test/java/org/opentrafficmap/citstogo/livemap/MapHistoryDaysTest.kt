package org.opentrafficmap.citstogo.livemap

import org.junit.Assert.assertEquals
import org.junit.Test

class MapHistoryDaysTest {
    @Test
    fun storedValuesAreLimitedToTheRange() {
        assertEquals(0, MapHistoryDays.clamp(Int.MIN_VALUE))
        assertEquals(0, MapHistoryDays.clamp(-1))
        assertEquals(0, MapHistoryDays.clamp(0))
        assertEquals(7, MapHistoryDays.clamp(7))
        assertEquals(3_650, MapHistoryDays.clamp(3_650))
        assertEquals(3_650, MapHistoryDays.clamp(Int.MAX_VALUE))
    }

    @Test
    fun numbersAreRoundedAndLimited() {
        assertEquals(7, MapHistoryDays.parse("7"))
        assertEquals(30, MapHistoryDays.parse(" 30 "))
        assertEquals(2, MapHistoryDays.parse("1.5"))
        assertEquals(0, MapHistoryDays.parse("0"))
        assertEquals(0, MapHistoryDays.parse("-3"))
        assertEquals(3_650, MapHistoryDays.parse("99999999999999"))
    }

    @Test
    fun anythingElseShowsAll() {
        for (text in listOf("", "  ", "seven", "7 days", "NaN", "Infinity", "-Infinity")) {
            assertEquals("'$text'", MapHistoryDays.ALL, MapHistoryDays.parse(text))
        }
    }
}
