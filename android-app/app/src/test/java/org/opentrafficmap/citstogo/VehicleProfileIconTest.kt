package org.opentrafficmap.citstogo

import org.junit.Assert.assertEquals
import org.junit.Test
import org.opentrafficmap.citstogo.srem.SremProfile

class VehicleProfileIconTest {
    @Test
    fun everyProfileHasTheIconOfItsOwnStationType() {
        assertEquals(
            mapOf(
                SremProfile.PEDESTRIAN to R.drawable.ic_station_type_pedestrian,
                SremProfile.BICYCLE to R.drawable.ic_station_type_cyclist,
                SremProfile.MOPED to R.drawable.ic_station_type_moped,
                SremProfile.MOTORCYCLE to R.drawable.ic_station_type_motorcycle,
                SremProfile.PASSENGER_CAR to R.drawable.ic_station_type_passenger_car,
                SremProfile.PUBLIC_TRANSPORT_BUS to R.drawable.ic_station_type_bus,
                SremProfile.LIGHT_TRUCK to R.drawable.ic_station_type_light_truck,
                SremProfile.HEAVY_TRUCK to R.drawable.ic_station_type_heavy_truck,
                SremProfile.SPECIAL_VEHICLE to R.drawable.ic_station_type_special_vehicles,
                SremProfile.TRAM to R.drawable.ic_station_type_tram,
            ),
            SremProfile.entries.associateWith { it.iconRes() },
        )
    }
}
