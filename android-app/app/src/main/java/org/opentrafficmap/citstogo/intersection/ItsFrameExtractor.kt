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
)

object ItsFrameExtractor {
    private val SNAP_GEONETWORKING = byteArrayOf(
        0xaa.toByte(), 0xaa.toByte(), 0x03, 0x00, 0x00, 0x00, 0x89.toByte(), 0x47,
    )

    fun extract(frame: ByteArray): ItsPacket? {
        val snapOffset = frame.indexOf(SNAP_GEONETWORKING)
        if (snapOffset < 0) return null
        val geoOffset = snapOffset + SNAP_GEONETWORKING.size
        if (frame.size < geoOffset + 12) return null

        val commonOffset = geoOffset + 4
        val commonNextHeader = (frame[commonOffset].toInt() ushr 4) and 0x0f
        if (commonNextHeader != NEXT_HEADER_BTP_B) return null
        val headerType = frame[commonOffset + 1].toInt() and 0xf0
        val btpOffset = when (headerType) {
            HEADER_TYPE_SHB -> geoOffset + 40
            HEADER_TYPE_GBC -> geoOffset + 56
            else -> return null
        }
        if (frame.size < btpOffset + 10) return null
        val destinationPort = u16(frame, btpOffset)
        val itsOffset = btpOffset + 4
        val protocolVersion = frame[itsOffset].toInt() and 0xff
        val messageId = frame[itsOffset + 1].toInt() and 0xff
        val stationId = u32(frame, itsOffset + 2)
        return ItsPacket(
            destinationPort = destinationPort,
            protocolVersion = protocolVersion,
            messageId = messageId,
            stationId = stationId,
            bodyOffset = 6,
            payload = frame.copyOfRange(itsOffset, frame.size),
            sourceLatitude = i32OrNull(frame, geoOffset + 4 + 8 + 4 + 8 + 4),
            sourceLongitude = i32OrNull(frame, geoOffset + 4 + 8 + 4 + 8 + 8),
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

    private fun u32(bytes: ByteArray, offset: Int): Long =
        ((u16(bytes, offset).toLong()) shl 16) or u16(bytes, offset + 2).toLong()

    private fun i32OrNull(bytes: ByteArray, offset: Int): Int? {
        if (bytes.size < offset + 4) return null
        return ((bytes[offset].toInt() and 0xff) shl 24) or
            ((bytes[offset + 1].toInt() and 0xff) shl 16) or
            ((bytes[offset + 2].toInt() and 0xff) shl 8) or
            (bytes[offset + 3].toInt() and 0xff)
    }

    private const val NEXT_HEADER_BTP_B = 2
    private const val HEADER_TYPE_GBC = 0x40
    private const val HEADER_TYPE_SHB = 0x50
}
