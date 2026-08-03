package org.opentrafficmap.citstogo.intersection

object SsemDecoder {
    const val MESSAGE_ID_SSEM = 10
    const val BTP_PORT_SSEM = 2008

    fun decode(packet: ItsPacket, receivedAtMs: Long): List<SsemStatus> {
        if (packet.protocolVersion != 2 || packet.messageId != MESSAGE_ID_SSEM) return emptyList()
        val reader = UperBitReader(packet.payload, packet.bodyOffset)
        return reader.signalStatusMessage(receivedAtMs)
    }

    private fun UperBitReader.signalStatusMessage(receivedAtMs: Long): List<SsemStatus> {
        val hasExtension = bit()
        if (hasExtension) throw IntersectionDecodeException("SignalStatusMessage extensions are not supported")
        val hasTimeStamp = bit()
        val hasSequenceNumber = bit()
        val hasRegional = bit()
        if (hasTimeStamp) constrained(0, 527_040)
        val second = constrained(0, 65_535).toInt()
        val messageSequenceNumber = if (hasSequenceNumber) constrained(0, 127).toInt() else null
        val statuses = List(sequenceLength(1, 32)) {
            signalStatus(receivedAtMs, messageSequenceNumber, second)
        }.flatten()
        if (hasRegional) regionalExtensions()
        return statuses
    }

    private fun UperBitReader.signalStatus(
        receivedAtMs: Long,
        messageSequenceNumber: Int?,
        messageSecond: Int,
    ): List<SsemStatus> {
        val hasExtension = bit()
        if (hasExtension) throw IntersectionDecodeException("SignalStatus extensions are not supported")
        val hasRegional = bit()
        val intersectionSequenceNumber = constrained(0, 127).toInt()
        val key = intersectionReferenceId()
        val packages = List(sequenceLength(1, 32)) {
            signalStatusPackage(
                intersectionKey = key,
                intersectionSequenceNumber = intersectionSequenceNumber,
                messageSequenceNumber = messageSequenceNumber,
                fallbackSecond = messageSecond,
                receivedAtMs = receivedAtMs,
            )
        }
        if (hasRegional) regionalExtensions()
        return packages
    }

    private fun UperBitReader.signalStatusPackage(
        intersectionKey: IntersectionKey,
        intersectionSequenceNumber: Int,
        messageSequenceNumber: Int?,
        fallbackSecond: Int,
        receivedAtMs: Long,
    ): SsemStatus {
        val hasExtension = bit()
        if (hasExtension) throw IntersectionDecodeException("SignalStatusPackage extensions are not supported")
        val hasRequester = bit()
        val hasOutbound = bit()
        val hasMinute = bit()
        val hasSecond = bit()
        val hasDuration = bit()
        val hasRegional = bit()
        val requester = if (hasRequester) signalRequesterInfo() else null
        val inbound = intersectionAccessPoint()
        val outbound = if (hasOutbound) intersectionAccessPoint() else null
        val minute = if (hasMinute) constrained(0, 527_040).toInt() else null
        val second = if (hasSecond) constrained(0, 65_535).toInt() else fallbackSecond
        val duration = if (hasDuration) constrained(0, 65_535).toInt() else null
        val responseStatus = SsemResponseStatus.fromCode(readExtensibleEnum(rootValues = 8))
        if (hasRegional) regionalExtensions()
        return SsemStatus(
            intersectionKey = intersectionKey,
            intersectionSequenceNumber = intersectionSequenceNumber,
            messageSequenceNumber = messageSequenceNumber,
            requesterStationId = requester?.stationId,
            requestId = requester?.requestId,
            requestSequenceNumber = requester?.sequenceNumber,
            inboundLaneId = inbound.laneId,
            outboundLaneId = outbound?.laneId,
            minute = minute,
            second = second,
            duration = duration,
            responseStatus = responseStatus,
            receivedAtMs = receivedAtMs,
        )
    }

    private data class RequesterInfo(
        val stationId: Long?,
        val requestId: Int,
        val sequenceNumber: Int,
    )

    private fun UperBitReader.signalRequesterInfo(): RequesterInfo {
        val hasExtension = bit()
        if (hasExtension) throw IntersectionDecodeException("SignalRequesterInfo extensions are not supported")
        val hasRole = bit()
        val hasTypeData = bit()
        val stationId = vehicleId()
        val requestId = constrained(0, 255).toInt()
        val sequenceNumber = constrained(0, 127).toInt()
        if (hasRole) readExtensibleEnum(rootValues = 23)
        if (hasTypeData) throw IntersectionDecodeException("SignalRequesterInfo typeData is not supported")
        return RequesterInfo(stationId, requestId, sequenceNumber)
    }

    private fun UperBitReader.vehicleId(): Long? {
        return when (readChoiceIndex(rootChoices = 2, extensible = false)) {
            0 -> {
                skipBits(32)
                null
            }
            1 -> constrained(0, 0xffff_ffffL)
            else -> null
        }
    }

    private data class AccessPoint(val laneId: Int?)

    private fun UperBitReader.intersectionAccessPoint(): AccessPoint {
        return when (readChoiceIndex(rootChoices = 3, extensible = true)) {
            0 -> AccessPoint(constrained(0, 255).toInt())
            1 -> {
                constrained(0, 15)
                AccessPoint(null)
            }
            2 -> {
                constrained(0, 255)
                AccessPoint(null)
            }
            else -> throw IntersectionDecodeException("Unsupported IntersectionAccessPoint choice")
        }
    }

    private fun UperBitReader.intersectionReferenceId(): IntersectionKey {
        val hasRegion = bit()
        val region = if (hasRegion) constrained(0, 65_535).toInt() else null
        val id = constrained(0, 65_535).toInt()
        return IntersectionKey(region, id)
    }

    private fun UperBitReader.regionalExtensions() {
        repeat(sequenceLength(1, 4)) {
            constrained(0, 255)
            skipOpenType()
        }
    }

    private fun UperBitReader.skipOpenType() {
        var remainingOctets = openTypeLength()
        while (remainingOctets >= 16_384) {
            skipOctets(16_384)
            remainingOctets -= 16_384
        }
        skipOctets(remainingOctets)
    }

    private fun UperBitReader.openTypeLength(): Int {
        if (!bit()) return bits(7).toInt()
        if (!bit()) return bits(14).toInt()
        val fragments = bits(6).toInt()
        if (fragments in 1..4) return fragments * 16_384 + openTypeLength()
        throw IntersectionDecodeException("Unsupported open type length determinant")
    }

    private fun UperBitReader.readChoiceIndex(rootChoices: Int, extensible: Boolean): Int {
        if (extensible && bit()) {
            throw IntersectionDecodeException("CHOICE extension is not supported")
        }
        val width = 32 - Integer.numberOfLeadingZeros(rootChoices - 1)
        return bits(width).toInt()
    }

    private fun UperBitReader.readExtensibleEnum(rootValues: Int): Int {
        if (bit()) throw IntersectionDecodeException("ENUMERATED extension is not supported")
        val width = 32 - Integer.numberOfLeadingZeros(rootValues - 1)
        return bits(width).toInt()
    }
}

