package org.opentrafficmap.citstogo.station

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import org.json.JSONObject

/** What kind of values a column holds, which decides its filter hint and first sort direction. */
enum class StationColumnKind {
    Text,
    Count,
    Signal,
    Timestamp,
    Position,
    ;

    /** Direction the column is sorted in the first time its header is tapped. */
    val sortsDescendingFirst: Boolean get() = this != Text
}

/**
 * One column of the discovered-stations table: its identity in persisted preferences, how a cell is
 * rendered, and how it sorts and filters.
 *
 * The definitions live outside the composable so that sorting, filtering, and the preference format
 * are testable without a composition, and so a filter always matches the text the user can see.
 */
class StationColumn(
    val key: String,
    val title: String,
    /** Floor for the measured, content-sized width; also the width before any column is resized. */
    val minWidthDp: Int,
    val kind: StationColumnKind,
    /** Set on the packet-type columns, one per [ItsPacketType] the app decodes. */
    val packetType: ItsPacketType? = null,
    /** Numeric value used for sorting and comparisons, null when the station has no value here. */
    val number: (DiscoveredStation) -> Double? = { null },
    /** Cell text, which is also what a plain filter matches against. */
    val display: (DiscoveredStation) -> String = { MISSING },
) {
    val filterHint: String get() = when (kind) {
        StationColumnKind.Text -> "Contains text"
        StationColumnKind.Count -> "Number, or a comparison such as >=5"
        StationColumnKind.Signal -> "dBm, or a comparison such as >=-75"
        StationColumnKind.Timestamp -> "Time or date, or compare epoch ms with > <"
        StationColumnKind.Position -> "Coordinate contains, e.g. 48.20"
    }

    fun matches(station: DiscoveredStation, query: String): Boolean {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return true
        val comparison = COMPARISON.matchEntire(trimmed)
        if (comparison != null) {
            val value = number(station) ?: return false
            val operand = comparison.groupValues[2].toDouble()
            return when (comparison.groupValues[1]) {
                ">" -> value > operand
                "<" -> value < operand
                ">=" -> value >= operand
                "<=" -> value <= operand
                else -> value == operand
            }
        }
        return display(station).contains(trimmed, ignoreCase = true)
    }

    override fun toString(): String = key

    companion object {
        /** Shown in place of a value no packet ever carried. */
        const val MISSING = "—"

        /** Widest a column may be, so one long value cannot push the rest of the table away. */
        const val MAX_WIDTH_DP = 320

        val macAddress = StationColumn(
            key = "mac_address",
            title = "MAC Address",
            minWidthDp = 148,
            kind = StationColumnKind.Text,
            display = { it.macAddress },
        )

        val lastSeen = StationColumn(
            key = "last_seen",
            title = "Last Seen",
            minWidthDp = 132,
            kind = StationColumnKind.Timestamp,
            number = { it.lastSeenMs.takeIf { seen -> seen > 0L }?.toDouble() },
            display = { station -> StationTime.stamp(station.lastSeenMs) },
        )

        val firstSeen = StationColumn(
            key = "first_seen",
            title = "First Seen",
            minWidthDp = 132,
            kind = StationColumnKind.Timestamp,
            number = { it.firstSeenMs.takeIf { seen -> seen > 0L }?.toDouble() },
            display = { station -> StationTime.stamp(station.firstSeenMs) },
        )

        /**
         * Where the station last said it was, from the GeoNetworking header's source position vector
         * rather than from any ITS container, so it is the position of the radio that transmitted.
         */
        val position = StationColumn(
            key = "position",
            title = "GeoNetworking Position",
            minWidthDp = 156,
            kind = StationColumnKind.Position,
            number = { station -> station.positionTimeMs.takeIf { it > 0L }?.toDouble() },
            display = { station ->
                val latitude = station.latitudeE7
                val longitude = station.longitudeE7
                if (latitude == null || longitude == null) {
                    MISSING
                } else {
                    String.format(
                        Locale.US,
                        "%.5f, %.5f",
                        latitude / 10_000_000.0,
                        longitude / 10_000_000.0,
                    )
                }
            },
        )

        val lastRssi = StationColumn(
            key = "last_rssi",
            title = "Last RSSI",
            minWidthDp = 96,
            kind = StationColumnKind.Signal,
            number = { it.lastRssiDbm?.toDouble() },
            display = { station -> station.lastRssiDbm?.let { "$it dBm" } ?: MISSING },
        )

        val bestRssi = StationColumn(
            key = "best_rssi",
            title = "Best RSSI",
            minWidthDp = 96,
            kind = StationColumnKind.Signal,
            number = { it.bestRssiDbm?.toDouble() },
            display = { station -> station.bestRssiDbm?.let { "$it dBm" } ?: MISSING },
        )

        /** One column per decoded ITS message type, in ITS message ID order. */
        val packetTypes: List<StationColumn> = ItsPacketType.entries.map { type ->
            StationColumn(
                key = "type_${type.name.lowercase(Locale.US)}",
                title = type.label,
                minWidthDp = 76,
                kind = StationColumnKind.Count,
                packetType = type,
                number = { station -> (station.typeCounts[type] ?: 0).toDouble() },
                display = { station -> (station.typeCounts[type] ?: 0).toString() },
            )
        }

        /** Columns in the order they appear, left to right. */
        val ordered: List<StationColumn> =
            listOf(macAddress, lastSeen, firstSeen, position) + packetTypes + listOf(lastRssi, bestRssi)

        val default: StationColumn get() = lastSeen

        fun fromKey(key: String?): StationColumn? = ordered.firstOrNull { it.key == key }

        private val COMPARISON = Regex("^(>=|<=|>|<|=)\\s*(-?\\d+(?:\\.\\d+)?)$")
    }
}

/** Shared formatting of the discovery table's timestamps. */
object StationTime {
    private val clock = DateTimeFormatter.ofPattern("HH:mm:ss")
    private val clockAndDay = DateTimeFormatter.ofPattern("dd.MM HH:mm:ss")
    private val zone: ZoneId get() = ZoneId.systemDefault()

    /** Absolute time of [instantMs], shortened to a plain clock time for today. */
    fun stamp(instantMs: Long, nowMs: Long = System.currentTimeMillis()): String {
        if (instantMs <= 0L) return StationColumn.MISSING
        val moment = Instant.ofEpochMilli(instantMs).atZone(zone)
        val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate() == moment.toLocalDate()
        return (if (today) clock else clockAndDay).format(moment)
    }

    /** Coarse "how long ago" caption, e.g. `just now`, `42 s`, `3 min`, `2 h`, `5 d`. */
    fun ago(instantMs: Long, nowMs: Long = System.currentTimeMillis()): String {
        if (instantMs <= 0L) return StationColumn.MISSING
        val seconds = ((nowMs - instantMs) / 1_000L).coerceAtLeast(0L)
        return when {
            seconds < 5 -> "just now"
            seconds < 60 -> "${seconds}s"
            seconds < 3_600 -> "${seconds / 60} min"
            seconds < 86_400 -> "${seconds / 3_600} h"
            else -> "${seconds / 86_400} d"
        }
    }
}

/** The user's sorting, filtering and column widths of the discovery table, persisted between visits. */
data class StationTableState(
    val sortColumn: StationColumn = StationColumn.default,
    val sortDescending: Boolean = StationColumn.default.kind.sortsDescendingFirst,
    val filters: Map<StationColumn, String> = emptyMap(),
    /**
     * Columns the user has dragged wider or narrower, in dp, overriding the width measured from the
     * cell contents.
     */
    val widths: Map<StationColumn, Int> = emptyMap(),
) {
    /** Tapping a header sorts by that column, reversing the order when it is already active. */
    fun sortedBy(column: StationColumn): StationTableState = when {
        column != sortColumn -> StationTableState(column, column.kind.sortsDescendingFirst, filters, widths)
        else -> copy(sortDescending = !sortDescending)
    }

    /** Width in dp the user dragged this column's edge to, or null to let the table measure it. */
    fun widthOf(column: StationColumn): Int? = widths[column]

    /** Remembers a dragged width, clamped so no column can crowd the table out of view. */
    fun withWidth(column: StationColumn, widthDp: Int): StationTableState =
        copy(widths = widths + (column to widthDp.coerceIn(column.minWidthDp, StationColumn.MAX_WIDTH_DP)))

    /** Hands the column back to the content-measuring table. */
    fun withoutWidth(column: StationColumn): StationTableState =
        if (!widths.containsKey(column)) this else copy(widths = widths - column)

    fun withFilter(column: StationColumn, query: String): StationTableState {
        val trimmed = query.trim()
        val next = filters.toMutableMap()
        if (trimmed.isEmpty()) {
            next.remove(column)
        } else {
            next[column] = trimmed
        }
        return copy(filters = next)
    }

    fun withoutFilter(column: StationColumn): StationTableState =
        if (!filters.containsKey(column)) this else copy(filters = filters - column)

    /** Back to the default ordering, every filter dropped and every column measured again. */
    fun resetView(): StationTableState = DEFAULT

    /** Filters first, so the visible rows are the ones the user sorted. */
    fun rows(stations: List<DiscoveredStation>): List<DiscoveredStation> {
        val matching = if (filters.isEmpty()) stations else stations.filter { station ->
            filters.all { (column, query) -> column.matches(station, query) }
        }
        val sign = if (sortDescending) -1 else 1
        return matching.sortedWith { first, second ->
            val left = sortColumn.number(first)
            val right = sortColumn.number(second)
            val order = when {
                // Rows without a value in the sorted column stay at the bottom either way.
                left == null && right == null ->
                    sortColumn.display(first).compareTo(sortColumn.display(second), ignoreCase = true) * sign
                left == null -> 1
                right == null -> -1
                else -> left.compareTo(right) * sign
            }
            order.takeIf { it != 0 } ?: first.macAddress.compareTo(second.macAddress)
        }
    }

    fun encode(): String = JSONObject()
        .put(KEY_SORT, sortColumn.key)
        .put(KEY_DESCENDING, sortDescending)
        .put(KEY_FILTERS, JSONObject().apply {
            filters.forEach { (column, query) -> put(column.key, query) }
        })
        .put(KEY_WIDTHS, JSONObject().apply {
            widths.forEach { (column, width) -> put(column.key, width) }
        })
        .toString()

    companion object {
        val DEFAULT = StationTableState()

        fun decode(raw: String?): StationTableState {
            if (raw.isNullOrBlank()) return DEFAULT
            val json = runCatching { JSONObject(raw) }.getOrNull() ?: return DEFAULT
            // A stored column that no longer exists is dropped, and with it the direction that
            // belonged to it: an order without its column would be a surprise, not a preference.
            val storedSortColumn = StationColumn.fromKey(json.optString(KEY_SORT).takeIf { it.isNotEmpty() })
            val sortColumn = storedSortColumn ?: StationColumn.default
            val filters = storedColumns(json.optJSONObject(KEY_FILTERS)) { _, raw ->
                raw.trim().takeIf { it.isNotEmpty() }
            }
            val widths = storedColumns(json.optJSONObject(KEY_WIDTHS)) { column, raw ->
                raw.toIntOrNull()?.coerceIn(column.minWidthDp, StationColumn.MAX_WIDTH_DP)
            }
            val descending = if (storedSortColumn != null && json.has(KEY_DESCENDING)) {
                json.getBoolean(KEY_DESCENDING)
            } else {
                sortColumn.kind.sortsDescendingFirst
            }
            return StationTableState(
                sortColumn = sortColumn,
                sortDescending = descending,
                filters = filters,
                widths = widths,
            )
        }

        /**
         * Reads a `{ columnKey: value }` object from stored state, dropping keys whose column no
         * longer exists and values [parse] rejects.
         */
        private fun <T : Any> storedColumns(
            stored: JSONObject?,
            parse: (StationColumn, String) -> T?,
        ): Map<StationColumn, T> {
            if (stored == null) return emptyMap()
            return buildMap {
                val keys = stored.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val column = StationColumn.fromKey(key) ?: continue
                    val value = parse(column, stored.optString(key)) ?: continue
                    put(column, value)
                }
            }
        }

        private const val KEY_SORT = "sort"
        private const val KEY_DESCENDING = "descending"
        private const val KEY_FILTERS = "filters"
        private const val KEY_WIDTHS = "widths"
    }
}
