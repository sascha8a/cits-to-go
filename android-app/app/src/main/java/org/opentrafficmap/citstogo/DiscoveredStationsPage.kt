package org.opentrafficmap.citstogo

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.FilterAlt
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.DateRangePickerState
import androidx.compose.material3.DisplayMode
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.coerceIn
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import org.opentrafficmap.citstogo.station.END_OF_DAY
import org.opentrafficmap.citstogo.station.DiscoveredStation
import org.opentrafficmap.citstogo.station.StationColumn
import org.opentrafficmap.citstogo.station.StationColumnKind
import org.opentrafficmap.citstogo.station.StationFilter
import org.opentrafficmap.citstogo.station.StationTableState
import org.opentrafficmap.citstogo.station.StationTime
import org.opentrafficmap.citstogo.station.TextMatchMode
import org.opentrafficmap.citstogo.station.TimeShortcut

/**
 * Table of the C-ITS stations the receiver has heard from. Tapping a header sorts by that column, its
 * funnel opens a filter editor that fits the column's values, and dragging its right edge sets the
 * width by hand; the current sort, filters and widths arrive through [tableState] and are handed back
 * through the callbacks so the activity can persist them.
 */
@Composable
fun DiscoveredStationsPage(
    stations: List<DiscoveredStation>,
    tableState: StationTableState,
    onTableStateChange: (StationTableState) -> Unit,
    onColumnWidthChange: (StationColumn, Int) -> Unit,
    onColumnWidthChangeFinished: () -> Unit,
    onClearStations: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            nowMs = System.currentTimeMillis()
            delay(1_000L)
        }
    }
    // Keyed on what actually selects and orders the rows, so a column-width drag does not re-sort
    // and re-measure the whole table on every frame.
    val rows = remember(stations, tableState.filters, tableState.sortColumn, tableState.sortDescending, nowMs) {
        tableState.rows(stations, nowMs)
    }
    val columnWidths = rememberColumnWidths(rows, tableState)
    var filterColumn by remember { mutableStateOf<StationColumn?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    val columnsState = rememberScrollState()
    val context = LocalContext.current
    val stripedColor = Color(ContextCompat.getColor(context, R.color.surface_variant)).copy(alpha = 0.45f)

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        StationTableToolbar(
            total = stations.size,
            shown = rows.size,
            tableState = tableState,
            onReset = { onTableStateChange(StationTableState.DEFAULT) },
            onClear = { confirmClear = true },
            onDropFilter = { column -> onTableStateChange(tableState.withoutFilter(column)) },
            onAutoWidth = { column -> onTableStateChange(tableState.withoutWidth(column)) },
        )
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp)),
            shape = RoundedCornerShape(10.dp),
            colors = CardDefaults.cardColors(containerColor = Color(ContextCompat.getColor(context, R.color.card))),
        ) {
            StationTableHeaderRow(
                tableState = tableState,
                widths = columnWidths,
                columnsState = columnsState,
                onSort = { column -> onTableStateChange(tableState.sortedBy(column)) },
                onFilter = { column -> filterColumn = column },
                onWidthChange = { column, width -> onColumnWidthChange(column, width) },
                onWidthChangeFinished = onColumnWidthChangeFinished,
                onAutoWidth = { column -> onTableStateChange(tableState.withoutWidth(column)) },
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            when {
                stations.isEmpty() -> StationTableMessage(
                    "No stations yet",
                    "Start a capture or replay a PCAP to populate this table.",
                )
                rows.isEmpty() -> StationTableMessage(
                    "No station matches",
                    "Relax the filters or press Reset to see all ${stations.size} discovered stations.",
                )
                else -> LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                    itemsIndexed(rows, key = { _, station -> station.macAddress }) { index, station ->
                        StationTableRow(
                            station = station,
                            tableState = tableState,
                            widths = columnWidths,
                            columnsState = columnsState,
                            nowMs = nowMs,
                            striped = index % 2 == 1,
                            stripedColor = stripedColor,
                        )
                    }
                }
            }
        }
    }

    filterColumn?.let { column ->
        StationFilterDialog(
            column = column,
            initialFilter = tableState.filters[column],
            onApply = { filter ->
                onTableStateChange(tableState.withFilter(column, filter))
                filterColumn = null
            },
            onClear = {
                onTableStateChange(tableState.withoutFilter(column))
                filterColumn = null
            },
            onDismiss = { filterColumn = null },
        )
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Delete discovered stations?") },
            text = {
                Text("This forgets all ${stations.size} stored stations. Packets received afterwards build a new table.")
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    onClearStations()
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun StationTableToolbar(
    total: Int,
    shown: Int,
    tableState: StationTableState,
    onReset: () -> Unit,
    onClear: () -> Unit,
    onDropFilter: (StationColumn) -> Unit,
    onAutoWidth: (StationColumn) -> Unit,
) {
    // Every view override the user has set, each one removable by tapping its chip.
    val overrides = buildList {
        tableState.filters.forEach { (column, filter) ->
            add("${column.title}: ${filter.summary}" to { onDropFilter(column) })
        }
        tableState.widths.forEach { (column, width) ->
            add("${column.title}: fixed ${width} dp" to { onAutoWidth(column) })
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = if (shown == total) "$total stations" else "$shown of $total stations",
                modifier = Modifier.weight(1f),
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.secondary,
            )
            TextButton(onClick = onReset) { Text("Reset", fontSize = 14.sp) }
            TextButton(onClick = onClear) { Text("Clear list", fontSize = 14.sp) }
        }
        if (overrides.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                overrides.forEach { (label, remove) ->
                    StationFilterChip(label = label, onRemove = remove)
                }
            }
        }
    }
}

@Composable
private fun StationFilterChip(
    label: String,
    onRemove: () -> Unit,
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.primaryContainer)
            .clickable(onClick = onRemove)
            .padding(horizontal = 9.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Text(
            text = label,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Icon(
            imageVector = Icons.Rounded.Close,
            contentDescription = "Remove $label",
            modifier = Modifier.size(13.dp),
            tint = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
}

@Composable
private fun StationTableHeaderRow(
    tableState: StationTableState,
    widths: Map<StationColumn, Dp>,
    columnsState: ScrollState,
    onSort: (StationColumn) -> Unit,
    onFilter: (StationColumn) -> Unit,
    onWidthChange: (StationColumn, Int) -> Unit,
    onWidthChangeFinished: () -> Unit,
    onAutoWidth: (StationColumn) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(columnsState)
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        StationColumn.ordered.forEach { column ->
            StationTableHeaderCell(
                column = column,
                width = widths.getValue(column),
                sorted = tableState.sortColumn == column,
                sortDescending = tableState.sortDescending,
                filtered = tableState.filters.containsKey(column),
                resized = tableState.widths.containsKey(column),
                onClick = { onSort(column) },
                onFilterClick = { onFilter(column) },
                onWidthChange = { width -> onWidthChange(column, width) },
                onWidthChangeFinished = onWidthChangeFinished,
                onAutoWidth = { onAutoWidth(column) },
            )
        }
    }
}

@Composable
private fun StationTableHeaderCell(
    column: StationColumn,
    width: Dp,
    sorted: Boolean,
    sortDescending: Boolean,
    filtered: Boolean,
    resized: Boolean,
    onClick: () -> Unit,
    onFilterClick: () -> Unit,
    onWidthChange: (Int) -> Unit,
    onWidthChangeFinished: () -> Unit,
    onAutoWidth: () -> Unit,
) {
    val accentColor = if (sorted || filtered) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        modifier = Modifier
            .width(width)
            .clip(RoundedCornerShape(4.dp))
            .clickable(onClick = onClick)
            .padding(start = 8.dp, end = 2.dp, top = 7.dp, bottom = 7.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = column.title,
                modifier = Modifier.weight(1f),
                fontSize = 11.sp,
                lineHeight = 13.sp,
                fontWeight = if (sorted) FontWeight.SemiBold else FontWeight.Medium,
                color = accentColor,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Icon(
                imageVector = when {
                    !sorted -> Icons.Rounded.SwapVert
                    sortDescending -> Icons.Rounded.ArrowDownward
                    else -> Icons.Rounded.ArrowUpward
                },
                contentDescription = when {
                    !sorted -> "Sort by ${column.title}"
                    sortDescending -> "${column.title}: highest first"
                    else -> "${column.title}: lowest first"
                },
                modifier = Modifier.size(13.dp),
                tint = accentColor.copy(alpha = if (sorted) 1f else 0.45f),
            )
            Spacer(Modifier.width(2.dp))
            if (column.filterable) {
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .clickable(onClick = onFilterClick),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.FilterAlt,
                        contentDescription = "Filter ${column.title}",
                        modifier = Modifier.size(14.dp),
                        tint = if (filtered) MaterialTheme.colorScheme.primary else accentColor.copy(alpha = 0.45f),
                    )
                }
            }
            StationColumnResizeHandle(
                column = column,
                width = width,
                resized = resized,
                onWidthChange = onWidthChange,
                onWidthChangeFinished = onWidthChangeFinished,
                onAutoWidth = onAutoWidth,
            )
        }
    }
}

/**
 * Grabber on a column's right edge: drag it to set the width by hand, tap it to hand the column back
 * to the table's own measurement. The dragged width is accumulated here rather than read back from
 * [width], because the state clamps the value and recomposition can lag a frame behind the finger.
 */
@Composable
private fun StationColumnResizeHandle(
    column: StationColumn,
    width: Dp,
    resized: Boolean,
    onWidthChange: (Int) -> Unit,
    onWidthChangeFinished: () -> Unit,
    onAutoWidth: () -> Unit,
) {
    val liveWidth = rememberUpdatedState(width)
    var dragged by remember(column) { mutableStateOf(width) }
    Box(
        modifier = Modifier
            .padding(start = 4.dp)
            .size(width = RESIZE_HANDLE_WIDTH_DP.dp, height = 24.dp)
            .clip(RoundedCornerShape(3.dp))
            .semantics { contentDescription = "Resize ${column.title}" }
            .combinedClickable(onClick = onAutoWidth)
            .pointerInput(column) {
                detectHorizontalDragGestures(
                    onDragStart = { dragged = liveWidth.value },
                    onDragCancel = onWidthChangeFinished,
                    onDragEnd = onWidthChangeFinished,
                ) { _, dragAmountPx ->
                    dragged = (dragged + dragAmountPx.toDp())
                        .coerceIn(column.minWidthDp.dp, StationColumn.MAX_WIDTH_DP.dp)
                    onWidthChange(dragged.value.roundToInt())
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(width = 2.dp, height = 16.dp)
                .background(
                    if (resized) {
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.8f)
                    } else {
                        MaterialTheme.colorScheme.outline.copy(alpha = 0.55f)
                    },
                    RoundedCornerShape(1.dp),
                ),
        )
    }
}

@Composable
private fun StationTableRow(
    station: DiscoveredStation,
    tableState: StationTableState,
    widths: Map<StationColumn, Dp>,
    columnsState: ScrollState,
    nowMs: Long,
    striped: Boolean,
    stripedColor: Color,
) {
    val background = if (striped) Modifier.background(stripedColor) else Modifier
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(columnsState)
            .then(background),
    ) {
        StationColumn.ordered.forEach { column ->
            StationTableCell(
                column = column,
                station = station,
                width = widths.getValue(column),
                sorted = tableState.sortColumn == column,
                nowMs = nowMs,
            )
        }
    }
}

@Composable
private fun StationTableCell(
    column: StationColumn,
    station: DiscoveredStation,
    width: Dp,
    sorted: Boolean,
    nowMs: Long,
) {
    val text = column.display(station)
    val missing = text == StationColumn.MISSING
    val zeroPacketType = column.packetType != null && station.typeCounts[column.packetType] == null
    Box(
        modifier = Modifier
            .width(width)
            .padding(horizontal = 8.dp, vertical = 5.dp),
    ) {
        Column {
            Text(
                text = text,
                fontSize = 12.sp,
                lineHeight = 15.sp,
                fontFamily = if (column in MONOSPACE_COLUMNS) FontFamily.Monospace else FontFamily.Default,
                fontWeight = if (sorted) FontWeight.Medium else FontWeight.Normal,
                color = when {
                    zeroPacketType || missing -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
                    else -> MaterialTheme.colorScheme.onSurface
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // "How long ago" under the timestamp the table is sorted by.
            val caption = if (column === StationColumn.lastSeen && station.lastSeenMs > 0L) {
                StationTime.ago(station.lastSeenMs, nowMs)
            } else {
                null
            }
            if (caption != null) {
                Text(
                    text = caption,
                    fontSize = 10.sp,
                    lineHeight = 12.sp,
                    color = MaterialTheme.colorScheme.secondary,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun StationTableMessage(title: String, detail: String) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 26.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
        Text(detail, fontSize = 13.sp, color = MaterialTheme.colorScheme.secondary)
    }
}

/**
 * Filter editor for one column. Each kind of value gets controls that fit it - match modes for text,
 * bounds for counts, a dBm slider for signal, a date range with rolling shortcuts for timestamps -
 * and the result is handed back as a [StationFilter]; a selection that holds no constraint removes
 * the filter again.
 */
@Composable
private fun StationFilterDialog(
    column: StationColumn,
    initialFilter: StationFilter?,
    onApply: (StationFilter?) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    when (column.kind) {
        StationColumnKind.Text -> StationTextFilterDialog(column, initialFilter, onApply, onClear, onDismiss)
        StationColumnKind.Count -> StationCountFilterDialog(column, initialFilter, onApply, onClear, onDismiss)
        StationColumnKind.Signal -> StationSignalFilterDialog(column, initialFilter, onApply, onClear, onDismiss)
        StationColumnKind.Timestamp -> StationTimeFilterDialog(column, initialFilter, onApply, onClear, onDismiss)
        StationColumnKind.Position -> Unit // the header has no funnel for this kind
    }
}

@Composable
private fun StationFilterDialogScaffold(
    column: StationColumn,
    hasStoredFilter: Boolean,
    confirmEnabled: Boolean,
    onApply: () -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Filter ${column.title}", fontSize = 18.sp) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(7.dp),
            ) { content() }
        },
        confirmButton = { TextButton(onClick = onApply, enabled = confirmEnabled) { Text("Apply") } },
        dismissButton = {
            Row {
                if (hasStoredFilter) {
                    TextButton(onClick = onClear) { Text("Clear") }
                }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

/**
 * Chip border with a clearly drawn outline, so a filter option reads as a button rather than a
 * label. Material's default unchecked border is too faint for that inside a dialog.
 */
@Composable
private fun StationFilterChipBorder(selected: Boolean): BorderStroke = FilterChipDefaults.filterChipBorder(
    enabled = true,
    selected = selected,
    borderColor = MaterialTheme.colorScheme.outline,
    selectedBorderColor = MaterialTheme.colorScheme.primary,
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StationTextFilterDialog(
    column: StationColumn,
    initialFilter: StationFilter?,
    onApply: (StationFilter?) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    val stored = initialFilter as? StationFilter.Text
    var query by remember(column) { mutableStateOf(stored?.query.orEmpty()) }
    var mode by remember(column) { mutableStateOf(stored?.mode ?: TextMatchMode.Contains) }
    StationFilterDialogScaffold(
        column = column,
        hasStoredFilter = initialFilter != null,
        confirmEnabled = true,
        onApply = { onApply(StationFilter.Text(mode, query)) },
        onClear = onClear,
        onDismiss = onDismiss,
    ) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            TextMatchMode.entries.forEach { option ->
                FilterChip(
                    selected = mode == option,
                    onClick = { mode = option },
                    border = StationFilterChipBorder(mode == option),
                    label = { Text(option.label, fontSize = 12.sp) },
                )
            }
        }
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text(column.title) },
            placeholder = { Text("02:1a", fontSize = 13.sp) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            textStyle = TextStyle(fontSize = 14.sp),
        )
        Text(
            "Upper and lower case are treated the same. An empty query removes the filter.",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.secondary,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StationCountFilterDialog(
    column: StationColumn,
    initialFilter: StationFilter?,
    onApply: (StationFilter?) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    val stored = initialFilter as? StationFilter.NumberRange
    var minText by remember(column) { mutableStateOf(stored?.min?.toString().orEmpty()) }
    var maxText by remember(column) { mutableStateOf(stored?.max?.toString().orEmpty()) }
    val min = minText.trim().toLongOrNull()
    val max = maxText.trim().toLongOrNull()
    val notWholeNumbers = (minText.isNotBlank() && min == null) || (maxText.isNotBlank() && max == null)
    val reversed = min != null && max != null && min > max
    StationFilterDialogScaffold(
        column = column,
        hasStoredFilter = initialFilter != null,
        confirmEnabled = !notWholeNumbers && !reversed,
        onApply = { onApply(StationFilter.NumberRange(min = min, max = max)) },
        onClear = onClear,
        onDismiss = onDismiss,
    ) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(
                selected = min == 1L && max == null,
                onClick = {
                    minText = "1"
                    maxText = ""
                },
                border = StationFilterChipBorder(min == 1L && max == null),
                label = { Text("Sent at least one", fontSize = 12.sp) },
            )
            FilterChip(
                selected = min == 0L && max == 0L,
                onClick = {
                    minText = "0"
                    maxText = "0"
                },
                border = StationFilterChipBorder(min == 0L && max == 0L),
                label = { Text("Sent none", fontSize = 12.sp) },
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StationNumberField(minText, { minText = it }, "At least", Modifier.weight(1f))
            StationNumberField(maxText, { maxText = it }, "At most", Modifier.weight(1f))
        }
        when {
            notWholeNumbers -> StationFilterError("Counts are whole numbers.")
            reversed -> StationFilterError("The minimum must not be above the maximum.")
        }
        Text(
            "Leave a bound empty to keep the range open there.",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.secondary,
        )
    }
}

/** Whole dBm a signal filter may span; a bound left at the edge of this range means "no limit". */
private val RSSI_FILTER_RANGE_DBM = -110f..-20f

@Composable
private fun StationSignalFilterDialog(
    column: StationColumn,
    initialFilter: StationFilter?,
    onApply: (StationFilter?) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    val stored = initialFilter as? StationFilter.NumberRange
    var missingOnly by remember(column) { mutableStateOf(stored?.missingOnly == true) }
    var bounds by remember(column) {
        mutableStateOf(
            (stored?.min?.toFloat() ?: RSSI_FILTER_RANGE_DBM.start)..
                (stored?.max?.toFloat() ?: RSSI_FILTER_RANGE_DBM.endInclusive),
        )
    }
    val atLeast = bounds.start.roundToInt().takeIf { it > RSSI_FILTER_RANGE_DBM.start.toInt() }
    val atMost = bounds.endInclusive.roundToInt().takeIf { it < RSSI_FILTER_RANGE_DBM.endInclusive.toInt() }
    StationFilterDialogScaffold(
        column = column,
        hasStoredFilter = initialFilter != null,
        confirmEnabled = true,
        onApply = {
            onApply(
                if (missingOnly) {
                    StationFilter.NumberRange(missingOnly = true)
                } else {
                    StationFilter.NumberRange(min = atLeast?.toLong(), max = atMost?.toLong())
                },
            )
        },
        onClear = onClear,
        onDismiss = onDismiss,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Checkbox(checked = missingOnly, onCheckedChange = { missingOnly = it })
            Text("Only stations without any RSSI", fontSize = 13.sp)
        }
        Column(Modifier.padding(top = 2.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    "${RSSI_FILTER_RANGE_DBM.start.toInt()} dBm",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.secondary,
                )
                Text(
                    "${RSSI_FILTER_RANGE_DBM.endInclusive.toInt()} dBm",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
            // 44 steps over 90 dBm puts the thumb on every second dBm, coarse enough to hit with a finger.
            RangeSlider(
                value = bounds,
                onValueChange = { bounds = it },
                valueRange = RSSI_FILTER_RANGE_DBM,
                steps = 44,
                enabled = !missingOnly,
            )
            Text(
                text = when {
                    atLeast != null && atMost != null -> "Keeps signals between $atLeast and $atMost dBm."
                    atLeast != null -> "Keeps signals of $atLeast dBm or stronger."
                    atMost != null -> "Keeps signals of $atMost dBm or weaker."
                    else -> "Drag both ends of the slider to narrow the signal range."
                },
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.secondary,
            )
        }
        Text(
            "A stronger signal is closer to zero: -50 dBm beats -80 dBm.",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.secondary,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StationTimeFilterDialog(
    column: StationColumn,
    initialFilter: StationFilter?,
    onApply: (StationFilter?) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    val stored = initialFilter as? StationFilter.TimeRange
    var shortcut by remember(column) { mutableStateOf(stored?.shortcut ?: TimeShortcut.None) }
    var fromDay by remember(column) { mutableStateOf(stored?.startMs?.let(::dayOf)) }
    var toDay by remember(column) { mutableStateOf(stored?.endMs?.let(::dayOf)) }
    var pickingRange by remember(column) { mutableStateOf(false) }

    val custom = shortcut == TimeShortcut.None
    val startMs = fromDay?.let(::dayStartMs)
    val endMs = toDay?.let(::dayEndMs)
    val reversed = custom && startMs != null && endMs != null && startMs > endMs

    StationFilterDialogScaffold(
        column = column,
        hasStoredFilter = initialFilter != null,
        confirmEnabled = !reversed,
        onApply = {
            onApply(
                if (custom) {
                    StationFilter.TimeRange(startMs = startMs, endMs = endMs)
                } else {
                    StationFilter.TimeRange(shortcut = shortcut)
                },
            )
        },
        onClear = onClear,
        onDismiss = onDismiss,
    ) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            TimeShortcut.entries.forEach { option ->
                FilterChip(
                    selected = shortcut == option,
                    onClick = { shortcut = option },
                    border = StationFilterChipBorder(shortcut == option),
                    label = { Text(option.label, fontSize = 12.sp) },
                )
            }
        }
        StationDateRangeButton(fromDay, toDay, Modifier.fillMaxWidth()) { pickingRange = true }
        if (reversed) {
            StationFilterError("The last day must not be before the first day.")
        }
    }

    if (pickingRange) {
        StationDateRangePickerDialog(fromDay, toDay, onDismiss = { pickingRange = false }) { from, to ->
            fromDay = from
            toDay = to
            shortcut = TimeShortcut.None
            pickingRange = false
        }
    }
}

@Composable
private fun StationDateRangeButton(
    fromDay: LocalDate?,
    toDay: LocalDate?,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val from = fromDay?.let(FILTER_DAY_FORMAT::format)
    val to = toDay?.let(FILTER_DAY_FORMAT::format)
    OutlinedCard(modifier = modifier.clickable(onClick = onClick)) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text("Date range", fontSize = 11.sp, color = MaterialTheme.colorScheme.secondary)
            Text(
                text = when {
                    from != null && to != null -> "$from – $to"
                    from != null -> "since $from"
                    to != null -> "until $to"
                    else -> "any day"
                },
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

/**
 * Material's range calendar in one dialog: both ends are selected in the same view. 1.4.0 ships no
 * `DateRangePickerDialog`, and a plain `AlertDialog` sizes itself to its content - the picker's
 * minimum content width then squeezes the month grid until day letters overlap - so the dialog
 * takes the screen width itself and hands the full measure to the picker.
 */
@Composable
private fun StationDateRangePickerDialog(
    initialFrom: LocalDate?,
    initialTo: LocalDate?,
    onDismiss: () -> Unit,
    onPick: (LocalDate?, LocalDate?) -> Unit,
) {
    // The picker reports UTC middays; they are read back as dates and placed in the local zone by
    // the caller, so the days that are selected are the days that are filtered on.
    val pickerState = rememberDateRangePickerState(
        initialSelectedStartDateMillis = initialFrom?.toPickerUtcMillis(),
        initialSelectedEndDateMillis = initialTo?.toPickerUtcMillis(),
    )
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 6.dp,
        ) {
            Column(Modifier.padding(top = 14.dp)) {
                // Bounded height, not a scroll wrapper: the picker scrolls its months itself.
                // The compact headline skeleton and the own title/headline slots keep "Select
                // dates" and the range left aligned with the calendar, and the mode toggle on the
                // right edge, instead of the default's indented, wrapping layout.
                Box(modifier = Modifier.heightIn(max = 520.dp)) {
                    DateRangePicker(
                        state = pickerState,
                        modifier = Modifier.fillMaxWidth(),
                        dateFormatter = DatePickerDefaults.dateFormatter(selectedDateSkeleton = "dd.MM.yy"),
                        title = {
                            Text(
                                "Select dates",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 16.dp, top = 20.dp, end = 16.dp),
                            )
                        },
                        headline = {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = rangeHeadline(pickerState),
                                    style = MaterialTheme.typography.headlineSmall,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    modifier = Modifier.weight(1f).padding(top = 8.dp, bottom = 16.dp),
                                )
                                val onTextInput = pickerState.displayMode == DisplayMode.Input
                                IconButton(
                                    onClick = {
                                        pickerState.displayMode =
                                            if (onTextInput) DisplayMode.Picker else DisplayMode.Input
                                    },
                                ) {
                                    Icon(
                                        imageVector = if (onTextInput) Icons.Rounded.CalendarMonth else Icons.Rounded.Edit,
                                        contentDescription = if (onTextInput) "Switch to calendar" else "Switch to text input",
                                    )
                                }
                            }
                        },
                        showModeToggle = false,
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    TextButton(onClick = {
                        onPick(
                            pickerState.selectedStartDateMillis?.toPickerUtcDay(),
                            pickerState.selectedEndDateMillis?.toPickerUtcDay(),
                        )
                    }) { Text("Apply") }
                }
            }
        }
    }
}

private fun LocalDate.toPickerUtcMillis(): Long = atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()

private fun Long.toPickerUtcDay(): LocalDate = Instant.ofEpochMilli(this).atZone(ZoneOffset.UTC).toLocalDate()

private val FILTER_HEADLINE_FORMAT = DateTimeFormatter.ofPattern("dd.MM.yy")

/** Headline like `15.10.26 – 17.10.26` from the UTC middays the picker reports. */
private fun rangeHeadline(state: DateRangePickerState): String {
    fun part(millis: Long?) =
        millis?.let { FILTER_HEADLINE_FORMAT.format(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC)) } ?: "----"
    return "${part(state.selectedStartDateMillis)} – ${part(state.selectedEndDateMillis)}"
}

@Composable
private fun StationNumberField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    keyboardType: KeyboardType = KeyboardType.Number,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        singleLine = true,
        label = { Text(label, fontSize = 13.sp) },
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = ImeAction.Done),
        textStyle = TextStyle(fontSize = 14.sp),
    )
}

@Composable
private fun StationFilterError(text: String) {
    Text(text, fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
}

/**
 * Width of every column: the one the user dragged it to, or otherwise the width its widest visible
 * cell needs, never narrower than what its own header needs or the column's floor.
 */
@Composable
private fun rememberColumnWidths(
    rows: List<DiscoveredStation>,
    tableState: StationTableState,
): Map<StationColumn, Dp> {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val measured = remember(rows, measurer, density) {
        StationColumn.ordered.associateWith { column -> column.fittedWidth(rows, measurer, density) }
    }
    return remember(measured, tableState.widths) {
        StationColumn.ordered.associateWith { column -> tableState.widthOf(column)?.dp ?: measured.getValue(column) }
    }
}

/**
 * Width [column] needs: the widest cell text in [rows], or its own header, whichever is wider,
 * within the column's bounds. Cells are single-line, so measuring them is exact.
 */
private fun StationColumn.fittedWidth(
    rows: List<DiscoveredStation>,
    measurer: TextMeasurer,
    density: Density,
): Dp {
    var widest = ""
    rows.forEach { station ->
        val text = display(station)
        if (text.length > widest.length) widest = text
    }
    return with(density) {
        val cells = measurer.measure(widest, cellStyleFor(this@fittedWidth)).size.width.toDp() +
            (CELL_HORIZONTAL_PADDING_DP * 2).dp
        // Titles wrap onto two lines when they can, but a single long word such as SPATEM cannot be
        // split, so only titles with a space are allowed to count as half their width.
        val titleWidth = measurer.measure(title, HEADER_TEXT_STYLE).size.width.toDp()
        val header = (if (' ' in title) titleWidth / 2f else titleWidth) + HEADER_OVERHEAD_DP.dp
        maxOf(cells, header, minWidthDp.dp).coerceAtMost(StationColumn.MAX_WIDTH_DP.dp)
    }
}

/** Left and right padding of a table cell. */
private const val CELL_HORIZONTAL_PADDING_DP = 8

/**
 * Room a header cell needs around its title: cell padding, the sort arrow, the gap, the filter
 * button, the resize handle and its gap.
 */
private const val HEADER_OVERHEAD_DP = 68

/** Drag strip width on a column header's right edge. */
private const val RESIZE_HANDLE_WIDTH_DP = 12

/** The style cells are drawn in, which is what the table measures to size a column. */
private fun cellStyleFor(column: StationColumn): TextStyle = if (column in MONOSPACE_COLUMNS) {
    CELL_TEXT_STYLE.copy(fontFamily = FontFamily.Monospace)
} else {
    CELL_TEXT_STYLE
}

private val CELL_TEXT_STYLE = TextStyle(fontSize = 12.sp, lineHeight = 15.sp)

/** Header titles are drawn in this face, so a column has to leave room for it as well. */
private val HEADER_TEXT_STYLE = TextStyle(fontSize = 11.sp, lineHeight = 13.sp, fontWeight = FontWeight.Medium)

/** Digits and identifiers scan better in a fixed-width face. */
private val MONOSPACE_COLUMNS: Set<StationColumn> = setOf(
    StationColumn.macAddress,
    StationColumn.firstSeen,
    StationColumn.lastSeen,
    StationColumn.lastRssi,
    StationColumn.bestRssi,
    StationColumn.position,
) + StationColumn.packetTypes

/** Day text of the timestamp filter, kept in the table's own `dd.MM.yyyy` idiom. */
private val FILTER_DAY_FORMAT = DateTimeFormatter.ofPattern("dd.MM.yyyy")

private fun dayOf(epochMs: Long): LocalDate =
    Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).toLocalDate()

/** First instant of [day] local time, and the last millisecond the table's epoch values can hold in it. */
private fun dayStartMs(day: LocalDate): Long =
    day.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

private fun dayEndMs(day: LocalDate): Long =
    day.atTime(END_OF_DAY).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
