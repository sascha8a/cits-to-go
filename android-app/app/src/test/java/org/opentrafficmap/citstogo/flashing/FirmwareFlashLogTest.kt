package org.opentrafficmap.citstogo.flashing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.opentrafficmap.citstogo.ActivityLevel

class FirmwareFlashLogTest {
    @Test
    fun addKeepsNewestEntryFirst() {
        val log = FirmwareFlashLog.add(
            FirmwareFlashLog.add(emptyList(), "00:00:01", ActivityLevel.INFO, "first"),
            "00:00:02",
            ActivityLevel.INFO,
            "second",
        )
        assertEquals(listOf("second", "first"), log.map { it.message })
    }

    @Test
    fun repeatedProgressWithSameKeyUpdatesInPlace() {
        var log = FirmwareFlashLog.addProgress(emptyList(), "00:00:01", "download", ActivityLevel.INFO, "10%")
        log = FirmwareFlashLog.add(log, "00:00:02", ActivityLevel.INFO, "milestone")
        log = FirmwareFlashLog.addProgress(log, "00:00:03", "download", ActivityLevel.INFO, "55%")

        assertEquals(2, log.size)
        assertEquals("milestone", log.first().message)
        val download = log.single { it.progressKey == "download" }
        assertEquals("55%", download.message)
        assertEquals("00:00:03", download.timestamp)
    }

    @Test
    fun progressWithNewKeyAddsSeparateLine() {
        var log = FirmwareFlashLog.addProgress(emptyList(), "00:00:01", "download", ActivityLevel.INFO, "100%")
        log = FirmwareFlashLog.addProgress(log, "00:00:02", "write", ActivityLevel.INFO, "5%")
        assertEquals(2, log.size)
        assertTrue(log.any { it.progressKey == "download" })
        assertTrue(log.any { it.progressKey == "write" })
    }

    @Test
    fun logIsCappedToMaximumEntries() {
        var log = emptyList<FirmwareFlashLogEntry>()
        repeat(FirmwareFlashLog.MAX_ENTRIES + 20) { index ->
            log = FirmwareFlashLog.add(log, "00:00:00", ActivityLevel.INFO, "entry-$index")
        }
        assertEquals(FirmwareFlashLog.MAX_ENTRIES, log.size)
        assertEquals("entry-${FirmwareFlashLog.MAX_ENTRIES + 19}", log.first().message)
    }

    @Test
    fun convertsToActivityEntriesForDisplay() {
        val log = FirmwareFlashLog.addProgress(emptyList(), "12:34:56", "download", ActivityLevel.WARN, "40%")
        val activity = FirmwareFlashLog.toActivityEntries(log)
        assertEquals(1, activity.size)
        assertEquals("12:34:56", activity.first().timestamp)
        assertEquals(ActivityLevel.WARN, activity.first().level)
        assertEquals("40%", activity.first().message)
    }
}
