package org.opentrafficmap.citstogo.flashing

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

object FirmwareFileReader {
    fun read(
        input: InputStream,
        maximumBytes: Long = CodebergReleaseClient.MAX_FIRMWARE_BYTES,
        onProgress: (Long) -> Unit = {},
    ): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count == 0) continue
            total += count
            if (total > maximumBytes) throw IOException("Firmware file exceeds the maximum allowed size")
            output.write(buffer, 0, count)
            onProgress(total)
        }
        if (total == 0L) throw IOException("Firmware file is empty")
        return output.toByteArray()
    }
}
