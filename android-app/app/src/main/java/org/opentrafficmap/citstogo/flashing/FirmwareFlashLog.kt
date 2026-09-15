package org.opentrafficmap.citstogo.flashing

import org.opentrafficmap.citstogo.ActivityLevel
import org.opentrafficmap.citstogo.ActivityLogEntry

data class FirmwareFlashLogEntry(
    val timestamp: String,
    val level: ActivityLevel,
    val message: String,
    val progressKey: String? = null,
)

object FirmwareFlashLog {
    const val MAX_ENTRIES = 80

    fun add(
        entries: List<FirmwareFlashLogEntry>,
        timestamp: String,
        level: ActivityLevel,
        message: String,
    ): List<FirmwareFlashLogEntry> =
        trim(listOf(FirmwareFlashLogEntry(timestamp, level, message)) + entries)

    fun addProgress(
        entries: List<FirmwareFlashLogEntry>,
        timestamp: String,
        progressKey: String,
        level: ActivityLevel,
        message: String,
    ): List<FirmwareFlashLogEntry> {
        val index = entries.indexOfFirst { it.progressKey == progressKey }
        val entry = FirmwareFlashLogEntry(timestamp, level, message, progressKey)
        if (index < 0) return trim(listOf(entry) + entries)
        return entries.toMutableList().also { it[index] = entry }
    }

    fun toActivityEntries(entries: List<FirmwareFlashLogEntry>): List<ActivityLogEntry> =
        entries.map { ActivityLogEntry(it.timestamp, it.level, it.message) }

    private fun trim(entries: List<FirmwareFlashLogEntry>): List<FirmwareFlashLogEntry> =
        if (entries.size > MAX_ENTRIES) entries.subList(0, MAX_ENTRIES) else entries
}

fun formatFirmwareSize(bytes: Long): String = when {
    bytes >= 1_000_000 -> String.format(java.util.Locale.US, "%.1f MB", bytes / 1_000_000.0)
    bytes >= 1_000 -> String.format(java.util.Locale.US, "%.1f KB", bytes / 1_000.0)
    else -> "$bytes B"
}
