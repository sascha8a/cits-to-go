package org.opentrafficmap.citstogo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DevicePositionTest {
    private val walking = DevicePosition(
        latitudeE7 = 482_024_036,
        longitudeE7 = 163_691_773,
        accuracyM = 4f,
        speedMetersPerSecond = 1.4f,
        heading = 1_355,
        hasHeading = true,
        timeMs = 1_785_242_510_000L,
    )

    @Test
    fun movingPhoneHasAHeadingInDegrees() {
        assertEquals(135.5f, walking.movingHeadingDeg)
    }

    @Test
    fun standingPhoneOrMissingBearingHasNoHeading() {
        assertNull(walking.copy(speedMetersPerSecond = null).movingHeadingDeg)
        assertNull(walking.copy(heading = 0, hasHeading = false).movingHeadingDeg)
    }

    @Test
    fun northIsAHeadingToo() {
        assertEquals(0f, walking.copy(heading = 0).movingHeadingDeg)
    }
}
