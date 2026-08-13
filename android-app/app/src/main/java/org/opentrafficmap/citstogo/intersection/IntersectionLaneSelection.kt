package org.opentrafficmap.citstogo.intersection

internal fun intersectionLaneSelectionAlpha(
    laneId: Int,
    selectedLaneIds: Collection<Int>,
    selectableLaneIds: Set<Int>,
): Float {
    if (selectedLaneIds.isEmpty()) return 1f
    return if (laneId in selectedLaneIds || laneId in selectableLaneIds) 1f else 0.2f
}

internal fun intersectionConnectionVisible(
    laneId: Int,
    connectedLaneId: Int,
    signalized: Boolean,
    alwaysVisible: Boolean = false,
    selectedLaneIds: Collection<Int>,
): Boolean = when (selectedLaneIds.size) {
    0 -> signalized || alwaysVisible
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
