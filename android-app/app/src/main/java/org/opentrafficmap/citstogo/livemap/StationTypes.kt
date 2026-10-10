package org.opentrafficmap.citstogo.livemap

/** The station icons of the map, each as an arrow along the heading or as a dot without one. */
enum class StationIconGroup {
    Car,
    TruckOrBus,
    Tram,
    VulnerableRoadUser,
    SpecialVehicle,
    RoadSideUnit,
    Unknown;

    fun icon(arrow: Boolean): String = "station-${name.lowercase()}-${if (arrow) "arrow" else "dot"}"
}

/**
 * Names and icons of received station types, by their CDD code. Unlike [org.opentrafficmap.citstogo.cam.StationType],
 * which holds the types the app sends as, this covers every code.
 */
object StationTypes {
    private val labels = listOf(
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

    fun label(stationType: Int): String = labels.getOrNull(stationType) ?: "Unknown ($stationType)"

    fun iconGroup(stationType: Int): StationIconGroup = when (stationType) {
        5 -> StationIconGroup.Car
        6, 7, 8, 9 -> StationIconGroup.TruckOrBus
        11 -> StationIconGroup.Tram
        1, 2, 3, 4, 12, 13 -> StationIconGroup.VulnerableRoadUser
        10, 14 -> StationIconGroup.SpecialVehicle
        15 -> StationIconGroup.RoadSideUnit
        else -> StationIconGroup.Unknown
    }
}
