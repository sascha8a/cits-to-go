package org.opentrafficmap.citstogo.protocol

data class CitsPacket(
    val sequence: Long,
    val timestampUs: Long,
    val frequencyMhz: Int,
    val rssiDbm: Int,
    val wifiType: Int,
    val rxState: Int,
    val flags: Int,
    val originalLength: Int,
    val capturedLength: Int,
    val payload: ByteArray,
) {
    val truncated: Boolean get() = flags and FLAG_TRUNCATED != 0
    val broadcast: Boolean get() = flags and FLAG_BROADCAST != 0

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CitsPacket) return false
        return sequence == other.sequence &&
            timestampUs == other.timestampUs &&
            frequencyMhz == other.frequencyMhz &&
            rssiDbm == other.rssiDbm &&
            wifiType == other.wifiType &&
            rxState == other.rxState &&
            flags == other.flags &&
            originalLength == other.originalLength &&
            capturedLength == other.capturedLength &&
            payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int {
        var result = sequence.hashCode()
        result = 31 * result + timestampUs.hashCode()
        result = 31 * result + frequencyMhz
        result = 31 * result + rssiDbm
        result = 31 * result + wifiType
        result = 31 * result + rxState
        result = 31 * result + flags
        result = 31 * result + originalLength
        result = 31 * result + capturedLength
        result = 31 * result + payload.contentHashCode()
        return result
    }

    companion object {
        const val FLAG_BROADCAST = 0x0001
        const val FLAG_TRUNCATED = 0x0002
    }
}
