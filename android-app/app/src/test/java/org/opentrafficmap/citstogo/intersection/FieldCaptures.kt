package org.opentrafficmap.citstogo.intersection

import java.io.File

/**
 * Reads the recorded field captures that live in the repository root, so decoding tests can run
 * against frames a real receiver captured over the air instead of hand-built ones.
 */
object FieldCaptures {
    /** Road-side units sending SPATEM, MAPEM and DENM, plus one passenger car CAM. */
    const val INTERSECTION_AND_CAM = "cits-1785335702733.pcap"

    /** The IEEE 802.11 frames of [fileName], exactly as they were captured. */
    fun frames(fileName: String = INTERSECTION_AND_CAM): List<ByteArray> {
        val pcap = listOf(
            File("../$fileName"),
            File("../../$fileName"),
            File(fileName),
        ).first { it.exists() }
        val bytes = pcap.readBytes()
        require(intLE(bytes, 0) == 0xA1B2C3D4.toInt()) { "Not a little-endian pcap: $fileName" }
        var offset = 24
        val frames = mutableListOf<ByteArray>()
        while (offset + 16 <= bytes.size) {
            val capturedLength = intLE(bytes, offset + 8)
            offset += 16
            if (offset + capturedLength > bytes.size) break
            frames += bytes.copyOfRange(offset, offset + capturedLength)
            offset += capturedLength
        }
        return frames
    }

    private fun intLE(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or
            ((bytes[offset + 1].toInt() and 0xff) shl 8) or
            ((bytes[offset + 2].toInt() and 0xff) shl 16) or
            ((bytes[offset + 3].toInt() and 0xff) shl 24)
}
