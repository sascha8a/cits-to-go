package org.opentrafficmap.citstogo.cam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import org.opentrafficmap.citstogo.cam.CamHighFrequencyContainer.BasicVehicle
import org.opentrafficmap.citstogo.cam.CamHighFrequencyContainer.RoadSideUnit
import org.opentrafficmap.citstogo.cam.CamHighFrequencyContainer.Unknown
import org.opentrafficmap.citstogo.intersection.IntersectionDecodeException
import org.opentrafficmap.citstogo.intersection.ItsFrameExtractor
import org.opentrafficmap.citstogo.packet.TestFrames.hex
import org.opentrafficmap.citstogo.packet.TestFrames.shbFrame
import org.opentrafficmap.citstogo.packet.Tshark

class CamDecoderTest {
    private val identity = CamIdentity(
        stationId = 0xfedc_ba98L,
        macAddress = byteArrayOf(0x02, 1, 2, 3, 4, 5),
    )
    private val timestamp = 1_785_242_510_349L
    private val generationDeltaTime = CamUperEncoder.timestampIts(timestamp).mod(65_536L).toInt()
    private val position = CamPosition(
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
    private val syntheticVehicleCam = ReceivedCam(
        stationId = 0x8000_0001L,
        generationDeltaTime = 40_000,
        stationType = 7,
        latitude = -337_654_321,
        longitude = 1_512_345_678,
        highFrequencyContainer = BasicVehicle,
        heading = 2_345,
        speedCms = 1_234,
        trailer = false,
    )
    private val syntheticPositionOnly = syntheticVehicleCam.copy(
        highFrequencyContainer = Unknown,
        heading = null,
        speedCms = null,
        trailer = null,
    )

    @Test
    fun encodedCamsRoundTrip() {
        val frames = listOf(
            camFrame(StationType.PASSENGER_CAR, position),
            camFrame(StationType.HEAVY_TRUCK, position.copy(latitude = -339_123_456, longitude = -1_512_345_678)),
            camFrame(StationType.MOTORCYCLE, position.copy(heading = 3_601, speedCms = 16_383)),
            camFrame(StationType.ROAD_SIDE_UNIT, position),
        )

        val cams = assertMatchesWireshark(frames)

        val car = ReceivedCam(
            stationId = identity.stationId,
            generationDeltaTime = generationDeltaTime,
            stationType = 5,
            latitude = position.latitude,
            longitude = position.longitude,
            highFrequencyContainer = BasicVehicle,
            heading = position.heading,
            speedCms = position.speedCms,
            // Without a size the CAM leaves the trailer unavailable.
            trailer = null,
        )
        assertEquals(
            listOf(
                car,
                car.copy(stationType = 8, latitude = -339_123_456, longitude = -1_512_345_678),
                car.copy(stationType = 4, heading = null, speedCms = null),
                car.copy(stationType = 15, highFrequencyContainer = RoadSideUnit, heading = null, speedCms = null),
            ),
            cams,
        )
    }

    @Test
    fun encodedTrailersRoundTrip() {
        val sizes = listOf(
            VehicleDimensions(165, 26, VehicleDimensions.Trailer.KNOWN_LENGTH),
            VehicleDimensions(120, 26, VehicleDimensions.Trailer.UNKNOWN_LENGTH),
            VehicleDimensions(45, 18, VehicleDimensions.Trailer.NONE),
            VehicleDimensions(null, 25, VehicleDimensions.Trailer.UNKNOWN_LENGTH),
            VehicleDimensions.UNAVAILABLE,
        )

        val cams = assertMatchesWireshark(sizes.map { camFrame(StationType.HEAVY_TRUCK, position, it) })

        assertEquals(listOf(true, true, false, null, null), cams.map { it?.trailer })
        // The own vehicle shows a trailer exactly when its CAMs report one.
        assertEquals(sizes.map { it.reportsTrailer }, cams.map { it?.trailer == true })
    }

    @Test
    fun trailerFollowsTheLengthConfidenceIndication() {
        val frames = (0L..7L).map { shbFrame(syntheticCam(lengthConfidenceIndication = it)) }

        val cams = assertMatchesWireshark(frames)

        // tshark reads the values 5 to 7 outside the range without marking the frames malformed.
        assertEquals(
            (0..7).map { "$it" },
            Tshark.fields(frames, listOf("its.vehicleLengthConfidenceIndication")).map { it.values.single() },
        )
        // noTrailerPresent, trailerPresentWithKnownLength, trailerPresentWithUnknownLength, trailerPresenceIsUnknown,
        // unavailable and the values out of range.
        assertEquals(listOf(false, true, true, null, null, null, null, null), cams.map { it?.trailer })
        assertEquals(List(8) { syntheticVehicleCam }, cams.map { it?.copy(trailer = false) })
    }

    @Test
    fun roadSideUnitCamWithProtectedZonesHasPositionOnly() {
        // Synthetic C-Roads style RSU CAM: rsuContainerHighFrequency with a permanent and a temporary
        // (ENUMERATED extension) protected zone, written from the ASN.1 and checked with tshark.
        val frame = hex(
            "88000000ffffffffffff020102030405ffffffffffff400f0300aaaa0300000089471100050120500200003a01003c00" +
                "020102030405da043f951cb628a009bbc2a0800000000000000007d1000002020012d6873f9500fa4b62340ea0b29400" +
                "c80c80003a9810a26a4b64a50ea0b7760360006072900539c072700525aea9075056d900",
        )

        val cam = assertMatchesWireshark(listOf(frame)).single()

        assertEquals(
            ReceivedCam(
                stationId = 1_234_567,
                generationDeltaTime = 16_277,
                stationType = 15,
                latitude = 481_700_000,
                longitude = 163_300_000,
                highFrequencyContainer = RoadSideUnit,
                heading = null,
                speedCms = null,
                trailer = null,
            ),
            cam,
        )
    }

    @Test
    fun cItsParserTestVectors() {
        // From c-its-parser 2.0.0, tests/lib_test.rs, Copyright (c) 2026 consider it GmbH, MIT License:
        // the CAM PDU of round_trip() and the IEEE 802.11 part (without radiotap) of the unsecured
        // frame in decode_pcap_frame_wasm(), whose expected values come from the same test.
        val pdu = hex(
            "0202de140ce5c7c0405ab23d82ce2781e9a278274bc633fa54587ca0a27e8302968a9733ff82001a103fe0143980106e" +
                "0075801158ce0002f03adc08c4c800015781d620469633800abc0edb0239319c0055e075081185900002af03a0c0912c" +
                "800016781c9e0565640000c3c0e0902dbb19c006de058d810218ce0035f0155c0006c67000df808d5fde662700073c04" +
                "76fd67319c0058604137d31589c006dc",
        )
        val frame = hex(
            "88000000ffffffffffff8ab0f8a8a225ffffffffffff00882300aaaa0300000089471100050120500280004301001400" +
                "8ab0f8a8a225449b26ff1feb290e05ec04bd800e02d40000000007d1000002029c6bc79326ff405ab20241ce26bad7a1" +
                "8618600036ccd0482d4fa005a882988a7f33ff01fffa00283300002c027902d9adf00379601a6833cd63f0432ce5dc89" +
                "8b",
        )

        val cams = assertMatchesWireshark(listOf(shbFrame(pdu), frame))

        assertEquals(
            listOf(
                ReceivedCam(
                    stationId = 3_725_855_973,
                    generationDeltaTime = 51_136,
                    stationType = 5,
                    latitude = 535_626_518,
                    longitude = 99_761_485,
                    highFrequencyContainer = BasicVehicle,
                    heading = 1_994,
                    speedCms = 1_277,
                    trailer = null,
                ),
                ReceivedCam(
                    stationId = 2_624_309_139,
                    generationDeltaTime = 9_983,
                    stationType = 5,
                    latitude = 535_505_166,
                    longitude = 99_353_789,
                    highFrequencyContainer = BasicVehicle,
                    heading = 724,
                    speedCms = 11,
                    trailer = null,
                ),
            ),
            cams,
        )
    }

    @Test
    fun syntheticVehicleCamMatchesWireshark() {
        val cam = assertMatchesWireshark(listOf(shbFrame(syntheticCam()))).single()

        assertEquals(syntheticVehicleCam, cam)
    }

    @Test
    fun highFrequencyChoiceNotStationTypeSelectsTheContainer() {
        val frames = listOf(
            shbFrame(syntheticCam(stationType = 15)),
            shbFrame(syntheticCam(stationType = 5, highFrequencyContainer = RoadSideUnit)),
        )

        assertEquals(
            listOf(
                syntheticVehicleCam.copy(stationType = 15),
                syntheticPositionOnly.copy(stationType = 5, highFrequencyContainer = RoadSideUnit),
            ),
            assertMatchesWireshark(frames),
        )
    }

    @Test
    fun basicContainerExtensionKeepsOnlyThePosition() {
        val frame = shbFrame(syntheticCam(basicContainerExtension = true))

        val wireshark = Tshark.fields(listOf(frame), WiresharkCam.fields).single()

        // tshark skips the unknown addition and reads on, the decoder stops in front of it.
        assertEquals("", wireshark.getValue("_ws.malformed"))
        assertEquals(syntheticVehicleCam, WiresharkCam.expected(wireshark))
        assertEquals(syntheticPositionOnly, decode(frame))
    }

    @Test
    fun highFrequencyChoiceExtensionKeepsOnlyThePosition() {
        val frame = shbFrame(syntheticCam(highFrequencyContainer = Unknown))

        assertEquals(syntheticPositionOnly, assertMatchesWireshark(listOf(frame)).single())
    }

    @Test
    fun unavailablePositionIsIgnored() {
        val frames = listOf(
            shbFrame(syntheticCam(latitude = 900_000_001)),
            shbFrame(syntheticCam(longitude = 1_800_000_001)),
        )

        assertEquals(listOf(null, null), assertMatchesWireshark(frames))
    }

    @Test
    fun otherProtocolVersionsAndMessagesAreIgnored() {
        val version1 = shbFrame(syntheticCam(protocolVersion = 1))

        // protocolVersion 1 differs on the wire from curvature on, so tshark reads these bytes as a malformed CAMv1.
        val wireshark = Tshark.fields(listOf(version1), listOf("_ws.col.Protocol", "_ws.malformed")).single()
        assertEquals("CAMv1", wireshark.getValue("_ws.col.Protocol"))
        assertNotEquals("", wireshark.getValue("_ws.malformed"))
        assertNull(decode(version1))
        assertNull(decode(shbFrame(syntheticCam(protocolVersion = 3))))
        assertNull(decode(shbFrame(syntheticCam(messageId = 1))))
    }

    @Test
    fun malformedCamThrows() {
        val cams = listOf(
            syntheticCam().copyOf(20),
            syntheticCam().copyOf(31), // Ends in front of the DriveDirection.
            syntheticCam(latitude = 900_000_002),
            syntheticCam(longitude = 1_800_000_002),
            syntheticCam(heading = 3_602),
        )

        for (cam in cams) assertThrows(IntersectionDecodeException::class.java) { decode(shbFrame(cam)) }
    }

    /**
     * A CAM written field by field from the ASN.1, independently of [CamUperEncoder], by default from a light truck.
     * An extended BasicContainer carries one unknown addition; an [Unknown] HighFrequencyContainer is an unknown
     * extension alternative.
     */
    private fun syntheticCam(
        protocolVersion: Long = 2,
        messageId: Long = 2,
        stationType: Long = 7,
        latitude: Long = -337_654_321,
        longitude: Long = 1_512_345_678,
        heading: Long = 2_345,
        lengthConfidenceIndication: Long = 0,
        basicContainerExtension: Boolean = false,
        highFrequencyContainer: CamHighFrequencyContainer = BasicVehicle,
    ): ByteArray {
        val out = UperBitWriter()
        out.constrained(protocolVersion, 0, 255)
        out.constrained(messageId, 0, 255)
        out.constrained(0x8000_0001L, 0, 0xffff_ffffL)
        out.constrained(40_000, 0, 65_535)
        out.bits(0, 3) // CamParameters without extensions, low frequency and special vehicle containers.

        out.bit(basicContainerExtension)
        out.constrained(stationType, 0, 255)
        out.bits(latitude + 900_000_000, 31) // Raw, so that out-of-range values can be written.
        out.bits(longitude + 1_800_000_000, 32)
        out.constrained(250, 0, 4_095)
        out.constrained(120, 0, 4_095)
        out.constrained(1_800, 0, 3_601)
        out.constrained(-1_500, -100_000, 800_001)
        out.constrained(9, 0, 15)
        if (basicContainerExtension) {
            out.bit(false) // Normally small bitmap length 1.
            out.bits(0, 6)
            out.bit(true)
            writeOneOctetOpenType(out)
        }

        out.bit(highFrequencyContainer == Unknown)
        if (highFrequencyContainer == Unknown) {
            out.bit(false) // Normally small index 0 of the extension alternatives.
            out.bits(0, 6)
            writeOneOctetOpenType(out)
            return out.toByteArray()
        }
        out.bit(highFrequencyContainer == RoadSideUnit)
        if (highFrequencyContainer == RoadSideUnit) {
            out.bits(0, 2) // rsuContainerHighFrequency without extensions and protected zones.
            return out.toByteArray()
        }
        out.bits(0, 7) // basicVehicleContainerHighFrequency without optional fields.
        out.bits(heading, 12) // Raw, like the position.
        out.constrained(5, 1, 127)
        out.constrained(1_234, 0, 16_383)
        out.constrained(3, 1, 127)
        out.constrained(0, 0, 2) // DriveDirection forward.
        out.constrained(68, 1, 1_023)
        out.bits(lengthConfidenceIndication, 3) // Raw, like the heading.
        out.constrained(24, 1, 62)
        out.constrained(-12, -160, 161)
        out.constrained(10, 0, 102)
        out.constrained(-30, -1_023, 1_023)
        out.constrained(3, 0, 7)
        out.bit(false) // CurvatureCalculationMode yawRateUsed.
        out.constrained(0, 0, 2)
        out.constrained(-250, -32_766, 32_767)
        out.constrained(4, 0, 8)
        return out.toByteArray()
    }

    private fun writeOneOctetOpenType(out: UperBitWriter) {
        out.bits(1, 8)
        out.bits(0x5a, 8)
    }

    private fun camFrame(
        stationType: StationType,
        position: CamPosition,
        dimensions: VehicleDimensions = VehicleDimensions.UNAVAILABLE,
    ): ByteArray =
        ItsG5FrameBuilder.camFrame(
            identity,
            stationType,
            position,
            timestamp,
            ItsG5FrameBuilder.SequenceCounters(initialWlanSequence = 0, initialGbcSequenceId = 0),
            dimensions,
        )

    private fun decode(frame: ByteArray): ReceivedCam? =
        CamDecoder.decode(requireNotNull(ItsFrameExtractor.extract(frame)))

    /** Decodes [frames] and asserts that every result is what tshark reads from the same well-formed frame. */
    private fun assertMatchesWireshark(frames: List<ByteArray>): List<ReceivedCam?> {
        val wireshark = Tshark.fields(frames, WiresharkCam.fields)
        return frames.mapIndexed { index, frame ->
            assertEquals("frame $index is malformed", "", wireshark[index].getValue("_ws.malformed"))
            val expected = WiresharkCam.expected(wireshark[index])
            decode(frame).also { assertEquals("frame $index: ${wireshark[index]}", expected, it) }
        }
    }
}

/** The [ReceivedCam] that tshark's dissection of a CAM implies, from raw `-T fields` values. */
internal object WiresharkCam {
    val fields = listOf(
        "_ws.malformed",
        "its.protocolVersion",
        "its.messageId",
        "its.stationId",
        "cam.generationDeltaTime",
        "its.stationType",
        "its.latitude",
        "its.longitude",
        "cam.highFrequencyContainer",
        "its.headingValue",
        "its.speedValue",
        "its.vehicleLengthConfidenceIndication",
    )

    fun expected(values: Map<String, String>): ReceivedCam? {
        if (values["its.protocolVersion"] != "2" || values["its.messageId"] != "2") return null
        val latitude = values.getValue("its.latitude").toInt()
        val longitude = values.getValue("its.longitude").toInt()
        if (latitude == 900_000_001 || longitude == 1_800_000_001) return null
        return ReceivedCam(
            stationId = values.getValue("its.stationId").toLong(),
            generationDeltaTime = values.getValue("cam.generationDeltaTime").toInt(),
            stationType = values.getValue("its.stationType").toInt(),
            latitude = latitude,
            longitude = longitude,
            highFrequencyContainer = when (values["cam.highFrequencyContainer"]) {
                "0" -> BasicVehicle
                "1" -> RoadSideUnit
                else -> Unknown
            },
            heading = values.getValue("its.headingValue").ifEmpty { null }?.toInt()?.takeUnless { it == 3_601 },
            speedCms = values.getValue("its.speedValue").ifEmpty { null }?.toInt()?.takeUnless { it == 16_383 },
            trailer = when (values.getValue("its.vehicleLengthConfidenceIndication")) {
                "0" -> false
                "1", "2" -> true
                else -> null
            },
        )
    }
}
