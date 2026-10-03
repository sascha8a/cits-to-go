package org.opentrafficmap.citstogo.intersection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.opentrafficmap.citstogo.srem.SremIdentity
import org.opentrafficmap.citstogo.srem.SremPosition
import org.opentrafficmap.citstogo.srem.SremProfile
import org.opentrafficmap.citstogo.srem.SremRequest
import org.opentrafficmap.citstogo.srem.SremUperEncoder

/**
 * Regression tests for the two supported movement-selection paths (Minor change 2) and the
 * vehicle-type lane filtering (Minor change 1), asserting both interactions converge on the same
 * canonical movement and the same on-air SREM payload.
 */
class IntersectionMovementSelectionTest {
    private val profile = SremProfile.PASSENGER_CAR

    private val inbound = lane(
        id = 10,
        type = LaneType.Vehicle,
        ingress = true,
        nodes = listOf(LaneNode(0, 0), LaneNode(0, 500)),
        connections = listOf(
            LaneConnection(laneId = 20, signalGroup = 3, connectionId = 7, remoteIntersection = null),
            LaneConnection(laneId = 30, signalGroup = 4, connectionId = 8, remoteIntersection = null),
            LaneConnection(laneId = 40, signalGroup = 5, connectionId = 9, remoteIntersection = null),
            LaneConnection(laneId = 999, signalGroup = 6, connectionId = 10, remoteIntersection = null), // unresolved target
        ),
    )
    private val outbound = lane(
        id = 20,
        type = LaneType.Vehicle,
        egress = true,
        nodes = listOf(LaneNode(0, 540), LaneNode(0, 1_040)),
    )
    private val otherOutbound = lane(
        id = 30,
        type = LaneType.Vehicle,
        egress = true,
        nodes = listOf(LaneNode(800, 540), LaneNode(800, 1_040)),
    )
    private val tramOutbound = lane(
        id = 40,
        type = LaneType.TrackedVehicle,
        egress = true,
        nodes = listOf(LaneNode(-800, 540), LaneNode(-800, 1_040)),
    )

    private val map = map(listOf(inbound, outbound, otherOutbound, tramOutbound))

    @Test
    fun lanePairAndConnectionPathsResolveTheSameMovement() {
        val viaLane = movementByLanePair(map, profile, inbound.id, outbound.id)
        val connection = inbound.connections.first { it.laneId == 20 }
        val viaConnection = movementByConnection(map, profile, inbound.id, connection)

        assertEquals(viaLane, viaConnection)
        assertEquals(SelectedMovement(inboundLaneId = 10, connectionId = 7, outboundLaneId = 20), viaLane)
    }

    @Test
    fun bothPathsEncodeTheSameSremPayload() {
        val viaLane = requireNotNull(movementByLanePair(map, profile, inbound.id, outbound.id))
        val connection = inbound.connections.first { it.laneId == 20 }
        val viaConnection = requireNotNull(movementByConnection(map, profile, inbound.id, connection))

        val identity = SremIdentity(0x0102_0304, byteArrayOf(2, 1, 2, 3, 4, 5))
        val payloadForLane = SremUperEncoder.encode(identity, sremRequest(viaLane))
        val payloadForConnection = SremUperEncoder.encode(identity, sremRequest(viaConnection))

        assertTrue(payloadForLane.contentEquals(payloadForConnection))
    }

    @Test
    fun nextSelectionCompletesMovementViaEitherPath() {
        // Path A: select inbound lane, then the outbound lane.
        val afterInbound = nextSremSelection(map, profile, emptyList(), inbound.id)
        assertEquals(listOf(10), afterInbound)
        val viaLane = nextSremSelection(map, profile, afterInbound, outbound.id)
        assertEquals(listOf(10, 20), viaLane)

        // Path B: select inbound lane, then tap its connection to the same outbound lane.
        val connectionMovement = outgoingConnectionMovements(map, profile, inbound.id).first { it.outboundLaneId == 20 }
        val viaConnection = connectionMovement.lanePair
        assertEquals(viaLane, viaConnection)
    }

    @Test
    fun tappingIncompatibleLaneDoesNotChangeSelection() {
        val afterInbound = nextSremSelection(map, profile, emptyList(), inbound.id)
        val tramConnection = inbound.connections.first { it.laneId == 40 }

        assertEquals(listOf(10), afterInbound)
        // Tapping the incompatible tram lane is ignored for a passenger car.
        assertEquals(afterInbound, nextSremSelection(map, profile, afterInbound, tramOutbound.id))
        assertNull(movementByLanePair(map, profile, inbound.id, tramOutbound.id))
        assertNull(movementByConnection(map, profile, inbound.id, tramConnection))
    }

    @Test
    fun firstTapOnIncompatibleLaneIsIgnored() {
        val sidewalk = lane(id = 55, type = LaneType.Sidewalk)
        val withSidewalk = map(map.lanes + sidewalk)

        assertEquals(emptyList<Int>(), nextSremSelection(withSidewalk, profile, emptyList(), sidewalk.id))
    }

    @Test
    fun outgoingConnectionsAreFilteredByCompatibilityAndResolution() {
        val movements = outgoingConnectionMovements(map, profile, inbound.id).map { it.outboundLaneId }

        // 20 and 30 are compatible vehicle lanes; 40 (tram) is filtered out; 999 does not resolve.
        assertEquals(listOf(20, 30), movements)
    }

    @Test
    fun connectionFromADifferentInboundLaneIsNotSelectable() {
        // outbound (id 20) has no connections, so nothing is offered when it is the inbound lane.
        assertEquals(emptyList<SelectedMovement>(), outgoingConnectionMovements(map, profile, outbound.id))
    }

    @Test
    fun unconnectedLanesRemainSeparate() {
        // Two compatible lanes with no MAPEM connection between them must never join.
        assertNull(movementByLanePair(map, profile, outbound.id, otherOutbound.id))
        // Lane 30 is not a connectivity target of lane 20 (only lane 10 connects to 20).
        assertEquals(setOf(10), connectedSremLaneIds(map, outbound.id, profile))
    }

    @Test
    fun overlappingConnectionTargetsPickTheNearestPolyline() {
        val far = ConnectionTapTarget(
            movement = SelectedMovement(10, 1, 30),
            polyline = listOf(Float2(110f, 100f), Float2(110f, 200f)),
        )
        val near = ConnectionTapTarget(
            movement = SelectedMovement(10, 2, 20),
            polyline = listOf(Float2(100f, 100f), Float2(100f, 200f)),
        )

        // Tap closer to `near` even though `far` is listed first; ties/nearest must pick the true nearest.
        val hit = nearestConnectionTarget(listOf(far, near), tap = Float2(102f, 150f), slopPx = 5f)

        assertEquals(near, hit)
    }

    @Test
    fun connectionTapBeyondSlopIsIgnored() {
        val target = ConnectionTapTarget(
            movement = SelectedMovement(10, 2, 20),
            polyline = listOf(Float2(0f, 0f), Float2(0f, 100f)),
        )

        assertNull(nearestConnectionTarget(listOf(target), tap = Float2(50f, 50f), slopPx = 10f))
    }

    private fun sremRequest(movement: SelectedMovement) = SremRequest(
        region = map.key.region,
        intersectionId = map.key.id,
        requestId = 5,
        sequenceNumber = 1,
        inboundLaneId = movement.inboundLaneId,
        outboundLaneId = movement.outboundLaneId,
        position = SremPosition(482_024_036, 163_691_773, 1_357, true),
        nowUnixMs = 1_785_242_510_349L,
        profile = profile,
        packageRequestUnixMs = 1_785_242_511_987L,
    )

    private fun map(lanes: List<MapLane>) = MapIntersection(
        key = IntersectionKey(43, 4_036),
        name = "Wiedner Hauptstraße - Resslgasse",
        revision = 1,
        latitude = 48_195_000,
        longitude = 16_370_000,
        laneWidthCm = null,
        lanes = lanes,
        receivedAtMs = 0,
    )

    private fun lane(
        id: Int,
        type: LaneType = LaneType.Vehicle,
        ingress: Boolean = false,
        egress: Boolean = false,
        nodes: List<LaneNode> = listOf(LaneNode(0, 0), LaneNode(0, 100)),
        connections: List<LaneConnection> = emptyList(),
    ) = MapLane(
        id = id,
        ingressApproach = null,
        egressApproach = null,
        laneType = type,
        ingress = ingress,
        egress = egress,
        nodes = nodes,
        connections = connections,
    )
}
