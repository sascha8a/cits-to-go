package org.opentrafficmap.citstogo.logging

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Instant
import java.util.logging.Formatter
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger

enum class LogSeverity { DEBUG, INFO, WARNING, ERROR }

data class DiagnosticEntry(
    val id: Long,
    val timestamp: Instant,
    val severity: LogSeverity,
    val source: String,
    val message: String,
) {
    fun asText(): String = "$timestamp ${severity.name} [$source] $message"
}

/** Thread-safe, bounded session history. Does not retain Throwable or Android objects. */
class DiagnosticLog(private val capacity: Int = 2_000) : Handler() {
    init {
        require(capacity > 0)
        level = Level.ALL
    }

    private var nextId = 0L
    private val mutableEntries = MutableStateFlow<List<DiagnosticEntry>>(emptyList())
    val entries = mutableEntries.asStateFlow()
    private val messageFormatter = object : Formatter() {
        override fun format(record: LogRecord): String = formatMessage(record)
    }

    @Synchronized
    override fun publish(record: LogRecord) {
        if (!isLoggable(record)) return
        val severity = when {
            record.level.intValue() >= Level.SEVERE.intValue() -> LogSeverity.ERROR
            record.level.intValue() >= Level.WARNING.intValue() -> LogSeverity.WARNING
            record.level.intValue() >= Level.INFO.intValue() -> LogSeverity.INFO
            else -> LogSeverity.DEBUG
        }
        val message = buildString {
            append(messageFormatter.formatMessage(record).orEmpty().take(8_192))
            record.thrown?.let { append('\n'); append(it.stackTraceToString().take(8_192)) }
        }
        val entry = DiagnosticEntry(
            nextId++, Instant.ofEpochMilli(record.millis), severity,
            (record.loggerName ?: "Java").take(256), message,
        )
        mutableEntries.value = (mutableEntries.value.takeLast(capacity - 1) + entry)
    }

    @Synchronized
    fun clear() { mutableEntries.value = emptyList() }

    override fun flush() = Unit
    override fun close() = Unit
}

object AppLogging {
    val history = DiagnosticLog()
    // Keep a strong reference: JUL's registry can hold loggers weakly.
    private val appLogger = Logger.getLogger("org.opentrafficmap.citstogo")

    @Synchronized
    fun install() {
        val root = Logger.getLogger("")
        if (root.handlers.none { it === history }) root.addHandler(history)
        // App debug records propagate to the handler without enabling verbose third-party logs.
        appLogger.level = Level.ALL
    }
}
