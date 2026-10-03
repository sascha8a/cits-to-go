package org.opentrafficmap.citstogo.intersection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IntersectionConnectionGeometryTest {
    @Test
    fun straightConnectionFollowsLaneTangents() {
        val controls = roadConnectionControlPoints(
            startX = 0f,
            startY = 0f,
            startAdjacentX = -10f,
            startAdjacentY = 0f,
            endX = 100f,
            endY = 0f,
            endAdjacentX = 110f,
            endAdjacentY = 0f,
            maxControlDistance = 100f,
        )

        assertEquals(38f, controls.startX, 0.001f)
        assertEquals(0f, controls.startY, 0.001f)
        assertEquals(62f, controls.endX, 0.001f)
        assertEquals(0f, controls.endY, 0.001f)
    }

    @Test
    fun turningConnectionPreservesBothApproachDirections() {
        val controls = roadConnectionControlPoints(
            startX = 0f,
            startY = 0f,
            startAdjacentX = -10f,
            startAdjacentY = 0f,
            endX = 100f,
            endY = 100f,
            endAdjacentX = 100f,
            endAdjacentY = 110f,
            maxControlDistance = 200f,
        )

        assertEquals(53.74f, controls.startX, 0.01f)
        assertEquals(0f, controls.startY, 0.001f)
        assertEquals(100f, controls.endX, 0.001f)
        assertEquals(46.26f, controls.endY, 0.01f)
    }

    @Test
    fun tangentPointingAwayFromJunctionFallsBackTowardDestination() {
        val controls = roadConnectionControlPoints(
            startX = 0f,
            startY = 0f,
            startAdjacentX = 10f,
            startAdjacentY = 0f,
            endX = 100f,
            endY = 0f,
            endAdjacentX = 90f,
            endAdjacentY = 0f,
            maxControlDistance = 100f,
        )

        assertEquals(38f, controls.startX, 0.001f)
        assertEquals(62f, controls.endX, 0.001f)
    }

    @Test
    fun controlDistanceIsClampedForWideGeometryGaps() {
        val controls = roadConnectionControlPoints(
            startX = 0f,
            startY = 0f,
            startAdjacentX = -10f,
            startAdjacentY = 0f,
            endX = 1000f,
            endY = 0f,
            endAdjacentX = 1010f,
            endAdjacentY = 0f,
            maxControlDistance = 80f,
        )

        assertEquals(80f, controls.startX, 0.001f)
        assertEquals(920f, controls.endX, 0.001f)
    }

    @Test
    fun laneConnectorJoinsTheNearestEndpointPair() {
        val source = lane(listOf(LaneNode(0, 0), LaneNode(0, 500)))
        val target = lane(listOf(LaneNode(0, 540), LaneNode(0, 1_040)))

        val connector = requireNotNull(laneConnector(source, target))

        assertEquals(LaneConnectorKind.Draw, connector.kind)
        assertEquals(40.0, connector.gapCm, 0.001)
        // Chosen by proximity, not by first()/last(): source tail to target head.
        assertEquals(Float2(0f, 500f), connector.start)
        assertEquals(Float2(0f, 540f), connector.end)
        // Adjacent nodes preserve the lane tangent direction for the Bézier control points.
        assertEquals(Float2(0f, 0f), connector.startAdjacent)
        assertEquals(Float2(0f, 1_040f), connector.endAdjacent)
    }

    @Test
    fun laneConnectorSelectsFlippedDestinationEndpointWhenNearer() {
        // Target lane runs "backwards" relative to the source; its last node is the closer endpoint.
        val source = lane(listOf(LaneNode(0, 0), LaneNode(0, 500)))
        val target = lane(listOf(LaneNode(0, 1_040), LaneNode(0, 520)))

        val connector = requireNotNull(laneConnector(source, target))

        assertEquals(Float2(0f, 520f), connector.end)
        assertEquals(20.0, connector.gapCm, 0.001)
        assertEquals(LaneConnectorKind.Coincident, connector.kind)
    }

    @Test
    fun laneConnectorReportsTooFarBeyondThePhysicalThreshold() {
        val source = lane(listOf(LaneNode(0, 0), LaneNode(0, 500)))
        val target = lane(listOf(LaneNode(0, 2_000), LaneNode(0, 2_500))) // 15 m gap

        assertEquals(LaneConnectorKind.TooFar, requireNotNull(laneConnector(source, target)).kind)
    }

    @Test
    fun bezierPolylineSharesTheDrawnEndpoints() {
        val start = Float2(0f, 0f)
        val end = Float2(100f, 50f)
        val polyline = cubicBezierPolyline(
            start = start,
            control1 = Float2(30f, 0f),
            control2 = Float2(70f, 50f),
            end = end,
        )

        assertEquals(start, polyline.first())
        assertEquals(end, polyline.last())
        assertTrue(polyline.size > 2)
    }

    @Test
    fun distanceToPolylineMeasuresPerpendicularDistance() {
        val polyline = listOf(Float2(0f, 0f), Float2(0f, 100f))

        assertEquals(10.0, distanceToPolyline(Float2(10f, 50f), polyline), 0.001)
        assertEquals(0.0, distanceToPolyline(Float2(0f, 50f), polyline), 0.001)
    }

    @Test
    fun connectorGeometryIgnoresLaneStyleType() {
        // A dashed bike lane and a solid vehicle lane built from identical nodes must produce an
        // identical connector: styling changes stroke appearance only, never the topology geometry.
        val nodes = listOf(LaneNode(0, 0), LaneNode(0, 500))
        val vehicle = laneWith(nodes, LaneType.Vehicle)
        val bike = laneWith(nodes, LaneType.Bike)
        val target = lane(listOf(LaneNode(0, 520), LaneNode(0, 1_020)))

        assertEquals(laneConnector(vehicle, target), laneConnector(bike, target))
    }

    private fun lane(nodes: List<LaneNode>) = laneWith(nodes, LaneType.Vehicle)

    private fun laneWith(nodes: List<LaneNode>, type: LaneType) = MapLane(
        id = 1,
        ingressApproach = null,
        egressApproach = null,
        laneType = type,
        ingress = true,
        egress = false,
        nodes = nodes,
        connections = emptyList(),
    )
}
