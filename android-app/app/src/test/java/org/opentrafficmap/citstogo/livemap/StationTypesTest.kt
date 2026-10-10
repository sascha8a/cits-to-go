package org.opentrafficmap.citstogo.livemap

import org.junit.Assert.assertEquals
import org.junit.Test

class StationTypesTest {
    @Test
    fun cddStationTypesHaveNames() {
        val names = listOf(
            "Unknown",
            "Pedestrian",
            "Cyclist",
            "Moped",
            "Motorcycle",
            "Passenger car",
            "Bus",
            "Light truck",
            "Heavy truck",
            "Trailer",
            "Special vehicle",
            "Tram",
            "Light VRU vehicle",
            "Animal",
            "Agricultural vehicle",
            "Road-side unit",
        )

        assertEquals(names, (0..15).map(StationTypes::label))
        assertEquals("Unknown (16)", StationTypes.label(16))
        assertEquals("Unknown (255)", StationTypes.label(255))
    }

    @Test
    fun iconGroupsFollowTheStationType() {
        val groups = mapOf(
            1 to StationIconGroup.VulnerableRoadUser,
            2 to StationIconGroup.VulnerableRoadUser,
            3 to StationIconGroup.VulnerableRoadUser,
            4 to StationIconGroup.VulnerableRoadUser,
            5 to StationIconGroup.Car,
            6 to StationIconGroup.TruckOrBus,
            7 to StationIconGroup.TruckOrBus,
            8 to StationIconGroup.TruckOrBus,
            9 to StationIconGroup.TruckOrBus,
            10 to StationIconGroup.SpecialVehicle,
            11 to StationIconGroup.Tram,
            12 to StationIconGroup.VulnerableRoadUser,
            13 to StationIconGroup.VulnerableRoadUser,
            14 to StationIconGroup.SpecialVehicle,
            15 to StationIconGroup.RoadSideUnit,
        )

        for (stationType in 0..255) {
            val expected = groups[stationType] ?: StationIconGroup.Unknown
            assertEquals("$stationType", expected, StationTypes.iconGroup(stationType))
        }
        val icons = StationIconGroup.entries.flatMap { listOf(it.icon(arrow = true), it.icon(arrow = false)) }
        assertEquals(icons.size, icons.toSet().size)
    }
}
