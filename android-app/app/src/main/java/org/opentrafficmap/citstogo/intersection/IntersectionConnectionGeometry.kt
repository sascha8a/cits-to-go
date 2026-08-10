package org.opentrafficmap.citstogo.intersection

import kotlin.math.hypot

internal data class RoadConnectionControlPoints(
    val startX: Float,
    val startY: Float,
    val endX: Float,
    val endY: Float,
)

internal fun roadConnectionControlPoints(
    startX: Float,
    startY: Float,
    startAdjacentX: Float,
    startAdjacentY: Float,
    endX: Float,
    endY: Float,
    endAdjacentX: Float,
    endAdjacentY: Float,
    maxControlDistance: Float,
): RoadConnectionControlPoints {
    val gapX = endX - startX
    val gapY = endY - startY
    val gapLength = hypot(gapX.toDouble(), gapY.toDouble()).toFloat()
    if (gapLength <= 0.001f) {
        return RoadConnectionControlPoints(startX, startY, endX, endY)
    }

    val controlDistance = (gapLength * 0.38f).coerceAtMost(maxControlDistance)
    val startDirection = outwardDirection(
        x = startX - startAdjacentX,
        y = startY - startAdjacentY,
        fallbackX = gapX,
        fallbackY = gapY,
    )
    val endDirection = outwardDirection(
        x = endX - endAdjacentX,
        y = endY - endAdjacentY,
        fallbackX = -gapX,
        fallbackY = -gapY,
    )
    return RoadConnectionControlPoints(
        startX = startX + startDirection.first * controlDistance,
        startY = startY + startDirection.second * controlDistance,
        endX = endX + endDirection.first * controlDistance,
        endY = endY + endDirection.second * controlDistance,
    )
}

private fun outwardDirection(
    x: Float,
    y: Float,
    fallbackX: Float,
    fallbackY: Float,
): Pair<Float, Float> {
    val fallbackLength = hypot(fallbackX.toDouble(), fallbackY.toDouble()).toFloat().coerceAtLeast(0.001f)
    val fallback = fallbackX / fallbackLength to fallbackY / fallbackLength
    val length = hypot(x.toDouble(), y.toDouble()).toFloat()
    if (length <= 0.001f) return fallback

    val direction = x / length to y / length
    val alignment = direction.first * fallback.first + direction.second * fallback.second
    return if (alignment < 0f) fallback else direction
}
