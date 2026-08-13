package org.opentrafficmap.citstogo.intersection

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MapSpatDecoderTest {
    @Test
    fun reportedCapturesDoNotProduceHourLongCountdowns() {
        var specialTimeMarkCount = 0
        listOf("cits-1785740418797.pcap", "cits-1785741343912.pcap").forEach { fileName ->
            pcapFrames(fileName).forEach { frame ->
                val packet = ItsFrameExtractor.extract(frame)
                    ?.takeIf { it.destinationPort == MapSpatDecoder.BTP_PORT_SPATEM }
                    ?: return@forEach
                MapSpatDecoder.decodeSpat(packet, 1_000L).forEach { spat ->
                    spat.movements.mapNotNull { it.currentEvent }.forEach { event ->
                        specialTimeMarkCount += listOfNotNull(event.minEndTime, event.likelyTime, event.maxEndTime)
                            .count { it >= 36_000 }
                        event.secondsUntilChange(spat, 1_000L)?.let { seconds ->
                            assertTrue("Unexpected countdown $seconds in $fileName", seconds in 0 until 600)
                        }
                    }
                }
            }
        }
        assertEquals(6, specialTimeMarkCount)
    }

    @Test
    fun staleTimingFromReportedIntersectionIsNotDisplayed() {
        val affectedIntersections = pcapFrames("cits-1786038712016.pcap").mapNotNull { frame ->
            val packet = ItsFrameExtractor.extract(frame)
                ?.takeIf { it.destinationPort == MapSpatDecoder.BTP_PORT_SPATEM }
                ?: return@mapNotNull null
            MapSpatDecoder.decodeSpat(packet, 1_000L)
                .firstOrNull { it.key == IntersectionKey(17153, 1010) }
        }

        assertTrue(affectedIntersections.isNotEmpty())
        affectedIntersections.forEach { spat ->
            assertNull(spat.movements.first { it.signalGroup == 1 }.currentEvent?.secondsUntilChange(spat, 1_000L))
        }
    }

    @Test
    fun reportedRailConnectionsRemainAvailableWithoutMatchingSpatGroups() {
        val frames = pcapFrames("cits-1786038712016.pcap")
        val mapPacket = requireNotNull(ItsFrameExtractor.extract(frames[19]))
        val map = MapSpatDecoder.decodeMap(mapPacket, 1_000L)
            .first { it.key == IntersectionKey(43, 1048) }
        val spatPacket = frames.asSequence()
            .mapNotNull(ItsFrameExtractor::extract)
            .first { packet ->
                packet.destinationPort == MapSpatDecoder.BTP_PORT_SPATEM &&
                    MapSpatDecoder.decodeSpat(packet, 1_000L).any { it.key == map.key }
            }
        val spatGroups = MapSpatDecoder.decodeSpat(spatPacket, 1_000L)
            .first { it.key == map.key }
            .movementsBySignalGroup.keys
        val railConnections = map.lanes
            .filter { it.laneType == LaneType.TrackedVehicle }
            .flatMap { lane -> lane.connections.map { connection -> lane to connection } }

        assertTrue(railConnections.isNotEmpty())
        assertTrue(railConnections.any { (_, connection) -> connection.signalGroup !in spatGroups })
        railConnections.forEach { (lane, connection) ->
            val connectedLane = map.lanes.first { it.id == connection.laneId }
            assertTrue(
                intersectionConnectionVisible(
                    laneId = lane.id,
                    connectedLaneId = connectedLane.id,
                    signalized = connection.signalGroup in spatGroups,
                    alwaysVisible = true,
                    selectedLaneIds = emptyList(),
                ),
            )
        }
    }

    @Test
    fun ignoresOutOfOrderSpatemFromReportedCapture() {
        val frames = pcapFrames("cits-1785740418797.pcap")
        val store = IntersectionStateStore()

        store.accept(frames[23], 1_000L)
        assertEquals(18_885, store.closest(null)?.spat?.timestampMs)

        store.accept(frames[45], 2_000L)
        val retained = requireNotNull(store.closest(null)?.spat)
        assertEquals(18_885, retained.timestampMs)
        assertEquals(1_000L, retained.receivedAtMs)
    }

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
    fun skipsSpatemMovementEventRegionalExtensions() {
        val packet = syntheticSpatemWithMovementEventRegionalExtension()
        val intersections = MapSpatDecoder.decodeSpat(packet, 1_000)

        assertEquals(1, intersections.size)
        val spat = intersections.first()
        assertEquals(IntersectionKey(43, 1005), spat.key)
        assertEquals(1, spat.movements.size)
        assertEquals(MovementPhaseState.ProtectedAllowed, spat.movements.first().currentEvent?.state)
    }

    @Test
    fun mergesSplitMapemLaneSetsForSameIntersectionRevision() {
        val frames = pcapFrames("cits-1785487825313.pcap")
        val store = IntersectionStateStore()

        store.accept(frames[24], 1_000)
        val firstHalf = requireNotNull(store.closest(null)?.map)
        assertEquals(IntersectionKey(43, 4003), firstHalf.key)
        assertEquals("Wien_04003_V011a", firstHalf.name)
        assertEquals(24, firstHalf.lanes.size)
        assertEquals(12, firstHalf.lanes.count { it.connections.isNotEmpty() })

        store.accept(frames[25], 1_001)
        val merged = requireNotNull(store.closest(null)?.map)
        assertEquals(IntersectionKey(43, 4003), merged.key)
        assertEquals(48, merged.lanes.size)
        assertEquals(30, merged.lanes.count { it.connections.isNotEmpty() })
        assertTrue(merged.lanes.any { it.id == 4 })
        assertTrue(merged.lanes.any { it.id == 58 })

        store.accept(frames[117], 1_002)
        val refreshed = requireNotNull(store.closest(null)?.map)
        assertEquals(48, refreshed.lanes.size)
        assertEquals(30, refreshed.lanes.count { it.connections.isNotEmpty() })
    }

    @Test
    fun listsActiveIntersectionsAndExpiresStaleOnes() {
        val frames = pcapFrames("cits-1785487825313.pcap")
        val store = IntersectionStateStore()

        store.accept(frames[16], 1_000)
        store.accept(frames[24], 1_001)
        store.accept(frames[20], 1_002)

        val active = store.activeSnapshots(1_002, 30_000)
        assertEquals(2, active.size)
        assertEquals(IntersectionKey(17153, 4006), active[0].spat?.key ?: active[0].map?.key)
        assertEquals(IntersectionKey(43, 4003), active[1].map?.key ?: active[1].spat?.key)

        val partiallyExpired = store.activeSnapshots(31_002, 30_000)
        assertEquals(1, partiallyExpired.size)
        assertEquals(IntersectionKey(17153, 4006), partiallyExpired[0].map?.key ?: partiallyExpired[0].spat?.key)
        assertTrue(store.activeSnapshots(31_003, 30_000).isEmpty())
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

    private fun syntheticSpatemWithMovementEventRegionalExtension(): ItsPacket {
        val writer = TestBitWriter()
        writer.bit(false) // SPAT extension marker.
        writer.bit(false) // timeStamp absent.
        writer.bit(false) // name absent.
        writer.bit(false) // regional absent.
        writer.constrained(1, 1, 32) // intersections.

        writer.bit(false) // IntersectionState extension marker.
        writer.bit(false) // name absent.
        writer.bit(false) // moy absent.
        writer.bit(false) // timeStamp absent.
        writer.bit(false) // enabledLanes absent.
        writer.bit(false) // maneuverAssistList absent.
        writer.bit(false) // regional absent.
        writer.bit(true) // IntersectionReferenceID region present.
        writer.constrained(43, 0, 65535)
        writer.constrained(1005, 0, 65535)
        writer.constrained(1, 0, 127) // revision.
        writer.bits(0, 16) // IntersectionStatusObject.
        writer.constrained(1, 1, 255) // states.

        writer.bit(false) // MovementState extension marker.
        writer.bit(false) // movementName absent.
        writer.bit(false) // maneuverAssistList absent.
        writer.bit(false) // regional absent.
        writer.constrained(1, 0, 255) // signalGroup.
        writer.constrained(1, 1, 16) // state-time-speed.

        writer.bit(false) // MovementEvent extension marker.
        writer.bit(false) // timing absent.
        writer.bit(false) // speeds absent.
        writer.bit(true) // regional present.
        writer.bits(MovementPhaseState.ProtectedAllowed.code.toLong(), 4)
        writer.constrained(1, 1, 4) // regional SEQUENCE OF length.
        writer.constrained(1, 0, 255) // regExtId.
        writer.bit(false)
        writer.bits(1, 7) // one-octet open type.
        writer.bits(0xaa, 8) // opaque regExtValue.

        val body = writer.toByteArray()
        return ItsPacket(
            destinationPort = MapSpatDecoder.BTP_PORT_SPATEM,
            protocolVersion = 2,
            messageId = MapSpatDecoder.MESSAGE_ID_SPATEM,
            stationId = 1005,
            bodyOffset = 6,
            payload = byteArrayOf(2, MapSpatDecoder.MESSAGE_ID_SPATEM.toByte(), 0, 0, 3, 0xed.toByte()) + body,
            sourceLatitude = null,
            sourceLongitude = null,
        )
    }

    private class TestBitWriter {
        private var buffer = ByteArray(32)
        private var bitCount = 0

        fun bit(value: Boolean) {
            bits(if (value) 1 else 0, 1)
        }

        fun constrained(value: Long, minimum: Long, maximum: Long) {
            val range = maximum - minimum + 1
            val width = if (range <= 1) 0 else 64 - java.lang.Long.numberOfLeadingZeros(range - 1)
            bits(value - minimum, width)
        }

        fun bits(value: Long, width: Int) {
            require(width in 0..64)
            ensure(bitCount + width)
            for (shift in width - 1 downTo 0) {
                if (((value ushr shift) and 1L) != 0L) {
                    val byteIndex = bitCount / 8
                    val bitIndex = 7 - bitCount % 8
                    buffer[byteIndex] = (buffer[byteIndex].toInt() or (1 shl bitIndex)).toByte()
                }
                bitCount += 1
            }
        }

        fun toByteArray(): ByteArray = buffer.copyOf((bitCount + 7) / 8)

        private fun ensure(bits: Int) {
            val bytes = (bits + 7) / 8
            if (bytes > buffer.size) buffer = buffer.copyOf(maxOf(bytes, buffer.size * 2))
        }
    }
}
