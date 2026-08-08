package org.opentrafficmap.citstogo.srem

import org.junit.Assert.assertEquals
import org.junit.Test

class SremEtaTest {
    @Test
    fun nearbyLaneUsesCurrentTime() {
        assertEquals(1_000L, estimateSremRequestTimeMs(1_000L, 49.9, null, SremProfile.PEDESTRIAN))
    }

    @Test
    fun reliableSpeedCalculatesArrivalTime() {
        assertEquals(11_000L, estimateSremRequestTimeMs(1_000L, 100.0, 10f, SremProfile.PASSENGER_CAR))
    }

    @Test
    fun unavailableSpeedUsesProfileFallbackAndBoundsLeadTime() {
        assertEquals(61_000L, estimateSremRequestTimeMs(1_000L, 10_000.0, null, SremProfile.PEDESTRIAN))
    }
}
