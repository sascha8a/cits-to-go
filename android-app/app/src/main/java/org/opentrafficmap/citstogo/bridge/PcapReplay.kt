package org.opentrafficmap.citstogo.bridge

import java.io.EOFException
import java.io.InputStream

data class PcapFrame(
    val timestampUs: Long,
    val originalLength: Int,
    val payload: ByteArray,
    /** Signal strength of the original reception, absent in a plain IEEE 802.11 capture. */
    val rssiDbm: Int? = null,
    /** Channel centre frequency it was received on, in MHz. */
    val frequencyMhz: Int? = null,
)

/**
 * Reads a classic PCAP stream of IEEE 802.11 frames, with or without the radiotap radio metadata that
 * link type 127 carries, so both this app's own recordings and captures made in monitor mode replay.
 */
class PcapReader(private val input: InputStream) {
    private val littleEndian: Boolean
    private val nanosecondResolution: Boolean
    private val hasRadioHeader: Boolean

    init {
        val header = readBytes(24, allowEof = false)!!
        val magic = header.copyOfRange(0, 4)
        when {
            magic.contentEquals(byteArrayOf(0xd4.toByte(), 0xc3.toByte(), 0xb2.toByte(), 0xa1.toByte())) -> {
                littleEndian = true
                nanosecondResolution = false
            }
            magic.contentEquals(byteArrayOf(0xa1.toByte(), 0xb2.toByte(), 0xc3.toByte(), 0xd4.toByte())) -> {
                littleEndian = false
                nanosecondResolution = false
            }
            magic.contentEquals(byteArrayOf(0x4d, 0x3c, 0xb2.toByte(), 0xa1.toByte())) -> {
                littleEndian = true
                nanosecondResolution = true
            }
            magic.contentEquals(byteArrayOf(0xa1.toByte(), 0xb2.toByte(), 0x3c, 0x4d)) -> {
                littleEndian = false
                nanosecondResolution = true
            }
            else -> throw IllegalArgumentException("Unsupported PCAP format")
        }
        val major = u16(header, 4)
        val linkType = u32(header, 20)
        require(major == 2) { "Unsupported PCAP version $major" }
        require(linkType == DLT_IEEE802_11.toLong() || linkType == Radiotap.LINK_TYPE.toLong()) {
            "PCAP link type $linkType is neither IEEE 802.11 ($DLT_IEEE802_11) " +
                "nor IEEE 802.11 radio (${Radiotap.LINK_TYPE})"
        }
        hasRadioHeader = linkType == Radiotap.LINK_TYPE.toLong()
    }

    fun nextFrame(): PcapFrame? {
        val header = readBytes(16, allowEof = true) ?: return null
        val seconds = u32(header, 0)
        val fraction = u32(header, 4)
        val capturedLength = u32(header, 8)
        val originalLength = u32(header, 12)
        require(capturedLength <= MAX_PACKET_BYTES) { "PCAP packet is too large: $capturedLength bytes" }
        require(originalLength <= Int.MAX_VALUE) { "PCAP original packet length is too large" }
        val subsecondUs = if (nanosecondResolution) fraction / 1_000L else fraction
        require(subsecondUs < 1_000_000L) { "Invalid PCAP packet timestamp" }
        val record = readBytes(capturedLength.toInt(), allowEof = false)!!
        // The lengths in the record cover the radio header, so the frame itself is what is left of
        // the record once that header is accounted for.
        val radio = if (hasRadioHeader) Radiotap.split(record) else null
        val payload = if (radio == null) record else record.copyOfRange(radio.frameOffset, record.size)
        return PcapFrame(
            timestampUs = Math.addExact(Math.multiplyExact(seconds, 1_000_000L), subsecondUs),
            originalLength = (originalLength.toInt() - (radio?.frameOffset ?: 0)).coerceAtLeast(payload.size),
            payload = payload,
            rssiDbm = radio?.information?.rssiDbm,
            frequencyMhz = radio?.information?.frequencyMhz,
        )
    }

    private fun u16(bytes: ByteArray, offset: Int): Int = if (littleEndian) {
        (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8)
    } else {
        ((bytes[offset].toInt() and 0xff) shl 8) or (bytes[offset + 1].toInt() and 0xff)
    }

    private fun u32(bytes: ByteArray, offset: Int): Long {
        var result = 0L
        if (littleEndian) {
            for (index in 3 downTo 0) result = (result shl 8) or (bytes[offset + index].toLong() and 0xff)
        } else {
            for (index in 0..3) result = (result shl 8) or (bytes[offset + index].toLong() and 0xff)
        }
        return result
    }

    private fun readBytes(count: Int, allowEof: Boolean): ByteArray? {
        val bytes = ByteArray(count)
        var offset = 0
        while (offset < count) {
            val read = input.read(bytes, offset, count - offset)
            if (read < 0) {
                if (allowEof && offset == 0) return null
                throw EOFException("Truncated PCAP file")
            }
            if (read == 0) continue
            offset += read
        }
        return bytes
    }

    companion object {
        private const val DLT_IEEE802_11 = 105
        private const val MAX_PACKET_BYTES = 16L * 1024L * 1024L
    }
}

class PcapReplay(
    private val elapsedRealtimeUs: () -> Long,
    private val sleepMs: (Long) -> Unit,
) {
    fun run(reader: PcapReader, active: () -> Boolean, onFrame: (PcapFrame) -> Unit): Long {
        var firstTimestampUs: Long? = null
        var replayStartUs = 0L
        var replayed = 0L
        while (active()) {
            val frame = reader.nextFrame() ?: break
            val first = firstTimestampUs ?: frame.timestampUs.also {
                firstTimestampUs = it
                replayStartUs = elapsedRealtimeUs()
            }
            val offsetUs = (frame.timestampUs - first).coerceAtLeast(0L)
            while (active()) {
                val remainingUs = replayStartUs + offsetUs - elapsedRealtimeUs()
                if (remainingUs <= 0L) break
                sleepMs(minOf((remainingUs + 999L) / 1_000L, 250L))
            }
            if (!active()) break
            onFrame(frame)
            replayed += 1
        }
        return replayed
    }
}
