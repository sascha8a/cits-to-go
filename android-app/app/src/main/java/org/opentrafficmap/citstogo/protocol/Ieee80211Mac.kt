package org.opentrafficmap.citstogo.protocol

object Ieee80211Mac {
    fun transmitterAddress(frame: ByteArray): String? {
        if (frame.size < ADDR2_OFFSET + MAC_ADDRESS_LEN) return null
        val frameControl = u16(frame, 0)
        val type = (frameControl ushr 2) and 0x3
        val version = frameControl and 0x3
        if (version != 0 || type == TYPE_CONTROL) return null

        val offset = ADDR2_OFFSET
        if (!isUsableUnicastAddress(frame, offset)) return null
        return formatMac(frame, offset)
    }

    private fun isUsableUnicastAddress(frame: ByteArray, offset: Int): Boolean {
        var allZero = true
        var allOnes = true
        for (i in 0 until MAC_ADDRESS_LEN) {
            val value = frame[offset + i].toInt() and 0xff
            if (value != 0x00) allZero = false
            if (value != 0xff) allOnes = false
        }
        val first = frame[offset].toInt() and 0xff
        val multicast = first and 0x01 != 0
        return !allZero && !allOnes && !multicast
    }

    private fun formatMac(frame: ByteArray, offset: Int): String =
        (0 until MAC_ADDRESS_LEN).joinToString(":") { "%02x".format(frame[offset + it].toInt() and 0xff) }

    private fun u16(buf: ByteArray, offset: Int): Int =
        (buf[offset].toInt() and 0xff) or ((buf[offset + 1].toInt() and 0xff) shl 8)

    private const val TYPE_CONTROL = 1
    private const val ADDR2_OFFSET = 10
    private const val MAC_ADDRESS_LEN = 6
}
