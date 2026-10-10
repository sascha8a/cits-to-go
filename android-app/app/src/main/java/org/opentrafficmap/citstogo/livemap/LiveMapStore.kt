package org.opentrafficmap.citstogo.livemap

import java.io.Serializable
import org.opentrafficmap.citstogo.cam.CamDecoder
import org.opentrafficmap.citstogo.cam.CamHighFrequencyContainer
import org.opentrafficmap.citstogo.cam.ReceivedCam
import org.opentrafficmap.citstogo.denm.DenmDecoder
import org.opentrafficmap.citstogo.denm.ReceivedDenm
import org.opentrafficmap.citstogo.intersection.IntersectionDecodeException
import org.opentrafficmap.citstogo.intersection.ItsPacket
import org.opentrafficmap.citstogo.protocol.ItsTime

/** The latest CAM of a station in transmitted units, received at [receivedAtMs] phone time. */
data class LiveMapStation(
    val stationId: Long,
    val stationType: Int,
    val roadSideUnit: Boolean,
    val latitude: Int,
    val longitude: Int,
    val heading: Int?,
    val speedCms: Int?,
    val receivedAtMs: Long,
) : Serializable

/**
 * An active DENM event in transmitted units, keyed by its actionId. Times are phone time: [receivedAtMs] is the last
 * reception, repetitions included. The cause is `null` without a decoded SituationContainer.
 */
data class LiveMapDenm(
    val originatingStationId: Long,
    val sequenceNumber: Int,
    val stationType: Int,
    val latitude: Int,
    val longitude: Int,
    val causeCode: Int?,
    val subCauseCode: Int?,
    val informationQuality: Int?,
    val detectedAtMs: Long,
    val receivedAtMs: Long,
    val expiresAtMs: Long,
) : Serializable

data class LiveMapSnapshot(
    val stations: List<LiveMapStation>,
    val denms: List<LiveMapDenm>,
) : Serializable

/**
 * Received stations and DENM events for the map. Packets come from the capture thread, snapshots are taken by the
 * status publisher. Stations stay until the longest station timeout, DENM events until they expire.
 *
 * Times are phone time. In a replay, the capture time of a frame shifts the detection and expiry time of its DENM: the
 * event stays as long after its reception as it was still valid after its capture.
 */
class LiveMapStore {
    // In the order of their latest CAM.
    private val stations = LinkedHashMap<Long, StationEntry>()
    private val denms = HashMap<ActionId, DenmEntry>()

    /**
     * Adds a CAM or DENM; other and malformed messages are ignored. [capturedAtMs] is the capture time of the frame
     * in a replay and [receivedAtMs] when live.
     */
    @Synchronized
    fun accept(packet: ItsPacket, receivedAtMs: Long, capturedAtMs: Long) {
        try {
            when (packet.messageId) {
                CamDecoder.MESSAGE_ID_CAM -> CamDecoder.decode(packet)?.let { accept(it, receivedAtMs) }
                DenmDecoder.MESSAGE_ID_DENM ->
                    DenmDecoder.decode(packet)?.let { accept(it, receivedAtMs, capturedAtMs) }
            }
        } catch (_: IntersectionDecodeException) {
            // A malformed message is dropped on its own.
        }
    }

    @Synchronized
    internal fun accept(cam: ReceivedCam, receivedAtMs: Long) {
        val stored = stations[cam.stationId]
        if (stored != null && !stored.isOlderThan(cam, receivedAtMs)) return
        stations.remove(cam.stationId)
        if (stations.size >= MAX_STATIONS) stations.remove(stations.keys.first())
        stations[cam.stationId] = StationEntry(cam, receivedAtMs)
    }

    /** Applies the receiving rules of EN 302 637-3 V1.3.1 §8.4.2, which TS 103 831 keeps. */
    @Synchronized
    internal fun accept(denm: ReceivedDenm, receivedAtMs: Long, capturedAtMs: Long) {
        val detectedAtMs = ItsTime.toUnixMs(denm.detectionTimeIts) + receivedAtMs - capturedAtMs
        val expiresAtMs = detectedAtMs + denm.validityDurationSeconds * 1_000L
        if (expiresAtMs <= receivedAtMs) return
        val actionId = ActionId(denm.originatingStationId, denm.sequenceNumber)
        val stored = denms[actionId]?.takeIf { it.expiresAtMs > receivedAtMs }
        if (stored == null) {
            // A termination of an unknown event is discarded.
            if (denm.termination != null) return
        } else {
            val previous = stored.denm
            if (
                denm.referenceTimeIts < previous.referenceTimeIts ||
                denm.detectionTimeIts < previous.detectionTimeIts
            ) {
                return
            }
            if (
                denm.referenceTimeIts == previous.referenceTimeIts &&
                denm.detectionTimeIts == previous.detectionTimeIts &&
                denm.termination == previous.termination
            ) {
                stored.receivedAtMs = receivedAtMs
                return
            }
        }
        if (actionId !in denms && denms.size >= MAX_DENMS) denms.remove(denms.minBy { it.value.expiresAtMs }.key)
        // A termination hides the event but stays until both have expired, so that late repetitions of the
        // terminated DENM are outdated instead of new.
        val keptUntilMs = if (denm.termination != null && stored != null) {
            maxOf(expiresAtMs, stored.expiresAtMs)
        } else {
            expiresAtMs
        }
        denms[actionId] = DenmEntry(denm, detectedAtMs, keptUntilMs, receivedAtMs)
    }

    /**
     * Forgets all stations and DENM events. The ordering rules compare with what is stored, so each capture and each
     * replay starts empty.
     */
    @Synchronized
    fun clear() {
        stations.clear()
        denms.clear()
    }

    /** Drops stations after the longest station timeout and expired DENMs, and returns what remains to be shown. */
    @Synchronized
    fun snapshot(nowMs: Long): LiveMapSnapshot {
        stations.values.removeAll { nowMs - it.receivedAtMs > LiveMapModel.MAX_STATION_TIMEOUT_MS }
        denms.values.removeAll { it.expiresAtMs <= nowMs }
        return LiveMapSnapshot(
            stations = stations.values.map { it.snapshot() },
            denms = denms.values.mapNotNull { it.snapshot() },
        )
    }

    private class StationEntry(val cam: ReceivedCam, val receivedAtMs: Long) {
        /**
         * Whether [newer] follows this CAM. The generationDeltaTime wraps every 65.536 s, so it orders CAMs only
         * within half that range; after a longer gap any CAM is newer.
         */
        fun isOlderThan(newer: ReceivedCam, newerReceivedAtMs: Long): Boolean {
            val advance = (newer.generationDeltaTime - cam.generationDeltaTime).mod(GENERATION_DELTA_TIME_RANGE)
            return advance in 1 until GENERATION_DELTA_TIME_RANGE / 2 ||
                newerReceivedAtMs - receivedAtMs >= CAM_REORDERING_WINDOW_MS
        }

        fun snapshot() = LiveMapStation(
            stationId = cam.stationId,
            stationType = cam.stationType,
            roadSideUnit = cam.highFrequencyContainer == CamHighFrequencyContainer.RoadSideUnit,
            latitude = cam.latitude,
            longitude = cam.longitude,
            heading = cam.heading,
            speedCms = cam.speedCms,
            receivedAtMs = receivedAtMs,
        )
    }

    private class DenmEntry(
        val denm: ReceivedDenm,
        val detectedAtMs: Long,
        val expiresAtMs: Long,
        var receivedAtMs: Long,
    ) {
        /** `null` once terminated or without an event position. */
        fun snapshot(): LiveMapDenm? {
            if (denm.termination != null) return null
            return LiveMapDenm(
                originatingStationId = denm.originatingStationId,
                sequenceNumber = denm.sequenceNumber,
                stationType = denm.stationType,
                latitude = denm.latitude ?: return null,
                longitude = denm.longitude ?: return null,
                causeCode = denm.situation?.causeCode,
                subCauseCode = denm.situation?.subCauseCode,
                informationQuality = denm.situation?.informationQuality,
                detectedAtMs = detectedAtMs,
                receivedAtMs = receivedAtMs,
                expiresAtMs = expiresAtMs,
            )
        }
    }

    private data class ActionId(val originatingStationId: Long, val sequenceNumber: Int)

    private companion object {
        const val MAX_STATIONS = 500
        const val MAX_DENMS = 200
        const val GENERATION_DELTA_TIME_RANGE = 65_536
        const val CAM_REORDERING_WINDOW_MS = 30_000L
    }
}
