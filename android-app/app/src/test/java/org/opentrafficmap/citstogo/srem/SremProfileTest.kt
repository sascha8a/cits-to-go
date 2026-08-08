package org.opentrafficmap.citstogo.srem

import org.junit.Assert.assertEquals
import org.junit.Test

class SremProfileTest {
    @Test
    fun preferenceCodesRoundTrip() {
        SremProfile.entries.forEach { profile ->
            assertEquals(profile, SremProfile.fromPreferenceCode(profile.preferenceCode))
        }
    }

    @Test
    fun unknownPreferenceFallsBackToPedestrian() {
        assertEquals(SremProfile.PEDESTRIAN, SremProfile.fromPreferenceCode(-1))
        assertEquals(SremProfile.PEDESTRIAN, SremProfile.fromPreferenceCode(999))
    }
}
