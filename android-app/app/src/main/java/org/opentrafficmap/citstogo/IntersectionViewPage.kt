package org.opentrafficmap.citstogo

import org.opentrafficmap.citstogo.ui.DragConfirmDirection
import org.opentrafficmap.citstogo.ui.DragConfirmSlider
import org.opentrafficmap.citstogo.srem.estimateSremRequestTimeMs

import org.opentrafficmap.citstogo.intersection.IntersectionSnapshot

import android.graphics.Paint as AndroidPaint
import android.graphics.Typeface as AndroidTypeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color as UiColor
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlinx.coroutines.delay
import org.opentrafficmap.citstogo.bridge.BridgeStatus
import org.opentrafficmap.citstogo.bridge.CitsBridgeService
import org.opentrafficmap.citstogo.intersection.ConnectionTapTarget
import org.opentrafficmap.citstogo.intersection.CountdownLabelBounds
import org.opentrafficmap.citstogo.intersection.Float2
import org.opentrafficmap.citstogo.intersection.LaneConnection
import org.opentrafficmap.citstogo.intersection.LaneNode
import org.opentrafficmap.citstogo.intersection.LaneType
import org.opentrafficmap.citstogo.intersection.MapIntersection
import org.opentrafficmap.citstogo.intersection.MapLane
import org.opentrafficmap.citstogo.intersection.MovementPhaseState
import org.opentrafficmap.citstogo.intersection.SelectedMovement
import org.opentrafficmap.citstogo.intersection.SpatIntersection
import org.opentrafficmap.citstogo.intersection.countdownLaneRepresentatives
import org.opentrafficmap.citstogo.intersection.countdownSignalGroupsForSelection
import org.opentrafficmap.citstogo.intersection.countdownSideOffset
import org.opentrafficmap.citstogo.intersection.connectedSremLaneIds
import org.opentrafficmap.citstogo.intersection.cubicBezierPolyline
import org.opentrafficmap.citstogo.intersection.directionLabel
import org.opentrafficmap.citstogo.intersection.intersectionConnectionVisible
import org.opentrafficmap.citstogo.intersection.intersectionLaneSelectionAlpha
import org.opentrafficmap.citstogo.intersection.isSelectableFor
import org.opentrafficmap.citstogo.intersection.laneConnector
import org.opentrafficmap.citstogo.intersection.nearestConnectionTarget
import org.opentrafficmap.citstogo.intersection.nextSremSelection
import org.opentrafficmap.citstogo.intersection.outgoingConnectionMovements
import org.opentrafficmap.citstogo.intersection.placeCountdownLabel
import org.opentrafficmap.citstogo.intersection.roadConnectionControlPoints
import org.opentrafficmap.citstogo.intersection.secondsUntilChange
import org.opentrafficmap.citstogo.intersection.selectableLaneIds
import org.opentrafficmap.citstogo.srem.SremProfile

private const val INTERSECTION_MAX_ZOOM = 6f
private const val LANE_TIMING_ZOOM_THRESHOLD = 2.2f
private const val E7_DEGREE_TO_CM = 1.1132f
private const val DOUBLE_TAP_TIMEOUT_MS = 300L
private const val TAP_TIMEOUT_MS = 220L
private const val QUICK_SCALE_SENSITIVITY = 0.006f
internal const val SREM_MAX_LOCATION_AGE_MS = 5_000L
private const val SREM_RESPONSE_TIMEOUT_MS = 15_000L


@Composable
fun IntersectionViewPage(
    snapshots: List<IntersectionSnapshot>,
    sortMode: IntersectionSortMode,
    onSortModeChange: (IntersectionSortMode) -> Unit,
    status: BridgeStatus,
    currentPosition: DevicePosition?,
    txApproved: Boolean,
    sremProfile: SremProfile,
    onSendSrem: (IntersectionSnapshot, Int, Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (snapshots.isEmpty()) {
        Column(
            modifier = modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            IntersectionPageContent(null, status, currentPosition, txApproved, sremProfile, onSendSrem)
        }
        return
    }

    val sortedSnapshots = remember(snapshots, sortMode, currentPosition) {
        when (sortMode) {
            IntersectionSortMode.FirstReceived -> snapshots.sortedWith(
                compareBy<IntersectionSnapshot> { it.firstReceivedAtMs }
                    .thenBy { it.updatedAtMs },
            )
            IntersectionSortMode.Distance -> snapshots.sortedWith(
                compareBy<IntersectionSnapshot> {
                    it.distanceTo(currentPosition) ?: Double.POSITIVE_INFINITY
                }.thenBy { it.firstReceivedAtMs },
            )
        }
    }
    val pagerState = rememberPagerState(pageCount = { sortedSnapshots.size })
    LaunchedEffect(snapshots.size) {
        if (pagerState.currentPage >= sortedSnapshots.lastIndex) {
            pagerState.scrollToPage(sortedSnapshots.lastIndex)
        }
    }
    LaunchedEffect(sortMode) {
        pagerState.scrollToPage(0)
    }

    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (sortedSnapshots.size > 1) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "${pagerState.currentPage + 1} / ${sortedSnapshots.size}",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary,
                )
                TextButton(
                    onClick = {
                        onSortModeChange(when (sortMode) {
                            IntersectionSortMode.FirstReceived -> IntersectionSortMode.Distance
                            IntersectionSortMode.Distance -> IntersectionSortMode.FirstReceived
                        })
                    },
                ) {
                    Text("Sort: ${sortMode.label}")
                }
            }
        }
        if (sortedSnapshots.size > 1 && sortMode == IntersectionSortMode.Distance && currentPosition == null) {
            Text(
                "Waiting for location fix; showing first-received order.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary,
            )
        }
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) { page ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 2.dp),
            ) {
                IntersectionPageContent(sortedSnapshots[page], status, currentPosition, txApproved, sremProfile, onSendSrem)
            }
        }
    }
}

@Composable
private fun IntersectionPageContent(
    snapshot: IntersectionSnapshot?,
    status: BridgeStatus,
    currentPosition: DevicePosition?,
    txApproved: Boolean,
    sremProfile: SremProfile,
    onSendSrem: (IntersectionSnapshot, Int, Int) -> Unit,
) {
    val map = snapshot?.map
    val spat = snapshot?.spat
    var selectedCrosswalkLaneIds by rememberSaveable(map?.key.toString(), map?.revision) {
        mutableStateOf<List<Int>>(emptyList())
    }
    val selectedPair = selectedCrosswalkLaneIds.takeIf { it.size == 2 }
    val sremUiState = sremUiState(status, snapshot, selectedCrosswalkLaneIds, spat)
    val context = LocalContext.current
    LaunchedEffect(sremProfile, map?.key, map?.revision) {
        val current = selectedCrosswalkLaneIds
        val incompatible = map != null && current.any { laneId ->
            map.lanes.firstOrNull { it.id == laneId }?.isSelectableFor(sremProfile) != true
        }
        if (incompatible) selectedCrosswalkLaneIds = emptyList()
    }
    Column(
        Modifier
            .fillMaxWidth()
            .background(Color(ContextCompat.getColor(context, R.color.card)), RoundedCornerShape(8.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (snapshot?.available != true) {
            Text("Waiting for MAPEM/SPATEM", style = MaterialTheme.typography.titleMedium)
            Text(
                if (status.running) "No intersection message has been decoded yet." else "Start capture to receive intersection messages.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.secondary,
            )
            return@Column
        }
        val title = map?.name?.takeIf { it.isNotBlank() } ?: "Intersection ${snapshot.map?.key ?: snapshot.spat?.key}"
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(
            listOfNotNull(
                map?.key?.let { "id $it" },
                snapshot.updatedAtMs.takeIf { it > 0L }?.let { "last ${formatIntersectionAge(it)}" },
                "SREM ${sremProfile.displayName}",
            ).joinToString(" • "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.secondary,
        )
        if (map == null) {
            Text("SPATEM received; waiting for matching MAPEM geometry.", color = MaterialTheme.colorScheme.secondary)
        } else {
            Text(
                "Only ${sremProfile.displayName.lowercase()} lanes can be selected for the request; " +
                    "lanes for other vehicle types stay visible for orientation but are dimmed.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary,
            )
            IntersectionRenderer(
                map = map,
                spat = spat,
                currentPosition = currentPosition,
                sremProfile = sremProfile,
                selectedCrosswalkLaneIds = selectedCrosswalkLaneIds,
                onSelectionChange = { nextSelection ->
                    selectedCrosswalkLaneIds = nextSelection
                },
            )
            SremRequestPanel(
                snapshot = snapshot,
                map = map,
                selectedLaneIds = selectedCrosswalkLaneIds,
                state = sremUiState,
                txApproved = txApproved,
                status = status,
                currentPosition = currentPosition,
                onClear = { selectedCrosswalkLaneIds = emptyList() },
                onSend = {
                    val pair = selectedPair ?: return@SremRequestPanel
                    onSendSrem(snapshot, pair[0], pair[1])
                },
            )
        }
    }
    SignalTimingPanel(snapshot, Modifier.padding(top = 12.dp))
}

@Composable
private fun SremRequestPanel(
    snapshot: IntersectionSnapshot,
    map: MapIntersection,
    selectedLaneIds: List<Int>,
    state: SremRequestUiState,
    txApproved: Boolean,
    status: BridgeStatus,
    currentPosition: DevicePosition?,
    onClear: () -> Unit,
    onSend: () -> Unit,
) {
    val context = LocalContext.current
    val selectedPair = selectedLaneIds.takeIf { it.size == 2 }
    val sliderEnabled = selectedPair != null &&
        state == SremRequestUiState.Ready &&
        txApproved &&
        status.running &&
        currentPosition?.isFreshForSrem() == true
    val displayState = if (selectedPair != null && state == SremRequestUiState.Ready && !sliderEnabled) {
        SremRequestUiState.NotReady
    } else {
        state
    }
    if (selectedLaneIds.isEmpty()) {
        Text(
            state.detail,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.secondary,
        )
        return
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(ContextCompat.getColor(context, R.color.surface)), RoundedCornerShape(8.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                selectedLaneIds.joinToString(" -> ") { laneId ->
                    val direction = map.lanes.firstOrNull { it.id == laneId }?.directionLabel()
                        ?: "Direction unavailable"
                    "Lane $laneId ($direction)"
                },
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            TextButton(onClick = onClear) {
                Text("Clear")
            }
        }
        Text(
            displayState.label,
            style = MaterialTheme.typography.bodyMedium,
            color = displayState.color(context),
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            requestDetailText(displayState, status, snapshot, selectedPair),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.secondary,
        )
        if (selectedPair != null) {
            SremRequestSlider(
                state = displayState,
                enabled = sliderEnabled,
                onSubmit = onSend,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

private fun requestDetailText(
    state: SremRequestUiState,
    status: BridgeStatus,
    snapshot: IntersectionSnapshot,
    selectedPair: List<Int>?,
): String {
    if (selectedPair == null) return state.detail
    val requestId = status.lastSremRequestId.takeIf { it >= 0 }?.let { "request $it" }
    val updated = status.lastSremUpdatedAtMs.takeIf { it > 0L }?.let { "last ${formatIntersectionAge(it)}" }
    val sremDetail = listOfNotNull(requestId, updated).joinToString(" • ")
    val base = when (state) {
        SremRequestUiState.Queued,
        SremRequestUiState.Transmitted,
        SremRequestUiState.Acknowledged,
        SremRequestUiState.Processing,
        SremRequestUiState.WatchOtherTraffic,
        SremRequestUiState.Granted,
        SremRequestUiState.Rejected,
        SremRequestUiState.UnknownResponse,
        SremRequestUiState.Failed,
        SremRequestUiState.TimedOut -> status.lastSremSummary.ifBlank { state.detail }
        else -> state.detail
    }
    val id = snapshot.map?.key?.let { "intersection $it" }
    return listOfNotNull(base, sremDetail, id).filter { it.isNotBlank() }.joinToString(" • ")
}

@Composable
private fun SremRequestSlider(
    state: SremRequestUiState,
    enabled: Boolean,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var sliderPosition by rememberSaveable(state, enabled) {
        mutableStateOf(
            if (state in setOf(
                    SremRequestUiState.Queued,
                    SremRequestUiState.Transmitted,
                    SremRequestUiState.Acknowledged,
                    SremRequestUiState.Processing,
                    SremRequestUiState.WatchOtherTraffic,
                    SremRequestUiState.Granted,
                    SremRequestUiState.Rejected,
                    SremRequestUiState.UnknownResponse,
                    SremRequestUiState.WalkActive,
                )
            ) 1f else 0f,
        )
    }
    var submitted by rememberSaveable(state) { mutableStateOf(false) }
    val constrainedPosition = sliderPosition.coerceIn(0f, 1f)
    val onDragStateChange = LocalSliderDragStateChange.current
    val stateColor = state.color(context)
    val trackColor = if (enabled) stateColor.copy(alpha = 0.22f) else Color(ContextCompat.getColor(context, R.color.divider))
    val fillColor = if (enabled || constrainedPosition > 0f) stateColor else Color(ContextCompat.getColor(context, R.color.secondary_variant))

    DragConfirmSlider(
        position = constrainedPosition,
        onPositionChange = { nextPosition ->
            sliderPosition = nextPosition
            if (!submitted && nextPosition >= 0.995f) {
                submitted = true
                sliderPosition = 1f
                onSubmit()
            }
        },
        enabled = enabled,
        direction = DragConfirmDirection.RightToLeft,
        trackColor = trackColor,
        fillColor = fillColor,
        thumbRingColor = state.onColor(context),
        onDragStateChange = onDragStateChange,
        onDragFinished = {
            if (!submitted && state == SremRequestUiState.Ready) sliderPosition = 0f
        },
        modifier = modifier.height(96.dp),
    ) { center, radius ->
        drawSremSliderIcon(state, center, radius, state.onColor(context))
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawSremSliderIcon(
    state: SremRequestUiState,
    center: Offset,
    radius: Float,
    ink: Color,
) {
    val white = ink
    val strokeWidth = 4.dp.toPx()
    when (state) {
        SremRequestUiState.WalkActive,
        SremRequestUiState.Granted -> {
            drawLine(white, center + Offset(-10.dp.toPx(), 0f), center + Offset(-2.dp.toPx(), 9.dp.toPx()), strokeWidth, cap = StrokeCap.Round)
            drawLine(white, center + Offset(-2.dp.toPx(), 9.dp.toPx()), center + Offset(13.dp.toPx(), -10.dp.toPx()), strokeWidth, cap = StrokeCap.Round)
        }
        SremRequestUiState.Failed,
        SremRequestUiState.Rejected -> {
            drawLine(white, center + Offset(-10.dp.toPx(), -10.dp.toPx()), center + Offset(10.dp.toPx(), 10.dp.toPx()), strokeWidth, cap = StrokeCap.Round)
            drawLine(white, center + Offset(10.dp.toPx(), -10.dp.toPx()), center + Offset(-10.dp.toPx(), 10.dp.toPx()), strokeWidth, cap = StrokeCap.Round)
        }
        SremRequestUiState.Queued,
        SremRequestUiState.Transmitted,
        SremRequestUiState.Acknowledged,
        SremRequestUiState.Processing,
        SremRequestUiState.WatchOtherTraffic,
        SremRequestUiState.UnknownResponse,
        SremRequestUiState.TimedOut -> {
            drawCircle(white, radius = radius * 0.34f, center = center, style = Stroke(width = strokeWidth))
            drawLine(white, center, center + Offset(0f, -12.dp.toPx()), strokeWidth, cap = StrokeCap.Round)
            drawLine(white, center, center + Offset(10.dp.toPx(), 4.dp.toPx()), strokeWidth, cap = StrokeCap.Round)
        }
        else -> {
            val arrowLength = 22.dp.toPx()
            val arrowHead = 8.dp.toPx()
            val arrowStart = Offset(center.x + arrowLength / 2f, center.y)
            val arrowEnd = Offset(center.x - arrowLength / 2f, center.y)
            drawLine(white, arrowStart, arrowEnd, strokeWidth, cap = StrokeCap.Round)
            drawLine(white, arrowEnd, Offset(arrowEnd.x + arrowHead, arrowEnd.y - arrowHead), strokeWidth, cap = StrokeCap.Round)
            drawLine(white, arrowEnd, Offset(arrowEnd.x + arrowHead, arrowEnd.y + arrowHead), strokeWidth, cap = StrokeCap.Round)
        }
    }
}

private fun sremUiState(
    status: BridgeStatus,
    snapshot: IntersectionSnapshot?,
    selectedLaneIds: List<Int>,
    spat: SpatIntersection?,
): SremRequestUiState {
    val selectedPair = selectedLaneIds.takeIf { it.size == 2 }
    if (selectedPair == null) {
        return if (selectedLaneIds.size == 1) SremRequestUiState.SelectSecond else SremRequestUiState.SelectFirst
    }
    val map = snapshot?.map
    val matchingStatus = map != null &&
        status.lastSremIntersectionId == map.key.id &&
        status.lastSremInboundLaneId == selectedPair[0] &&
        status.lastSremOutboundLaneId == selectedPair[1]
    if (matchingStatus) {
        when (status.lastSremState) {
            CitsBridgeService.SREM_STATE_FAILED -> return SremRequestUiState.Failed
            CitsBridgeService.SREM_STATE_REJECTED -> return SremRequestUiState.Rejected
            CitsBridgeService.SREM_STATE_GRANTED -> {
                return if (isSelectedCrossingWalkActive(map, selectedPair, spat)) {
                    SremRequestUiState.WalkActive
                } else {
                    SremRequestUiState.Granted
                }
            }
            CitsBridgeService.SREM_STATE_ACKNOWLEDGED -> return SremRequestUiState.Acknowledged
            CitsBridgeService.SREM_STATE_PROCESSING -> return SremRequestUiState.Processing
            CitsBridgeService.SREM_STATE_WATCH_OTHER_TRAFFIC -> return SremRequestUiState.WatchOtherTraffic
            CitsBridgeService.SREM_STATE_UNKNOWN_RESPONSE -> return SremRequestUiState.UnknownResponse
            CitsBridgeService.SREM_STATE_QUEUED -> return SremRequestUiState.Queued
            CitsBridgeService.SREM_STATE_TRANSMITTED -> {
                val ageMs = System.currentTimeMillis() - status.lastSremUpdatedAtMs
                return if (ageMs > SREM_RESPONSE_TIMEOUT_MS) {
                    SremRequestUiState.TimedOut
                } else {
                    SremRequestUiState.Transmitted
                }
            }
        }
    }
    if (isSelectedCrossingWalkActive(snapshot?.map, selectedPair, spat)) return SremRequestUiState.WalkActive
    return if (status.running) SremRequestUiState.Ready else SremRequestUiState.NotReady
}

private fun isSelectedCrossingWalkActive(
    map: MapIntersection?,
    selectedPair: List<Int>,
    spat: SpatIntersection?,
): Boolean {
    val lanesById = map?.lanes?.associateBy { it.id } ?: return false
    val first = lanesById[selectedPair[0]] ?: return false
    val second = lanesById[selectedPair[1]] ?: return false
    val signalGroups = spat?.movementsBySignalGroup.orEmpty()
    val signalGroup = first.connections.firstOrNull {
        it.remoteIntersection == null && it.laneId == second.id
    }?.signalGroup
        ?: second.connections.firstOrNull {
            it.remoteIntersection == null && it.laneId == first.id
        }?.signalGroup
        ?: return false
    return signalGroups[signalGroup]?.currentEvent?.state in setOf(
        MovementPhaseState.PermissiveAllowed,
        MovementPhaseState.ProtectedAllowed,
    )
}

private fun DevicePosition.isFreshForSrem(nowMs: Long = System.currentTimeMillis()): Boolean =
    timeMs in (nowMs - SREM_MAX_LOCATION_AGE_MS)..(nowMs + 1_000L)

private fun formatIntersectionAge(updatedAtMs: Long, nowMs: Long = System.currentTimeMillis()): String {
    val ageSeconds = ((nowMs - updatedAtMs).coerceAtLeast(0L) / 1_000L)
    return when {
        ageSeconds < 2L -> "just now"
        ageSeconds < 60L -> "${ageSeconds}s ago"
        else -> "${ageSeconds / 60L}m ${ageSeconds % 60L}s ago"
    }
}

private fun IntersectionSnapshot.distanceTo(position: DevicePosition?): Double? {
    val map = map ?: return null
    val devicePosition = position ?: return null
    return map.distanceTo(devicePosition.latitudeE7, devicePosition.longitudeE7)
}

private fun DevicePosition.toIntersectionOffsetCm(map: MapIntersection): Offset {
    val latitudeScaleCm = E7_DEGREE_TO_CM
    val longitudeScaleCm = E7_DEGREE_TO_CM * cos(Math.toRadians(map.latitude / 10_000_000.0)).toFloat()
    return Offset(
        x = (longitudeE7 - map.longitude) * longitudeScaleCm,
        y = (latitudeE7 - map.latitude) * latitudeScaleCm,
    )
}

internal fun sremPackageRequestTimeMs(
    map: MapIntersection,
    inboundLaneId: Int,
    position: DevicePosition,
    profile: SremProfile,
    nowMs: Long,
): Long {
    val lane = map.lanes.firstOrNull { it.id == inboundLaneId }
    val positionCm = position.toIntersectionOffsetCm(map)
    val distanceMeters = lane
        ?.nodes
        ?.takeIf { it.isNotEmpty() }
        ?.let { nodes ->
            val distanceCm = if (nodes.size == 1) {
                hypot(
                    (positionCm.x - nodes[0].xCm).toDouble(),
                    (positionCm.y - nodes[0].yCm).toDouble(),
                )
            } else {
                nodes.zipWithNext().minOf { (start, end) ->
                    distanceToSegment(
                        positionCm,
                        Offset(start.xCm.toFloat(), start.yCm.toFloat()),
                        Offset(end.xCm.toFloat(), end.yCm.toFloat()),
                    ).toDouble()
                }
            }
            distanceCm / 100.0
        }
        ?: map.distanceTo(position.latitudeE7, position.longitudeE7)
    return estimateSremRequestTimeMs(nowMs, distanceMeters, position.speedMetersPerSecond, profile)
}

private fun clampIntersectionPan(
    pan: Offset,
    zoomScale: Float,
    viewportWidth: Float,
    viewportHeight: Float,
): Offset {
    if (zoomScale <= 1.0001f || viewportWidth <= 0f || viewportHeight <= 0f) return Offset.Zero
    return Offset(
        x = pan.x.coerceIn(viewportWidth - viewportWidth * zoomScale, 0f),
        y = pan.y.coerceIn(viewportHeight - viewportHeight * zoomScale, 0f),
    )
}

private class IntersectionProjection(
    private val minX: Float,
    private val minY: Float,
    private val scale: Float,
    private val canvasHeight: Float,
    private val zoomScale: Float,
    private val pan: Offset,
    private val paddingPx: Float,
) {
    fun point(xCm: Float, yCm: Float): Offset {
        val base = Offset(
            x = paddingPx + (xCm - minX) * scale,
            y = canvasHeight - paddingPx - (yCm - minY) * scale,
        )
        return Offset(
            x = base.x * zoomScale + pan.x,
            y = base.y * zoomScale + pan.y,
        )
    }

    fun point(node: LaneNode): Offset = point(node.xCm.toFloat(), node.yCm.toFloat())
}

private fun buildIntersectionProjection(
    map: MapIntersection,
    canvasSize: IntSize,
    zoomScale: Float,
    pan: Offset,
    paddingPx: Float,
): IntersectionProjection? {
    if (canvasSize.width <= 0 || canvasSize.height <= 0) return null
    val allNodes = map.lanes.flatMap { it.nodes }
    if (allNodes.isEmpty()) return null
    val minX = allNodes.minOf { it.xCm }.toFloat()
    val maxX = allNodes.maxOf { it.xCm }.toFloat()
    val minY = allNodes.minOf { it.yCm }.toFloat()
    val maxY = allNodes.maxOf { it.yCm }.toFloat()
    val width = (maxX - minX).coerceAtLeast(1f)
    val height = (maxY - minY).coerceAtLeast(1f)
    val scale = minOf((canvasSize.width - paddingPx * 2) / width, (canvasSize.height - paddingPx * 2) / height)
    return IntersectionProjection(minX, minY, scale, canvasSize.height.toFloat(), zoomScale, pan, paddingPx)
}

private fun hitTestCrosswalkLane(
    map: MapIntersection,
    projection: IntersectionProjection,
    tap: Offset,
    hitSlopPx: Float,
): MapLane? = map.lanes
    .filter { it.nodes.size >= 2 }
    .mapNotNull { lane ->
        val distance = lane.nodes.zipWithNext().minOf { (start, end) ->
            distanceToSegment(tap, projection.point(start), projection.point(end)).toDouble()
        }.toFloat()
        if (distance <= hitSlopPx) lane to distance else null
    }
    .minByOrNull { it.second }
    ?.first

/**
 * Builds the tap targets for the outgoing connections of the currently selected inbound lane, using
 * the same screen-space Bézier geometry the renderer draws. Connections that are incompatible with
 * the vehicle type or whose destination lane does not resolve are excluded by the domain layer.
 */
private fun connectionTapTargets(
    map: MapIntersection,
    projection: IntersectionProjection,
    profile: SremProfile,
    inboundLaneId: Int,
    controlDistancePx: Float,
): List<ConnectionTapTarget> {
    val lanesById = map.lanes.associateBy { it.id }
    return outgoingConnectionMovements(map, profile, inboundLaneId).mapNotNull { movement ->
        val source = lanesById[movement.inboundLaneId] ?: return@mapNotNull null
        val target = lanesById[movement.outboundLaneId] ?: return@mapNotNull null
        val connector = laneConnector(source, target) ?: return@mapNotNull null
        val start = projection.point(connector.start.x, connector.start.y)
        val startAdjacent = projection.point(connector.startAdjacent.x, connector.startAdjacent.y)
        val end = projection.point(connector.end.x, connector.end.y)
        val endAdjacent = projection.point(connector.endAdjacent.x, connector.endAdjacent.y)
        val controls = roadConnectionControlPoints(
            startX = start.x,
            startY = start.y,
            startAdjacentX = startAdjacent.x,
            startAdjacentY = startAdjacent.y,
            endX = end.x,
            endY = end.y,
            endAdjacentX = endAdjacent.x,
            endAdjacentY = endAdjacent.y,
            maxControlDistance = controlDistancePx,
        )
        val polyline = cubicBezierPolyline(
            start = Float2(start.x, start.y),
            control1 = Float2(controls.startX, controls.startY),
            control2 = Float2(controls.endX, controls.endY),
            end = Float2(end.x, end.y),
        )
        ConnectionTapTarget(movement, polyline)
    }
}

private fun selectedMovementForConnection(targets: List<ConnectionTapTarget>, tap: Offset, slopPx: Float): SelectedMovement? =
    nearestConnectionTarget(
        targets = targets,
        tap = Float2(tap.x, tap.y),
        slopPx = slopPx,
    )?.movement

/**
 * Resolves a single tap to the next selection state, applying the plan's precedence: a tap that lands
 * on a rendered connection from the selected inbound lane completes the movement through the connection
 * path; otherwise the tap falls back to lane hit-testing. Both paths funnel through the shared
 * movement/selection reducers, so they always produce the same canonical `[inbound, outbound]` pair.
 * Returns null when nothing selectable was hit (the caller leaves the selection unchanged).
 */
private fun resolveIntersectionTap(
    map: MapIntersection,
    profile: SremProfile,
    selectedLaneIds: List<Int>,
    projection: IntersectionProjection?,
    tap: Offset,
    hitSlopPx: Float,
    connectionSlopPx: Float,
    controlDistancePx: Float,
): List<Int>? {
    if (projection == null) return null
    val inboundLaneId = selectedLaneIds.singleOrNull()
    if (inboundLaneId != null) {
        val targets = connectionTapTargets(map, projection, profile, inboundLaneId, controlDistancePx)
        val movement = selectedMovementForConnection(targets, tap, connectionSlopPx)
        if (movement != null) return movement.lanePair
    }
    val lane = hitTestCrosswalkLane(map, projection, tap, hitSlopPx) ?: return null
    return nextSremSelection(map, profile, selectedLaneIds, lane.id)
}

private fun distanceToSegment(point: Offset, start: Offset, end: Offset): Float {
    val dx = end.x - start.x
    val dy = end.y - start.y
    val lengthSquared = dx * dx + dy * dy
    if (lengthSquared <= 0.0001f) {
        return hypot((point.x - start.x).toDouble(), (point.y - start.y).toDouble()).toFloat()
    }
    val t = (((point.x - start.x) * dx + (point.y - start.y) * dy) / lengthSquared).coerceIn(0f, 1f)
    val projection = Offset(start.x + dx * t, start.y + dy * t)
    return hypot((point.x - projection.x).toDouble(), (point.y - projection.y).toDouble()).toFloat()
}

@Composable
private fun IntersectionRenderer(
    map: MapIntersection,
    spat: SpatIntersection?,
    currentPosition: DevicePosition?,
    sremProfile: SremProfile,
    selectedCrosswalkLaneIds: List<Int>,
    onSelectionChange: (List<Int>) -> Unit,
) {
    val signalGroups = spat?.movementsBySignalGroup.orEmpty()
    val context = LocalContext.current
    fun paletteColor(resId: Int) = Color(ContextCompat.getColor(context, resId))
    fun phaseColor(state: MovementPhaseState) = paletteColor(state.phaseColorResId())
    fun baseColor(type: LaneType) = paletteColor(type.baseColorResId())
    val canvasBackground = paletteColor(R.color.surface)
    var zoomScale by rememberSaveable(map.key.toString(), map.revision) { mutableStateOf(1f) }
    var panX by rememberSaveable(map.key.toString(), map.revision) { mutableStateOf(0f) }
    var panY by rememberSaveable(map.key.toString(), map.revision) { mutableStateOf(0f) }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    var lastTapUpMs by remember { mutableStateOf<Long?>(null) }
    var lastTapPosition by remember { mutableStateOf<Offset?>(null) }
    var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }
    val firstSelectedLaneId = selectedCrosswalkLaneIds.firstOrNull()
    val selectableSecondLaneIds = remember(map, firstSelectedLaneId, sremProfile) {
        firstSelectedLaneId?.let { connectedSremLaneIds(map, it, sremProfile) }.orEmpty()
    }
    val selectableFirstLaneIds = remember(map, sremProfile) { selectableLaneIds(map, sremProfile) }
    val currentSelectedLaneIds by rememberUpdatedState(selectedCrosswalkLaneIds)
    val currentProfile by rememberUpdatedState(sremProfile)
    // Connections leaving the currently selected inbound lane that are tappable for this vehicle type.
    val activeConnectionMovements = remember(map, firstSelectedLaneId, sremProfile) {
        if (selectedCrosswalkLaneIds.size == 1 && firstSelectedLaneId != null) {
            outgoingConnectionMovements(map, sremProfile, firstSelectedLaneId).map { it.lanePair to it }
        } else {
            emptyList()
        }
    }.toMap()
    fun updateTransform(nextScale: Float, nextPan: Offset) {
        val constrainedScale = nextScale.coerceIn(1f, INTERSECTION_MAX_ZOOM)
        val constrainedPan = clampIntersectionPan(
            pan = nextPan,
            zoomScale = constrainedScale,
            viewportWidth = canvasSize.width.toFloat(),
            viewportHeight = canvasSize.height.toFloat(),
        )
        zoomScale = constrainedScale
        panX = constrainedPan.x
        panY = constrainedPan.y
    }
    LaunchedEffect(spat?.receivedAtMs) {
        while (true) {
            nowMs = System.currentTimeMillis()
            delay(1_000L)
        }
    }
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(420.dp)
            .onSizeChanged { canvasSize = it }
            .clip(RoundedCornerShape(8.dp))
            .background(canvasBackground)
            .pointerInput(map.key, map.revision) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    down.consume()
                    val downPosition = down.position
                    val downTimeMs = down.uptimeMillis
                    val tapSlop = 18.dp.toPx()
                    val doubleTapSlop = 56.dp.toPx()
                    val isQuickScale = lastTapUpMs
                        ?.let { downTimeMs - it <= DOUBLE_TAP_TIMEOUT_MS }
                        ?.takeIf { it }
                        ?.let {
                            lastTapPosition?.let { previousTap ->
                                hypot(
                                    (downPosition.x - previousTap.x).toDouble(),
                                    (downPosition.y - previousTap.y).toDouble(),
                                ) <= doubleTapSlop
                            }
                        } == true
                    if (isQuickScale) {
                        lastTapUpMs = null
                        lastTapPosition = null
                    }
                    var movedDistance = 0f
                    var lastQuickScaleY = downPosition.y
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val pressedChanges = event.changes.filter { it.pressed }
                        event.changes.forEach { it.consume() }
                        if (pressedChanges.isEmpty()) {
                            if (!isQuickScale && movedDistance <= tapSlop && event.changes.any { !it.pressed }) {
                                val upTimeMs = event.changes.maxOf { it.uptimeMillis }
                                if (upTimeMs - downTimeMs <= TAP_TIMEOUT_MS) {
                                    resolveIntersectionTap(
                                        map = map,
                                        profile = currentProfile,
                                        selectedLaneIds = currentSelectedLaneIds,
                                        projection = buildIntersectionProjection(
                                            map = map,
                                            canvasSize = canvasSize,
                                            zoomScale = zoomScale,
                                            pan = Offset(panX, panY),
                                            paddingPx = 28.dp.toPx(),
                                        ),
                                        tap = downPosition,
                                        hitSlopPx = 18.dp.toPx(),
                                        connectionSlopPx = 24.dp.toPx(),
                                        controlDistancePx = 96.dp.toPx() * zoomScale,
                                    )?.let(onSelectionChange)
                                    lastTapUpMs = upTimeMs
                                    lastTapPosition = downPosition
                                }
                            }
                            break
                        }

                        if (isQuickScale && pressedChanges.size == 1) {
                            val currentY = pressedChanges.first().position.y
                            val dy = currentY - lastQuickScaleY
                            val scaleChange = exp((dy * QUICK_SCALE_SENSITIVITY).toDouble()).toFloat()
                            val nextScale = (zoomScale * scaleChange).coerceIn(1f, INTERSECTION_MAX_ZOOM)
                            val actualScaleChange = nextScale / zoomScale
                            updateTransform(
                                nextScale = nextScale,
                                nextPan = downPosition - (downPosition - Offset(panX, panY)) * actualScaleChange,
                            )
                            movedDistance += kotlin.math.abs(dy)
                            lastQuickScaleY = currentY
                            continue
                        }

                        if (pressedChanges.size >= 2) {
                            val zoom = event.calculateZoom()
                            val pan = event.calculatePan()
                            val centroid = event.calculateCentroid()
                            val nextScale = (zoomScale * zoom).coerceIn(1f, INTERSECTION_MAX_ZOOM)
                            val scaleChange = nextScale / zoomScale
                            val currentPan = Offset(panX, panY)
                            updateTransform(
                                nextScale = nextScale,
                                nextPan = centroid - (centroid - currentPan) * scaleChange + pan,
                            )
                            movedDistance += hypot(pan.x.toDouble(), pan.y.toDouble()).toFloat()
                            continue
                        }

                        val change = pressedChanges.first()
                        val pan = change.position - change.previousPosition
                        updateTransform(
                            nextScale = zoomScale,
                            nextPan = Offset(panX + pan.x, panY + pan.y),
                        )
                        movedDistance += hypot(pan.x.toDouble(), pan.y.toDouble()).toFloat()
                    }
                }
            },
    ) {
        val allNodes = map.lanes.flatMap { it.nodes }
        if (allNodes.isEmpty()) return@Canvas
        val minX = allNodes.minOf { it.xCm }.toFloat()
        val maxX = allNodes.maxOf { it.xCm }.toFloat()
        val minY = allNodes.minOf { it.yCm }.toFloat()
        val maxY = allNodes.maxOf { it.yCm }.toFloat()
        val padding = 28.dp.toPx()
        val width = (maxX - minX).coerceAtLeast(1f)
        val height = (maxY - minY).coerceAtLeast(1f)
        val scale = minOf((size.width - padding * 2) / width, (size.height - padding * 2) / height)
        val panOffset = Offset(panX, panY)

        fun point(x: Float, y: Float): Offset {
            val base = Offset(
                x = padding + (x - minX) * scale,
                y = size.height - padding - (y - minY) * scale,
            )
            return Offset(
                x = base.x * zoomScale + panOffset.x,
                y = base.y * zoomScale + panOffset.y,
            )
        }

        fun point(x: Int, y: Int): Offset = point(x.toFloat(), y.toFloat())

        fun drawLocationDot(center: Offset, accuracyM: Float?) {
            accuracyM?.let { accuracy ->
                val accuracyRadius = (accuracy * 100f * scale * zoomScale)
                    .coerceIn(10.dp.toPx(), 48.dp.toPx())
                drawCircle(
                    color = paletteColor(R.color.info).copy(alpha = 0.08f),
                    radius = accuracyRadius,
                    center = center,
                )
                drawCircle(
                    color = paletteColor(R.color.info).copy(alpha = 0.24f),
                    radius = accuracyRadius,
                    center = center,
                    style = Stroke(width = 1.dp.toPx()),
                )
            }
            drawCircle(paletteColor(R.color.on_info), radius = 8.dp.toPx(), center = center)
            drawCircle(paletteColor(R.color.info), radius = 6.dp.toPx(), center = center)
            drawCircle(paletteColor(R.color.on_info), radius = 2.dp.toPx(), center = center)
        }

        fun drawLocationEdgeArrow(direction: Offset) {
            val margin = 16.dp.toPx()
            val halfWidth = (size.width / 2f - margin).coerceAtLeast(1f)
            val halfHeight = (size.height / 2f - margin).coerceAtLeast(1f)
            val vectorLength = sqrt(direction.x * direction.x + direction.y * direction.y).coerceAtLeast(0.001f)
            val unit = Offset(direction.x / vectorLength, direction.y / vectorLength)
            val edgeScale = minOf(
                halfWidth / kotlin.math.abs(unit.x).coerceAtLeast(0.001f),
                halfHeight / kotlin.math.abs(unit.y).coerceAtLeast(0.001f),
            )
            val tip = Offset(size.width / 2f, size.height / 2f) + unit * edgeScale
            val markerLength = 22.dp.toPx()
            val markerHalfWidth = 9.dp.toPx()
            val base = tip - unit * markerLength
            val normal = Offset(-unit.y, unit.x)
            val arrow = Path().apply {
                moveTo(tip.x, tip.y)
                lineTo(base.x + normal.x * markerHalfWidth, base.y + normal.y * markerHalfWidth)
                lineTo(base.x - normal.x * markerHalfWidth, base.y - normal.y * markerHalfWidth)
                close()
            }
            drawCircle(paletteColor(R.color.on_info).copy(alpha = 0.92f), radius = 17.dp.toPx(), center = tip - unit * 9.dp.toPx())
            drawPath(arrow, paletteColor(R.color.on_info), style = Stroke(width = 5.dp.toPx(), join = StrokeJoin.Round))
            drawPath(arrow, paletteColor(R.color.info))
        }

        val lanesById = map.lanes.associateBy { it.id }
        fun connectionColorFor(lane: MapLane, connection: LaneConnection): Color {
            val phase = connection.signalGroup?.let { signalGroups[it]?.currentEvent?.state }
            return phase?.let { phaseColor(it) } ?: baseColor(lane.laneType)
        }

        fun laneSelectionAlpha(lane: MapLane): Float {
            // While a selection is active keep vehicle-type-compatible lanes at full emphasis and dim the
            // rest; before any selection every lane is shown normally so the full layout stays readable.
            return intersectionLaneSelectionAlpha(
                laneId = lane.id,
                selectedLaneIds = selectedCrosswalkLaneIds,
                selectableLaneIds = selectableSecondLaneIds + selectableFirstLaneIds,
            )
        }

        fun laneLabelPoint(
            lane: MapLane,
            labelWidth: Float,
            labelHeight: Float,
            laneWidth: Float,
        ): Offset {
            val lanePoints = lane.nodes.map { node -> point(node.xCm, node.yCm) }
            val startPoint = lanePoints.first()
            val endPoint = lanePoints.last()
            val center = Offset((startPoint.x + endPoint.x) / 2f, (startPoint.y + endPoint.y) / 2f)
            if (lane.laneType != LaneType.Crosswalk) return center

            val laneLength = lanePoints.zipWithNext().sumOf { (start, end) ->
                hypot((end.x - start.x).toDouble(), (end.y - start.y).toDouble())
            }.toFloat()
            val sideOffset = countdownSideOffset(
                directionX = endPoint.x - startPoint.x,
                directionY = endPoint.y - startPoint.y,
                laneLength = laneLength,
                labelWidth = labelWidth,
                labelHeight = labelHeight,
                laneWidth = laneWidth,
                gap = 5.dp.toPx(),
            )
            if (sideOffset.first == 0f && sideOffset.second == 0f) return center

            val firstSide = center + Offset(sideOffset.first, sideOffset.second)
            val secondSide = center - Offset(sideOffset.first, sideOffset.second)
            fun viewportOverflow(labelCenter: Offset): Float {
                val left = labelCenter.x - labelWidth / 2f
                val right = labelCenter.x + labelWidth / 2f
                val top = labelCenter.y - labelHeight / 2f
                val bottom = labelCenter.y + labelHeight / 2f
                return (-left).coerceAtLeast(0f) +
                    (right - size.width).coerceAtLeast(0f) +
                    (-top).coerceAtLeast(0f) +
                    (bottom - size.height).coerceAtLeast(0f)
            }
            return if (viewportOverflow(firstSide) <= viewportOverflow(secondSide)) firstSide else secondSide
        }

        // Builds a lane's polyline by projecting each decoded node into screen space. Node coordinates
        // are in lane-centimetre space; the `point(...)` transform maps them into the current viewport.
        fun lanePath(lane: MapLane): Path = Path().apply {
            val first = lane.nodes.first()
            moveTo(point(first.xCm, first.yCm).x, point(first.xCm, first.yCm).y)
            lane.nodes.drop(1).forEach { node ->
                val p = point(node.xCm, node.yCm)
                lineTo(p.x, p.y)
            }
        }

        val crosswalkDash = PathEffect.dashPathEffect(floatArrayOf(10.dp.toPx(), 8.dp.toPx()))
        val bikeDash = PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 7.dp.toPx()))
        val sidewalkDash = PathEffect.dashPathEffect(floatArrayOf(16.dp.toPx(), 8.dp.toPx()))
        val medianDash = PathEffect.dashPathEffect(floatArrayOf(18.dp.toPx(), 10.dp.toPx()))
        val stripingDash = PathEffect.dashPathEffect(floatArrayOf(10.dp.toPx(), 4.dp.toPx(), 2.dp.toPx(), 4.dp.toPx()))
        val parkingDash = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 6.dp.toPx()))
        val otherDash = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 8.dp.toPx()))

        class LaneVisualStyle(
            val width: Float,
            val pathEffect: PathEffect? = null,
            val backingWidth: Float? = null,
            val backingColor: Color = paletteColor(R.color.lane_backing).copy(alpha = 0.84f),
            val centerGapWidth: Float? = null,
            val unsignalizedAlpha: Float = 0.72f,
        )

        fun styleFor(type: LaneType): LaneVisualStyle = when (type) {
            LaneType.Vehicle -> LaneVisualStyle(width = 4.5.dp.toPx(), unsignalizedAlpha = 0.58f)
            LaneType.Crosswalk -> LaneVisualStyle(
                width = 5.dp.toPx(),
                pathEffect = crosswalkDash,
                backingWidth = 8.dp.toPx(),
                backingColor = paletteColor(R.color.lane_backing).copy(alpha = 0.76f),
                unsignalizedAlpha = 0.76f,
            )
            LaneType.Bike -> LaneVisualStyle(
                width = 3.5.dp.toPx(),
                pathEffect = bikeDash,
                backingWidth = 5.5.dp.toPx(),
                backingColor = paletteColor(R.color.lane_backing).copy(alpha = 0.58f),
                unsignalizedAlpha = 0.42f,
            )
            LaneType.Sidewalk -> LaneVisualStyle(
                width = 3.dp.toPx(),
                pathEffect = sidewalkDash,
                backingWidth = 5.dp.toPx(),
                backingColor = paletteColor(R.color.lane_backing).copy(alpha = 0.5f),
                unsignalizedAlpha = 0.28f,
            )
            LaneType.Median -> LaneVisualStyle(
                width = 6.dp.toPx(),
                pathEffect = medianDash,
                unsignalizedAlpha = 0.22f,
            )
            LaneType.Striping -> LaneVisualStyle(
                width = 2.5.dp.toPx(),
                pathEffect = stripingDash,
                unsignalizedAlpha = 0.28f,
            )
            LaneType.TrackedVehicle -> LaneVisualStyle(
                width = 6.dp.toPx(),
                backingWidth = 8.dp.toPx(),
                backingColor = paletteColor(R.color.lane_backing).copy(alpha = 0.54f),
                centerGapWidth = 3.dp.toPx(),
                unsignalizedAlpha = 0.4f,
            )
            LaneType.Parking -> LaneVisualStyle(
                width = 3.dp.toPx(),
                pathEffect = parkingDash,
                unsignalizedAlpha = 0.25f,
            )
            LaneType.Other -> LaneVisualStyle(
                width = 2.5.dp.toPx(),
                pathEffect = otherDash,
                unsignalizedAlpha = 0.22f,
            )
        }

        fun drawCenterGap(path: Path, style: LaneVisualStyle) {
            style.centerGapWidth?.let { centerGapWidth ->
                drawPath(
                    path = path,
                    color = canvasBackground,
                    style = Stroke(
                        width = centerGapWidth,
                        cap = StrokeCap.Round,
                        join = StrokeJoin.Round,
                        pathEffect = style.pathEffect,
                    ),
                )
            }
        }

        fun drawStyledPath(
            path: Path,
            style: LaneVisualStyle,
            color: Color,
            colorAlpha: Float,
            styleAlpha: Float,
        ) {
            style.backingWidth?.let { backingWidth ->
                drawPath(
                    path = path,
                    color = style.backingColor.copy(alpha = style.backingColor.alpha * styleAlpha),
                    style = Stroke(
                        width = backingWidth,
                        cap = StrokeCap.Round,
                        join = StrokeJoin.Round,
                        pathEffect = style.pathEffect,
                    ),
                )
            }
            drawPath(
                path = path,
                color = color.copy(alpha = colorAlpha),
                style = Stroke(
                    width = style.width,
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round,
                    pathEffect = style.pathEffect,
                ),
            )
            drawCenterGap(path, style)
        }

        fun renderOrder(type: LaneType): Int = when (type) {
            LaneType.Median -> 0
            LaneType.Striping -> 1
            LaneType.Parking -> 2
            LaneType.Sidewalk -> 3
            LaneType.Bike -> 4
            LaneType.TrackedVehicle -> 5
            LaneType.Vehicle -> 6
            LaneType.Other -> 7
            LaneType.Crosswalk -> 8
        }

        map.lanes.sortedBy { renderOrder(it.laneType) }.forEach { lane ->
            if (lane.nodes.size < 2) return@forEach
            val color = baseColor(lane.laneType)
            val path = lanePath(lane)
            val style = styleFor(lane.laneType)
            val selectionAlpha = laneSelectionAlpha(lane)
            drawStyledPath(
                path = path,
                style = style,
                color = color,
                colorAlpha = style.unsignalizedAlpha * selectionAlpha,
                styleAlpha = selectionAlpha,
            )
            if (lane.id in selectedCrosswalkLaneIds) {
                drawPath(
                    path = path,
                    color = paletteColor(R.color.primary),
                    style = Stroke(
                        width = style.width + 7.dp.toPx(),
                        cap = StrokeCap.Round,
                        join = StrokeJoin.Round,
                    ),
                )
                drawPath(
                    path = path,
                    color = paletteColor(R.color.on_primary),
                    style = Stroke(
                        width = style.width + 3.dp.toPx(),
                        cap = StrokeCap.Round,
                        join = StrokeJoin.Round,
                    ),
                )
                drawPath(
                    path = path,
                    color = color,
                    style = Stroke(
                        width = style.width,
                        cap = StrokeCap.Round,
                        join = StrokeJoin.Round,
                        pathEffect = style.pathEffect,
                    ),
                )
                drawCenterGap(path, style)
            }
        }
        val drawnConnections = mutableSetOf<Pair<Int, Int>>()
        map.lanes.forEach { lane ->
            val style = styleFor(lane.laneType)
            lane.connections.forEach { connection ->
                if (connection.remoteIntersection != null) return@forEach
                val connectedLane = lanesById[connection.laneId]
                if (connectedLane == null) return@forEach
                if (lane.nodes.size < 2 || connectedLane.nodes.size < 2) return@forEach
                val connectionIsVisible = intersectionConnectionVisible(
                    laneId = lane.id,
                    connectedLaneId = connectedLane.id,
                    selectedLaneIds = selectedCrosswalkLaneIds,
                )
                if (!connectionIsVisible) return@forEach
                val connectionKey = minOf(lane.id, connectedLane.id) to maxOf(lane.id, connectedLane.id)
                if (!drawnConnections.add(connectionKey)) return@forEach
                val connector = laneConnector(lane, connectedLane)
                if (connector == null) {
                    // A resolvable MAPEM connection with a degenerate lane (fewer than two nodes) cannot be
                    // drawn. Long spans are legitimate (a junction connects far-side lanes), so distance is
                    // never a reason to hide a connector.
                    return@forEach
                }
                val start = point(connector.start.x, connector.start.y)
                val startAdjacent = point(connector.startAdjacent.x, connector.startAdjacent.y)
                val end = point(connector.end.x, connector.end.y)
                val endAdjacent = point(connector.endAdjacent.x, connector.endAdjacent.y)
                val controls = roadConnectionControlPoints(
                    startX = start.x,
                    startY = start.y,
                    startAdjacentX = startAdjacent.x,
                    startAdjacentY = startAdjacent.y,
                    endX = end.x,
                    endY = end.y,
                    endAdjacentX = endAdjacent.x,
                    endAdjacentY = endAdjacent.y,
                    maxControlDistance = 96.dp.toPx() * zoomScale,
                )
                val path = Path().apply {
                    moveTo(start.x, start.y)
                    cubicTo(
                        controls.startX,
                        controls.startY,
                        controls.endX,
                        controls.endY,
                        end.x,
                        end.y,
                    )
                }
                val signalized = connection.signalGroup?.let(signalGroups::containsKey) == true
                val tram = lane.laneType == LaneType.TrackedVehicle || connectedLane.laneType == LaneType.TrackedVehicle
                // Selected movement connections stand out; at rest signalized/tram connectors lead and
                // plain MAPEM topology connectors stay visible but recede, so nothing looks disconnected.
                val connectionAlpha = when {
                    selectedCrosswalkLaneIds.isNotEmpty() -> 0.92f
                    signalized || tram -> 0.66f
                    else -> 0.40f
                }
                // Emphasize connections that are currently tappable targets (leaving the selected inbound
                // lane and compatible with the vehicle type) so the lane -> connection path is discoverable.
                val isActiveTapTarget = activeConnectionMovements.containsKey(listOf(lane.id, connectedLane.id))
                if (isActiveTapTarget) {
                    drawPath(
                        path = path,
                        color = paletteColor(R.color.primary).copy(alpha = 0.5f),
                        style = Stroke(
                            width = style.width + 9.dp.toPx(),
                            cap = StrokeCap.Round,
                            join = StrokeJoin.Round,
                        ),
                    )
                }
                drawStyledPath(
                    path = path,
                    style = style,
                    color = connectionColorFor(lane, connection),
                    colorAlpha = connectionAlpha,
                    styleAlpha = connectionAlpha,
                )
            }
        }
        if (zoomScale >= LANE_TIMING_ZOOM_THRESHOLD || selectedCrosswalkLaneIds.isNotEmpty()) {
            val textPaint = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
                color = paletteColor(R.color.on_phase_active).toArgb()
                textAlign = AndroidPaint.Align.LEFT
                textSize = 12.dp.toPx()
                typeface = AndroidTypeface.create(AndroidTypeface.DEFAULT, AndroidTypeface.BOLD)
            }
            val horizontalPadding = 6.dp.toPx()
            val verticalPadding = 3.dp.toPx()
            val occupiedLabels = mutableListOf<CountdownLabelBounds>()
            val representatives = countdownLaneRepresentatives(
                lanes = map.lanes,
                availableSignalGroups = signalGroups.keys,
                selectedLaneIds = selectedCrosswalkLaneIds.toSet(),
                selectableLaneIds = selectableSecondLaneIds,
            )
            val emphasizedSignalGroups = countdownSignalGroupsForSelection(
                lanes = map.lanes,
                selectedLaneIds = selectedCrosswalkLaneIds,
                availableSignalGroups = signalGroups.keys,
            )
            representatives.forEach { representative ->
                val lane = representative.lane
                if (lane.nodes.size < 2) return@forEach
                if (zoomScale < LANE_TIMING_ZOOM_THRESHOLD &&
                    lane.id !in selectedCrosswalkLaneIds && lane.id !in selectableSecondLaneIds
                ) return@forEach
                val event = signalGroups[representative.signalGroup]?.currentEvent ?: return@forEach
                val seconds = event.secondsUntilChange(spat, nowMs) ?: return@forEach
                val label = "${seconds}s"
                val textWidth = textPaint.measureText(label)
                val labelWidth = textWidth + horizontalPadding * 2
                val labelHeight = textPaint.textSize + verticalPadding * 2
                val labelPoint = laneLabelPoint(
                    lane = lane,
                    labelWidth = labelWidth,
                    labelHeight = labelHeight,
                    laneWidth = styleFor(lane.laneType).width,
                )
                val bounds = placeCountdownLabel(
                    preferredX = labelPoint.x,
                    preferredY = labelPoint.y,
                    labelWidth = labelWidth,
                    labelHeight = labelHeight,
                    viewportWidth = size.width,
                    viewportHeight = size.height,
                    occupied = occupiedLabels,
                    gap = 4.dp.toPx(),
                ) ?: return@forEach
                occupiedLabels += bounds
                val topLeft = Offset(bounds.left, bounds.top)
                val deemphasized = emphasizedSignalGroups.isNotEmpty() &&
                    representative.signalGroup !in emphasizedSignalGroups
                drawRoundRect(
                    color = phaseColor(event.state).copy(alpha = if (deemphasized) 0.16f else 0.94f),
                    topLeft = topLeft,
                    size = Size(labelWidth, labelHeight),
                    cornerRadius = CornerRadius(5.dp.toPx(), 5.dp.toPx()),
                )
                textPaint.color = (if (deemphasized) paletteColor(R.color.on_surface_variant) else paletteColor(R.color.on_phase_active)).toArgb()
                drawContext.canvas.nativeCanvas.drawText(
                    label,
                    topLeft.x + horizontalPadding,
                    topLeft.y + verticalPadding + textPaint.textSize * 0.82f,
                    textPaint,
                )
            }
        }

        currentPosition?.let { position ->
            val location = position.toIntersectionOffsetCm(map)
            val laneWidthPadding = ((map.laneWidthCm ?: 300) / 2f).coerceAtLeast(150f)
            val withinIntersection =
                location.x in (minX - laneWidthPadding)..(maxX + laneWidthPadding) &&
                    location.y in (minY - laneWidthPadding)..(maxY + laneWidthPadding)
            if (withinIntersection) {
                drawLocationDot(point(location.x, location.y), position.accuracyM)
            } else {
                val centerX = (minX + maxX) / 2f
                val centerY = (minY + maxY) / 2f
                drawLocationEdgeArrow(
                    Offset(
                        x = location.x - centerX,
                        y = centerY - location.y,
                    ),
                )
            }
        }
    }
}

@Composable
private fun SignalTimingPanel(snapshot: IntersectionSnapshot?, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val map = snapshot?.map
    val spat = snapshot?.spat
    val movements = spat?.movements.orEmpty()
    if (movements.isEmpty()) return
    var expanded by rememberSaveable(map?.key.toString(), spat?.revision) { mutableStateOf(false) }
    var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(spat?.receivedAtMs, expanded) {
        while (expanded) {
            nowMs = System.currentTimeMillis()
            delay(1_000L)
        }
    }
    Column(
        modifier
            .fillMaxWidth()
            .background(Color(ContextCompat.getColor(context, R.color.card)), RoundedCornerShape(8.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Signal phases", style = MaterialTheme.typography.titleMedium)
                Text(
                    listOfNotNull(
                        "${movements.size} groups",
                        map?.let { "${it.lanes.size} lanes" },
                        map?.let { intersection -> "${intersection.lanes.count { it.connections.isNotEmpty() }} linked" },
                    ).joinToString(" • "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
            TextButton(onClick = { expanded = !expanded }) {
                Text(if (expanded) "Hide" else "Show")
            }
        }
        if (!expanded) return@Column
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Group", modifier = Modifier.weight(0.6f), style = MaterialTheme.typography.labelMedium)
            Text("Phase", modifier = Modifier.weight(1.2f), style = MaterialTheme.typography.labelMedium)
            Text("Next change", modifier = Modifier.weight(1.0f), style = MaterialTheme.typography.labelMedium)
        }
        movements.sortedBy { it.signalGroup }.forEach { movement ->
            val event = movement.currentEvent
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "SG ${movement.signalGroup}",
                    modifier = Modifier.weight(0.6f),
                    fontWeight = FontWeight.SemiBold,
                )
                Row(
                    modifier = Modifier.weight(1.2f),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Canvas(Modifier.width(18.dp).height(18.dp)) {
                        drawCircle(
                            event?.state?.let { Color(ContextCompat.getColor(context, it.phaseColorResId())) }
                                ?: Color(ContextCompat.getColor(context, R.color.lane_other)),
                            radius = 6.dp.toPx(),
                        )
                    }
                    Text(event?.state?.label ?: "Unknown")
                }
                Text(
                    event?.secondsUntilChange(spat, nowMs)?.let { "${it}s" } ?: "No timing",
                    modifier = Modifier.weight(1.0f),
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
        }
    }
}

private fun MovementPhaseState.phaseColorResId(): Int = when (this) {
    MovementPhaseState.StopAndRemain,
    MovementPhaseState.StopThenProceed -> R.color.phase_stop
    MovementPhaseState.PreMovement,
    MovementPhaseState.PermissiveClearance,
    MovementPhaseState.ProtectedClearance,
    MovementPhaseState.CautionConflictingTraffic -> R.color.phase_caution
    MovementPhaseState.PermissiveAllowed,
    MovementPhaseState.ProtectedAllowed -> R.color.phase_allowed
    MovementPhaseState.Dark,
    MovementPhaseState.Unavailable,
    MovementPhaseState.Unknown -> R.color.phase_unknown
}

private fun LaneType.baseColorResId(): Int = when (this) {
    LaneType.Vehicle -> R.color.lane_vehicle
    LaneType.Crosswalk -> R.color.lane_crosswalk
    LaneType.Bike -> R.color.lane_bike
    LaneType.Sidewalk -> R.color.lane_sidewalk
    LaneType.TrackedVehicle -> R.color.lane_tracked_vehicle
    LaneType.Parking -> R.color.lane_parking
    LaneType.Median,
    LaneType.Striping,
    LaneType.Other -> R.color.lane_other
}
