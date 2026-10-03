package org.opentrafficmap.citstogo.intersection

import kotlin.math.hypot

internal data class Float2(val x: Float, val y: Float) {
    fun distanceTo(other: Float2): Double = hypot(
        (x - other.x).toDouble(),
        (y - other.y).toDouble(),
    )
}

/**
 * Physical policy for the topology connector drawn between two MAPEM lanes that MAPEM says connect.
 *
 * * `gapCm <= [COINCIDENT_GAP_CM]`: the endpoints are effectively the same point; a connector is
 *   optional (drawn, but visually a no-op).
 * * `gapCm <= [MAX_CONNECTOR_GAP_CM]`: MAPEM describes the topology separately from the geometry, so
 *   a short connector is drawn to make the movement continuous.
 * * otherwise: the endpoints are too far apart to be a genuine topology gap; the lanes stay separate
 *   and the caller records a debug warning rather than hiding bad data behind a long bridge.
 */
internal enum class LaneConnectorKind { None, Coincident, Draw, TooFar }

internal data class LaneConnector(
    val kind: LaneConnectorKind,
    val start: Float2,
    val startAdjacent: Float2,
    val end: Float2,
    val endAdjacent: Float2,
    val gapCm: Double,
)

private const val COINCIDENT_GAP_CM = 25.0
private const val MAX_CONNECTOR_GAP_CM = 800.0

/**
 * Selects the closest endpoint pair across two decoded lanes and classifies the gap.
 *
 * Endpoint selection is done in the lane's centimetre coordinate space so it is independent of the
 * screen projection (a uniform scale with a single axis flip keeps the nearest pair nearest). A lane
 * can point either way, so the destination endpoint is chosen by proximity rather than by
 * `first()`/`last()`.
 */
internal fun laneConnector(source: MapLane, target: MapLane): LaneConnector? {
    val sourceEndpoints = laneEndpointPairs(source.nodes) ?: return null
    val targetEndpoints = laneEndpointPairs(target.nodes) ?: return null

    var best: LaneConnector? = null
    for ((start, startAdjacent) in sourceEndpoints) {
        for ((end, endAdjacent) in targetEndpoints) {
            val gap = start.distanceTo(end)
            val current = best
            if (current == null || gap < current.gapCm) {
                best = LaneConnector(
                    kind = connectorKind(gap),
                    start = start,
                    startAdjacent = startAdjacent,
                    end = end,
                    endAdjacent = endAdjacent,
                    gapCm = gap,
                )
            }
        }
    }
    return best
}

private fun connectorKind(gapCm: Double): LaneConnectorKind = when {
    gapCm <= COINCIDENT_GAP_CM -> LaneConnectorKind.Coincident
    gapCm <= MAX_CONNECTOR_GAP_CM -> LaneConnectorKind.Draw
    else -> LaneConnectorKind.TooFar
}

private fun laneEndpointPairs(nodes: List<LaneNode>): List<Pair<Float2, Float2>>? {
    if (nodes.size < 2) return null
    val first = Float2(nodes.first().xCm.toFloat(), nodes.first().yCm.toFloat())
    val second = Float2(nodes[1].xCm.toFloat(), nodes[1].yCm.toFloat())
    val last = Float2(nodes.last().xCm.toFloat(), nodes.last().yCm.toFloat())
    val penultimate = Float2(nodes[nodes.lastIndex - 1].xCm.toFloat(), nodes[nodes.lastIndex - 1].yCm.toFloat())
    return listOf(first to second, last to penultimate)
}

/** Samples a cubic Bézier into a polyline, used for both drawing and tap targets. */
internal fun cubicBezierPolyline(
    start: Float2,
    control1: Float2,
    control2: Float2,
    end: Float2,
    segments: Int = 24,
): List<Float2> {
    val count = segments.coerceAtLeast(1)
    return (0..count).map { step ->
        val t = step.toFloat() / count
        val mt = 1f - t
        val a = mt * mt * mt
        val b = 3f * mt * mt * t
        val c = 3f * mt * t * t
        val d = t * t * t
        Float2(
            x = a * start.x + b * control1.x + c * control2.x + d * end.x,
            y = a * start.y + b * control1.y + c * control2.y + d * end.y,
        )
    }
}

/** Shortest distance from [point] to the polyline through [polyline]. */
internal fun distanceToPolyline(point: Float2, polyline: List<Float2>): Double {
    if (polyline.isEmpty()) return Double.POSITIVE_INFINITY
    if (polyline.size == 1) return point.distanceTo(polyline[0])
    var best = Double.MAX_VALUE
    for (i in 0 until polyline.size - 1) {
        best = minOf(best, distanceToSegment(point, polyline[i], polyline[i + 1]))
    }
    return best
}

private fun distanceToSegment(point: Float2, start: Float2, end: Float2): Double {
    val dx = (end.x - start.x).toDouble()
    val dy = (end.y - start.y).toDouble()
    val lengthSquared = dx * dx + dy * dy
    if (lengthSquared <= 0.0001) return point.distanceTo(start)
    val t = (((point.x - start.x).toDouble() * dx + (point.y - start.y).toDouble() * dy) / lengthSquared).coerceIn(0.0, 1.0)
    return point.distanceTo(Float2((start.x + dx * t).toFloat(), (start.y + dy * t).toFloat()))
}

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
