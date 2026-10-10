package org.opentrafficmap.citstogo.protocol

/**
 * ETSI TimestampIts: elapsed milliseconds since 2004-01-01T00:00:00Z, leap seconds included. The conversion
 * counts the five leap seconds inserted up to 2017, like Wireshark, so it holds for times since then.
 */
object ItsTime {
    const val EPOCH_UNIX_MS = 1_072_915_200_000L

    // TAI-UTC increased from 32 s at the ITS epoch to 37 s in 2017.
    private const val LEAP_MILLISECONDS_SINCE_EPOCH = 5_000L

    fun fromUnixMs(unixMs: Long): Long = unixMs - EPOCH_UNIX_MS + LEAP_MILLISECONDS_SINCE_EPOCH

    fun toUnixMs(timestampIts: Long): Long = timestampIts + EPOCH_UNIX_MS - LEAP_MILLISECONDS_SINCE_EPOCH
}
