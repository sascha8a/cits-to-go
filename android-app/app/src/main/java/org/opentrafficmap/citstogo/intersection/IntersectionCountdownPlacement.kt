package org.opentrafficmap.citstogo.intersection

import kotlin.math.hypot

internal fun countdownSideOffset(
    directionX: Float,
    directionY: Float,
    laneLength: Float,
    labelWidth: Float,
    labelHeight: Float,
    laneWidth: Float,
    gap: Float,
): Pair<Float, Float> {
    if (laneLength >= labelWidth + gap * 2f) return 0f to 0f

    val offsetDistance = laneWidth / 2f + labelHeight / 2f + gap
    val directionLength = hypot(directionX.toDouble(), directionY.toDouble()).toFloat()
    if (directionLength <= 0.001f) return 0f to -offsetDistance
    return -directionY / directionLength * offsetDistance to
        directionX / directionLength * offsetDistance
}

internal data class CountdownLaneRepresentative(
    val signalGroup: Int,
    val lane: MapLane,
)

internal fun countdownLaneRepresentatives(
    lanes: List<MapLane>,
    availableSignalGroups: Set<Int>,
    selectedLaneIds: Set<Int>,
    selectableLaneIds: Set<Int>,
): List<CountdownLaneRepresentative> {
    fun priority(lane: MapLane): Int = when (lane.id) {
        in selectedLaneIds -> 0
        in selectableLaneIds -> 1
        else -> 2
    }

    return lanes
        .flatMap { lane ->
            lane.connections.mapNotNull { connection ->
                connection.signalGroup
                    ?.takeIf { it in availableSignalGroups }
                    ?.let { signalGroup -> signalGroup to lane }
            }
        }
        .groupBy({ it.first }, { it.second })
        .map { (signalGroup, candidates) ->
            CountdownLaneRepresentative(
                signalGroup = signalGroup,
                lane = candidates.minWith(compareBy<MapLane> { priority(it) }.thenBy { it.id }),
            )
        }
        .sortedWith(compareBy({ priority(it.lane) }, CountdownLaneRepresentative::signalGroup))
}

internal fun countdownSignalGroupsForSelection(
    lanes: List<MapLane>,
    selectedLaneIds: List<Int>,
    availableSignalGroups: Set<Int>,
): Set<Int> {
    val selectedLaneId = selectedLaneIds.firstOrNull() ?: return emptySet()
    val pairedLaneId = selectedLaneIds.getOrNull(1)
    return lanes.asSequence()
        .filter { lane ->
            if (pairedLaneId == null) {
                lane.id == selectedLaneId || lane.connections.any { it.laneId == selectedLaneId }
            } else {
                lane.id == selectedLaneId || lane.id == pairedLaneId
            }
        }
        .flatMap { lane ->
            lane.connections.asSequence().filter { connection ->
                connection.remoteIntersection == null &&
                    if (pairedLaneId == null) {
                        lane.id == selectedLaneId || connection.laneId == selectedLaneId
                    } else {
                        (lane.id == selectedLaneId && connection.laneId == pairedLaneId) ||
                            (lane.id == pairedLaneId && connection.laneId == selectedLaneId)
                    }
            }
        }
        .mapNotNull { it.signalGroup }
        .filter { it in availableSignalGroups }
        .toSet()
}

internal data class CountdownLabelBounds(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
)

internal fun placeCountdownLabel(
    preferredX: Float,
    preferredY: Float,
    labelWidth: Float,
    labelHeight: Float,
    viewportWidth: Float,
    viewportHeight: Float,
    occupied: List<CountdownLabelBounds>,
    gap: Float,
): CountdownLabelBounds? {
    val horizontalStep = labelWidth + gap
    val verticalStep = labelHeight + gap
    val offsets = listOf(
        0f to 0f,
        0f to -verticalStep,
        0f to verticalStep,
        -horizontalStep to 0f,
        horizontalStep to 0f,
        -horizontalStep to -verticalStep,
        horizontalStep to -verticalStep,
        -horizontalStep to verticalStep,
        horizontalStep to verticalStep,
    )
    return offsets.firstNotNullOfOrNull { (offsetX, offsetY) ->
        val centerX = preferredX + offsetX
        val centerY = preferredY + offsetY
        val bounds = CountdownLabelBounds(
            left = centerX - labelWidth / 2f,
            top = centerY - labelHeight / 2f,
            right = centerX + labelWidth / 2f,
            bottom = centerY + labelHeight / 2f,
        )
        bounds.takeIf {
            it.left >= 0f && it.top >= 0f &&
                it.right <= viewportWidth && it.bottom <= viewportHeight &&
                occupied.none { other -> bounds.overlaps(other, gap) }
        }
    }
}

private fun CountdownLabelBounds.overlaps(other: CountdownLabelBounds, gap: Float): Boolean =
    left < other.right + gap && right > other.left - gap &&
        top < other.bottom + gap && bottom > other.top - gap
