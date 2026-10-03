package org.opentrafficmap.citstogo.intersection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.opentrafficmap.citstogo.srem.SremProfile

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
    fun everyResolvableConnectionIsVisibleBeforeSelection() {
        // A MAPEM topology connector must render even without a matching SPATEM, otherwise lanes such as
        // Wiedner Hauptstraße – Resselgasse look disconnected because no signal group is present.
        assertEquals(true, intersectionConnectionVisible(10, 11, selectedLaneIds = emptyList()))
    }

    @Test
    fun structuralConnectionWithoutSignalGroupRemainsVisible() {
        assertEquals(true, intersectionConnectionVisible(10, 20, selectedLaneIds = emptyList()))
    }

    @Test
    fun firstSelectionEmphasizesOnlyItsConnections() {
        assertEquals(true, intersectionConnectionVisible(10, 11, selectedLaneIds = listOf(10)))
        assertEquals(true, intersectionConnectionVisible(10, 11, selectedLaneIds = listOf(11)))
        assertEquals(false, intersectionConnectionVisible(20, 21, selectedLaneIds = listOf(10)))
    }

    @Test
    fun completeSelectionEmphasizesOnlyChosenConnection() {
        val selected = listOf(10, 11)

        assertEquals(true, intersectionConnectionVisible(10, 11, selectedLaneIds = selected))
        assertEquals(false, intersectionConnectionVisible(10, 12, selectedLaneIds = selected))
    }

    @Test
    fun everyGenericLaneTypeRemainsInRawConnectivitySet() {
        // The connectivity set drives what stays *visible* for orientation, independent of vehicle type.
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
    fun vehicleTypeFilteringRestrictsSelectableOutboundLanes() {
        val targets = LaneType.entries.mapIndexed { index, type -> lane(index + 2, type) }
        val vehicleSource = lane(
            id = 1,
            type = LaneType.Vehicle,
            connections = targets.map { LaneConnection(it.id, null, null, null) },
        )
        val map = map(listOf(vehicleSource) + targets)

        // A road vehicle may only hand off to a compatible vehicle lane.
        assertEquals(
            setOf(LaneType.entries.indexOf(LaneType.Vehicle) + 2),
            connectedSremLaneIds(map, vehicleSource.id, SremProfile.PASSENGER_CAR),
        )
    }

    @Test
    fun incompatibleInboundLaneHasNoSelectableConnections() {
        val tram = lane(id = 1, type = LaneType.TrackedVehicle, connections = listOf(LaneConnection(2, null, null, null)))
        val vehicle = lane(id = 2, type = LaneType.Vehicle)
        val map = map(listOf(tram, vehicle))

        assertEquals(emptySet<Int>(), connectedSremLaneIds(map, tram.id, SremProfile.PASSENGER_CAR))
    }

    @Test
    fun selectableLaneIdsCoversEveryCompatibleLaneType() {
        val lanes = LaneType.entries.mapIndexed { index, type -> lane(index + 1, type) }
        val map = map(lanes)

        val pedestrian = selectableLaneIds(map, SremProfile.PEDESTRIAN)
        assertTrue(pedestrian.contains(lanes[LaneType.Crosswalk.ordinal].id))
        assertTrue(pedestrian.contains(lanes[LaneType.Sidewalk.ordinal].id))
        assertFalse(pedestrian.contains(lanes[LaneType.Vehicle.ordinal].id))

        assertEquals(setOf(lanes[LaneType.TrackedVehicle.ordinal].id), selectableLaneIds(map, SremProfile.TRAM))
        assertEquals(setOf(lanes[LaneType.Bike.ordinal].id), selectableLaneIds(map, SremProfile.BICYCLE))
        assertEquals(setOf(lanes[LaneType.Vehicle.ordinal].id), selectableLaneIds(map, SremProfile.PASSENGER_CAR))
    }

    @Test
    fun laneTypeCompatibilityMatchesThePlanTable() {
        assertTrue(LaneType.Crosswalk.isSelectableFor(SremProfile.PEDESTRIAN))
        assertTrue(LaneType.Sidewalk.isSelectableFor(SremProfile.PEDESTRIAN))
        assertFalse(LaneType.TrackedVehicle.isSelectableFor(SremProfile.PEDESTRIAN))
        assertTrue(LaneType.TrackedVehicle.isSelectableFor(SremProfile.TRAM))
        assertFalse(LaneType.Vehicle.isSelectableFor(SremProfile.TRAM))
        assertTrue(LaneType.Bike.isSelectableFor(SremProfile.BICYCLE))
        assertFalse(LaneType.Crosswalk.isSelectableFor(SremProfile.BICYCLE))
        assertTrue(LaneType.Vehicle.isSelectableFor(SremProfile.PASSENGER_CAR))
        assertTrue(LaneType.Vehicle.isSelectableFor(SremProfile.HEAVY_TRUCK))
        assertTrue(LaneType.Vehicle.isSelectableFor(SremProfile.PUBLIC_TRANSPORT_BUS))
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

    @Test
    fun laneDirectionLabelsReflectMapemAttributes() {
        assertEquals("Inbound", lane(1, ingress = true).directionLabel())
        assertEquals("Outbound", lane(2, egress = true).directionLabel())
        assertEquals("Inbound and outbound", lane(3, ingress = true, egress = true).directionLabel())
        assertEquals("Direction unavailable", lane(4).directionLabel())
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
