package org.opentrafficmap.citstogo.livemap

import org.junit.Assert.assertEquals
import org.junit.Test

class MapStationTimeoutTest {
    @Test
    fun settingMatchesTheModel() {
        assertEquals(10, MapStationTimeout.DEFAULT_SECONDS)
        assertEquals(5..300, MapStationTimeout.RANGE_SECONDS)
    }

    @Test
    fun storedValuesAreLimitedToTheRange() {
        assertEquals(5, MapStationTimeout.clamp(Int.MIN_VALUE))
        assertEquals(5, MapStationTimeout.clamp(0))
        assertEquals(5, MapStationTimeout.clamp(5))
        assertEquals(42, MapStationTimeout.clamp(42))
        assertEquals(300, MapStationTimeout.clamp(300))
        assertEquals(300, MapStationTimeout.clamp(301))
        assertEquals(300, MapStationTimeout.clamp(Int.MAX_VALUE))
    }

    @Test
    fun numbersAreRoundedAndLimited() {
        assertEquals(10, MapStationTimeout.parse("10"))
        assertEquals(42, MapStationTimeout.parse(" 42 "))
        assertEquals(13, MapStationTimeout.parse("12.5"))
        assertEquals(5, MapStationTimeout.parse("4"))
        assertEquals(5, MapStationTimeout.parse("-30"))
        assertEquals(300, MapStationTimeout.parse("301"))
        assertEquals(300, MapStationTimeout.parse("99999999999999"))
    }

    @Test
    fun anythingElseIsTheDefault() {
        for (text in listOf("", "  ", "ten", "10s", "NaN", "Infinity", "-Infinity")) {
            assertEquals("'$text'", 10, MapStationTimeout.parse(text))
        }
    }
}
