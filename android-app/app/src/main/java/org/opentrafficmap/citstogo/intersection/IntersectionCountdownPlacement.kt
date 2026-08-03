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
