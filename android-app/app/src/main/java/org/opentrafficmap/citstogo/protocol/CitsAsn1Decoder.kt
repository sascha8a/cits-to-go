package org.opentrafficmap.citstogo.protocol

/**
 * Lightweight ETSI ITS UPER decoder for the live log view.
 *
 * The field order and integer constraints are taken from the ETSI ITS ASN.1
 * modules published at https://forge.etsi.org/rep/ITS/asn1:
 * - CDD TS 102 894-2: ItsPduHeader ::= SEQUENCE { protocolVersion, messageID, stationID }
 * - CAM EN 302 637-2: CAM ::= SEQUENCE { header, cam }
 * - DENM EN 302 637-3: DENM ::= SEQUENCE { header, denm }
 */
object CitsAsn1Decoder {
    fun decode(packet: CitsPacket): DecodedCitsPacket {
        val mac = Ieee80211Mac.transmitterAddress(packet.payload)
        val candidates = candidateAsn1Offsets(packet.payload)
        for (offset in candidates) {
            decodeItsPdu(packet.payload, offset, packet, mac)?.let { return it }
        }
        return DecodedCitsPacket(
            sequence = packet.sequence,
            packetBytes = packet.payload.size,
            messageName = "Unknown",
            summary = "#${packet.sequence} ${packet.payload.size}B ${packet.frequencyMhz}MHz ${packet.rssiDbm}dBm raw=${packet.payload.hexPrefix()}",
            details = listOfNotNull(mac?.let { "mac=$it" }, "asn1=not found"),
        )
    }

    private fun decodeItsPdu(
        frame: ByteArray,
        offset: Int,
        packet: CitsPacket,
        mac: String?,
    ): DecodedCitsPacket? {
        if (offset + ITS_HEADER_BYTES > frame.size) return null
        val protocolVersion = frame.u8(offset)
        val messageId = frame.u8(offset + 1)
        if (protocolVersion !in 0..2 || messageId !in KNOWN_MESSAGE_IDS) return null
        val stationId = frame.u32be(offset + 2)
        val bitReader = UperBitReader(frame, (offset + ITS_HEADER_BYTES) * 8)
        val details = mutableListOf<String>()
        mac?.let { details += "mac=$it" }
        details += "protocol=$protocolVersion"
        details += "station=$stationId"

        val messageName = messageName(messageId)
        when (messageId) {
            MESSAGE_CAM -> decodeCam(bitReader, details)
            MESSAGE_DENM -> decodeDenm(bitReader, details)
        }

        return DecodedCitsPacket(
            sequence = packet.sequence,
            packetBytes = packet.payload.size,
            messageName = messageName,
            summary = "#${packet.sequence} $messageName station=$stationId ${packet.frequencyMhz}MHz ${packet.rssiDbm}dBm",
            details = details,
        )
    }

    private fun decodeCam(reader: UperBitReader, details: MutableList<String>) {
        val generationDeltaTime = reader.readConstrainedWholeNumber(0, 65_535) ?: return
        details += "generationDeltaTime=$generationDeltaTime"

        reader.readExtensionBit()
        reader.readBit()
        reader.readBit()
        val stationType = reader.readConstrainedWholeNumber(0, 255) ?: return
        val latitude = reader.readConstrainedWholeNumber(-900_000_000L, 900_000_001L)
        val longitude = reader.readConstrainedWholeNumber(-1_800_000_000L, 1_800_000_001L)
        details += "stationType=${stationTypeLabel(stationType)}"
        if (latitude != null && latitude != 900_000_001L) details += "lat=${latitude / 10_000_000.0}"
        if (longitude != null && longitude != 1_800_000_001L) details += "lon=${longitude / 10_000_000.0}"
    }

    private fun decodeDenm(reader: UperBitReader, details: MutableList<String>) {
        reader.readExtensionBit()
        val optionalCount = 1
        repeat(optionalCount) { reader.readBit() }
        val originatingStationId = reader.readConstrainedWholeNumber(0, 4_294_967_295L)
        val sequenceNumber = reader.readConstrainedWholeNumber(0, 65_535)
        val detectionTime = reader.readConstrainedWholeNumber(0, 4_398_046_511_103L)
        val referenceTime = reader.readConstrainedWholeNumber(0, 4_398_046_511_103L)
        if (originatingStationId != null) details += "originatingStation=$originatingStationId"
        if (sequenceNumber != null) details += "sequenceNumber=$sequenceNumber"
        if (detectionTime != null) details += "detectionTime=$detectionTime"
        if (referenceTime != null) details += "referenceTime=$referenceTime"
    }

    private fun candidateAsn1Offsets(frame: ByteArray): List<Int> {
        val offsets = linkedSetOf<Int>()
        btpPayloadOffset(frame)?.let { offsets += it }
        for (i in 0..frame.size - ITS_HEADER_BYTES) {
            val protocolVersion = frame.u8(i)
            val messageId = frame.u8(i + 1)
            if (protocolVersion in 0..2 && messageId in KNOWN_MESSAGE_IDS) offsets += i
        }
        return offsets.toList()
    }

    private fun btpPayloadOffset(frame: ByteArray): Int? {
        val llc = llcSnapOffset(frame) ?: return null
        if (llc + LLC_SNAP_BYTES + GN_BASIC_HEADER_BYTES + GN_COMMON_HEADER_BYTES >= frame.size) return null
        val etherType = frame.u16be(llc + 6)
        if (etherType != ETHERTYPE_GEONETWORKING) return null
        val basicHeader = llc + LLC_SNAP_BYTES
        val commonHeader = basicHeader + GN_BASIC_HEADER_BYTES
        val commonNextHeader = (frame.u8(commonHeader) ushr 4) and 0x0f
        val headerType = (frame.u8(commonHeader + 1) ushr 4) and 0x0f
        if (commonNextHeader !in BTP_NEXT_HEADERS) return null
        val gnPayload = commonHeader + GN_COMMON_HEADER_BYTES + geonetExtendedHeaderLength(headerType)
        val btpPayload = gnPayload + BTP_HEADER_BYTES
        return btpPayload.takeIf { it + ITS_HEADER_BYTES <= frame.size }
    }

    private fun llcSnapOffset(frame: ByteArray): Int? {
        val headerLength = ieee80211HeaderLength(frame) ?: return null
        if (headerLength + LLC_SNAP_BYTES > frame.size) return null
        if (frame.u8(headerLength) == 0xaa && frame.u8(headerLength + 1) == 0xaa && frame.u8(headerLength + 2) == 0x03) {
            return headerLength
        }
        return null
    }

    private fun ieee80211HeaderLength(frame: ByteArray): Int? {
        if (frame.size < 24) return null
        val frameControl = frame.u16le(0)
        val type = (frameControl ushr 2) and 0x03
        val subtype = (frameControl ushr 4) and 0x0f
        if (type != 2) return null
        val toDs = frameControl and 0x0100 != 0
        val fromDs = frameControl and 0x0200 != 0
        val protected = frameControl and 0x4000 != 0
        if (protected) return null
        var length = 24
        if (toDs && fromDs) length += 6
        if (subtype and 0x08 != 0) length += 2
        return length.takeIf { it <= frame.size }
    }

    private fun geonetExtendedHeaderLength(headerType: Int): Int =
        when (headerType) {
            0 -> 0
            1 -> 36
            2 -> 44
            3 -> 44
            4 -> 56
            5 -> 68
            6 -> 20
            7 -> 20
            else -> 0
        }

    private fun messageName(messageId: Int): String =
        when (messageId) {
            MESSAGE_DENM -> "DENM"
            MESSAGE_CAM -> "CAM"
            3 -> "POI"
            4 -> "SPATEM"
            5 -> "MAPEM"
            6 -> "IVIM"
            7 -> "EV-RSR"
            8 -> "TISTPG"
            9 -> "SREM"
            10 -> "SSEM"
            11 -> "EVCSN"
            12 -> "SAEM"
            13 -> "RTCMEM"
            else -> "message-$messageId"
        }

    private fun stationTypeLabel(value: Long): String =
        when (value) {
            0L -> "unknown(0)"
            1L -> "pedestrian(1)"
            2L -> "cyclist(2)"
            3L -> "moped(3)"
            4L -> "motorcycle(4)"
            5L -> "passengerCar(5)"
            6L -> "bus(6)"
            7L -> "lightTruck(7)"
            8L -> "heavyTruck(8)"
            9L -> "trailer(9)"
            10L -> "specialVehicle(10)"
            11L -> "tram(11)"
            15L -> "roadSideUnit(15)"
            else -> value.toString()
        }

    private fun ByteArray.u8(offset: Int): Int = this[offset].toInt() and 0xff

    private fun ByteArray.u16be(offset: Int): Int = (u8(offset) shl 8) or u8(offset + 1)

    private fun ByteArray.u16le(offset: Int): Int = u8(offset) or (u8(offset + 1) shl 8)

    private fun ByteArray.u32be(offset: Int): Long =
        (u8(offset).toLong() shl 24) or (u8(offset + 1).toLong() shl 16) or
            (u8(offset + 2).toLong() shl 8) or u8(offset + 3).toLong()

    private fun ByteArray.hexPrefix(): String =
        take(16).joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private val KNOWN_MESSAGE_IDS = setOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13)
    private val BTP_NEXT_HEADERS = setOf(1, 2)
    private const val MESSAGE_DENM = 1
    private const val MESSAGE_CAM = 2
    private const val ITS_HEADER_BYTES = 6
    private const val ETHERTYPE_GEONETWORKING = 0x8947
    private const val LLC_SNAP_BYTES = 8
    private const val GN_BASIC_HEADER_BYTES = 4
    private const val GN_COMMON_HEADER_BYTES = 8
    private const val BTP_HEADER_BYTES = 4
}

data class DecodedCitsPacket(
    val sequence: Long,
    val packetBytes: Int,
    val messageName: String,
    val summary: String,
    val details: List<String>,
) {
    fun logLine(): String =
        if (details.isEmpty()) summary else "$summary | ${details.joinToString(" | ")}"
}

private class UperBitReader(private val data: ByteArray, private var bitOffset: Int) {
    fun readExtensionBit(): Boolean? = readBit()?.let { it == 1 }

    fun readBit(): Int? {
        if (bitOffset >= data.size * 8) return null
        val byte = data[bitOffset / 8].toInt() and 0xff
        val bit = (byte ushr (7 - (bitOffset % 8))) and 1
        bitOffset += 1
        return bit
    }

    fun readConstrainedWholeNumber(min: Long, max: Long): Long? {
        val range = max - min + 1
        val bits = bitsForRange(range)
        val raw = readBits(bits) ?: return null
        return min + raw
    }

    private fun readBits(count: Int): Long? {
        var value = 0L
        repeat(count) {
            val bit = readBit() ?: return null
            value = (value shl 1) or bit.toLong()
        }
        return value
    }

    private fun bitsForRange(range: Long): Int {
        if (range <= 1) return 0
        var value = range - 1
        var bits = 0
        while (value > 0) {
            bits += 1
            value = value ushr 1
        }
        return bits
    }
}
