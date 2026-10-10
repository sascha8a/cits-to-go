package org.opentrafficmap.citstogo.denm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import org.opentrafficmap.citstogo.cam.UperBitWriter
import org.opentrafficmap.citstogo.denm.DenmTermination.Cancellation
import org.opentrafficmap.citstogo.denm.DenmTermination.Negation
import org.opentrafficmap.citstogo.intersection.IntersectionDecodeException
import org.opentrafficmap.citstogo.intersection.ItsFrameExtractor
import org.opentrafficmap.citstogo.packet.TestFrames.DENM_PORT
import org.opentrafficmap.citstogo.packet.TestFrames.hex
import org.opentrafficmap.citstogo.packet.TestFrames.shbFrame
import org.opentrafficmap.citstogo.packet.Tshark

class DenmDecoderTest {
    private val vehicleDenm = ReceivedDenm(
        stationId = 0x8000_0001L,
        originatingStationId = 0x8000_0001L,
        sequenceNumber = 4_711,
        detectionTimeIts = 712_327_315_349,
        referenceTimeIts = 712_327_315_474,
        termination = null,
        latitude = 482_024_036,
        longitude = 163_691_773,
        awarenessDistance = 2,
        awarenessTrafficDirection = 0,
        validityDurationSeconds = 60,
        transmissionIntervalMs = null,
        stationType = 5,
        situation = DenmSituation(informationQuality = 3, causeCode = 94, subCauseCode = 2),
    )
    private val roadSideUnitDenm = ReceivedDenm(
        stationId = 1_234_567,
        originatingStationId = 7_654_321,
        sequenceNumber = 65_535,
        detectionTimeIts = 712_327_000_000,
        referenceTimeIts = 712_327_315_349,
        termination = null,
        latitude = -337_654_321,
        longitude = -1_512_345_678,
        awarenessDistance = 6,
        awarenessTrafficDirection = 1,
        validityDurationSeconds = 86_400,
        transmissionIntervalMs = 1_000,
        stationType = 15,
        situation = DenmSituation(informationQuality = 7, causeCode = 3, subCauseCode = 4),
    )

    @Test
    fun vehicleDenmMatchesWireshark() {
        assertEquals(listOf(vehicleDenm), assertMatchesWireshark(listOf(frame(pdu(vehicleDenm)))))
    }

    @Test
    fun roadSideUnitDenmKeepsTheHeaderAndOriginatingStation() {
        assertEquals(listOf(roadSideUnitDenm), assertMatchesWireshark(listOf(frame(pdu(roadSideUnitDenm)))))
    }

    @Test
    fun terminationsHaveNoSituation() {
        val cancellation = roadSideUnitDenm.copy(
            referenceTimeIts = 712_327_400_000,
            termination = Cancellation,
            awarenessDistance = null,
            awarenessTrafficDirection = null,
            transmissionIntervalMs = null,
            situation = null,
        )
        val negation = vehicleDenm.copy(stationId = 0x0102_0304, termination = Negation, situation = null)

        val denms = assertMatchesWireshark(listOf(frame(pdu(cancellation)), frame(pdu(negation))))

        assertEquals(listOf(cancellation, negation), denms)
    }

    @Test
    fun absentValidityDurationIsTheDefault() {
        val denm = vehicleDenm.copy(validityDurationSeconds = 600)

        val decoded = assertMatchesWireshark(listOf(frame(pdu(denm, writeValidityDuration = false)))).single()

        assertEquals(denm, decoded)
    }

    @Test
    fun awarenessDistanceAndTrafficDirectionAreIndependent() {
        val denms = listOf(
            vehicleDenm.copy(awarenessTrafficDirection = null),
            vehicleDenm.copy(awarenessDistance = null),
        )

        assertEquals(denms, assertMatchesWireshark(denms.map { frame(pdu(it)) }))
    }

    @Test
    fun unavailableEventPositionIsNull() {
        val denms = listOf(vehicleDenm.copy(latitude = null), vehicleDenm.copy(longitude = null))

        assertEquals(denms, assertMatchesWireshark(denms.map { frame(pdu(it)) }))
    }

    @Test
    fun causesWithoutTheirOwnSubCauseTypeMatchWireshark() {
        // Aquaplaning and the reserved cause codes, whose subcause is a plain SubCauseCodeType.
        val denms = listOf(7, 13, 128).map {
            vehicleDenm.copy(situation = DenmSituation(informationQuality = 1, causeCode = it, subCauseCode = 9))
        }

        assertEquals(denms, assertMatchesWireshark(denms.map { frame(pdu(it)) }))
    }

    @Test
    fun managementExtensionHidesTheSituation() {
        val frame = frame(pdu(roadSideUnitDenm, managementExtension = true))

        val wireshark = Tshark.fields(listOf(frame), WiresharkDenm.fields).single()

        // tshark skips the unknown addition and reads on, the decoder stops in front of it.
        assertEquals("", wireshark.getValue("_ws.malformed"))
        assertEquals(roadSideUnitDenm, WiresharkDenm.expected(wireshark))
        assertEquals(roadSideUnitDenm.copy(situation = null), decode(frame))
    }

    @Test
    fun causeCodeExtensionKeepsTheCause() {
        val denm = assertMatchesWireshark(listOf(frame(pdu(vehicleDenm, causeCodeExtension = true)))).single()

        assertEquals(vehicleDenm, denm)
    }

    @Test
    fun cItsParserTestVector() {
        // The DENM PDU from c-its-parser 2.0.0, tests/lib_test.rs, Copyright (c) 2026 consider it GmbH, MIT License.
        val pdu = hex(
            "0201e0fd1d37e7465aa8bc8006910d64c90404435932459d59019207135f2142be2be000186a09880050144018030014" +
                "fb843f0a2fdd6bfb00c50a06022d297ff59fffe606805f9c00ff000d3124049be0064802ad89201387001bc0394c3281" +
                "12f801a5fed56144095fc00d2ffc93110015de00577f46580602c86ffd1bfccec7d80bb77fd59fcdb70e4289fbff7b00" +
                "8f33cc0577e00418010d86f0176f0019bfeeeca803a337fc66041e656810fdc002f0021317406d5e004a7ff1d8bf0151" +
                "70037bf862cdf013d78007a002164a001fdbffd2fff8b2aa0270dffb9800b58e700f9effb33fffec4b813d77ff2dffb3" +
                "62e80af9c000900c1b1240784dff12808119f003816ff7f401ccc828423f7ffadfffe602c0531c0278ff0ab1100671df" +
                "fce7ff999fa01daefff440172cc880c7f8001a00ee63d80d0abffe0ffdcaec2093d5ff4b7fffd8970d1ab00533fd64c9" +
                "b80c1d80145fe3b64640545c023cff922fee0883e0081801458e2013870052c06acb72015df800a1fff763880671c01d" +
                "7015d31920577dfff58060d7c00106420480600c",
        )

        val denm = assertMatchesWireshark(listOf(frame(pdu))).single()

        assertEquals(
            ReceivedDenm(
                stationId = 3_774_684_471,
                originatingStationId = 2_360_693_113,
                sequenceNumber = 13,
                detectionTimeIts = 585_913_223_200,
                referenceTimeIts = 585_913_223_347,
                termination = null,
                latitude = 535_506_976,
                longitude = 99_360_788,
                awarenessDistance = 4,
                awarenessTrafficDirection = 1,
                validityDurationSeconds = 20,
                transmissionIntervalMs = null,
                stationType = 5,
                situation = DenmSituation(informationQuality = 4, causeCode = 3, subCauseCode = 0),
            ),
            denm,
        )
    }

    @Test
    fun otherProtocolVersionsAndMessagesAreIgnored() {
        val version1 = frame(pdu(vehicleDenm, protocolVersion = 1))

        // With protocolVersion 1 tshark reads these bits as a DENMv1, whose CauseCode has no extension bit.
        val wireshark = Tshark.fields(listOf(version1), listOf("_ws.col.Protocol")).single()
        assertEquals("DENMv1", wireshark.getValue("_ws.col.Protocol"))
        assertNull(decode(version1))
        assertNull(decode(frame(pdu(vehicleDenm, protocolVersion = 3))))
        assertNull(decode(frame(pdu(vehicleDenm, messageId = 2))))
    }

    @Test
    fun malformedDenmThrows() {
        val pdus = listOf(
            pdu(vehicleDenm).copyOf(20),
            pdu(vehicleDenm.copy(latitude = 900_000_002)),
            pdu(vehicleDenm.copy(longitude = 1_800_000_002)),
            pdu(vehicleDenm.copy(validityDurationSeconds = 86_401)),
            pdu(vehicleDenm.copy(transmissionIntervalMs = 10_001)),
        )

        for (malformed in pdus) assertThrows(IntersectionDecodeException::class.java) { decode(frame(malformed)) }
    }

    /**
     * Writes [denm] field by field from the ASN.1, independently of the decoder, with a minimal LocationContainer
     * whenever it has a situation. An extended ManagementContainer or event type carries one unknown addition.
     */
    private fun pdu(
        denm: ReceivedDenm,
        protocolVersion: Long = 2,
        messageId: Long = 1,
        writeValidityDuration: Boolean = true,
        managementExtension: Boolean = false,
        causeCodeExtension: Boolean = false,
    ): ByteArray {
        val out = UperBitWriter()
        out.constrained(protocolVersion, 0, 255)
        out.constrained(messageId, 0, 255)
        out.constrained(denm.stationId, 0, 0xffff_ffffL)
        val situation = denm.situation
        out.bit(situation != null)
        out.bit(situation != null) // location
        out.bit(false) // alacarte

        out.bit(managementExtension)
        out.bit(denm.termination != null)
        out.bit(denm.awarenessDistance != null)
        out.bit(denm.awarenessTrafficDirection != null)
        out.bit(writeValidityDuration)
        out.bit(denm.transmissionIntervalMs != null)
        out.constrained(denm.originatingStationId, 0, 0xffff_ffffL)
        out.constrained(denm.sequenceNumber.toLong(), 0, 65_535)
        out.constrained(denm.detectionTimeIts, 0, 4_398_046_511_103)
        out.constrained(denm.referenceTimeIts, 0, 4_398_046_511_103)
        denm.termination?.let { out.bit(it == Negation) }
        // Raw, so that out-of-range values can be written.
        out.bits((denm.latitude ?: 900_000_001) + 900_000_000L, 31)
        out.bits((denm.longitude ?: 1_800_000_001) + 1_800_000_000L, 32)
        out.constrained(250, 0, 4_095)
        out.constrained(120, 0, 4_095)
        out.constrained(1_800, 0, 3_601)
        out.constrained(800_001, -100_000, 800_001)
        out.constrained(15, 0, 15)
        denm.awarenessDistance?.let { out.constrained(it.toLong(), 0, 7) }
        denm.awarenessTrafficDirection?.let { out.constrained(it.toLong(), 0, 3) }
        if (writeValidityDuration) out.bits(denm.validityDurationSeconds.toLong(), 17)
        denm.transmissionIntervalMs?.let { out.bits(it - 1L, 14) }
        out.constrained(denm.stationType.toLong(), 0, 255)
        if (managementExtension) writeOneUnknownAddition(out)
        if (situation == null) return out.toByteArray()

        out.bits(0, 3) // SituationContainer without extensions, linkedCause and eventZone.
        out.constrained(situation.informationQuality.toLong(), 0, 7)
        out.bit(causeCodeExtension)
        out.constrained(situation.causeCode.toLong(), 0, 128) // CauseCodeChoice index.
        out.constrained(situation.subCauseCode.toLong(), 0, 255)
        if (causeCodeExtension) writeOneUnknownAddition(out)

        out.bits(0, 4) // LocationContainer without extensions and optional fields.
        out.constrained(1, 1, 7) // One trace without path points.
        out.constrained(0, 0, 40)
        return out.toByteArray()
    }

    /** An extension bitmap with one present addition: an open type of one octet. */
    private fun writeOneUnknownAddition(out: UperBitWriter) {
        out.bit(false) // Normally small bitmap length 1.
        out.bits(0, 6)
        out.bit(true)
        out.bits(1, 8)
        out.bits(0x5a, 8)
    }

    private fun frame(pdu: ByteArray): ByteArray = shbFrame(pdu, DENM_PORT)

    private fun decode(frame: ByteArray): ReceivedDenm? =
        DenmDecoder.decode(requireNotNull(ItsFrameExtractor.extract(frame)))

    /** Decodes [frames] and asserts that every result is what tshark reads from the same well-formed frame. */
    private fun assertMatchesWireshark(frames: List<ByteArray>): List<ReceivedDenm?> {
        val wireshark = Tshark.fields(frames, WiresharkDenm.fields)
        return frames.mapIndexed { index, frame ->
            assertEquals("frame $index is malformed", "", wireshark[index].getValue("_ws.malformed"))
            val expected = WiresharkDenm.expected(wireshark[index])
            decode(frame).also { assertEquals("frame $index: ${wireshark[index]}", expected, it) }
        }
    }
}

/** The [ReceivedDenm] that tshark's dissection of a DENM implies, from raw `-T fields` values. */
internal object WiresharkDenm {
    /**
     * The subcause field of each cause code: tshark names it after the cause, for example `its.roadworks3`. For causes
     * whose subcause is a plain SubCauseCodeType, such as aquaplaning7 and the reserved ones, tshark leaves that field
     * empty and fills `its.subCauseCode` instead.
     */
    private val subCauseFields: Map<Int, String> by lazy {
        val causeSuffix = Regex("\\d+$")
        // Glossary rows: F, name, field, type, protocol, base, bitmask and the ASN.1 type as the description.
        Tshark.run("-G", "fields").lines()
            .map { it.split('\t') }
            .filter { it.size == 8 && it[2].startsWith("its.") && it[7].contains("SubCauseCode") }
            .mapNotNull { row -> causeSuffix.find(row[2])?.let { it.value.toInt() to row[2] } }
            .toMap()
            .also { assertEquals("subcause fields", 129, it.size) }
    }

    val fields: List<String> by lazy {
        listOf(
            "_ws.malformed",
            "its.protocolVersion",
            "its.messageId",
            "its.stationId",
            "its.originatingStationId",
            "its.sequenceNumber",
            "denm.detectionTime",
            "denm.referenceTime",
            "denm.termination",
            "its.latitude",
            "its.longitude",
            "denm.awarenessDistance",
            "denm.awarenessTrafficDirection",
            "denm.validityDuration",
            "denm.transmissionInterval",
            "denm.stationType",
            "denm.informationQuality",
            "its.ccAndScc",
            "its.subCauseCode",
        ) + subCauseFields.values
    }

    fun expected(values: Map<String, String>): ReceivedDenm? {
        if (values["its.protocolVersion"] != "2" || values["its.messageId"] != "1") return null
        // Later occurrences belong to linked causes, à la carte containers and the like.
        fun first(field: String): String? = values.getValue(field).substringBefore(',').ifEmpty { null }
        val informationQuality = first("denm.informationQuality")?.toInt()
        val causeCode = first("its.ccAndScc")?.toInt()
        return ReceivedDenm(
            stationId = values.getValue("its.stationId").toLong(),
            originatingStationId = first("its.originatingStationId")!!.toLong(),
            sequenceNumber = first("its.sequenceNumber")!!.toInt(),
            detectionTimeIts = values.getValue("denm.detectionTime").toLong(),
            referenceTimeIts = values.getValue("denm.referenceTime").toLong(),
            termination = when (values.getValue("denm.termination")) {
                "" -> null
                "0" -> DenmTermination.Cancellation
                else -> DenmTermination.Negation
            },
            latitude = first("its.latitude")!!.toInt().takeUnless { it == 900_000_001 },
            longitude = first("its.longitude")!!.toInt().takeUnless { it == 1_800_000_001 },
            awarenessDistance = first("denm.awarenessDistance")?.toInt(),
            awarenessTrafficDirection = first("denm.awarenessTrafficDirection")?.toInt(),
            validityDurationSeconds = first("denm.validityDuration")?.toInt() ?: 600,
            transmissionIntervalMs = first("denm.transmissionInterval")?.toInt(),
            stationType = values.getValue("denm.stationType").toInt(),
            situation = if (informationQuality == null || causeCode == null) {
                null
            } else {
                val subCauseCode = first(subCauseFields.getValue(causeCode)) ?: first("its.subCauseCode")
                DenmSituation(informationQuality, causeCode, subCauseCode!!.toInt())
            },
        )
    }
}
