package org.opentrafficmap.citstogo.station

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import org.json.JSONObject
import kotlin.math.ceil
import kotlin.math.floor

/** How a [StationFilter.Text] query is compared with the text a cell shows. */
enum class TextMatchMode(val storageKey: String, val label: String) {
    Contains("contains", "Contains"),
    StartsWith("starts", "Starts with"),
    Exact("exact", "Exact"),
    ;

    companion object {
        fun fromStorageKey(key: String?): TextMatchMode? = entries.firstOrNull { it.storageKey == key }
    }
}

/**
 * Rolling window a timestamp filter follows instead of hand-picked dates. It is resolved against the
 * current time on every pass, so it keeps following a live capture. [StationTableState.rows] is
 * already recomputed once a second for the "how long ago" captions.
 */
enum class TimeShortcut(val storageKey: String, val label: String) {
    None("none", "All"),
    LastHour("last_hour", "Last hour"),
    Last24Hours("last_24_h", "Last 24 h"),
    Today("today", "Today"),
    ;

    companion object {
        fun fromStorageKey(key: String?): TimeShortcut =
            entries.firstOrNull { it.storageKey == key } ?: None
    }
}

/**
 * The active filter of one table column. Each column kind gets the editor that fits its values:
 * a text match mode, a whole-number range, a dBm range, or a date range with rolling shortcuts.
 *
 * Filters are stored in app preferences. Anything that holds no bound is [isBlank] and is dropped
 * rather than stored, and filters written by the versions that used one free-text box per column are
 * converted by [fromLegacyQuery] the first time the state is read after the upgrade.
 */
sealed class StationFilter {
    /** True when every row passes; such a filter is removed instead of remembered. */
    abstract val isBlank: Boolean

    /** Short description for the chip above the table, without the column title. */
    abstract val summary: String

    abstract fun matches(column: StationColumn, station: DiscoveredStation, nowMs: Long): Boolean

    internal abstract fun encode(into: JSONObject)

    /** Contains query, start-with or exact match of the text a cell shows, ignoring case. */
    data class Text(val mode: TextMatchMode = TextMatchMode.Contains, val query: String) : StationFilter() {
        override val isBlank: Boolean get() = query.isBlank()

        override val summary: String get() = "${mode.label.lowercase(Locale.US)} \"$query\""

        override fun matches(column: StationColumn, station: DiscoveredStation, nowMs: Long): Boolean {
            val cell = column.display(station).trim().lowercase(Locale.US)
            return when (mode) {
                TextMatchMode.Contains -> cell.contains(query.trim().lowercase(Locale.US))
                TextMatchMode.StartsWith -> cell.startsWith(query.trim().lowercase(Locale.US))
                TextMatchMode.Exact -> cell == query.trim().lowercase(Locale.US)
            }
        }

        override fun encode(into: JSONObject) {
            into.put(KEY_TYPE, TYPE_TEXT)
            into.put(KEY_MODE, mode.storageKey)
            into.put(KEY_QUERY, query)
        }
    }

    /**
     * Range of a whole-number column: the packet counters and the RSSI readings. [missingOnly]
     * asks for the rows that carry no value at all, which only the RSSI columns can.
     */
    data class NumberRange(val min: Long? = null, val max: Long? = null, val missingOnly: Boolean = false) :
        StationFilter() {
        override val isBlank: Boolean get() = !missingOnly && min == null && max == null

        override val summary: String
            get() = when {
                missingOnly -> "no value"
                min != null && max != null -> if (min == max) "is $min" else "$min – $max"
                min != null -> "at least $min"
                else -> "at most $max"
            }

        override fun matches(column: StationColumn, station: DiscoveredStation, nowMs: Long): Boolean {
            val value = column.number(station) ?: return missingOnly
            if (missingOnly) return false
            if (min != null && value < min) return false
            if (max != null && value > max) return false
            return true
        }

        override fun encode(into: JSONObject) {
            into.put(KEY_TYPE, TYPE_NUMBER)
            min?.let { into.put(KEY_MIN, it) }
            max?.let { into.put(KEY_MAX, it) }
            if (missingOnly) into.put(KEY_MISSING, true)
        }
    }

    /**
     * Range of a timestamp column, either absolute dates or a rolling [shortcut] window.
     * [missingOnly] asks for the rows that were never seen.
     */
    data class TimeRange(
        val startMs: Long? = null,
        val endMs: Long? = null,
        val shortcut: TimeShortcut = TimeShortcut.None,
        val missingOnly: Boolean = false,
    ) : StationFilter() {
        override val isBlank: Boolean
            get() = !missingOnly && shortcut == TimeShortcut.None && startMs == null && endMs == null

        override val summary: String
            get() = when {
                missingOnly -> "never seen"
                shortcut != TimeShortcut.None -> shortcut.label
                startMs != null && endMs != null -> "${stamp(startMs)} – ${stamp(endMs)}"
                startMs != null -> "since ${stamp(startMs)}"
                endMs != null -> "until ${stamp(endMs)}"
                else -> ""
            }

        /** Millisecond window this filter keeps, with rolling shortcuts resolved against [nowMs]. */
        fun window(nowMs: Long): LongRange? = when {
            missingOnly -> null
            shortcut != TimeShortcut.None -> when (shortcut) {
                TimeShortcut.LastHour -> (nowMs - 3_600_000L)..nowMs
                TimeShortcut.Last24Hours -> (nowMs - 86_400_000L)..nowMs
                else -> LocalDate.now()
                    .atStartOfDay(ZoneId.systemDefault())
                    .toInstant()
                    .toEpochMilli()..Long.MAX_VALUE
            }
            else -> (startMs ?: Long.MIN_VALUE)..(endMs ?: Long.MAX_VALUE)
        }

        override fun matches(column: StationColumn, station: DiscoveredStation, nowMs: Long): Boolean {
            val value = column.number(station) ?: return missingOnly
            if (missingOnly) return false
            val range = window(nowMs) ?: return false
            return value >= range.first && value <= range.last
        }

        override fun encode(into: JSONObject) {
            into.put(KEY_TYPE, TYPE_TIME)
            startMs?.let { into.put(KEY_START, it) }
            endMs?.let { into.put(KEY_END, it) }
            if (shortcut != TimeShortcut.None) into.put(KEY_SHORTCUT, shortcut.storageKey)
            if (missingOnly) into.put(KEY_MISSING, true)
        }

        private fun stamp(epochMs: Long): String =
            SUMMARY_CLOCK.format(Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()))
    }

    companion object {
        private const val KEY_TYPE = "type"
        private const val TYPE_TEXT = "text"
        private const val TYPE_NUMBER = "number"
        private const val TYPE_TIME = "time"
        private const val KEY_MODE = "mode"
        private const val KEY_QUERY = "query"
        private const val KEY_MIN = "min"
        private const val KEY_MAX = "max"
        private const val KEY_START = "start"
        private const val KEY_END = "end"
        private const val KEY_SHORTCUT = "shortcut"
        private const val KEY_MISSING = "missing"

        private val SUMMARY_CLOCK = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")

        /** Comparison grammar the free-text filter boxes used before dedicated editors existed. */
        private val LEGACY_COMPARISON = Regex("^(>=|<=|>|<|=)\\s*(-?\\d+(?:\\.\\d+)?)$")

        /** Reads a filter written by [encode]; null when it is unusable for [column]. */
        internal fun decode(column: StationColumn, stored: JSONObject): StationFilter? {
            if (!column.filterable) return null
            val filter = when (stored.optString(KEY_TYPE)) {
                TYPE_TEXT -> Text(
                    mode = TextMatchMode.fromStorageKey(stored.optString(KEY_MODE)) ?: TextMatchMode.Contains,
                    query = stored.optString(KEY_QUERY),
                )
                TYPE_NUMBER -> NumberRange(
                    min = stored.optLongOrNull(KEY_MIN),
                    max = stored.optLongOrNull(KEY_MAX),
                    missingOnly = stored.optBoolean(KEY_MISSING),
                )
                TYPE_TIME -> TimeRange(
                    startMs = stored.optLongOrNull(KEY_START),
                    endMs = stored.optLongOrNull(KEY_END),
                    shortcut = TimeShortcut.fromStorageKey(stored.optString(KEY_SHORTCUT)),
                    missingOnly = stored.optBoolean(KEY_MISSING),
                )
                else -> return null
            }
            return filter.takeIf { !it.isBlank }
        }

        /**
         * Converts a query saved by the free-text filter boxes, keeping what the new editors can
         * express: text stays a contains match, a comparison becomes the equivalent whole-number
         * bound, and the dash becomes the missing-value request. Anything else is dropped.
         */
        internal fun fromLegacyQuery(column: StationColumn, query: String): StationFilter? {
            if (!column.filterable) return null
            val trimmed = query.trim()
            if (trimmed.isEmpty()) return null
            if (trimmed == StationColumn.MISSING) {
                return when (column.kind) {
                    StationColumnKind.Timestamp -> TimeRange(missingOnly = true)
                    StationColumnKind.Signal -> NumberRange(missingOnly = true)
                    else -> null
                }
            }
            return when (column.kind) {
                StationColumnKind.Text -> Text(query = trimmed)
                StationColumnKind.Count, StationColumnKind.Signal, StationColumnKind.Timestamp ->
                    fromLegacyNumber(column.kind, trimmed)
                StationColumnKind.Position -> null
            }
        }

        private fun fromLegacyNumber(kind: StationColumnKind, query: String): StationFilter? {
            val comparison = LEGACY_COMPARISON.matchEntire(query)
            val operator = comparison?.groupValues?.get(1) ?: "="
            val operand = (comparison?.groupValues?.get(2) ?: query).toDoubleOrNull() ?: return null
            // The columns hold whole numbers, so a strict bound becomes the next whole number
            // inside it and a bare number means equality: both keep exactly the rows the box found.
            val min = when (operator) {
                ">" -> floor(operand) + 1
                ">=", "=" -> ceil(operand)
                else -> null
            }
            val max = when (operator) {
                "<" -> ceil(operand) - 1
                "<=", "=" -> floor(operand)
                else -> null
            }
            return if (kind == StationColumnKind.Timestamp) {
                TimeRange(startMs = min?.toLong(), endMs = max?.toLong())
            } else {
                NumberRange(min = min?.toLong(), max = max?.toLong())
            }
        }

        private fun JSONObject.optLongOrNull(key: String): Long? =
            if (has(key) && !isNull(key)) getLong(key) else null
    }
}

/**
 * End of the last day a [StationFilter.TimeRange] covers, down to the millisecond the table's
 * epoch values reach.
 */
internal val END_OF_DAY: LocalTime = LocalTime.of(23, 59, 59, 999_000_000)
