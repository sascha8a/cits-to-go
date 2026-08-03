package org.opentrafficmap.citstogo.intersection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpatTimingTest {
    @Test
    fun expiredTimeMarkStopsAtZeroInsteadOfWrappingToNextHour() {
        val spat = spat(moy = 10, timestampMs = 35_800, receivedAtMs = 1_000L)
        val event = event(likelyTime = 358)

        assertEquals(0L, event.secondsUntilChange(spat, 1_000L))
        assertEquals(0L, event.secondsUntilChange(spat, 10_000L))
    }

    @Test
    fun countdownNeverBecomesNegativeAfterItExpires() {
        val spat = spat(moy = 10, timestampMs = 10_000, receivedAtMs = 1_000L)
        val event = event(likelyTime = 6_120)

        assertEquals(2L, event.secondsUntilChange(spat, 1_000L))
        assertEquals(0L, event.secondsUntilChange(spat, 4_000L))
    }

    @Test
    fun unavailableAndUnknownTimeMarksAreNotDisplayed() {
        val spat = spat(moy = 10, timestampMs = 10_000, receivedAtMs = 1_000L)

        assertNull(event(minEndTime = 36_000).secondsUntilChange(spat, 1_000L))
        assertNull(event(minEndTime = 36_001).secondsUntilChange(spat, 1_000L))
    }

    @Test
    fun validFallbackIsUsedWhenLikelyTimeIsUnknown() {
        val spat = spat(moy = 10, timestampMs = 10_000, receivedAtMs = 1_000L)
        val event = event(minEndTime = 6_120, likelyTime = 36_001)

        assertEquals(2L, event.secondsUntilChange(spat, 1_000L))
    }

    @Test
    fun genuineHourBoundaryRolloverIsPreserved() {
        val spat = spat(moy = 59, timestampMs = 59_000, receivedAtMs = 1_000L)
        val event = event(likelyTime = 20)

        assertEquals(3L, event.secondsUntilChange(spat, 1_000L))
    }

    private fun spat(moy: Int, timestampMs: Int, receivedAtMs: Long) = SpatIntersection(
        key = IntersectionKey(43, 1),
        revision = 1,
        moy = moy,
        timestampMs = timestampMs,
        movements = emptyList(),
        receivedAtMs = receivedAtMs,
    )

    private fun event(
        minEndTime: Int? = null,
        likelyTime: Int? = null,
        maxEndTime: Int? = null,
    ) = SignalEvent(
        state = MovementPhaseState.StopAndRemain,
        minEndTime = minEndTime,
        likelyTime = likelyTime,
        maxEndTime = maxEndTime,
        confidence = null,
    )
}
