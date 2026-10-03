package org.opentrafficmap.citstogo.intersection

import org.opentrafficmap.citstogo.srem.SremProfile

/**
 * The single canonical result of completing a movement in the intersection view.
 *
 * It can be produced through either supported interaction path — selecting the inbound lane then the
 * outbound lane, or selecting the inbound lane then one of its rendered MAPEM connections. Both paths
 * resolve to the same object, so the SREM builder never needs to know how the user made the choice.
 */
internal data class SelectedMovement(
    val inboundLaneId: Int,
    val connectionId: Int?,
    val outboundLaneId: Int,
) {
    /** Ordered `[inbound, outbound]` lane pair consumed by the existing SREM request/status path. */
    val lanePair: List<Int> get() = listOf(inboundLaneId, outboundLaneId)
}

/**
 * Resolves a movement from two tapped lanes, honouring vehicle-type compatibility. The MAPEM
 * connectivity decides which lane is the inbound one: the lane whose `connectsTo` references the
 * other. Returns null when either lane is incompatible or the two lanes do not actually connect.
 */
internal fun movementByLanePair(
    map: MapIntersection,
    profile: SremProfile,
    firstLaneId: Int,
    secondLaneId: Int,
): SelectedMovement? {
    val lanes = map.lanes.associateBy { it.id }
    val first = lanes[firstLaneId] ?: return null
    val second = lanes[secondLaneId] ?: return null
    if (firstLaneId == secondLaneId) return null
    if (!first.isSelectableFor(profile) || !second.isSelectableFor(profile)) return null

    val firstToSecond = first.connections.firstOrNull {
        it.remoteIntersection == null && it.laneId == second.id
    }
    val secondToFirst = second.connections.firstOrNull {
        it.remoteIntersection == null && it.laneId == first.id
    }
    return when {
        firstToSecond != null -> SelectedMovement(first.id, firstToSecond.connectionId, second.id)
        secondToFirst != null -> SelectedMovement(second.id, secondToFirst.connectionId, first.id)
        else -> null
    }
}

/**
 * Resolves a movement from tapping a specific connection that leaves [inboundLaneId]. The connection
 * must be a local (same-intersection) reference whose destination lane exists and whose both endpoints
 * are compatible with the vehicle type. Unresolved or incompatible references return null so they stay
 * non-selectable.
 */
internal fun movementByConnection(
    map: MapIntersection,
    profile: SremProfile,
    inboundLaneId: Int,
    connection: LaneConnection,
): SelectedMovement? {
    if (connection.remoteIntersection != null) return null
    val lanes = map.lanes.associateBy { it.id }
    val inbound = lanes[inboundLaneId] ?: return null
    val outbound = lanes[connection.laneId] ?: return null
    if (!inbound.isSelectableFor(profile) || !outbound.isSelectableFor(profile)) return null
    return SelectedMovement(inbound.id, connection.connectionId, outbound.id)
}

/**
 * Movements reachable by tapping a rendered connection leaving the selected inbound lane. Each entry is
 * selectable only when it originates from [inboundLaneId], is compatible with the vehicle type, and its
 * destination lane resolves — matching [movementByLanePair] so both interaction paths agree.
 */
internal fun outgoingConnectionMovements(
    map: MapIntersection,
    profile: SremProfile,
    inboundLaneId: Int,
): List<SelectedMovement> {
    val inbound = map.lanes.firstOrNull { it.id == inboundLaneId } ?: return emptyList()
    if (!inbound.isSelectableFor(profile)) return emptyList()
    return inbound.connections.mapNotNull { connection ->
        movementByConnection(map, profile, inboundLaneId, connection)
    }
}

/**
 * Movements reachable by tapping a rendered connection incident to [selectedLaneId], in either
 * direction. A MAPEM `connectsTo` is directional, but the renderer highlights every connector that
 * touches the selected lane, so the connection tap path must accept both:
 *  - the selected lane is the inbound (its own outgoing connections), and
 *  - the selected lane is the outbound (some other compatible lane's connection into it).
 *
 * Each result is the canonical `[inbound, outbound]` movement, matching [movementByLanePair] so both
 * interaction paths agree regardless of which lane the user tapped first. Incompatible or unresolved
 * references are excluded.
 */
internal fun incidentConnectionMovements(
    map: MapIntersection,
    profile: SremProfile,
    selectedLaneId: Int,
): List<SelectedMovement> {
    val selected = map.lanes.firstOrNull { it.id == selectedLaneId } ?: return emptyList()
    if (!selected.isSelectableFor(profile)) return emptyList()

    val byPair = LinkedHashMap<Pair<Int, Int>, SelectedMovement>()
    outgoingConnectionMovements(map, profile, selectedLaneId)
        .forEach { movement -> byPair[movement.inboundLaneId to movement.outboundLaneId] = movement }

    map.lanes.forEach { other ->
        if (other.id == selectedLaneId) return@forEach
        other.connections.forEach { connection ->
            if (connection.remoteIntersection == null && connection.laneId == selectedLaneId) {
                movementByConnection(map, profile, other.id, connection)
                    ?.let { movement -> byPair.putIfAbsent(movement.inboundLaneId to movement.outboundLaneId, movement) }
            }
        }
    }
    return byPair.values.toList()
}

/**
 * Advances the lane-selection state for a tap on [tappedLaneId]. Taps on lanes incompatible with the
 * vehicle type are ignored, so they can never alter the pending SREM selection. Completing a second,
 * connected, compatible lane yields the canonical inbound-first lane pair.
 */
internal fun nextSremSelection(
    map: MapIntersection,
    profile: SremProfile,
    selectedLaneIds: List<Int>,
    tappedLaneId: Int,
): List<Int> {
    val tapped = map.lanes.firstOrNull { it.id == tappedLaneId } ?: return selectedLaneIds
    if (!tapped.isSelectableFor(profile)) return selectedLaneIds
    if (selectedLaneIds.isEmpty()) return listOf(tapped.id)
    val firstLaneId = selectedLaneIds.first()
    if (tapped.id == firstLaneId) return emptyList()
    if (selectedLaneIds.size == 1) {
        val movement = movementByLanePair(map, profile, firstLaneId, tapped.id)
        if (movement != null) return movement.lanePair
    }
    return listOf(tapped.id)
}

/**
 * A tappable MAPEM connection expressed in screen space, ready for hit-testing after selection has
 * started. [polyline] samples the same Bézier geometry the renderer draws for the connection.
 */
internal data class ConnectionTapTarget(
    val movement: SelectedMovement,
    val polyline: List<Float2>,
)

/**
 * Selects the connection whose rendered geometry is nearest to [tap], within [slopPx]. Used to resolve
 * the inbound-lane → connection interaction path, and to break ties between overlapping connection hit
 * areas by choosing the nearest rendered segment.
 */
internal fun nearestConnectionTarget(
    targets: List<ConnectionTapTarget>,
    tap: Float2,
    slopPx: Float,
): ConnectionTapTarget? = targets
    .mapNotNull { target -> distanceToPolyline(tap, target.polyline).let { distance -> target to distance } }
    .filter { (_, distance) -> distance <= slopPx }
    .minByOrNull { (_, distance) -> distance }
    ?.first
