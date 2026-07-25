package org.opentrafficmap.citstogo.protocol

object Ieee80211Mac {
    fun sourceAddress(frame: ByteArray): String? {
        if (frame.size < ADDRESS_2_OFFSET + MAC_ADDRESS_LEN) return null

        val frameControl = (frame[0].toInt() and 0xff) or ((frame[1].toInt() and 0xff) shl 8)
        val frameType = (frameControl shr 2) and 0x03
        if (frameType != TYPE_MANAGEMENT && frameType != TYPE_DATA) return null

        val toDs = frameControl and TO_DS_FLAG != 0
        val fromDs = frameControl and FROM_DS_FLAG != 0
        val sourceOffset = when {
            frameType == TYPE_MANAGEMENT -> ADDRESS_2_OFFSET
            toDs && fromDs -> ADDRESS_4_OFFSET
            fromDs -> ADDRESS_3_OFFSET
            else -> ADDRESS_2_OFFSET
        }
        if (frame.size < sourceOffset + MAC_ADDRESS_LEN) return null

        return formatUnicastMac(frame, sourceOffset)
    }

    private fun formatUnicastMac(frame: ByteArray, offset: Int): String? {
        var allZero = true
        for (i in 0 until MAC_ADDRESS_LEN) {
            if (frame[offset + i].toInt() and 0xff != 0) {
                allZero = false
                break
            }
        }
        if (allZero) return null
        if (frame[offset].toInt() and 0x01 != 0) return null

        return (0 until MAC_ADDRESS_LEN).joinToString(":") { i ->
            "%02x".format(frame[offset + i].toInt() and 0xff)
        }
    }

    private const val TYPE_MANAGEMENT = 0
    private const val TYPE_DATA = 2
    private const val TO_DS_FLAG = 0x0100
    private const val FROM_DS_FLAG = 0x0200
    private const val ADDRESS_2_OFFSET = 10
    private const val ADDRESS_3_OFFSET = 16
    private const val ADDRESS_4_OFFSET = 24
    private const val MAC_ADDRESS_LEN = 6
}
