package org.opentrafficmap.citstogo.intersection

import java.io.Serializable
import kotlin.math.hypot

data class IntersectionSnapshot(
    val map: MapIntersection?,
    val spat: SpatIntersection?,
    val source: SelectionSource,
    val updatedAtMs: Long,
) : Serializable {
    val available: Boolean get() = map != null || spat != null
}

data class IntersectionSnapshotList(
    val snapshots: List<IntersectionSnapshot>,
) : Serializable

enum class SelectionSource : Serializable {
    DeviceLocation,
    LatestObserved,
}

data class IntersectionKey(
    val region: Int?,
    val id: Int,
) : Serializable {
    override fun toString(): String = if (region == null) id.toString() else "$region/$id"
}

data class MapIntersection(
    val key: IntersectionKey,
    val name: String?,
    val revision: Int,
    val latitude: Int,
    val longitude: Int,
    val laneWidthCm: Int?,
    val lanes: List<MapLane>,
    val receivedAtMs: Long,
) : Serializable {
    fun distanceTo(latitudeE7: Int, longitudeE7: Int): Double {
        val latMeters = (latitude - latitudeE7) * 0.011132
        val lonMeters = (longitude - longitudeE7) * 0.011132 *
            kotlin.math.cos(Math.toRadians(latitude / 10_000_000.0))
        return hypot(latMeters, lonMeters)
    }
}

data class MapLane(
    val id: Int,
    val ingressApproach: Int?,
    val egressApproach: Int?,
    val laneType: LaneType,
    val ingress: Boolean,
    val egress: Boolean,
    val nodes: List<LaneNode>,
    val connections: List<LaneConnection>,
) : Serializable

data class LaneNode(
    val xCm: Int,
    val yCm: Int,
    val stopLine: Boolean = false,
    val widthDeltaCm: Int? = null,
) : Serializable

data class LaneConnection(
    val laneId: Int,
    val signalGroup: Int?,
    val connectionId: Int?,
) : Serializable

enum class LaneType : Serializable {
    Vehicle,
    Crosswalk,
    Bike,
    Sidewalk,
    Median,
    Striping,
    TrackedVehicle,
    Parking,
    Other,
}

data class SpatIntersection(
    val key: IntersectionKey,
    val revision: Int,
    val moy: Int?,
    val timestampMs: Int?,
    val movements: List<SignalMovement>,
    val receivedAtMs: Long,
) : Serializable {
    val movementsBySignalGroup: Map<Int, SignalMovement> get() = movements.associateBy { it.signalGroup }
}

data class SignalMovement(
    val signalGroup: Int,
    val events: List<SignalEvent>,
    val connectionIds: List<Int>,
) : Serializable {
    val currentEvent: SignalEvent? get() = events.firstOrNull()
}

data class SignalEvent(
    val state: MovementPhaseState,
    val minEndTime: Int?,
    val likelyTime: Int?,
    val maxEndTime: Int?,
    val confidence: Int?,
) : Serializable

enum class MovementPhaseState(val code: Int, val label: String) : Serializable {
    Unavailable(0, "Unavailable"),
    Dark(1, "Dark"),
    StopThenProceed(2, "Stop then proceed"),
    StopAndRemain(3, "Stop"),
    PreMovement(4, "Prepare"),
    PermissiveAllowed(5, "Permissive"),
    ProtectedAllowed(6, "Protected"),
    PermissiveClearance(7, "Clearance"),
    ProtectedClearance(8, "Clearance"),
    CautionConflictingTraffic(9, "Caution"),
    Unknown(-1, "Unknown");

    companion object {
        fun fromCode(code: Int): MovementPhaseState = entries.firstOrNull { it.code == code } ?: Unknown
    }
}
