package org.opentrafficmap.citstogo.intersection

private const val MINUTES_PER_LEAP_YEAR = 527_040
private const val MILLIS_PER_MINUTE = 60_000
private const val TENTHS_PER_MINUTE = 600
private const val TENTHS_PER_HOUR = 36_000
private const val TIME_MARK_UNAVAILABLE = 36_000

/** Returns a stable, non-negative countdown for a SPATEM TimeMark. */
fun SignalEvent.secondsUntilChange(spat: SpatIntersection?, nowMs: Long): Long? {
    val targetTenths = listOfNotNull(likelyTime, minEndTime, maxEndTime)
        .firstOrNull { it in 0 until TIME_MARK_UNAVAILABLE }
        ?: return null
    val messageTenths = spat?.timeWithinHourTenths() ?: return null

    var remainingAtReceipt = targetTenths - messageTenths
    if (remainingAtReceipt < 0) {
        val crossesHourBoundary = messageTenths >= TENTHS_PER_HOUR - TENTHS_PER_MINUTE &&
            targetTenths < TENTHS_PER_MINUTE
        if (!crossesHourBoundary) return null
        remainingAtReceipt += TENTHS_PER_HOUR
    }

    val elapsedTenths = ((nowMs - spat.receivedAtMs).coerceAtLeast(0L) / 100L).toInt()
    val remainingTenths = remainingAtReceipt - elapsedTenths
    if (remainingTenths < 0) return null
    return (remainingTenths + 9L) / 10L
}

internal fun SpatIntersection.isAtLeastAsRecentAs(other: SpatIntersection): Boolean {
    val incomingMoy = moy?.takeIf { it in 0 until MINUTES_PER_LEAP_YEAR } ?: return true
    val existingMoy = other.moy?.takeIf { it in 0 until MINUTES_PER_LEAP_YEAR } ?: return true
    val incomingTimestamp = timestampMs?.takeIf { it in 0 until MILLIS_PER_MINUTE } ?: return true
    val existingTimestamp = other.timestampMs?.takeIf { it in 0 until MILLIS_PER_MINUTE } ?: return true

    if (incomingMoy != existingMoy) {
        val yearRolledOver = existingMoy >= MINUTES_PER_LEAP_YEAR - 2 && incomingMoy < 2
        return incomingMoy > existingMoy || yearRolledOver
    }
    return incomingTimestamp >= existingTimestamp
}

private fun SpatIntersection.timeWithinHourTenths(): Int? {
    val validMoy = moy?.takeIf { it in 0 until MINUTES_PER_LEAP_YEAR } ?: return null
    val validTimestamp = timestampMs?.takeIf { it in 0 until MILLIS_PER_MINUTE } ?: return null
    return (validMoy % 60) * TENTHS_PER_MINUTE + validTimestamp / 100
}
