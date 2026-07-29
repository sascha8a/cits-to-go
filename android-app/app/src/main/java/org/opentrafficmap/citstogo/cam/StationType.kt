package org.opentrafficmap.citstogo.cam

enum class StationType(val code: Int, val displayName: String) {
    PEDESTRIAN(1, "Pedestrian"),
    CYCLIST(2, "Bicycle"),
    MOPED(3, "Moped"),
    MOTORCYCLE(4, "Motorcycle"),
    PASSENGER_CAR(5, "Passenger car"),
    BUS(6, "Bus"),
    LIGHT_TRUCK(7, "Light truck"),
    HEAVY_TRUCK(8, "Heavy truck"),
    TRAILER(9, "Trailer"),
    SPECIAL_VEHICLE(10, "Special vehicle"),
    TRAM(11, "Tram"),
    ROAD_SIDE_UNIT(15, "Road-side unit");

    companion object {
        val selectable: List<StationType> = listOf(PEDESTRIAN, CYCLIST)

        fun fromCode(code: Int): StationType =
            entries.firstOrNull { it.code == code } ?: PEDESTRIAN

        fun selectableFromCode(code: Int): StationType {
            val stationType = fromCode(code)
            return if (stationType in selectable) stationType else PEDESTRIAN
        }
    }
}
