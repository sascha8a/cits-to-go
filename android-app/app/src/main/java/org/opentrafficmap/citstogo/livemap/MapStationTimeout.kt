package org.opentrafficmap.citstogo.livemap

import kotlin.math.roundToInt

/** The station timeout of the map as the settings store it, in whole seconds. */
object MapStationTimeout {
    val DEFAULT_SECONDS = (LiveMapModel.DEFAULT_STATION_TIMEOUT_MS / 1_000L).toInt()
    val RANGE_SECONDS = (LiveMapModel.MIN_STATION_TIMEOUT_MS / 1_000L).toInt()..
        (LiveMapModel.MAX_STATION_TIMEOUT_MS / 1_000L).toInt()

    fun clamp(seconds: Int): Int = seconds.coerceIn(RANGE_SECONDS)

    /** Reads the settings field: numbers are rounded and limited to the range, anything else is the default. */
    fun parse(text: String): Int =
        text.trim().toDoubleOrNull()?.takeIf { it.isFinite() }?.let { clamp(it.roundToInt()) } ?: DEFAULT_SECONDS
}
