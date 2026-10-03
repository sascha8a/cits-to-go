package org.opentrafficmap.citstogo.intersection

import org.opentrafficmap.citstogo.srem.SremProfile

/**
 * A decoded MAPEM lane is selectable for a signal request only when its lane type matches the
 * vehicle type configured in Settings. Incompatible lanes stay visible for orientation but never
 * enter the SREM lane-selection state.
 */
internal fun LaneType.isSelectableFor(profile: SremProfile): Boolean = when (profile) {
    SremProfile.PEDESTRIAN -> this == LaneType.Crosswalk || this == LaneType.Sidewalk
    SremProfile.BICYCLE -> this == LaneType.Bike
    SremProfile.TRAM -> this == LaneType.TrackedVehicle
    else -> this == LaneType.Vehicle
}

internal fun MapLane.isSelectableFor(profile: SremProfile): Boolean = laneType.isSelectableFor(profile)

internal fun selectableLaneIds(map: MapIntersection, profile: SremProfile): Set<Int> =
    map.lanes.asSequence()
        .filter { it.isSelectableFor(profile) }
        .map { it.id }
        .toSet()

internal fun intersectionLaneSelectionAlpha(
    laneId: Int,
    selectedLaneIds: Collection<Int>,
    selectableLaneIds: Set<Int>,
): Float {
    if (selectedLaneIds.isEmpty()) return 1f
    return if (laneId in selectedLaneIds || laneId in selectableLaneIds) 1f else 0.2f
}

/**
 * Decides whether the topology connector between two MAPEM lanes is drawn.
 *
 * MAPEM lane geometry and lane topology are independent: two lanes can be joined by a `connectsTo`
 * relationship even when their polylines stop short of each other and even when the intersection has
 * no matching SPATEM (so the connection is not signalized). Rendering only signalized movements left
 * intersections such as Wiedner Hauptstraße – Resselgasse looking disconnected, so every resolvable
 * MAPEM connection is drawn; signalization and the current selection only change how strongly a
 * connector is emphasized, never whether the lanes are joined.
 */
internal fun intersectionConnectionVisible(
    laneId: Int,
    connectedLaneId: Int,
    selectedLaneIds: Collection<Int>,
): Boolean = when (selectedLaneIds.size) {
    0 -> true
    1 -> laneId in selectedLaneIds || connectedLaneId in selectedLaneIds
    else -> laneId in selectedLaneIds && connectedLaneId in selectedLaneIds
}

internal fun connectedSremLaneIds(map: MapIntersection, laneId: Int): Set<Int> {
    val lane = map.lanes.firstOrNull { it.id == laneId } ?: return emptySet()
    return map.lanes.asSequence()
        .filter { it.id != laneId }
        .filter { candidate ->
            lane.connections.any { it.remoteIntersection == null && it.laneId == candidate.id } ||
                candidate.connections.any { it.remoteIntersection == null && it.laneId == laneId }
        }
        .map { it.id }
        .toSet()
}

/**
 * Lanes reachable from [laneId] through MAPEM connectivity that are also compatible with the given
 * vehicle type. Used to constrain both the second lane tap and the rendered connection tap targets
 * so a mixed-mode request (for example a pedestrian inbound lane with a tram outbound lane) cannot
 * be assembled.
 */
internal fun connectedSremLaneIds(
    map: MapIntersection,
    laneId: Int,
    profile: SremProfile,
): Set<Int> {
    val lane = map.lanes.firstOrNull { it.id == laneId } ?: return emptySet()
    if (!lane.isSelectableFor(profile)) return emptySet()
    return connectedSremLaneIds(map, laneId).filter { id ->
        map.lanes.firstOrNull { it.id == id }?.isSelectableFor(profile) == true
    }.toSet()
}

internal fun resolveSremLaneDirection(map: MapIntersection, firstLaneId: Int, secondLaneId: Int): List<Int> {
    val lanes = map.lanes.associateBy { it.id }
    val first = lanes[firstLaneId] ?: return listOf(firstLaneId, secondLaneId)
    val second = lanes[secondLaneId] ?: return listOf(firstLaneId, secondLaneId)
    val firstToSecond = first.connections.any { it.remoteIntersection == null && it.laneId == secondLaneId }
    val secondToFirst = second.connections.any { it.remoteIntersection == null && it.laneId == firstLaneId }
    return when {
        firstToSecond && !secondToFirst -> listOf(firstLaneId, secondLaneId)
        secondToFirst && !firstToSecond -> listOf(secondLaneId, firstLaneId)
        first.ingress && !first.egress && second.egress && !second.ingress -> listOf(firstLaneId, secondLaneId)
        second.ingress && !second.egress && first.egress && !first.ingress -> listOf(secondLaneId, firstLaneId)
        else -> listOf(firstLaneId, secondLaneId)
    }
}

internal fun MapLane.directionLabel(): String = when {
    ingress && egress -> "Inbound and outbound"
    ingress -> "Inbound"
    egress -> "Outbound"
    else -> "Direction unavailable"
}
