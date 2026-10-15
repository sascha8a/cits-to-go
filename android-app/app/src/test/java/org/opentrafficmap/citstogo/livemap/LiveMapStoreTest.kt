package org.opentrafficmap.citstogo.livemap

import java.io.ByteArrayOutputStream
import java.io.ObjectOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.opentrafficmap.citstogo.cam.CamHighFrequencyContainer.BasicVehicle
import org.opentrafficmap.citstogo.cam.CamHighFrequencyContainer.RoadSideUnit
import org.opentrafficmap.citstogo.cam.CamHighFrequencyContainer.Unknown
import org.opentrafficmap.citstogo.cam.CamIdentity
import org.opentrafficmap.citstogo.cam.CamPosition
import org.opentrafficmap.citstogo.cam.ItsG5FrameBuilder
import org.opentrafficmap.citstogo.cam.ReceivedCam
import org.opentrafficmap.citstogo.cam.StationType
import org.opentrafficmap.citstogo.cam.VehicleDimensions
import org.opentrafficmap.citstogo.denm.DenmSituation
import org.opentrafficmap.citstogo.denm.DenmTermination.Cancellation
import org.opentrafficmap.citstogo.denm.DenmTermination.Negation
import org.opentrafficmap.citstogo.denm.ReceivedDenm
import org.opentrafficmap.citstogo.denm.SyntheticDenm
import org.opentrafficmap.citstogo.intersection.ItsFrameExtractor
import org.opentrafficmap.citstogo.packet.TestFrames.DENM_PORT
import org.opentrafficmap.citstogo.packet.TestFrames.shbFrame
import org.opentrafficmap.citstogo.protocol.ItsTime

class LiveMapStoreTest {
    private val nowMs = 1_785_242_510_000L
    private val car = ReceivedCam(
        stationId = 0x8000_0001L,
        generationDeltaTime = 40_000,
        stationType = 5,
        latitude = 482_024_036,
        longitude = 163_691_773,
        highFrequencyContainer = BasicVehicle,
        heading = 1_355,
        speedCms = 1_389,
        trailer = false,
    )
    private val jam = ReceivedDenm(
        stationId = 0x8000_0001L,
        originatingStationId = 0x8000_0001L,
        sequenceNumber = 4_711,
        detectionTimeIts = ItsTime.fromUnixMs(nowMs - 2_000),
        referenceTimeIts = ItsTime.fromUnixMs(nowMs - 1_900),
        termination = null,
        latitude = 482_024_036,
        longitude = 163_691_773,
        awarenessDistance = 4,
        awarenessTrafficDirection = 1,
        validityDurationSeconds = 60,
        transmissionIntervalMs = null,
        stationType = 5,
        situation = DenmSituation(informationQuality = 1, causeCode = 1, subCauseCode = 0),
    )
    private val jamExpiresAtMs = nowMs - 2_000 + 60_000

    @Test
    fun camKeepsTheLatestStateOfEachStation() {
        val store = LiveMapStore()
        val moved = car.copy(
            generationDeltaTime = 41_000,
            latitude = 482_025_000,
            heading = null,
            speedCms = null,
            trailer = true,
        )
        val roadSideUnit = car.copy(stationId = 7, stationType = 15, highFrequencyContainer = RoadSideUnit)
        val positionOnly = car.copy(
            stationId = 8,
            highFrequencyContainer = Unknown,
            heading = null,
            speedCms = null,
            trailer = null,
        )

        store.accept(car, nowMs)
        store.accept(moved, nowMs + 1_000)
        store.accept(roadSideUnit, nowMs + 1_100)
        store.accept(positionOnly, nowMs + 1_200)

        assertEquals(
            listOf(
                LiveMapStation(0x8000_0001L, 5, false, 482_025_000, 163_691_773, null, null, true, nowMs + 1_000),
                LiveMapStation(7, 15, true, 482_024_036, 163_691_773, 1_355, 1_389, false, nowMs + 1_100),
                LiveMapStation(8, 5, false, 482_024_036, 163_691_773, null, null, false, nowMs + 1_200),
            ),
            store.snapshot(nowMs + 2_000).stations,
        )
    }

    @Test
    fun trailerIsShownOnlyWhenTheCamReportsOne() {
        val store = LiveMapStore()

        store.accept(car.copy(stationId = 1, trailer = true), nowMs)
        store.accept(car.copy(stationId = 2, trailer = false), nowMs)
        store.accept(car.copy(stationId = 3, trailer = null), nowMs)

        assertEquals(
            listOf(1L to true, 2L to false, 3L to false),
            store.snapshot(nowMs).stations.map { it.stationId to it.trailer },
        )
    }

    @Test
    fun trailerOfAnEncodedCamReachesTheSnapshot() {
        val store = LiveMapStore()
        val frames = VehicleDimensions.Trailer.entries.mapIndexed { index, trailer ->
            ItsG5FrameBuilder.camFrame(
                CamIdentity(stationId = index.toLong(), macAddress = byteArrayOf(0x02, 1, 2, 3, 4, 5)),
                StationType.HEAVY_TRUCK,
                CamPosition.unavailable().copy(latitude = 482_024_036, longitude = 163_691_773),
                nowMs,
                ItsG5FrameBuilder.SequenceCounters(initialWlanSequence = 0, initialGbcSequenceId = 0),
                VehicleDimensions(lengthDm = 165, widthDm = 26, trailer = trailer),
            )
        }

        for (frame in frames) store.accept(requireNotNull(ItsFrameExtractor.extract(frame)), nowMs, nowMs)

        assertEquals(
            listOf(0L to false, 1L to true, 2L to true),
            store.snapshot(nowMs).stations.map { it.stationId to it.trailer },
        )
    }

    @Test
    fun roadSideUnitFollowsTheHighFrequencyContainer() {
        val store = LiveMapStore()

        store.accept(car.copy(stationId = 1, stationType = 15, highFrequencyContainer = BasicVehicle), nowMs)
        store.accept(car.copy(stationId = 2, stationType = 5, highFrequencyContainer = RoadSideUnit), nowMs)

        assertEquals(
            listOf(1L to false, 2L to true),
            store.snapshot(nowMs).stations.map { it.stationId to it.roadSideUnit },
        )
    }

    @Test
    fun olderAndRepeatedCamsAreDropped() {
        val store = LiveMapStore()
        store.accept(car, nowMs)

        store.accept(car.copy(generationDeltaTime = 39_000, latitude = 1), nowMs + 100)
        store.accept(car.copy(latitude = 2), nowMs + 200)
        store.accept(car.copy(generationDeltaTime = 40_000 + 32_768 - 65_536, latitude = 3), nowMs + 300)
        assertEquals(listOf(car.latitude to nowMs), store.positions())

        store.accept(car.copy(generationDeltaTime = 40_000 + 32_767 - 65_536, latitude = 4), nowMs + 400)
        assertEquals(listOf(4 to nowMs + 400), store.positions())
    }

    @Test
    fun generationDeltaTimeWrapsAround() {
        val store = LiveMapStore()
        store.accept(car.copy(generationDeltaTime = 65_000), nowMs)

        store.accept(car.copy(generationDeltaTime = 500, latitude = 1), nowMs + 1_000)
        store.accept(car.copy(generationDeltaTime = 65_400, latitude = 2), nowMs + 1_100)

        assertEquals(listOf(1 to nowMs + 1_000), store.positions())
    }

    @Test
    fun anyCamFollowsAStationSilentForThirtySeconds() {
        val store = LiveMapStore()
        store.accept(car, nowMs)

        store.accept(car.copy(generationDeltaTime = 39_000, latitude = 1), nowMs + 29_999)
        assertEquals(listOf(car.latitude to nowMs), store.positions())

        store.accept(car.copy(generationDeltaTime = 39_000, latitude = 2), nowMs + 30_000)
        assertEquals(listOf(2 to nowMs + 30_000), store.positions())
    }

    @Test
    fun stationsArePrunedAfterTheLongestTimeout() {
        val store = LiveMapStore()
        store.accept(car, nowMs)

        assertEquals(1, store.snapshot(nowMs + 300_000).stations.size)
        assertEquals(0, store.snapshot(nowMs + 300_001).stations.size)
        assertEquals(0, store.snapshot(nowMs).stations.size)
    }

    @Test
    fun fullStoreEvictsTheStationHeardLongestAgo() {
        val store = LiveMapStore()
        for (id in 1L..500L) store.accept(car.copy(stationId = id), nowMs + id)
        store.accept(car.copy(stationId = 1, generationDeltaTime = 41_000), nowMs + 1_000)

        store.accept(car.copy(stationId = 501), nowMs + 1_001)

        val stationIds = store.snapshot(nowMs + 1_001).stations.map { it.stationId }.toSet()
        assertEquals(500, stationIds.size)
        assertEquals((1L..501L).toSet() - 2L, stationIds)
    }

    @Test
    fun liveDenmExpiresAtDetectionTimePlusValidity() {
        val store = LiveMapStore()

        store.accept(jam, nowMs, nowMs)

        val expected = LiveMapDenm(
            originatingStationId = 0x8000_0001L,
            sequenceNumber = 4_711,
            stationType = 5,
            latitude = 482_024_036,
            longitude = 163_691_773,
            causeCode = 1,
            subCauseCode = 0,
            informationQuality = 1,
            detectedAtMs = nowMs - 2_000,
            receivedAtMs = nowMs,
            expiresAtMs = jamExpiresAtMs,
        )
        assertEquals(listOf(expected), store.snapshot(jamExpiresAtMs - 1).denms)
        assertEquals(emptyList<LiveMapDenm>(), store.snapshot(jamExpiresAtMs).denms)
    }

    @Test
    fun replayedDenmLastsAsLongAsItWasValidWhenCaptured() {
        val store = LiveMapStore()
        val capturedAtMs = nowMs - 3 * 86_400_000L
        val captured = jam.copy(
            detectionTimeIts = ItsTime.fromUnixMs(capturedAtMs - 2_000),
            referenceTimeIts = ItsTime.fromUnixMs(capturedAtMs - 1_900),
        )

        store.accept(captured, nowMs, capturedAtMs)
        store.accept(captured.copy(sequenceNumber = 1, validityDurationSeconds = 2), nowMs, capturedAtMs)

        val denm = store.snapshot(nowMs).denms.single()
        assertEquals(4_711, denm.sequenceNumber)
        assertEquals(nowMs - 2_000, denm.detectedAtMs)
        assertEquals(nowMs + 58_000, denm.expiresAtMs)
    }

    @Test
    fun clearedStoreTakesAReplayedCaptureAsNew() {
        val store = LiveMapStore()
        val capturedAtMs = nowMs - 3 * 86_400_000L
        val captured = jam.copy(
            detectionTimeIts = ItsTime.fromUnixMs(capturedAtMs - 2_000),
            referenceTimeIts = ItsTime.fromUnixMs(capturedAtMs - 1_900),
        )
        store.accept(car, nowMs)
        store.accept(car.copy(generationDeltaTime = 41_000, latitude = 1), nowMs + 1_000)
        store.accept(captured, nowMs, capturedAtMs)

        store.clear()
        assertEquals(LiveMapSnapshot(emptyList(), emptyList()), store.snapshot(nowMs + 1_000))

        store.accept(car, nowMs + 5_000)
        store.accept(captured, nowMs + 5_000, capturedAtMs)
        val snapshot = store.snapshot(nowMs + 5_000)
        assertEquals(listOf(car.latitude to nowMs + 5_000), snapshot.stations.map { it.latitude to it.receivedAtMs })
        assertEquals(nowMs + 5_000 + 58_000, snapshot.denms.single().expiresAtMs)
    }

    @Test
    fun expiredDenmIsDropped() {
        val store = LiveMapStore()

        store.accept(jam.copy(detectionTimeIts = ItsTime.fromUnixMs(nowMs - 60_000)), nowMs, nowMs)
        assertEquals(emptyList<LiveMapDenm>(), store.snapshot(nowMs).denms)

        store.accept(jam.copy(detectionTimeIts = ItsTime.fromUnixMs(nowMs - 59_999)), nowMs, nowMs)
        assertEquals(nowMs + 1, store.snapshot(nowMs).denms.single().expiresAtMs)
    }

    @Test
    fun expiredUpdateOrTerminationLeavesTheEvent() {
        val store = LiveMapStore()
        store.accept(jam, nowMs, nowMs)
        val expiredUpdate = jam.copy(
            referenceTimeIts = jam.referenceTimeIts + 1_000,
            latitude = 1,
            validityDurationSeconds = 1,
        )
        val expiredCancellation = cancellationOf(jam).copy(validityDurationSeconds = 5)

        store.accept(expiredUpdate, nowMs + 1_000, nowMs + 1_000)
        assertEquals(jam.latitude, store.snapshot(nowMs + 1_000).denms.single().latitude)

        store.accept(expiredCancellation, nowMs + 20_000, nowMs + 20_000)
        val denm = store.snapshot(nowMs + 20_000).denms.single()
        assertEquals(jam.latitude, denm.latitude)
        assertEquals(nowMs, denm.receivedAtMs)
    }

    @Test
    fun repetitionOnlyRefreshesTheReception() {
        val store = LiveMapStore()
        store.accept(jam, nowMs, nowMs)

        // Forwarded by another station, with a position that a repetition cannot change.
        store.accept(jam.copy(stationId = 99, latitude = 1), nowMs + 1_000, nowMs + 1_000)

        val denm = store.snapshot(nowMs + 1_000).denms.single()
        assertEquals(jam.latitude, denm.latitude)
        assertEquals(nowMs + 1_000, denm.receivedAtMs)
    }

    @Test
    fun updateReplacesTheEvent() {
        val store = LiveMapStore()
        store.accept(jam, nowMs, nowMs)
        val update = jam.copy(
            detectionTimeIts = jam.detectionTimeIts + 14_800,
            referenceTimeIts = jam.referenceTimeIts + 14_800,
            latitude = 482_030_000,
            validityDurationSeconds = 30,
            situation = DenmSituation(informationQuality = 3, causeCode = 94, subCauseCode = 0),
        )

        store.accept(update, nowMs + 14_000, nowMs + 14_000)

        val denm = store.snapshot(nowMs + 14_000).denms.single()
        assertEquals(482_030_000, denm.latitude)
        assertEquals(94, denm.causeCode)
        assertEquals(3, denm.informationQuality)
        assertEquals(nowMs + 12_800, denm.detectedAtMs)
        assertEquals(nowMs + 42_800, denm.expiresAtMs)
    }

    @Test
    fun olderReferenceOrDetectionTimeIsDropped() {
        val store = LiveMapStore()
        store.accept(jam, nowMs, nowMs)

        store.accept(jam.copy(referenceTimeIts = jam.referenceTimeIts - 1, latitude = 1), nowMs + 100, nowMs + 100)
        store.accept(
            jam.copy(
                detectionTimeIts = jam.detectionTimeIts - 1,
                referenceTimeIts = jam.referenceTimeIts + 5_000,
                latitude = 2,
            ),
            nowMs + 200,
            nowMs + 200,
        )

        val denm = store.snapshot(nowMs + 200).denms.single()
        assertEquals(jam.latitude, denm.latitude)
        assertEquals(nowMs, denm.receivedAtMs)
    }

    @Test
    fun terminationOfAnUnknownEventIsIgnored() {
        val store = LiveMapStore()
        val cancellation = cancellationOf(jam)

        store.accept(cancellation, nowMs + 10_000, nowMs + 10_000)
        assertEquals(emptyList<LiveMapDenm>(), store.snapshot(nowMs + 10_000).denms)

        // Without an entry left behind, the event is still new afterwards.
        store.accept(jam, nowMs + 10_100, nowMs + 10_100)
        assertEquals(1, store.snapshot(nowMs + 10_100).denms.size)
    }

    @Test
    fun cancellationHidesTheEventUntilItWouldHaveExpired() {
        val store = LiveMapStore()
        store.accept(jam, nowMs, nowMs)
        val cancellation = cancellationOf(jam)
        val cancellationExpiresAtMs = nowMs + 8_000 + 30_000

        store.accept(cancellation, nowMs + 10_000, nowMs + 10_000)
        assertEquals(emptyList<LiveMapDenm>(), store.snapshot(nowMs + 10_000).denms)

        // The cancellation expires before the event, and late repetitions stay outdated until the event expires.
        for (lateMs in listOf(cancellationExpiresAtMs - 1, cancellationExpiresAtMs, jamExpiresAtMs - 1)) {
            store.accept(jam, lateMs, lateMs)
            assertEquals("$lateMs", emptyList<LiveMapDenm>(), store.snapshot(lateMs).denms)
        }
    }

    @Test
    fun newerDenmGetsPastATermination() {
        val store = LiveMapStore()
        store.accept(jam, nowMs, nowMs)
        val cancellation = cancellationOf(jam)
        val update = jam.copy(
            detectionTimeIts = cancellation.detectionTimeIts + 1_000,
            referenceTimeIts = cancellation.referenceTimeIts + 1_000,
            latitude = 1,
        )

        store.accept(cancellation, nowMs + 10_000, nowMs + 10_000)
        store.accept(update, nowMs + 11_000, nowMs + 11_000)

        assertEquals(1, store.snapshot(nowMs + 11_000).denms.single().latitude)
    }

    @Test
    fun negationWithTheCopiedReferenceTimeHidesTheEvent() {
        val store = LiveMapStore()
        store.accept(jam, nowMs, nowMs)
        val negation = jam.copy(
            stationId = 99,
            detectionTimeIts = jam.detectionTimeIts + 5_000,
            termination = Negation,
            validityDurationSeconds = 10,
            situation = null,
        )

        store.accept(negation, nowMs + 5_000, nowMs + 5_000)
        assertEquals(emptyList<LiveMapDenm>(), store.snapshot(nowMs + 5_000).denms)

        // The originator does not know of the negation and keeps repeating the event.
        store.accept(jam, nowMs + 6_000, nowMs + 6_000)
        assertEquals(emptyList<LiveMapDenm>(), store.snapshot(nowMs + 6_000).denms)

        store.accept(negation, nowMs + 7_000, nowMs + 7_000)
        assertEquals(emptyList<LiveMapDenm>(), store.snapshot(nowMs + 7_000).denms)

        // Also once the negation has expired, as long as the event has not.
        store.accept(jam, nowMs + 20_000, nowMs + 20_000)
        assertEquals(emptyList<LiveMapDenm>(), store.snapshot(nowMs + 20_000).denms)
    }

    @Test
    fun denmsAreKeyedByActionIdNotBySender() {
        val store = LiveMapStore()

        store.accept(jam, nowMs, nowMs)
        store.accept(jam.copy(stationId = 99), nowMs, nowMs)
        store.accept(jam.copy(sequenceNumber = 4_712), nowMs, nowMs)
        store.accept(jam.copy(originatingStationId = 99), nowMs, nowMs)

        assertEquals(
            setOf(0x8000_0001L to 4_711, 0x8000_0001L to 4_712, 99L to 4_711),
            store.snapshot(nowMs).denms.map { it.originatingStationId to it.sequenceNumber }.toSet(),
        )
    }

    @Test
    fun fullStoreEvictsTheEventThatExpiresFirst() {
        val store = LiveMapStore()
        for (sequenceNumber in 0 until 200) {
            val denm = jam.copy(sequenceNumber = sequenceNumber, validityDurationSeconds = 100 + sequenceNumber)
            store.accept(denm, nowMs, nowMs)
        }

        store.accept(jam.copy(sequenceNumber = 200, validityDurationSeconds = 60), nowMs, nowMs)

        val sequenceNumbers = store.snapshot(nowMs).denms.map { it.sequenceNumber }.toSet()
        assertEquals((1..200).toSet(), sequenceNumbers)
    }

    @Test
    fun eventWithoutPositionIsNotShown() {
        val store = LiveMapStore()

        store.accept(jam.copy(latitude = null), nowMs, nowMs)
        store.accept(jam.copy(sequenceNumber = 1, longitude = null), nowMs, nowMs)

        assertEquals(emptyList<LiveMapDenm>(), store.snapshot(nowMs).denms)
    }

    @Test
    fun framesAreDecodedAndMalformedOnesSkipped() {
        val store = LiveMapStore()
        val cam = ItsG5FrameBuilder.camFrame(
            CamIdentity(stationId = 0xfedc_ba98L, macAddress = byteArrayOf(0x02, 1, 2, 3, 4, 5)),
            StationType.PASSENGER_CAR,
            CamPosition.unavailable().copy(latitude = 482_024_036, longitude = 163_691_773),
            nowMs,
            ItsG5FrameBuilder.SequenceCounters(initialWlanSequence = 0, initialGbcSequenceId = 0),
        )
        val frames = listOf(
            cam,
            shbFrame(SyntheticDenm.pdu(jam), DENM_PORT),
            shbFrame(SyntheticDenm.pdu(jam.copy(sequenceNumber = 1)).copyOf(20), DENM_PORT),
            shbFrame(SyntheticDenm.pdu(jam.copy(sequenceNumber = 2), protocolVersion = 1), DENM_PORT),
        )

        for (frame in frames) store.accept(requireNotNull(ItsFrameExtractor.extract(frame)), nowMs, nowMs)

        val snapshot = store.snapshot(nowMs)
        assertEquals(listOf(0xfedc_ba98L), snapshot.stations.map { it.stationId })
        assertEquals(listOf(4_711), snapshot.denms.map { it.sequenceNumber })
    }

    @Test
    fun fullSnapshotStaysSmall() {
        val store = LiveMapStore()
        for (id in 0 until 500) {
            store.accept(
                car.copy(stationId = 0xffff_0000L + id, heading = 1_000 + id, speedCms = 2_000 + id, trailer = true),
                nowMs,
            )
        }
        for (sequenceNumber in 0 until 200) {
            store.accept(
                jam.copy(sequenceNumber = 65_535 - sequenceNumber, situation = DenmSituation(7, 255, 255)),
                nowMs,
                nowMs,
            )
        }
        val snapshot = store.snapshot(nowMs)

        val bytes = ByteArrayOutputStream().also { ObjectOutputStream(it).use { out -> out.writeObject(snapshot) } }

        println("Full snapshot: ${bytes.size()} bytes")
        assertEquals(500, snapshot.stations.size)
        assertEquals(200, snapshot.denms.size)
        assertTrue("${bytes.size()} bytes", bytes.size() < 64 * 1_024)
    }

    /** A cancellation of [denm] 10 s after its detection, valid for 30 s. */
    private fun cancellationOf(denm: ReceivedDenm) = denm.copy(
        detectionTimeIts = denm.detectionTimeIts + 10_000,
        referenceTimeIts = denm.referenceTimeIts + 10_000,
        termination = Cancellation,
        validityDurationSeconds = 30,
        situation = null,
    )

    /** The latitude and reception time of each station. */
    private fun LiveMapStore.positions(): List<Pair<Int, Long>> =
        snapshot(nowMs).stations.map { it.latitude to it.receivedAtMs }
}
