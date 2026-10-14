package org.opentrafficmap.citstogo.bridge

/**
 * What a receiver reported about the radio conditions one frame was captured under. Both values are
 * null when the capture did not record them, which is how a plain IEEE 802.11 PCAP arrives and how a
 * firmware frame with nothing to report is written.
 */
data class RadioInformation(
    val rssiDbm: Int? = null,
    val frequencyMhz: Int? = null,
)

/** A captured record separated into its radio information and the offset its frame begins at. */
data class RadioHeader(val information: RadioInformation, val frameOffset: Int)

/**
 * Radiotap, the radio-metadata header defined for PCAP link type 127 ("IEEE 802.11 radio").
 *
 * A header is a fixed part (version, padding, header length, present bitmap) followed by exactly the
 * fields the bitmap selects, in ascending field order, each aligned to a multiple of its own width.
 * Radiotap is little-endian by definition, whatever byte order the surrounding PCAP file uses.
 *
 * Only the fields this app's receiver actually reports are written; a value the firmware did not
 * report is left out of the bitmap rather than written as zero, so a reader cannot mistake "unknown"
 * for "measured as zero". When reading, fields this reader does not know are stepped over, and a
 * field newer than [KNOWN_FIELDS] ends the walk: the frame behind the header is still found, because
 * the header length says where it begins, and every field this app reads is numbered low enough to
 * have been visited before the walk stops.
 */
object Radiotap {
    /** PCAP link type of an IEEE 802.11 frame preceded by a radiotap header. */
    const val LINK_TYPE = 127

    /** Version this writer emits and the only version this reader accepts. */
    private const val VERSION = 0

    /** Length of the fixed part: version, padding, header length and one present bitmap word. */
    private const val FIXED_PART_BYTES = 8

    /** Bit 31 of a bitmap word announces another bitmap word directly behind it. */
    private const val EXTENSION_BIT = 1 shl 31

    private const val FIELD_CHANNEL = 3
    private const val FIELD_DBM_ANTSIGNAL = 5

    /**
     * Size and alignment of every field numbered up to transmit flags, as radiotap defines them,
     * starting at the timestamp field 0: timestamp, flags, rate, channel, FHSS, signal dBm, noise
     * dBm, lock quality, TX attenuation, TX attenuation dB, TX power dBm, antenna, signal dB, noise
     * dB, receive flags, transmit flags. The channel field is four bytes wide but only two-byte
     * aligned, being a frequency followed by flags, which is why alignment is not always the size.
     */
    private val KNOWN_FIELDS = listOf(
        8 to 8, // timestamp in microseconds
        1 to 1, // flags
        1 to 1, // data rate
        4 to 2, // channel: frequency in MHz, then channel flags
        4 to 1, // FHSS hop number and pattern
        1 to 1, // signal strength above the antenna noise floor, dBm
        1 to 1, // antenna noise floor, dBm
        2 to 2, // lock quality
        2 to 2, // transmit attenuation
        1 to 1, // TX attenuation, dB
        1 to 1, // transmit power, dBm
        1 to 1, // antenna index
        1 to 1, // signal, dB relative
        1 to 1, // noise, dB relative
        2 to 2, // receive flags
        2 to 2, // transmit flags
    )

    /** Channel flags for OFDM in the 5 GHz band, which is where ITS-G5 and ITS-G5B transmit. */
    private const val CHANNEL_FLAGS_OFDM_5GHZ = 0x0040 or 0x0100

    /** A header carrying nothing at all, for a frame whose radio conditions were never known. */
    val EMPTY_HEADER: ByteArray = header(RadioInformation())

    /**
     * Radiotap header describing [information], omitting every value it does not carry. A frequency
     * outside the range a channel field can hold, or a signal level outside one signed byte, is
     * omitted rather than clipped, so the frame is never labelled with a number the receiver did not
     * report.
     */
    fun header(information: RadioInformation): ByteArray {
        val frequency = information.frequencyMhz?.takeIf { it in 1..0xffff }
        val signal = information.rssiDbm?.takeIf { it in Byte.MIN_VALUE..Byte.MAX_VALUE }
        val channelBytes = if (frequency == null) 0 else 4
        val signalBytes = if (signal == null) 0 else 1
        val header = ByteArray(FIXED_PART_BYTES + channelBytes + signalBytes)
        putShort(header, 2, header.size)
        putInt(
            header,
            4,
            (if (frequency != null) 1 shl FIELD_CHANNEL else 0) or
                (if (signal != null) 1 shl FIELD_DBM_ANTSIGNAL else 0),
        )
        var offset = FIXED_PART_BYTES
        if (frequency != null) {
            putShort(header, offset, frequency)
            putShort(header, offset + 2, CHANNEL_FLAGS_OFDM_5GHZ)
            offset += 4
        }
        if (signal != null) header[offset] = signal.toByte()
        return header
    }

    /**
     * Splits one captured [record] into the radio conditions it records and where its IEEE 802.11
     * frame begins.
     *
     * @throws IllegalArgumentException when the record does not hold a radiotap header this reader
     *   understands, which means the file is not what its link type claimed.
     */
    fun split(record: ByteArray): RadioHeader {
        require(record.size > FIXED_PART_BYTES) { "Radiotap header is truncated" }
        val version = record[0].toInt() and 0xff
        require(version == VERSION) { "Unsupported radiotap version $version" }
        val headerLength = u16(record, 2)
        require(headerLength in FIXED_PART_BYTES..record.size) {
            "Radiotap header length $headerLength does not fit the ${record.size} byte record"
        }
        val fields = fieldOffsets(record, headerLength)
        return RadioHeader(
            information = RadioInformation(
                rssiDbm = fields[FIELD_DBM_ANTSIGNAL]?.let { record[it].toInt().toByte().toInt() },
                frequencyMhz = fields[FIELD_CHANNEL]?.let { u16(record, it) }?.takeIf { it > 0 },
            ),
            frameOffset = headerLength,
        )
    }

    /**
     * Offset of every radiotap field [record] carries, walking the fields in the order the bitmap
     * declares them and stopping at the first field this reader does not know.
     */
    private fun fieldOffsets(record: ByteArray, headerLength: Int): Map<Int, Int> {
        val words = mutableListOf<Int>()
        var wordOffset = 4
        while (wordOffset + 4 <= headerLength) {
            val word = u32(record, wordOffset)
            words += word
            if (word and EXTENSION_BIT == 0) break
            wordOffset += 4
        }
        // The fixed part holds the first bitmap word; every announced extension adds another.
        var offset = FIXED_PART_BYTES + 4 * (words.size - 1)
        val fields = mutableMapOf<Int, Int>()
        for (wordIndex in words.indices) {
            val word = words[wordIndex]
            for (bit in 0 until 32) {
                // Bit 31 only means "another bitmap word follows" in the words that are not the last.
                if (bit == 31 && wordIndex < words.size - 1) continue
                if (word and (1 shl bit) == 0) continue
                val index = wordIndex * 32 + bit
                val (size, alignment) = KNOWN_FIELDS.getOrNull(index) ?: return fields
                offset = (offset + alignment - 1) / alignment * alignment
                require(offset + size <= headerLength) {
                    "Radiotap field $index runs past the $headerLength byte header"
                }
                fields[index] = offset
                offset += size
            }
        }
        return fields
    }

    /** Radiotap is defined little-endian, independent of the byte order of the PCAP holding it. */
    private fun u16(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8)

    private fun u32(bytes: ByteArray, offset: Int): Int {
        var result = 0
        for (index in 3 downTo 0) result = (result shl 8) or (bytes[offset + index].toInt() and 0xff)
        return result
    }

    private fun putShort(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value and 0xff).toByte()
        bytes[offset + 1] = ((value ushr 8) and 0xff).toByte()
    }

    private fun putInt(bytes: ByteArray, offset: Int, value: Int) {
        for (index in 0 until 4) bytes[offset + index] = ((value ushr (8 * index)) and 0xff).toByte()
    }
}
