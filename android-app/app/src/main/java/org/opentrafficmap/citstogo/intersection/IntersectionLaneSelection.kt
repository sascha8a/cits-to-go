package org.opentrafficmap.citstogo.intersection

internal fun intersectionLaneSelectionAlpha(
    laneId: Int,
    selectedLaneIds: Collection<Int>,
    selectableLaneIds: Set<Int>,
): Float {
    if (selectedLaneIds.isEmpty()) return 1f
    return if (laneId in selectedLaneIds || laneId in selectableLaneIds) 1f else 0.2f
}

internal fun intersectionConnectionSelectionAlpha(
    laneId: Int,
    connectedLaneId: Int,
    selectedLaneIds: Collection<Int>,
): Float {
    if (selectedLaneIds.isEmpty()) return 1f
    return if (laneId in selectedLaneIds || connectedLaneId in selectedLaneIds) 1f else 0.2f
}
