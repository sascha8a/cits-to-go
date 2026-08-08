package org.opentrafficmap.citstogo.srem

import org.opentrafficmap.citstogo.cam.StationType

enum class SremProfile(
    val preferenceCode: Int,
    val displayName: String,
    val stationType: StationType,
    val basicVehicleRole: BasicVehicleRole,
    val fallbackSpeedMetersPerSecond: Float,
    val hasTransitStatus: Boolean = false,
) {
    PEDESTRIAN(1, "Pedestrian", StationType.PEDESTRIAN, BasicVehicleRole.root(20), 1.4f),
    BICYCLE(2, "Bicycle", StationType.CYCLIST, BasicVehicleRole.root(19), 4.2f),
    MOPED(3, "Moped", StationType.MOPED, BasicVehicleRole.root(10), 8.3f),
    MOTORCYCLE(4, "Motorcycle", StationType.MOTORCYCLE, BasicVehicleRole.root(10), 8.3f),
    PASSENGER_CAR(5, "Passenger car", StationType.PASSENGER_CAR, BasicVehicleRole.root(0), 8.3f),
    PUBLIC_TRANSPORT_BUS(6, "Public transport bus", StationType.BUS, BasicVehicleRole.root(1), 5.6f, true),
    LIGHT_TRUCK(7, "Light truck", StationType.LIGHT_TRUCK, BasicVehicleRole.root(9), 8.3f),
    HEAVY_TRUCK(8, "Heavy truck", StationType.HEAVY_TRUCK, BasicVehicleRole.root(9), 8.3f),
    SPECIAL_VEHICLE(10, "Special vehicle", StationType.SPECIAL_VEHICLE, BasicVehicleRole.root(2), 8.3f),
    TRAM(11, "Tram", StationType.TRAM, BasicVehicleRole.extension(0), 5.6f, true),
    ;

    companion object {
        fun fromPreferenceCode(code: Int): SremProfile =
            entries.firstOrNull { it.preferenceCode == code } ?: PEDESTRIAN
    }
}

class BasicVehicleRole private constructor(
    val value: Int,
    val isExtension: Boolean,
) {
    companion object {
        fun root(value: Int) = BasicVehicleRole(value.also { require(it in 0..22) }, false)

        fun extension(index: Int) = BasicVehicleRole(index.also { require(it >= 0) }, true)
    }
}
