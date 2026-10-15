package org.opentrafficmap.citstogo.intersection

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.opentrafficmap.citstogo.cam.CamIdentity
import org.opentrafficmap.citstogo.cam.CamPosition
import org.opentrafficmap.citstogo.cam.ItsG5FrameBuilder
import org.opentrafficmap.citstogo.cam.StationType
import org.opentrafficmap.citstogo.packet.Tshark
import org.opentrafficmap.citstogo.srem.SremPosition
import org.opentrafficmap.citstogo.srem.SremProfile
import org.opentrafficmap.citstogo.srem.SremRequest

class ItsFrameExtractorTest {
    private val camIdentity = CamIdentity(stationId = 0x0102_0304, macAddress = byteArrayOf(0x02, 1, 2, 3, 4, 5))
    private val camPosition = CamPosition(
        latitude = 482_024_036,
        longitude = 163_691_773,
        semiMajorConfidenceCm = 50,
        semiMinorConfidenceCm = 40,
        semiMajorOrientation = 900,
        altitudeCm = 18_000,
        altitudeConfidence = 8,
        heading = 1_350,
        headingConfidence = 10,
        speedCms = 140,
        speedConfidence = 10,
        positionAccurate = true,
    )

    @Test
    fun extractsSecuredSpatemAndExcludesSecurityTrailer() {
        val frame = fixture("secured-spatem-frame.bin")

        val packet = requireNotNull(ItsFrameExtractor.extract(frame))

        assertTrue(packet.geonetworkingSecured)
        assertEquals(MapSpatDecoder.BTP_PORT_SPATEM, packet.destinationPort)
        assertEquals(2, packet.protocolVersion)
        assertEquals(MapSpatDecoder.MESSAGE_ID_SPATEM, packet.messageId)
        assertEquals(121, packet.payload.size)
        assertEquals(IntersectionKey(43, 40103), MapSpatDecoder.decodeSpat(packet, 1_000L).single().key)
    }

    @Test
    fun extractsSecuredMapemWithVariableSecurityPrefixAndRegionalLaneExtension() {
        val frame = fixture("secured-mapem-regional-frame.bin")

        val packet = requireNotNull(ItsFrameExtractor.extract(frame))
        val map = MapSpatDecoder.decodeMap(packet, 1_000L).single()

        assertTrue(packet.geonetworkingSecured)
        assertEquals(MapSpatDecoder.BTP_PORT_MAPEM, packet.destinationPort)
        assertEquals(2, packet.protocolVersion)
        assertEquals(MapSpatDecoder.MESSAGE_ID_MAPEM, packet.messageId)
        assertEquals(1385, packet.payload.size)
        assertEquals(IntersectionKey(43, 40200), map.key)
        assertEquals("Bahnhofstr. - 8. Mai Str.", map.name)
        assertEquals(19, map.lanes.size)
    }

    @Test
    fun securedFrameIgnoresCommonHeaderLookalikeInPositionVector() {
        assertExtractsSecuredSpatem(spatemWithCommonHeaderLookalikeInPositionVector())
    }

    @Test
    fun securedFrameIgnoresCommonHeaderLookalikeInSignature() {
        assertExtractsSecuredSpatem(spatemWithCommonHeaderLookalikeInSignature())
    }

    @Test
    fun recognisedSecuredEnvelopeIsNotScannedForAnotherCommonHeader() {
        // Behind the 1609.2 prefix the Common Header position is known. When that header is unsupported or
        // does not fill the unsecuredData, the frame stays malformed even if a lookalike passes every check.
        val frames = mapOf(
            "BTP-A" to spatemWithCommonHeaderLookalikeInSignature().apply { patch(SPATEM_COMMON_OFFSET, 0x10) },
            "beacon" to spatemWithCommonHeaderLookalikeInSignature().apply { patch(SPATEM_COMMON_OFFSET + 1, 0x10) },
            "unsecuredData length" to
                fixture("secured-spatem-frame.bin").apply { patch(SPATEM_SECURED_OFFSET + 7, 0xb2) },
        )

        for ((change, frame) in frames) {
            val result = ItsFrameExtractor.extractDetailed(frame)
            assertTrue("$change: $result", result is ItsExtractionResult.Malformed && result.secured)
        }
    }

    @Test
    fun securedFrameWithOtherEnvelopeFallsBackToScanningForCommonHeader() {
        val expected = requireNotNull(ItsFrameExtractor.extract(fixture("secured-spatem-frame.bin"))).payload
        val otherVersion = fixture("secured-spatem-frame.bin").apply { patch(SPATEM_SECURED_OFFSET, 0x02) }

        val result = ItsFrameExtractor.extractDetailed(otherVersion)

        assertTrue("$result", result is ItsExtractionResult.Success)
        assertArrayEquals(expected, (result as ItsExtractionResult.Success).packet.payload)
    }

    @Test
    fun fallbackScanStillRejectsTwoPlausibleCommonHeaders() {
        val frame = spatemWithCommonHeaderLookalikeInSignature().apply { patch(SPATEM_SECURED_OFFSET, 0x02) }

        val result = ItsFrameExtractor.extractDetailed(frame)

        assertEquals(ItsExtractionResult.Malformed("Ambiguous secured GeoNetworking payload", secured = true), result)
    }

    @Test
    fun truncatedSecuredFrameIsMalformed() {
        // A lookalike that still fits into the truncated frame must not stand in for the cut-off packet.
        val frames = listOf(fixture("secured-spatem-frame.bin"), spatemWithCommonHeaderLookalikeInPositionVector())

        for (frame in frames) {
            for (size in SPATEM_SECURED_OFFSET until SPATEM_GEONETWORKING_END) {
                val result = ItsFrameExtractor.extractDetailed(frame.copyOf(size))
                assertTrue(
                    "truncated to $size bytes: $result",
                    result is ItsExtractionResult.Malformed && result.secured,
                )
            }
        }
    }

    @Test
    fun stateStoreAcceptsSecuredIntersectionFramesAndExposesDiagnostics() {
        val store = IntersectionStateStore()

        store.accept(fixture("secured-mapem-regional-frame.bin"), 1_000L)
        store.accept(fixture("secured-spatem-frame.bin"), 1_001L)

        val snapshots = store.activeSnapshots(1_001L, 30_000L)
        assertTrue(snapshots.any { it.map?.key == IntersectionKey(43, 40200) })
        assertTrue(snapshots.any { it.spat?.key == IntersectionKey(43, 40103) })
        val diagnostics = store.diagnostics()
        assertEquals(2L, diagnostics.framesInspected)
        assertEquals(2L, diagnostics.itsPacketsExtracted)
        assertEquals(2L, diagnostics.securedItsPackets)
        assertEquals(1L, diagnostics.mapemDecoded)
        assertEquals(0L, diagnostics.mapemDecodeFailures)
        assertEquals(1L, diagnostics.spatemDecoded)
        assertEquals(0L, diagnostics.spatemDecodeFailures)
    }

    @Test
    fun unsecuredGeoNetworkingStillUsesDirectCommonHeader() {
        val frame = syntheticUnsecuredSpatem()

        val packet = requireNotNull(ItsFrameExtractor.extract(frame))

        assertFalse(packet.geonetworkingSecured)
        assertEquals(MapSpatDecoder.BTP_PORT_SPATEM, packet.destinationPort)
        assertEquals(2, packet.protocolVersion)
        assertEquals(MapSpatDecoder.MESSAGE_ID_SPATEM, packet.messageId)
        assertEquals(6, packet.payload.size)
    }

    @Test
    fun malformedSecuredEnvelopeReportsDecodeErrorNotUnsupported() {
        val frame = syntheticSecuredEnvelope(nh = 2, trailing = ByteArray(48))

        val result = ItsFrameExtractor.extractDetailed(frame)

        assertTrue("secured NH=2 must be a decode error, not 'unsupported'", result is ItsExtractionResult.Malformed)
        val malformed = result as ItsExtractionResult.Malformed
        assertTrue(malformed.secured)
        assertNull(ItsFrameExtractor.extract(frame))
    }

    @Test
    fun basicNextHeaderOutsideSupportedEncodingsIsUnsupportedNotMalformed() {
        val frame = syntheticSecuredEnvelope(nh = 3, trailing = ByteArray(48))

        val result = ItsFrameExtractor.extractDetailed(frame)

        assertTrue("NH=3 must be reported as unsupported", result is ItsExtractionResult.Unsupported)
        assertEquals(3, (result as ItsExtractionResult.Unsupported).nextHeader)
    }

    @Test
    fun stateStoreSeparatesMalformedSecuredFromUnsupportedProtocol() {
        val store = IntersectionStateStore()

        store.accept(syntheticSecuredEnvelope(nh = 2, trailing = ByteArray(48)), 1_000L)
        store.accept(syntheticSecuredEnvelope(nh = 3, trailing = ByteArray(48)), 1_001L)

        val diagnostics = store.diagnostics()
        assertEquals(2L, diagnostics.framesInspected)
        assertEquals(0L, diagnostics.itsPacketsExtracted)
        assertEquals(1L, diagnostics.securedGeoNetworkingFrames)
        assertEquals(0L, diagnostics.securedItsPackets)
        assertEquals(1L, diagnostics.malformedGeoNetworkingFrames)
        assertEquals(1L, diagnostics.unsupportedGeoNetworkingFrames)
    }

    @Test
    fun extractsSourcePositionFromTheGeonetworkingAddress() {
        val frame = ItsG5FrameBuilder.camFrame(camIdentity, StationType.PASSENGER_CAR, camPosition, 1_785_242_510_349L, counters())

        val packet = requireNotNull(ItsFrameExtractor.extract(frame))

        assertEquals(camPosition.latitude, packet.sourceLatitude)
        assertEquals(camPosition.longitude, packet.sourceLongitude)
    }

    @Test
    fun extractsSourcePositionFromBroadcastGeonetworkingHeaders() {
        val packet = requireNotNull(ItsFrameExtractor.extract(sremFrame()))

        assertEquals(camPosition.latitude, packet.sourceLatitude)
        assertEquals(camPosition.longitude, packet.sourceLongitude)
    }

    @Test
    fun readsTheGeonetworkingSourceAddressLikeTshark() {
        val cam = ItsG5FrameBuilder.camFrame(camIdentity, StationType.PASSENGER_CAR, camPosition, 1_785_242_510_349L, counters())
        val spatem = fixture("secured-spatem-frame.bin")
        val frames = listOf(cam, sremFrame(), spatem, tsbFrame(cam))

        val sources = frames.map { requireNotNull(ItsFrameExtractor.extract(it)?.geoNetworkingSource) }

        Tshark.fields(frames, SOURCE_FIELDS).zip(sources).forEach { (expected, source) -> assertSource(expected, source) }
        assertEquals(StationType.PASSENGER_CAR.code, sources[0].stationType)
        assertEquals("02:01:02:03:04:05", sources[0].address)
        assertFalse(sources[0].forwarded)
        assertFalse(sources[1].forwarded)
        // The fixture is the SPATEM of a road side unit that another radio forwarded.
        assertEquals(ROAD_SIDE_UNIT, sources[2].stationType)
        assertTrue(sources[2].forwarded)
        // The position vector of a topologically-scoped broadcast follows a sequence number.
        assertEquals(sources[0], sources[3])
    }

    @Test
    fun marksAPacketForwardedOnceItsRemainingHopLimitDropsBelowTheMaximum() {
        val original = sremFrame()
        val forwarded = original.copyOf().apply { this[snapEnd(this) + 3] = (this[snapEnd(this) + 3] - 1).toByte() }

        assertFalse(requireNotNull(ItsFrameExtractor.extract(original)?.geoNetworkingSource).forwarded)
        val source = requireNotNull(ItsFrameExtractor.extract(forwarded)?.geoNetworkingSource)
        assertTrue(source.forwarded)
        assertSource(Tshark.fields(listOf(forwarded), SOURCE_FIELDS).single(), source)
    }

    @Test
    fun readsBeaconsAsSourcesWithoutItsMessage() {
        val beacon = beaconFrame(secured = false)

        val result = ItsFrameExtractor.extractDetailed(beacon)

        assertEquals(ItsExtractionResult.Beacon(BEACON_SOURCE, secured = false), result)
        assertNull(ItsFrameExtractor.extract(beacon))
        val tshark = Tshark.fields(listOf(beacon), SOURCE_FIELDS + "geonw.ch.htype").single()
        assertEquals("0x10", tshark["geonw.ch.htype"])
        assertSource(tshark, BEACON_SOURCE)
    }

    @Test
    fun readsSignedBeacons() {
        assertEquals(
            ItsExtractionResult.Beacon(BEACON_SOURCE, secured = true),
            ItsFrameExtractor.extractDetailed(beaconFrame(secured = true)),
        )
    }

    @Test
    fun rejectsATruncatedBeacon() {
        val beacon = beaconFrame(secured = false)

        val result = ItsFrameExtractor.extractDetailed(beacon.copyOf(beacon.size - 1))

        assertTrue("$result", result is ItsExtractionResult.Malformed)
    }

    @Test
    fun stateStoreCountsBeaconsInsteadOfReportingThemUnsupported() {
        val store = IntersectionStateStore()

        store.accept(beaconFrame(secured = false), 1_000L)
        store.accept(beaconFrame(secured = true), 1_001L)

        val diagnostics = store.diagnostics()
        assertEquals(2L, diagnostics.geoNetworkingBeacons)
        assertEquals(1L, diagnostics.securedGeoNetworkingFrames)
        assertEquals(0L, diagnostics.malformedGeoNetworkingFrames)
        assertEquals(0L, diagnostics.unsupportedGeoNetworkingFrames)
    }

    @Test
    fun readsTheSourcePositionsOfAFieldCapture() {
        val packets = FieldCaptures.frames().mapNotNull(ItsFrameExtractor::extract)

        // The discovery table's GeoNetworking Position column shows exactly these numbers, taken
        // from each transmitter's own position vector, so they have to be real coordinates.
        assertTrue(packets.isNotEmpty())
        assertEquals(
            setOf(MESSAGE_DENM, MESSAGE_CAM, MESSAGE_SPAT, MESSAGE_MAP),
            packets.map { it.messageId }.toSet(),
        )
        packets.forEach { packet ->
            val latitude = requireNotNull(packet.sourceLatitude) { "no position in a ${packet.messageId} frame" }
            val longitude = requireNotNull(packet.sourceLongitude) { "no position in a ${packet.messageId} frame" }
            assertTrue("position out of range: $latitude, $longitude", latitude in 481_000_000..483_000_000)
            assertTrue("position out of range: $latitude, $longitude", longitude in 162_000_000..165_000_000)
        }
    }

    private fun counters() = ItsG5FrameBuilder.SequenceCounters(initialWlanSequence = 0x0f0, initialGbcSequenceId = 0x0fa0)

    private fun sremFrame(): ByteArray {
        val request = SremRequest(
            region = 43,
            intersectionId = 1_039,
            requestId = 5,
            sequenceNumber = 27,
            inboundLaneId = 31,
            outboundLaneId = 16,
            position = SremPosition(camPosition.latitude, camPosition.longitude, camPosition.heading, true),
            nowUnixMs = 1_785_242_510_349L,
            profile = SremProfile.PEDESTRIAN,
            packageRequestUnixMs = 1_785_242_511_349L,
        )
        return ItsG5FrameBuilder.sremFrame(camIdentity, request, counters())
    }

    /**
     * [shb] as a topologically-scoped broadcast: header subtype 1, and the extended header starts with a sequence
     * number and a reserved field instead of ending with them.
     */
    private fun tsbFrame(shb: ByteArray): ByteArray {
        val common = snapEnd(shb) + 4
        val extended = common + 8
        return shb.copyOf().apply {
            this[common + 1] = 0x51
            byteArrayOf(0x12, 0x34, 0x00, 0x00).copyInto(this, extended)
            shb.copyInto(this, extended + 4, extended, extended + 24)
        }
    }

    /** The offset of the GeoNetworking Basic Header behind the LLC/SNAP header. */
    private fun snapEnd(frame: ByteArray): Int {
        val snap = byteArrayOf(0xaa.toByte(), 0xaa.toByte(), 0x03, 0x00, 0x00, 0x00, 0x89.toByte(), 0x47)
        val start = (0..frame.size - snap.size).first { offset -> snap.indices.all { frame[offset + it] == snap[it] } }
        return start + snap.size
    }

    /**
     * A road side unit's beacon behind the 802.11 and LLC headers of a CAM frame: a Common Header without payload
     * and the Long Position Vector of [BEACON_SOURCE]. A [secured] one is signed IEEE 1609.2 data.
     */
    private fun beaconFrame(secured: Boolean): ByteArray {
        val cam = ItsG5FrameBuilder.camFrame(camIdentity, StationType.PASSENGER_CAR, camPosition, 1_785_242_510_349L, counters())
        val linkLayer = cam.copyOf(snapEnd(cam))
        // Next header any, beacon, traffic class 2, mobile, no payload, maximum one hop.
        val common = byteArrayOf(0x00, 0x10, 0x02, 0x80.toByte(), 0x00, 0x00, 0x01, 0x00)
        val positionVector = byteArrayOf((ROAD_SIDE_UNIT shl 2).toByte(), 0x00) + BEACON_MID +
            int32(0x1234_5678) + int32(BEACON_LATITUDE) + int32(BEACON_LONGITUDE) + ByteArray(4)
        val packet = common + positionVector
        return if (secured) {
            // Basic Header with secured packet next, then signedData { sha256, data { unsecuredData } }.
            val signedPrefix = byteArrayOf(0x03, 0x81.toByte(), 0x00, 0x40, 0x03, 0x80.toByte(), packet.size.toByte())
            linkLayer + byteArrayOf(0x12, 0x00, 0x1a, 0x01) + signedPrefix + packet + ByteArray(80)
        } else {
            linkLayer + byteArrayOf(0x11, 0x00, 0x1a, 0x01) + packet
        }
    }

    private fun int32(value: Int): ByteArray =
        byteArrayOf((value ushr 24).toByte(), (value ushr 16).toByte(), (value ushr 8).toByte(), value.toByte())

    private fun assertSource(tshark: Map<String, String>, source: GeoNetworkingSource) {
        assertEquals(tshark.getValue("geonw.src_pos.addr.type").toInt(), source.stationType)
        assertEquals(tshark.getValue("geonw.src_pos.addr.mid"), source.address)
        assertEquals(tshark.getValue("geonw.src_pos.lat").toInt(), source.latitude)
        assertEquals(tshark.getValue("geonw.src_pos.long").toInt(), source.longitude)
        assertEquals(
            tshark.getValue("geonw.bh.rhl").toInt() < tshark.getValue("geonw.ch.mhl").toInt(),
            source.forwarded,
        )
    }

    private fun fixture(name: String): ByteArray = requireNotNull(
        javaClass.getResourceAsStream("/intersection/$name"),
    ).use { it.readBytes() }

    private fun assertExtractsSecuredSpatem(frame: ByteArray) {
        val expected = requireNotNull(ItsFrameExtractor.extract(fixture("secured-spatem-frame.bin")))

        val result = ItsFrameExtractor.extractDetailed(frame)

        assertTrue("$result", result is ItsExtractionResult.Success)
        val packet = (result as ItsExtractionResult.Success).packet
        assertTrue(packet.geonetworkingSecured)
        assertEquals(MapSpatDecoder.BTP_PORT_SPATEM, packet.destinationPort)
        assertEquals(MapSpatDecoder.MESSAGE_ID_SPATEM, packet.messageId)
        assertArrayEquals(expected.payload, packet.payload)
    }

    /**
     * Turns the sender's GN address and timestamp in the source position vector into a second plausible
     * packet, as seen in the example captures (there the lookalikes sat in the source position vector or
     * in the ITS payload): an SHB Common Header for BTP-B with payload length 10 at byte 62, whose BTP and
     * ITS PDU headers coincide with the real ones.
     */
    private fun spatemWithCommonHeaderLookalikeInPositionVector(): ByteArray =
        fixture("secured-spatem-frame.bin").apply {
            patch(SPATEM_SOURCE_POSITION_OFFSET + 4, 0x20, 0x50)
            patch(SPATEM_SOURCE_POSITION_OFFSET + 8, 0x00, 0x0a)
        }

    /**
     * Turns the random ECDSA signature at the end of the security trailer into a second plausible
     * packet: an SHB Common Header for BTP-B with payload length 10 at the start of `r`, and an ITS
     * PDU header (version 2, CAM) 8 + 28 + 4 bytes later, inside `s`.
     */
    private fun spatemWithCommonHeaderLookalikeInSignature(): ByteArray =
        fixture("secured-spatem-frame.bin").apply {
            patch(SPATEM_SIGNATURE_R_OFFSET, 0x20, 0x50, 0x00, 0x00, 0x00, 0x0a)
            patch(SPATEM_SIGNATURE_R_OFFSET + 40, 0x02, 0x02)
        }

    private fun ByteArray.patch(offset: Int, vararg values: Int) {
        values.forEachIndexed { index, value -> this[offset + index] = value.toByte() }
    }

    private fun syntheticSecuredEnvelope(nh: Int, trailing: ByteArray): ByteArray {
        val snap = byteArrayOf(
            0xaa.toByte(), 0xaa.toByte(), 0x03, 0x00, 0x00, 0x00, 0x89.toByte(), 0x47,
        )
        val basic = byteArrayOf(nh.toByte(), 0x00, 0x01, 0x01)
        return ByteArray(24) + snap + basic + trailing
    }

    private fun syntheticUnsecuredSpatem(): ByteArray {
        val snap = byteArrayOf(
            0xaa.toByte(), 0xaa.toByte(), 0x03, 0x00, 0x00, 0x00, 0x89.toByte(), 0x47,
        )
        val basic = byteArrayOf(0x11, 0x00, 0x01, 0x01)
        val common = byteArrayOf(
            0x20, 0x50, 0x00, 0x00,
            0x00, 0x0a, // BTP header (4) + ITS PDU header (6).
            0x01, 0x00,
        )
        val shbExtended = ByteArray(28)
        val btp = byteArrayOf(0x07, 0xd4.toByte(), 0x00, 0x00)
        val its = byteArrayOf(0x02, 0x04, 0x00, 0x00, 0x00, 0x01)
        return ByteArray(24) + snap + basic + common + shbExtended + btp + its
    }

    private companion object {
        /** ITS-PDU message identifiers of the packets the field capture contains. */
        const val MESSAGE_DENM = 1
        const val MESSAGE_CAM = 2
        const val MESSAGE_SPAT = 4
        const val MESSAGE_MAP = 5

        // secured-spatem-frame.bin: the IEEE 1609.2 envelope starts with `03 81 00 40 03 80 81 b1`,
        // the GeoNetworking packet it carries (GBC Common Header at byte 46, source position vector
        // from byte 58) ends at byte 223, and the frame ends with the outer signature (`r` from
        // byte 462, `s` from byte 494) followed by 8 capture bytes.
        const val SPATEM_SECURED_OFFSET = 38
        const val SPATEM_COMMON_OFFSET = 46
        const val SPATEM_SOURCE_POSITION_OFFSET = 58
        const val SPATEM_GEONETWORKING_END = 223
        const val SPATEM_SIGNATURE_R_OFFSET = 462

        const val ROAD_SIDE_UNIT = 15
        val SOURCE_FIELDS = listOf(
            "geonw.src_pos.addr.type",
            "geonw.src_pos.addr.mid",
            "geonw.src_pos.lat",
            "geonw.src_pos.long",
            "geonw.bh.rhl",
            "geonw.ch.mhl",
        )

        // A made-up, locally administered address and a position in Vienna.
        val BEACON_MID = byteArrayOf(0x02, 0x42, 0x42, 0x00, 0x00, 0x01)
        const val BEACON_LATITUDE = 481_234_567
        const val BEACON_LONGITUDE = 162_345_678
        val BEACON_SOURCE = GeoNetworkingSource(
            stationType = ROAD_SIDE_UNIT,
            address = "02:42:42:00:00:01",
            latitude = BEACON_LATITUDE,
            longitude = BEACON_LONGITUDE,
            forwarded = false,
        )
    }
}
