package org.opentrafficmap.citstogo

import java.io.File
import java.math.BigDecimal
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
import org.opentrafficmap.citstogo.livemap.LiveMapStore
import org.opentrafficmap.citstogo.packet.Tshark
import org.opentrafficmap.citstogo.protocol.ItsTime

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
     * Replays each capture into a [LiveMapStore] as if live, and checks that the map shows every station and every
     * DENM event with each detection time that arrives valid. Events that only arrive expired or as a termination
     * never show.
     */
    @Test
    fun liveMapShowsEveryStationAndEveryEventThatArrivesValid() {
        assumeTrue("No captures in example-pcap", captures.isNotEmpty())
        for (capture in captures) {
            val wireshark = Tshark.fields(
                capture.toPath(),
                LIVE_MAP_FIELDS,
                filter = "its.protocolVersion == 2 && (its.messageId == 1 || its.messageId == 2)",
                timeoutSeconds = 300,
            ).filter { first(it, "its.latitude") != "900000001" && first(it, "its.longitude") != "1800000001" }
            val expectedStations = wireshark
                .filter { it.getValue("its.messageId") == "${CamDecoder.MESSAGE_ID_CAM}" }
                .map { it.getValue("its.stationId").toLong() }
                .toSet()
            val denmsByActionId = wireshark
                .filter { it.getValue("its.messageId") == "${DenmDecoder.MESSAGE_ID_DENM}" }
                .groupBy { "${first(it, "its.originatingStationId")}/${first(it, "its.sequenceNumber")}" }
            val expectedDetections = denmsByActionId
                .mapValues { (_, frames) -> frames.mapNotNullTo(mutableSetOf(), ::detectionIfValid) }
                .filterValues { it.isNotEmpty() }
            val terminationsOnly = denmsByActionId.filterValues { frames ->
                frames.all { it.getValue("denm.termination").isNotEmpty() }
            }

            val store = LiveMapStore()
            val stations = mutableSetOf<Long>()
            val detections = mutableMapOf<String, MutableSet<Long>>()
            forEachItsPacket(capture) { _, capturedAtMs, packet ->
                store.accept(packet, capturedAtMs, capturedAtMs)
                if (packet.messageId != CamDecoder.MESSAGE_ID_CAM && packet.messageId != DenmDecoder.MESSAGE_ID_DENM) {
                    return@forEachItsPacket
                }
                val snapshot = store.snapshot(capturedAtMs)
                snapshot.stations.mapTo(stations) { it.stationId }
                for (denm in snapshot.denms) {
                    val actionId = "${denm.originatingStationId}/${denm.sequenceNumber}"
                    detections.getOrPut(actionId) { mutableSetOf() } += denm.detectedAtMs
                }
            }

            assertEquals("${capture.name}: stations", expectedStations, stations)
            assertEquals("${capture.name}: DENM detections", expectedDetections, detections)
            when (capture.name) {
                // A cancellation of an event the node never heard.
                "opentrafficmap-node477-2026-10-10.pcap" -> assertEquals(1, terminationsOnly.size)
                // A road side unit refreshes its roadworks warning with a new detection time hours later.
                "opentrafficmap-node585-2026-10-06.pcap" ->
                    assertEquals(2, detections.getValue("1021000/1027").size)
            }
            println(
                "${capture.name}: ${stations.size} stations, ${detections.size} of ${denmsByActionId.size} DENM " +
                    "events shown, ${terminationsOnly.size} only as termination",
            )
        }
    }

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

    /**
     * Calls [block] with the frame number, counted from 1 like tshark, the capture time and the ITS packet of each
     * frame that has one, in capture order.
     */
    private fun forEachItsPacket(capture: File, block: (number: Long, capturedAtMs: Long, packet: ItsPacket) -> Unit) {
        capture.inputStream().buffered().use { input ->
            val reader = PcapReader(input)
            var number = 0L
            while (true) {
                val frame = reader.nextFrame() ?: break
                number += 1
                ItsFrameExtractor.extract(frame.payload)?.let { block(number, frame.timestampUs / 1_000L, it) }
            }
        }
    }

    /** The detection time of a DENM frame as Unix time, unless it is a termination or arrived expired. */
    private fun detectionIfValid(values: Map<String, String>): Long? {
        if (values.getValue("denm.termination").isNotEmpty()) return null
        val detectedAtMs = ItsTime.toUnixMs(values.getValue("denm.detectionTime").toLong())
        val validitySeconds = values.getValue("denm.validityDuration").ifEmpty { "600" }.toLong()
        val capturedAtMs = BigDecimal(values.getValue("frame.time_epoch")).movePointRight(3).toLong()
        return detectedAtMs.takeIf { it + validitySeconds * 1_000L > capturedAtMs }
    }

    /** The first value of [field]; later ones belong to other containers. */
    private fun first(values: Map<String, String>, field: String): String = values.getValue(field).substringBefore(',')

    /** Extracted ITS packets with [messageId] by frame number. */
    private fun itsPackets(capture: File, messageId: Int): Map<Long, ItsPacket> {
        val packets = mutableMapOf<Long, ItsPacket>()
        forEachItsPacket(capture) { number, _, packet -> if (packet.messageId == messageId) packets[number] = packet }
        return packets
    }

    private companion object {
        const val EXTENSION_BIT = "per.extension_bit"
        val LIVE_MAP_FIELDS = listOf(
            "frame.time_epoch",
            "its.messageId",
            "its.stationId",
            "its.latitude",
            "its.longitude",
            "its.originatingStationId",
            "its.sequenceNumber",
            "denm.detectionTime",
            "denm.validityDuration",
            "denm.termination",
        )
    }
}
