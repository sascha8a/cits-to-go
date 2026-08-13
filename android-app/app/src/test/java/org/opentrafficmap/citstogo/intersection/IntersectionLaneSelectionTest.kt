package org.opentrafficmap.citstogo.intersection

import org.junit.Assert.assertEquals
import org.junit.Test

class IntersectionLaneSelectionTest {
    @Test
    fun allLanesHaveFullOpacityBeforeSelection() {
        assertEquals(1f, intersectionLaneSelectionAlpha(10, emptyList(), emptySet()))
    }

    @Test
    fun selectedAndSelectableLanesKeepFullOpacity() {
        val selectedLaneIds = listOf(10)
        val selectableLaneIds = setOf(11)

        assertEquals(1f, intersectionLaneSelectionAlpha(10, selectedLaneIds, selectableLaneIds))
        assertEquals(1f, intersectionLaneSelectionAlpha(11, selectedLaneIds, selectableLaneIds))
    }

    @Test
    fun everyOtherLaneIsDimmedAfterSelection() {
        assertEquals(0.2f, intersectionLaneSelectionAlpha(99, listOf(10), setOf(11)))
    }

    @Test
    fun onlySignalizedConnectionsAreVisibleBeforeSelection() {
        assertEquals(true, intersectionConnectionVisible(10, 11, signalized = true, selectedLaneIds = emptyList()))
        assertEquals(false, intersectionConnectionVisible(10, 11, signalized = false, selectedLaneIds = emptyList()))
    }

    @Test
    fun structuralConnectionsCanRemainVisibleWithoutAValidSignalGroup() {
        assertEquals(
            true,
            intersectionConnectionVisible(
                10,
                11,
                signalized = false,
                alwaysVisible = true,
                selectedLaneIds = emptyList(),
            ),
        )
    }

    @Test
    fun firstSelectionShowsOnlyItsConnections() {
        assertEquals(true, intersectionConnectionVisible(10, 11, signalized = false, selectedLaneIds = listOf(10)))
        assertEquals(true, intersectionConnectionVisible(10, 11, signalized = false, selectedLaneIds = listOf(11)))
        assertEquals(false, intersectionConnectionVisible(20, 21, signalized = true, selectedLaneIds = listOf(10)))
    }

    @Test
    fun completeSelectionShowsOnlyChosenConnection() {
        val selected = listOf(10, 11)

        assertEquals(true, intersectionConnectionVisible(10, 11, signalized = false, selectedLaneIds = selected))
        assertEquals(false, intersectionConnectionVisible(10, 12, signalized = true, selectedLaneIds = selected))
    }

    @Test
    fun everyGenericLaneTypeCanBeSelectedWhenConnected() {
        val targetLanes = LaneType.entries.mapIndexed { index, type -> lane(index + 2, type) }
        val source = lane(
            id = 1,
            type = LaneType.Vehicle,
            connections = targetLanes.map { LaneConnection(it.id, null, null, null) },
        )
        val map = map(listOf(source) + targetLanes)

        assertEquals(targetLanes.map { it.id }.toSet(), connectedSremLaneIds(map, source.id))
    }

    @Test
    fun remoteIntersectionConnectionsAreNotSelectable() {
        val source = lane(
            id = 1,
            connections = listOf(LaneConnection(2, null, null, IntersectionKey(43, 99))),
        )
        val map = map(listOf(source, lane(2)))

        assertEquals(emptySet<Int>(), connectedSremLaneIds(map, source.id))
    }

    @Test
    fun directedConnectionDeterminesInboundLaneRegardlessOfTapOrder() {
        val inbound = lane(1, ingress = true, connections = listOf(LaneConnection(2, 7, null, null)))
        val outbound = lane(2, egress = true)
        val map = map(listOf(inbound, outbound))

        assertEquals(listOf(1, 2), resolveSremLaneDirection(map, 2, 1))
    }

    private fun map(lanes: List<MapLane>) = MapIntersection(
        key = IntersectionKey(43, 1_039),
        name = null,
        revision = 1,
        latitude = 0,
        longitude = 0,
        laneWidthCm = null,
        lanes = lanes,
        receivedAtMs = 0,
    )

    private fun lane(
        id: Int,
        type: LaneType = LaneType.Vehicle,
        ingress: Boolean = false,
        egress: Boolean = false,
        connections: List<LaneConnection> = emptyList(),
    ) = MapLane(
        id = id,
        ingressApproach = null,
        egressApproach = null,
        laneType = type,
        ingress = ingress,
        egress = egress,
        nodes = listOf(LaneNode(0, 0), LaneNode(1, 1)),
        connections = connections,
    )
}
