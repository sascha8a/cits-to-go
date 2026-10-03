package org.opentrafficmap.citstogo.intersection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ItsFrameExtractorTest {
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

    private fun fixture(name: String): ByteArray = requireNotNull(
        javaClass.getResourceAsStream("/intersection/$name"),
    ).use { it.readBytes() }

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
}
