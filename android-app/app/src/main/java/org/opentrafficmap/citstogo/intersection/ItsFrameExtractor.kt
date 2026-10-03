package org.opentrafficmap.citstogo.intersection

data class ItsPacket(
    val destinationPort: Int,
    val protocolVersion: Int,
    val messageId: Int,
    val stationId: Long,
    val bodyOffset: Int,
    val payload: ByteArray,
    val sourceLatitude: Int?,
    val sourceLongitude: Int?,
    val geonetworkingSecured: Boolean = false,
)

sealed interface ItsExtractionResult {
    data class Success(val packet: ItsPacket) : ItsExtractionResult
    data object NotGeoNetworking : ItsExtractionResult

    /** GeoNetworking Basic Header next-header outside the supported encodings (`1` / `2`). */
    data class Unsupported(val reason: String, val nextHeader: Int) : ItsExtractionResult

    /** A recognised (optionally secured) GeoNetworking envelope whose contents could not be decoded. */
    data class Malformed(val reason: String, val secured: Boolean) : ItsExtractionResult
}

object ItsFrameExtractor {
    fun extract(frame: ByteArray): ItsPacket? = when (val result = extractDetailed(frame)) {
        is ItsExtractionResult.Success -> result.packet
        ItsExtractionResult.NotGeoNetworking,
        is ItsExtractionResult.Unsupported,
        is ItsExtractionResult.Malformed -> null
    }

    fun extractDetailed(frame: ByteArray): ItsExtractionResult {
        val geo = when (val result = GeoNetworkingFrameParser.parse(frame)) {
            is GeoNetworkingParseResult.Success -> result.payload
            GeoNetworkingParseResult.NotGeoNetworking -> return ItsExtractionResult.NotGeoNetworking
            is GeoNetworkingParseResult.Unsupported -> {
                return ItsExtractionResult.Unsupported(result.reason, result.nextHeader)
            }
            is GeoNetworkingParseResult.Malformed -> {
                return ItsExtractionResult.Malformed(result.reason, result.secured)
            }
        }

        val btpOffset = geo.btpOffset
        if (frame.size < btpOffset + BTP_HEADER_LEN + ITS_PDU_HEADER_LEN) {
            return ItsExtractionResult.Malformed("Truncated BTP/ITS payload", geo.secured)
        }
        val destinationPort = u16(frame, btpOffset)
        val itsOffset = btpOffset + BTP_HEADER_LEN
        val itsPayloadLength = geo.payloadLength - BTP_HEADER_LEN
        val itsEnd = itsOffset + itsPayloadLength
        if (itsPayloadLength < ITS_PDU_HEADER_LEN || itsEnd > frame.size) {
            return ItsExtractionResult.Malformed("Invalid GeoNetworking payload length", geo.secured)
        }

        val protocolVersion = frame[itsOffset].toInt() and 0xff
        val messageId = frame[itsOffset + 1].toInt() and 0xff
        val stationId = u32(frame, itsOffset + 2)
        return ItsExtractionResult.Success(
            ItsPacket(
                destinationPort = destinationPort,
                protocolVersion = protocolVersion,
                messageId = messageId,
                stationId = stationId,
                bodyOffset = ITS_PDU_HEADER_LEN,
                payload = frame.copyOfRange(itsOffset, itsEnd),
                sourceLatitude = geo.sourceLatitude,
                sourceLongitude = geo.sourceLongitude,
                geonetworkingSecured = geo.secured,
            ),
        )
    }

    private fun u16(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xff) shl 8) or (bytes[offset + 1].toInt() and 0xff)

    private fun u32(bytes: ByteArray, offset: Int): Long =
        (u16(bytes, offset).toLong() shl 16) or u16(bytes, offset + 2).toLong()

    private const val BTP_HEADER_LEN = 4
    private const val ITS_PDU_HEADER_LEN = 6
}
