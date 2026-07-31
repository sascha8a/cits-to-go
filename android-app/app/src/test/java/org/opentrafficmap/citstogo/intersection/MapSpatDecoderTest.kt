package org.opentrafficmap.citstogo.intersection

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MapSpatDecoderTest {
    @Test
    fun extractsItsPacketFromSampleSpatemFrame() {
        val frame = pcapFrames().first()
        val packet = ItsFrameExtractor.extract(frame)

        assertNotNull(packet)
        requireNotNull(packet)
        assertEquals(MapSpatDecoder.BTP_PORT_SPATEM, packet.destinationPort)
        assertEquals(2, packet.protocolVersion)
        assertEquals(MapSpatDecoder.MESSAGE_ID_SPATEM, packet.messageId)
        assertEquals(1039L, packet.stationId)
    }

    @Test
    fun decodesSampleSpatemSignalStates() {
        val packet = requireNotNull(ItsFrameExtractor.extract(pcapFrames("cits-1785335702733.pcap")[0]))
        val intersections = MapSpatDecoder.decodeSpat(packet, 1_000)

        assertEquals(1, intersections.size)
        val spat = intersections.first()
        assertEquals(IntersectionKey(43, 1039), spat.key)
        assertEquals(3, spat.revision)
        assertEquals(21, spat.movements.size)
        assertEquals(MovementPhaseState.StopAndRemain, spat.movements.first { it.signalGroup == 1 }.currentEvent?.state)
        assertEquals(21280, spat.movements.first { it.signalGroup == 1 }.currentEvent?.minEndTime)
    }

    @Test
    fun decodesNamedSpatemSignalStates() {
        val packet = requireNotNull(ItsFrameExtractor.extract(pcapFrames("cits-1785487825313.pcap")[0]))
        val intersections = MapSpatDecoder.decodeSpat(packet, 1_000)

        assertEquals(1, intersections.size)
        val spat = intersections.first()
        assertEquals(IntersectionKey(43, 4003), spat.key)
        assertEquals(1, spat.revision)
        assertEquals(22, spat.movements.size)
        assertEquals(MovementPhaseState.ProtectedAllowed, spat.movements.first { it.signalGroup == 1 }.currentEvent?.state)
        assertEquals(MovementPhaseState.StopAndRemain, spat.movements.first { it.signalGroup == 2 }.currentEvent?.state)
        assertEquals(MovementPhaseState.PermissiveAllowed, spat.movements.first { it.signalGroup == 9 }.currentEvent?.state)
    }

    @Test
    fun decodesNamedSpatemSignalStatesAcrossCapture() {
        val decoded = pcapFrames("cits-1785487825313.pcap")
            .mapIndexedNotNull { index, frame ->
                val packet = ItsFrameExtractor.extract(frame)
                    ?.takeIf { it.destinationPort == MapSpatDecoder.BTP_PORT_SPATEM }
                    ?: return@mapIndexedNotNull null
                index to MapSpatDecoder.decodeSpat(packet, 1_000)
            }

        assertTrue(decoded.isNotEmpty())
        decoded.forEach { (_, intersections) ->
            intersections.forEach { spat ->
                assertTrue(spat.movements.isNotEmpty())
                assertTrue(spat.movements.none { it.currentEvent?.state == MovementPhaseState.Unknown })
            }
        }
    }

    @Test
    fun decodesNamedSpatemTimingDetails() {
        val packet = requireNotNull(ItsFrameExtractor.extract(pcapFrames("cits-1785487825313.pcap")[16]))
        val intersections = MapSpatDecoder.decodeSpat(packet, 1_000)

        assertEquals(1, intersections.size)
        val spat = intersections.first()
        assertEquals(IntersectionKey(17153, 4006), spat.key)
        assertEquals(4, spat.movements.size)
        val signalGroup1 = spat.movements.first { it.signalGroup == 1 }
        assertEquals(MovementPhaseState.StopAndRemain, signalGroup1.currentEvent?.state)
        assertEquals(30440, signalGroup1.currentEvent?.minEndTime)
        assertEquals(30440, signalGroup1.currentEvent?.likelyTime)
        assertEquals(30440, signalGroup1.currentEvent?.maxEndTime)
        assertEquals(MovementPhaseState.PreMovement, signalGroup1.events[1].state)
        assertEquals(30460, signalGroup1.events[1].likelyTime)
    }

    @Test
    fun decodesSampleMapemIntersectionGeometry() {
        val packet = requireNotNull(ItsFrameExtractor.extract(pcapFrames("cits-1785335702733.pcap")[3]))
        val intersections = MapSpatDecoder.decodeMap(packet, 1_000)

        assertEquals(1, intersections.size)
        val map = intersections.first()
        assertEquals(IntersectionKey(43, 1039), map.key)
        assertEquals("Kaerntner Ring - Kaerntnerstr.", map.name)
        assertEquals(3, map.revision)
        assertEquals(482023549, map.latitude)
        assertEquals(163696051, map.longitude)
        assertEquals(49, map.lanes.size)
        assertTrue(map.lanes.any { lane -> lane.id == 11 && lane.connections.any { it.signalGroup == 1 } })
        assertTrue(map.lanes.any { lane -> lane.nodes.any { it.stopLine } })
    }

    private fun pcapFrames(fileName: String = "cits-1785335702733.pcap"): List<ByteArray> {
        val pcap = listOf(
            File("../$fileName"),
            File("../../$fileName"),
            File(fileName),
        ).first { it.exists() }
        val bytes = pcap.readBytes()
        require(intLE(bytes, 0) == 0xA1B2C3D4.toInt())
        var offset = 24
        val frames = mutableListOf<ByteArray>()
        while (offset + 16 <= bytes.size) {
            val capturedLength = intLE(bytes, offset + 8)
            offset += 16
            if (offset + capturedLength > bytes.size) break
            frames += bytes.copyOfRange(offset, offset + capturedLength)
            offset += capturedLength
        }
        return frames
    }

    private fun intLE(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or
            ((bytes[offset + 1].toInt() and 0xff) shl 8) or
            ((bytes[offset + 2].toInt() and 0xff) shl 16) or
            ((bytes[offset + 3].toInt() and 0xff) shl 24)
}
