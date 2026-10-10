package org.opentrafficmap.citstogo.packet

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.opentrafficmap.citstogo.bridge.PcapWriter

/** The Wireshark `tshark` dissector as the reference for packet tests. Set `TSHARK` to use another binary. */
internal object Tshark {
    private const val DEFAULT_TIMESTAMP_US = 1_785_242_510_349_000L

    /** Runs tshark and returns its standard output and error combined. */
    fun run(vararg arguments: String): String = execute(arguments.toList(), separateErrors = false, timeoutSeconds = 30)

    /** Writes [frames] to a temporary PCAP, one microsecond apart, and deletes it after [block]. */
    fun <T> withPcap(frames: List<ByteArray>, timestampUs: Long = DEFAULT_TIMESTAMP_US, block: (Path) -> T): T {
        val pcap = Files.createTempFile("cits-to-go-packet-", ".pcap")
        try {
            PcapWriter(Files.newOutputStream(pcap)).use { writer ->
                frames.forEachIndexed { index, frame -> writer.writeRawPacket(frame, timestampUs + index) }
            }
            return block(pcap)
        } finally {
            Files.deleteIfExists(pcap)
        }
    }

    /** Dissects [frames] and returns the raw values of the fields [names] for each frame, in order. */
    fun fields(frames: List<ByteArray>, names: List<String>): List<Map<String, String>> {
        val rows = withPcap(frames) { pcap -> fields(pcap, names) }
        assertEquals("tshark rows", frames.size, rows.size)
        return rows
    }

    /**
     * Returns the raw values of the fields [names] for each frame of [pcap] that matches the display [filter].
     * A field that occurs several times in a frame lists all values, separated by commas; a missing
     * field is empty.
     */
    fun fields(
        pcap: Path,
        names: List<String>,
        filter: String? = null,
        timeoutSeconds: Long = 30,
    ): List<Map<String, String>> {
        val arguments = mutableListOf("-r", pcap.toString(), "-T", "fields", "-E", "occurrence=a")
        if (filter != null) arguments += listOf("-Y", filter)
        names.forEach { arguments += listOf("-e", it) }
        return execute(arguments, separateErrors = true, timeoutSeconds)
            .lines()
            .dropLast(1)
            .map { line -> names.zip(line.split('\t')).toMap() }
    }

    private fun execute(arguments: List<String>, separateErrors: Boolean, timeoutSeconds: Long): String {
        val executable = System.getenv("TSHARK")?.takeIf { it.isNotBlank() } ?: "tshark"
        val errors = if (separateErrors) Files.createTempFile("cits-to-go-tshark-", ".log") else null
        try {
            val builder = ProcessBuilder(listOf(executable) + arguments)
            if (errors == null) builder.redirectErrorStream(true) else builder.redirectError(errors.toFile())
            val process = try {
                builder.start()
            } catch (exception: IOException) {
                fail(
                    "tshark is required for packet tests. Install Wireshark CLI or set TSHARK: " +
                        exception.message,
                )
                error("unreachable")
            }
            // Read while tshark runs, so large dissections cannot fill the pipe and stall it.
            val output = CompletableFuture.supplyAsync { process.inputStream.bufferedReader().use { it.readText() } }
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                fail("tshark timed out")
            }
            val text = output.get()
            val log = errors?.toFile()?.readText().orEmpty()
            assertEquals("tshark failed: $text$log", 0, process.exitValue())
            return text
        } finally {
            errors?.let(Files::deleteIfExists)
        }
    }
}
