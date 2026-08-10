package org.opentrafficmap.citstogo.intersection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class IntersectionCountdownPlacementTest {
    @Test
    fun longLaneKeepsCountdownCentered() {
        val offset = countdownSideOffset(100f, 0f, 100f, 30f, 20f, 12f, 5f)

        assertEquals(0f, offset.first, 0.001f)
        assertEquals(0f, offset.second, 0.001f)
    }

    @Test
    fun shortLaneMovesCountdownPerpendicularToLane() {
        val offset = countdownSideOffset(20f, 0f, 20f, 30f, 20f, 12f, 5f)

        assertEquals(0f, offset.first, 0.001f)
        assertEquals(21f, offset.second, 0.001f)
    }

    @Test
    fun zeroLengthLaneMovesCountdownAboveLane() {
        val offset = countdownSideOffset(0f, 0f, 0f, 30f, 20f, 12f, 5f)

        assertEquals(0f, offset.first, 0.001f)
        assertEquals(-21f, offset.second, 0.001f)
    }

    @Test
    fun countdownRepresentativesDeduplicateSignalGroupsAndPrioritizeSelection() {
        val lanes = listOf(
            lane(1, signalGroup = 4),
            lane(2, signalGroup = 4),
            lane(3, signalGroup = 7),
        )

        val representatives = countdownLaneRepresentatives(
            lanes = lanes,
            availableSignalGroups = setOf(4, 7),
            selectedLaneIds = setOf(2),
            selectableLaneIds = setOf(3),
        )

        assertEquals(listOf(4, 7), representatives.map { it.signalGroup })
        assertEquals(listOf(2, 3), representatives.map { it.lane.id })
    }

    @Test
    fun countdownRepresentativesIgnoreUnavailableSignalGroups() {
        val representatives = countdownLaneRepresentatives(
            lanes = listOf(lane(1, signalGroup = 4), lane(2, signalGroup = 7)),
            availableSignalGroups = setOf(7),
            selectedLaneIds = emptySet(),
            selectableLaneIds = emptySet(),
        )

        assertEquals(listOf(7), representatives.map { it.signalGroup })
    }

    @Test
    fun selectedLaneEmphasizesSignalGroupsDeclaredInEitherDirection() {
        val groups = countdownSignalGroupsForSelection(
            lanes = listOf(lane(1, signalGroup = 4, connectedLaneId = 2)),
            selectedLaneIds = listOf(2),
            availableSignalGroups = setOf(4, 7),
        )

        assertEquals(setOf(4), groups)
    }

    @Test
    fun selectedPairNarrowsEmphasisToItsExactMovement() {
        val source = lane(1, signalGroup = 4, connectedLaneId = 2).copy(
            connections = listOf(
                LaneConnection(2, 4, null, null),
                LaneConnection(3, 7, null, null),
            ),
        )

        val groups = countdownSignalGroupsForSelection(
            lanes = listOf(source),
            selectedLaneIds = listOf(1, 3),
            availableSignalGroups = setOf(4, 7),
        )

        assertEquals(setOf(7), groups)
    }

    @Test
    fun countdownPlacementMovesAwayFromOccupiedLabel() {
        val occupied = CountdownLabelBounds(left = 35f, top = 40f, right = 65f, bottom = 60f)

        val placed = placeCountdownLabel(
            preferredX = 50f,
            preferredY = 50f,
            labelWidth = 30f,
            labelHeight = 20f,
            viewportWidth = 100f,
            viewportHeight = 100f,
            occupied = listOf(occupied),
            gap = 4f,
        )

        assertNotNull(placed)
        assertEquals(16f, placed!!.top, 0.001f)
        assertEquals(36f, placed.bottom, 0.001f)
    }

    @Test
    fun countdownPlacementSkipsLabelWhenNoCandidateFits() {
        val placed = placeCountdownLabel(
            preferredX = 10f,
            preferredY = 10f,
            labelWidth = 30f,
            labelHeight = 20f,
            viewportWidth = 20f,
            viewportHeight = 20f,
            occupied = emptyList(),
            gap = 4f,
        )

        assertNull(placed)
    }

    private fun lane(id: Int, signalGroup: Int, connectedLaneId: Int = id + 100): MapLane = MapLane(
        id = id,
        ingressApproach = null,
        egressApproach = null,
        laneType = LaneType.Crosswalk,
        ingress = true,
        egress = false,
        nodes = listOf(LaneNode(0, 0), LaneNode(100, 0)),
        connections = listOf(LaneConnection(connectedLaneId, signalGroup, null, null)),
    )
}
