package org.opentrafficmap.citstogo.protocol

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

class ItsTimeTest {
    @Test
    fun convertsLikeWireshark() {
        // TimestampIts values and the UTC time tshark 4.6.9 shows for them: the detectionTime of the
        // c-its-parser DENM test vector and of a synthetic DENM in DenmDecoderTest.
        val wireshark = mapOf(
            585_913_223_200L to "2022-07-26T09:40:18.200Z",
            712_327_315_349L to "2026-07-28T12:41:50.349Z",
        )

        for ((timestampIts, utc) in wireshark) {
            val unixMs = Instant.parse(utc).toEpochMilli()
            assertEquals(utc, unixMs, ItsTime.toUnixMs(timestampIts))
            assertEquals(utc, timestampIts, ItsTime.fromUnixMs(unixMs))
        }
    }

    @Test
    fun countsTheLeapSecondsSinceTheItsEpoch() {
        // 4,749 days from 2004 to 2017 plus the five leap seconds inserted in between.
        val start2017 = Instant.parse("2017-01-01T00:00:00Z").toEpochMilli()

        assertEquals(410_313_605_000L, ItsTime.fromUnixMs(start2017))
        assertEquals(start2017, ItsTime.toUnixMs(410_313_605_000L))
    }
}
