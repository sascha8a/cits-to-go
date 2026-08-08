package org.opentrafficmap.citstogo.packet

import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.opentrafficmap.citstogo.bridge.PcapWriter
import org.opentrafficmap.citstogo.cam.CamIdentity
import org.opentrafficmap.citstogo.cam.CamPosition
import org.opentrafficmap.citstogo.cam.ItsG5FrameBuilder
import org.opentrafficmap.citstogo.cam.StationType
import org.opentrafficmap.citstogo.srem.SremRequest
import org.opentrafficmap.citstogo.srem.SremPosition
import org.opentrafficmap.citstogo.srem.SremProfile

class WiresharkPacketValidationTest {
    private val identity = CamIdentity(
        stationId = 0x0102_0304,
        macAddress = byteArrayOf(0x02, 1, 2, 3, 4, 5),
    )
    private val timestamp = 1_785_242_510_349L
    private val availablePosition = CamPosition(
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
    fun camFramesForEveryStationTypeAreWellFormed() {
        StationType.entries.forEach { stationType ->
            assertWellFormed(
                label = "CAM ${stationType.name}",
                frame = ItsG5FrameBuilder.camFrame(identity, stationType, availablePosition, timestamp),
                expectedProtocol = "CAM",
                expectedMessageId = 2,
                expectedBtpPort = 2_001,
            )
        }
    }

    @Test
    fun camFramesWithUnavailablePositionAreWellFormed() {
        StationType.entries.forEach { stationType ->
            assertWellFormed(
                label = "CAM ${stationType.name} with unavailable position",
                frame = ItsG5FrameBuilder.camFrame(
                    identity,
                    stationType,
                    CamPosition.unavailable(),
                    timestamp,
                ),
                expectedProtocol = "CAM",
                expectedMessageId = 2,
                expectedBtpPort = 2_001,
            )
        }
    }

    @Test
    fun sremFramesForEveryProfileAreWellFormed() {
        SremProfile.entries.forEach { profile ->
            val request = SremRequest(
                region = 43,
                intersectionId = 1_039,
                requestId = 5,
                sequenceNumber = 27,
                inboundLaneId = 31,
                outboundLaneId = 16,
                position = SremPosition(
                    availablePosition.latitude,
                    availablePosition.longitude,
                    availablePosition.heading,
                    true,
                ),
                nowUnixMs = timestamp,
                profile = profile,
                packageRequestUnixMs = timestamp + 1_000,
            )

            assertWellFormed(
                label = "SREM ${profile.name}",
                frame = ItsG5FrameBuilder.sremFrame(identity, request),
                expectedProtocol = "SREM",
                expectedMessageId = 9,
                expectedBtpPort = 2_007,
                expectedStationType = profile.stationType.code,
                expectedRole = if (profile.basicVehicleRole.isExtension) 23 else profile.basicVehicleRole.value,
            )
        }
    }

    private fun assertWellFormed(
        label: String,
        frame: ByteArray,
        expectedProtocol: String,
        expectedMessageId: Int,
        expectedBtpPort: Int,
        expectedStationType: Int? = null,
        expectedRole: Int? = null,
    ) {
        val pcap = Files.createTempFile("cits-to-go-packet-", ".pcap")
        try {
            PcapWriter(Files.newOutputStream(pcap)).use {
                it.writeRawPacket(frame, timestamp * 1_000L)
            }

            val decoded = tshark(
                "-r", pcap.toString(),
                "-T", "fields",
                "-e", "frame.number",
                "-e", "_ws.col.Protocol",
                "-e", "its.messageId",
                "-e", "btpb.dstport",
                "-e", "geonw.src_pos.addr.type",
                "-e", "dsrc.role",
                "-e", "_ws.col.Info",
            ).trim().split('\t')
            assertTrue("$label was not decoded by tshark: $decoded", decoded.size >= 5)
            assertEquals("$label protocol", expectedProtocol, decoded[1])
            assertEquals("$label ITS message ID", expectedMessageId.toString(), decoded[2])
            assertEquals("$label BTP port", expectedBtpPort.toString(), decoded[3])
            expectedStationType?.let {
                assertEquals("$label GN station type", it.toString(), decoded[4])
            }
            expectedRole?.let { assertEquals("$label requestor role", it.toString(), decoded[5]) }

            val malformed = tshark(
                "-r", pcap.toString(),
                "-Y", "_ws.malformed",
                "-T", "fields",
                "-e", "frame.number",
                "-e", "_ws.col.Info",
                "-e", "_ws.expert.message",
            )
            assertTrue("$label is malformed according to tshark: ${malformed.trim()}", malformed.isBlank())
        } finally {
            Files.deleteIfExists(pcap)
        }
    }

    private fun tshark(vararg arguments: String): String {
        val executable = System.getenv("TSHARK")?.takeIf { it.isNotBlank() } ?: "tshark"
        val process = try {
            ProcessBuilder(listOf(executable) + arguments)
                .redirectErrorStream(true)
                .start()
        } catch (exception: IOException) {
            fail(
                "tshark is required for packet-generation tests. Install Wireshark CLI or set TSHARK: " +
                    exception.message,
            )
            error("unreachable")
        }
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            fail("tshark timed out")
        }
        val output = process.inputStream.bufferedReader().use { it.readText() }
        assertEquals("tshark failed: $output", 0, process.exitValue())
        return output
    }
}
