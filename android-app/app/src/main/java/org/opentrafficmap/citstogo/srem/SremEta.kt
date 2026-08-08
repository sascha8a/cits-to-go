package org.opentrafficmap.citstogo.srem

import kotlin.math.roundToLong

internal fun estimateSremRequestTimeMs(
    nowMs: Long,
    distanceMeters: Double,
    speedMetersPerSecond: Float?,
    profile: SremProfile,
): Long {
    if (distanceMeters < 50.0) return nowMs
    val speed = speedMetersPerSecond?.takeIf { it >= 0.5f } ?: profile.fallbackSpeedMetersPerSecond
    val leadMs = (distanceMeters / speed * 1_000.0).roundToLong().coerceIn(1_000L, 60_000L)
    return nowMs + leadMs
}
