package org.opentrafficmap.citstogo.intersection

internal data class GeoNetworkingTransportPayload(
    val btpOffset: Int,
    val payloadLength: Int,
    val sourceLatitude: Int?,
    val sourceLongitude: Int?,
    val secured: Boolean,
)

internal sealed interface GeoNetworkingParseResult {
    data class Success(val payload: GeoNetworkingTransportPayload) : GeoNetworkingParseResult
    data object NotGeoNetworking : GeoNetworkingParseResult

    /**
     * The GeoNetworking Basic Header carries a next-header value outside the encodings this app
     * understands (anything other than Common Header `1` or Secured Packet `2`). This is a protocol
     * classification, never a decode error.
     */
    data class Unsupported(val reason: String, val nextHeader: Int) : GeoNetworkingParseResult

    /**
     * A recognised GeoNetworking envelope (Common Header or Secured Packet) whose contents could not
     * be decoded. Kept distinct from [Unsupported] so the Debug screen never reports a valid
     * `NH = 2` secured packet as an unsupported protocol value.
     */
    data class Malformed(val reason: String, val secured: Boolean) : GeoNetworkingParseResult
}

/**
 * Parses the GeoNetworking envelope around BTP-B payloads.
 *
 * GeoNetworking security wraps the Common Header + Extended Header + payload
 * between the Basic Header and a security trailer. The IEEE 1609.2 / ETSI
 * security envelope is variable-length, so the parser deliberately does not
 * hard-code the security-header size. Instead it locates the encapsulated
 * Common Header using strict GeoNetworking/BTP structural checks and the Common
 * Header payload length. This keeps security trailers out of upper-layer ASN.1
 * decoders and works for both secured and unsecured packets.
 */
internal object GeoNetworkingFrameParser {
    private val SNAP_GEONETWORKING = byteArrayOf(
        0xaa.toByte(), 0xaa.toByte(), 0x03, 0x00, 0x00, 0x00, 0x89.toByte(), 0x47,
    )

    fun parse(frame: ByteArray): GeoNetworkingParseResult {
        val snapOffset = frame.indexOf(SNAP_GEONETWORKING)
        if (snapOffset < 0) return GeoNetworkingParseResult.NotGeoNetworking

        val basicOffset = snapOffset + SNAP_GEONETWORKING.size
        if (frame.size < basicOffset + BASIC_HEADER_LEN) {
            return GeoNetworkingParseResult.Malformed("Truncated GeoNetworking Basic Header", secured = false)
        }

        val basicNextHeader = frame[basicOffset].toInt() and 0x0f
        return when (basicNextHeader) {
            BASIC_NEXT_HEADER_COMMON -> when (val inner = parseCommon(frame, basicOffset + BASIC_HEADER_LEN, secured = false)) {
                is CommonParseResult.Ok -> GeoNetworkingParseResult.Success(inner.payload)
                CommonParseResult.Malformed ->
                    GeoNetworkingParseResult.Malformed("Malformed GeoNetworking Common/Extended Header", secured = false)
                CommonParseResult.UnsupportedHeader ->
                    GeoNetworkingParseResult.Unsupported(
                        "Unsupported GeoNetworking Common/Extended Header",
                        nextHeader = basicNextHeader,
                    )
            }

            BASIC_NEXT_HEADER_SECURED -> locateSecuredCommon(frame, basicOffset + BASIC_HEADER_LEN)
            else -> GeoNetworkingParseResult.Unsupported(
                "Unsupported GeoNetworking Basic Header next-header $basicNextHeader",
                nextHeader = basicNextHeader,
            )
        }
    }

    private fun locateSecuredCommon(frame: ByteArray, securedPayloadOffset: Int): GeoNetworkingParseResult {
        val scanEndExclusive = minOf(
            frame.size - COMMON_HEADER_LEN + 1,
            securedPayloadOffset + MAX_SECURED_PREFIX_BYTES,
        )
        var candidate: GeoNetworkingTransportPayload? = null
        for (commonOffset in securedPayloadOffset until scanEndExclusive) {
            val parsed = parseCommon(frame, commonOffset, secured = true)
            if (parsed !is CommonParseResult.Ok) continue
            if (candidate != null) {
                return GeoNetworkingParseResult.Malformed(
                    "Ambiguous secured GeoNetworking payload",
                    secured = true,
                )
            }
            candidate = parsed.payload
        }
        return candidate?.let(GeoNetworkingParseResult::Success)
            ?: GeoNetworkingParseResult.Malformed(
                "Secured GeoNetworking payload does not contain a supported BTP-B Common Header",
                secured = true,
            )
    }

    private sealed interface CommonParseResult {
        data class Ok(val payload: GeoNetworkingTransportPayload) : CommonParseResult
        data object Malformed : CommonParseResult
        data object UnsupportedHeader : CommonParseResult
    }

    private fun parseCommon(
        frame: ByteArray,
        commonOffset: Int,
        secured: Boolean,
    ): CommonParseResult {
        if (frame.size < commonOffset + COMMON_HEADER_LEN) return CommonParseResult.Malformed

        val commonNextHeader = (frame[commonOffset].toInt() ushr 4) and 0x0f
        if (commonNextHeader != COMMON_NEXT_HEADER_BTP_B) return CommonParseResult.UnsupportedHeader

        val headerType = frame[commonOffset + 1].toInt() and 0xf0
        val extendedHeaderLength = when (headerType) {
            HEADER_TYPE_GBC -> GBC_EXTENDED_HEADER_LEN
            HEADER_TYPE_SHB -> SHB_EXTENDED_HEADER_LEN
            else -> return CommonParseResult.UnsupportedHeader
        }

        val payloadLength = u16(frame, commonOffset + 4)
        if (payloadLength < BTP_HEADER_LEN + ITS_PDU_HEADER_LEN) return CommonParseResult.Malformed

        val extendedOffset = commonOffset + COMMON_HEADER_LEN
        val btpOffset = extendedOffset + extendedHeaderLength
        val payloadEnd = btpOffset + payloadLength
        if (btpOffset < 0 || payloadEnd > frame.size) return CommonParseResult.Malformed

        val itsOffset = btpOffset + BTP_HEADER_LEN
        if (frame.size < itsOffset + ITS_PDU_HEADER_LEN) return CommonParseResult.Malformed
        val protocolVersion = frame[itsOffset].toInt() and 0xff
        val messageId = frame[itsOffset + 1].toInt() and 0xff
        if (protocolVersion !in SUPPORTED_ITS_PROTOCOL_VERSIONS || messageId == 0) return CommonParseResult.Malformed

        val sourcePositionOffset = when (headerType) {
            HEADER_TYPE_GBC -> extendedOffset + GBC_SOURCE_POSITION_OFFSET
            HEADER_TYPE_SHB -> extendedOffset + SHB_SOURCE_POSITION_OFFSET
            else -> return CommonParseResult.Malformed
        }
        val sourceLatitude = i32OrNull(frame, sourcePositionOffset + LONG_POSITION_VECTOR_LATITUDE_OFFSET)
        val sourceLongitude = i32OrNull(frame, sourcePositionOffset + LONG_POSITION_VECTOR_LONGITUDE_OFFSET)

        return CommonParseResult.Ok(
            GeoNetworkingTransportPayload(
                btpOffset = btpOffset,
                payloadLength = payloadLength,
                sourceLatitude = sourceLatitude,
                sourceLongitude = sourceLongitude,
                secured = secured,
            ),
        )
    }

    private fun ByteArray.indexOf(pattern: ByteArray): Int {
        if (pattern.isEmpty() || size < pattern.size) return -1
        for (offset in 0..size - pattern.size) {
            var matched = true
            for (i in pattern.indices) {
                if (this[offset + i] != pattern[i]) {
                    matched = false
                    break
                }
            }
            if (matched) return offset
        }
        return -1
    }

    private fun u16(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xff) shl 8) or (bytes[offset + 1].toInt() and 0xff)

    private fun i32OrNull(bytes: ByteArray, offset: Int): Int? {
        if (bytes.size < offset + 4) return null
        return ((bytes[offset].toInt() and 0xff) shl 24) or
            ((bytes[offset + 1].toInt() and 0xff) shl 16) or
            ((bytes[offset + 2].toInt() and 0xff) shl 8) or
            (bytes[offset + 3].toInt() and 0xff)
    }

    private const val BASIC_HEADER_LEN = 4
    private const val COMMON_HEADER_LEN = 8
    private const val BTP_HEADER_LEN = 4
    private const val ITS_PDU_HEADER_LEN = 6

    private const val BASIC_NEXT_HEADER_COMMON = 1
    private const val BASIC_NEXT_HEADER_SECURED = 2
    private const val COMMON_NEXT_HEADER_BTP_B = 2

    private const val HEADER_TYPE_GBC = 0x40
    private const val HEADER_TYPE_SHB = 0x50
    private const val GBC_EXTENDED_HEADER_LEN = 44
    private const val SHB_EXTENDED_HEADER_LEN = 28

    // GBC starts with a 4-byte sequence/reserved prefix before its Long Position Vector.
    private const val GBC_SOURCE_POSITION_OFFSET = 4
    private const val SHB_SOURCE_POSITION_OFFSET = 0
    private const val LONG_POSITION_VECTOR_LATITUDE_OFFSET = 12
    private const val LONG_POSITION_VECTOR_LONGITUDE_OFFSET = 16

    // Security headers observed in practice are small, while trailers can be large.
    // A bounded scan avoids both hard-coded security offsets and unbounded false-positive searching.
    private const val MAX_SECURED_PREFIX_BYTES = 512
    private val SUPPORTED_ITS_PROTOCOL_VERSIONS = 1..3
}
