package org.opentrafficmap.citstogo.logging

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger

class DiagnosticLogTest {
    @Test fun `JUL handler captures severity formatted parameters and exception causes`() {
        val log = DiagnosticLog()
        val logger = Logger.getAnonymousLogger().apply {
            useParentHandlers = false
            level = Level.ALL
            addHandler(log)
        }
        logger.fine("setup")
        logger.log(Level.INFO, "MTU {0}", 517)
        logger.warning("timeout")
        logger.log(Level.SEVERE, "connect failed", IllegalStateException("outer", IllegalArgumentException("inner")))
        val entries = log.entries.value
        assertEquals(LogSeverity.entries, entries.map { it.severity })
        assertEquals("MTU 517", entries[1].message)
        assertTrue(entries.last().message.contains("Caused by: java.lang.IllegalArgumentException: inner"))
        assertTrue(entries.last().asText().contains("ERROR"))
    }

    @Test fun `history evicts oldest and clear never reuses entry IDs`() {
        val log = DiagnosticLog(2)
        repeat(3) { log.publish(LogRecord(Level.INFO, "message $it")) }
        assertEquals(listOf("message 1", "message 2"), log.entries.value.map { it.message })
        val lastId = log.entries.value.last().id
        log.clear()
        assertTrue(log.entries.value.isEmpty())
        log.publish(LogRecord(Level.INFO, "new"))
        assertTrue(log.entries.value.single().id > lastId)
    }

    @Test fun `concurrent callbacks preserve all entries and unique ordered IDs`() {
        val log = DiagnosticLog(1_000)
        val executor = Executors.newFixedThreadPool(4)
        try {
            val tasks = (0 until 400).map { index ->
                executor.submit { log.publish(LogRecord(Level.INFO, "callback $index")) }
            }
            tasks.forEach { it.get(5, TimeUnit.SECONDS) }
            assertEquals(400, log.entries.value.size)
            assertEquals((0L until 400L).toList(), log.entries.value.map { it.id })
        } finally {
            executor.shutdownNow()
        }
    }

    @Test fun `oversized messages and handler threshold are bounded`() {
        val log = DiagnosticLog()
        log.level = Level.WARNING
        log.publish(LogRecord(Level.INFO, "ignored"))
        log.publish(LogRecord(Level.WARNING, "x".repeat(100_000)))
        assertEquals(8_192, log.entries.value.single().message.length)
    }

    @Test fun `installation is idempotent and captures child logger debug messages`() {
        AppLogging.install()
        AppLogging.install()
        AppLogging.history.clear()
        Logger.getLogger("org.opentrafficmap.citstogo.Test").fine("debug callback")
        assertEquals(1, AppLogging.history.entries.value.count { it.message == "debug callback" })
    }
}
