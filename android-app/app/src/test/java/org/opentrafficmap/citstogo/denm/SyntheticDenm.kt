package org.opentrafficmap.citstogo.denm

import org.opentrafficmap.citstogo.cam.UperBitWriter
import org.opentrafficmap.citstogo.denm.DenmTermination.Negation

/** Synthetic DENM PDUs for the decoder and map tests. */
internal object SyntheticDenm {
    /**
     * Writes [denm] field by field from the ASN.1, independently of the decoder, with a minimal LocationContainer
     * whenever it has a situation. An extended ManagementContainer or event type carries one unknown addition.
     */
    fun pdu(
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
}
