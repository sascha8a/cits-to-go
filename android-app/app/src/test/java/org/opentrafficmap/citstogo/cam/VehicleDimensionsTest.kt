package org.opentrafficmap.citstogo.cam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.opentrafficmap.citstogo.packet.Tshark
import org.opentrafficmap.citstogo.srem.SremProfile

class VehicleDimensionsTest {
    @Test
    fun defaultsFollowIssue11AndLeaveOutVulnerableRoadUsers() {
        assertEquals(VehicleDimensions(45, 18, VehicleDimensions.Trailer.NONE), VehicleDimensions.defaults(SremProfile.PASSENGER_CAR))
        assertEquals(VehicleDimensions(120, 26, VehicleDimensions.Trailer.NONE), VehicleDimensions.defaults(SremProfile.PUBLIC_TRANSPORT_BUS))
        assertEquals(VehicleDimensions(20, 8, VehicleDimensions.Trailer.NONE), VehicleDimensions.defaults(SremProfile.MOTORCYCLE))
        for (profile in listOf(SremProfile.PEDESTRIAN, SremProfile.BICYCLE)) {
            assertFalse(VehicleDimensions.applies(profile))
            assertEquals(VehicleDimensions.UNAVAILABLE, VehicleDimensions.defaults(profile))
        }
        SremProfile.entries.filter(VehicleDimensions::applies).forEach { profile ->
            val defaults = VehicleDimensions.defaults(profile)
            assertTrue("$profile", defaults.lengthDm != null && defaults.widthDm != null)
        }
    }

    @Test
    fun valuesUseTheCddRangesAndTheirUnavailableCodes() {
        val unavailable = VehicleDimensions.UNAVAILABLE
        assertEquals(1_023, unavailable.lengthValue)
        assertEquals(4, unavailable.lengthConfidenceIndication)
        assertEquals(62, unavailable.widthValue)

        val roadTrain = VehicleDimensions(1_500, 80, VehicleDimensions.Trailer.KNOWN_LENGTH)
        assertEquals(1_022, roadTrain.lengthValue)
        assertEquals(1, roadTrain.lengthConfidenceIndication)
        assertEquals(61, roadTrain.widthValue)

        val trailerWithoutLength = VehicleDimensions(null, 25, VehicleDimensions.Trailer.UNKNOWN_LENGTH)
        assertEquals(4, trailerWithoutLength.lengthConfidenceIndication)
        assertEquals(2, VehicleDimensions(165, 26, VehicleDimensions.Trailer.UNKNOWN_LENGTH).lengthConfidenceIndication)
    }

    @Test
    fun trailerIsReportedOnlyWithALength() {
        assertTrue(VehicleDimensions(165, 26, VehicleDimensions.Trailer.KNOWN_LENGTH).reportsTrailer)
        assertTrue(VehicleDimensions(165, null, VehicleDimensions.Trailer.UNKNOWN_LENGTH).reportsTrailer)
        assertFalse(VehicleDimensions(165, 26, VehicleDimensions.Trailer.NONE).reportsTrailer)
        assertFalse(VehicleDimensions(null, 26, VehicleDimensions.Trailer.KNOWN_LENGTH).reportsTrailer)
        assertFalse(VehicleDimensions.UNAVAILABLE.reportsTrailer)
        SremProfile.entries.forEach { assertFalse("$it", VehicleDimensions.defaults(it).reportsTrailer) }
    }

    @Test
    fun settingsFieldsReadMetresWithPointOrComma() {
        assertEquals(45, VehicleDimensions.parseMeters("4.5", VehicleDimensions.MAX_LENGTH_DM))
        assertEquals(26, VehicleDimensions.parseMeters(" 2,55 ", VehicleDimensions.MAX_WIDTH_DM))
        assertEquals(1, VehicleDimensions.parseMeters("0.01", VehicleDimensions.MAX_WIDTH_DM))
        assertEquals(61, VehicleDimensions.parseMeters("7", VehicleDimensions.MAX_WIDTH_DM))
        for (text in listOf("", " ", "0", "-3", "long", "NaN", "Infinity")) {
            assertNull("'$text'", VehicleDimensions.parseMeters(text, VehicleDimensions.MAX_LENGTH_DM))
        }
        assertEquals("4.5", VehicleDimensions.formatMeters(45))
        assertEquals("12.0", VehicleDimensions.formatMeters(120))
        assertEquals("", VehicleDimensions.formatMeters(null))
    }

    @Test
    fun camCarriesTheSizeAsTsharkReadsIt() {
        val identity = CamIdentity(stationId = 0x0102_0304, macAddress = byteArrayOf(0x02, 1, 2, 3, 4, 5))
        val position = CamPosition(
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
        val sizes = listOf(
            VehicleDimensions(165, 26, VehicleDimensions.Trailer.KNOWN_LENGTH),
            VehicleDimensions(45, 18, VehicleDimensions.Trailer.NONE),
            VehicleDimensions.UNAVAILABLE,
        )
        val frames = sizes.mapIndexed { index, size ->
            val counters = ItsG5FrameBuilder.SequenceCounters(initialWlanSequence = index, initialGbcSequenceId = 0)
            ItsG5FrameBuilder.camFrame(identity, StationType.HEAVY_TRUCK, position, 1_785_242_510_349L, counters, size)
        }

        val wireshark = Tshark.fields(
            frames,
            listOf("its.vehicleLengthValue", "its.vehicleLengthConfidenceIndication", "cam.vehicleWidth", "_ws.malformed"),
        )

        wireshark.zip(sizes).forEach { (fields, size) ->
            assertEquals(size.lengthValue.toString(), fields["its.vehicleLengthValue"])
            assertEquals(size.lengthConfidenceIndication.toString(), fields["its.vehicleLengthConfidenceIndication"])
            assertEquals(size.widthValue.toString(), fields["cam.vehicleWidth"])
            assertEquals("", fields["_ws.malformed"])
        }
    }
}
