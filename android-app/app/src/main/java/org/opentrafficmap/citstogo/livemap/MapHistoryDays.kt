package org.opentrafficmap.citstogo.livemap

import kotlin.math.roundToInt

/** How many days back the map shows road side units from history, as the settings store it; 0 shows all. */
object MapHistoryDays {
    const val ALL = 0
    val RANGE = ALL..3_650

    fun clamp(days: Int): Int = days.coerceIn(RANGE)

    /** Reads the settings field: numbers are rounded and limited to the range, anything else shows all. */
    fun parse(text: String): Int =
        text.trim().toDoubleOrNull()?.takeIf { it.isFinite() }?.let { clamp(it.roundToInt()) } ?: ALL
}
