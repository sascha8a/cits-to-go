package org.opentrafficmap.citstogo.cam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StationTypeTest {
    @Test
    fun selectableTypesAreOnlyPedestrianAndBicycle() {
        assertEquals(listOf(StationType.PEDESTRIAN, StationType.CYCLIST), StationType.selectable)
        assertTrue(StationType.PEDESTRIAN in StationType.selectable)
        assertTrue(StationType.CYCLIST in StationType.selectable)
        assertFalse(StationType.PASSENGER_CAR in StationType.selectable)
    }

    @Test
    fun nonSelectableCodesFallBackToPedestrian() {
        assertEquals(StationType.CYCLIST, StationType.selectableFromCode(StationType.CYCLIST.code))
        assertEquals(StationType.PEDESTRIAN, StationType.selectableFromCode(StationType.PASSENGER_CAR.code))
        assertEquals(StationType.PEDESTRIAN, StationType.selectableFromCode(255))
    }
}
