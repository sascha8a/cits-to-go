package org.opentrafficmap.citstogo

import org.junit.Assert.assertEquals
import org.junit.Test
import org.opentrafficmap.citstogo.srem.SremProfile

class VehicleProfileIconTest {
    @Test
    fun everyProfileHasTheIconOfItsOwnStationType() {
        val icons = SremProfile.entries.map { it.iconRes() }

        assertEquals(SremProfile.entries.size, icons.toSet().size)
        assertEquals(R.drawable.ic_station_type_passenger_car, SremProfile.PASSENGER_CAR.iconRes())
        assertEquals(R.drawable.ic_station_type_cyclist, SremProfile.BICYCLE.iconRes())
        assertEquals(R.drawable.ic_station_type_bus, SremProfile.PUBLIC_TRANSPORT_BUS.iconRes())
    }
}
