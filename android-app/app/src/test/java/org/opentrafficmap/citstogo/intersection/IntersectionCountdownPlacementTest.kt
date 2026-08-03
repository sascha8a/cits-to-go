package org.opentrafficmap.citstogo.intersection

import org.junit.Assert.assertEquals
import org.junit.Test

class IntersectionCountdownPlacementTest {
    @Test
    fun longLaneKeepsCountdownCentered() {
        val offset = countdownSideOffset(100f, 0f, 100f, 30f, 20f, 12f, 5f)

        assertEquals(0f, offset.first, 0.001f)
        assertEquals(0f, offset.second, 0.001f)
    }

    @Test
    fun shortLaneMovesCountdownPerpendicularToLane() {
        val offset = countdownSideOffset(20f, 0f, 20f, 30f, 20f, 12f, 5f)

        assertEquals(0f, offset.first, 0.001f)
        assertEquals(21f, offset.second, 0.001f)
    }

    @Test
    fun zeroLengthLaneMovesCountdownAboveLane() {
        val offset = countdownSideOffset(0f, 0f, 0f, 30f, 20f, 12f, 5f)

        assertEquals(0f, offset.first, 0.001f)
        assertEquals(-21f, offset.second, 0.001f)
    }
}
