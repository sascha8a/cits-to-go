package org.opentrafficmap.citstogo

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.opentrafficmap.citstogo.bridge.PcapReader
import org.opentrafficmap.citstogo.cam.CamDecoder
import org.opentrafficmap.citstogo.cam.WiresharkCam
import org.opentrafficmap.citstogo.denm.DenmDecoder
import org.opentrafficmap.citstogo.denm.WiresharkDenm
import org.opentrafficmap.citstogo.intersection.ItsFrameExtractor
import org.opentrafficmap.citstogo.intersection.ItsPacket
import org.opentrafficmap.citstogo.packet.Tshark

/**
 * Checks the receive path against tshark on the real captures in the repository's `example-pcap/`. They hold
 * pseudonymous station IDs and positions and stay local, so the test is skipped without them. Set
 * `EXAMPLE_PCAP_DIR` to read the captures from another directory.
 */
class ExampleCapturesTest {
    private val captures: List<File> =
        File(System.getenv("EXAMPLE_PCAP_DIR")?.takeIf { it.isNotBlank() } ?: "../../example-pcap")
            .listFiles { file -> file.isFile && file.name.endsWith(".pcap") }
            .orEmpty()
            .sortedBy { it.name }

    @Test
    fun everyCamIsExtractedAndDecodedLikeWireshark() = assertDecodedLikeWireshark(
        label = "CAMs",
        messageId = CamDecoder.MESSAGE_ID_CAM,
        fields = WiresharkCam.fields,
        expected = WiresharkCam::expected,
        decode = CamDecoder::decode,
    )

    @Test
    fun everyDenmIsExtractedAndDecodedLikeWireshark() = assertDecodedLikeWireshark(
        label = "DENMs",
        messageId = DenmDecoder.MESSAGE_ID_DENM,
        fields = WiresharkDenm.fields,
        expected = WiresharkDenm::expected,
        decode = DenmDecoder::decode,
    )

    /**
     * Asserts that every frame tshark sees as message [messageId] is extracted, and decoded to what tshark reads, and
     * that no other frame is extracted as that message.
     */
    private fun <T> assertDecodedLikeWireshark(
        label: String,
        messageId: Int,
        fields: List<String>,
        expected: (Map<String, String>) -> T?,
        decode: (ItsPacket) -> T?,
    ) {
        assumeTrue("No captures in example-pcap", captures.isNotEmpty())
        var total = 0
        for (capture in captures) {
            val wireshark = Tshark.fields(
                capture.toPath(),
                listOf("frame.number", EXTENSION_BIT) + fields,
                filter = "its.messageId == $messageId",
                timeoutSeconds = 300,
            ).associateBy { it.getValue("frame.number").toLong() }
            val decoded = itsPackets(capture, messageId).mapValues { decode(it.value) }

            assertEquals("${capture.name}: $label not extracted", emptySet<Long>(), wireshark.keys - decoded.keys)
            assertEquals("${capture.name}: not $label for tshark", emptySet<Long>(), decoded.keys - wireshark.keys)
            for ((number, values) in wireshark) {
                val frame = "${capture.name} frame $number"
                assertComparable(frame, values)
                assertEquals(frame, expected(values), decoded[number])
            }
            println("${capture.name}: ${wireshark.size} $label, ${decoded.values.count { it != null }} decoded")
            total += wireshark.size
        }
        println("${captures.size} captures: $total $label")
    }

    /**
     * Asserts that tshark finds the frame well-formed and without extension bits. The decoders stop in front of
     * extension additions that tshark reads past, so tshark's values are only comparable for frames without them.
     */
    private fun assertComparable(frame: String, values: Map<String, String>) {
        assertEquals("$frame is malformed", "", values.getValue("_ws.malformed"))
        assertFalse("$frame has an extension", "True" in values.getValue(EXTENSION_BIT).split(','))
    }

    /** Extracted ITS packets with [messageId] by frame number, counted from 1 like tshark. */
    private fun itsPackets(capture: File, messageId: Int): Map<Long, ItsPacket> {
        val packets = mutableMapOf<Long, ItsPacket>()
        capture.inputStream().buffered().use { input ->
            val reader = PcapReader(input)
            var number = 0L
            while (true) {
                val frame = reader.nextFrame() ?: break
                number += 1
                val packet = ItsFrameExtractor.extract(frame.payload) ?: continue
                if (packet.messageId == messageId) packets[number] = packet
            }
        }
        return packets
    }

    private companion object {
        const val EXTENSION_BIT = "per.extension_bit"
    }
}
