package org.opentrafficmap.citstogo.bridge

import org.opentrafficmap.citstogo.protocol.CitsPacket
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.OutputStream

class PcapWriter(
    output: OutputStream,
    private val currentTimeUs: () -> Long = { System.currentTimeMillis() * 1_000L },
) : Closeable {
    private val out = BufferedOutputStream(output, 64 * 1024)
    private var closed = false
    private var packetsSinceFlush = 0
    private var lastFlushMs = System.currentTimeMillis()
    private var captureEpochUs: Long? = null
    private var captureDeviceUs: Long? = null
    private var previousDeviceUs: Long? = null
    private var deviceWrapOffsetUs = 0L

    init {
        writeGlobalHeader()
    }

    @Synchronized
    fun writePacket(packet: CitsPacket) {
        writeRawPacket(packet.payload, captureTimestampToUnixUs(packet.timestampUs))
    }

    @Synchronized
    fun writeRawPacket(payload: ByteArray, timestampUs: Long) {
        if (closed) return
        val seconds = (timestampUs / 1_000_000L).toInt()
        val micros = (timestampUs % 1_000_000L).toInt()
        writeIntLE(seconds)
        writeIntLE(micros)
        writeIntLE(payload.size)
        writeIntLE(payload.size)
        out.write(payload)
        packetsSinceFlush += 1
        maybeFlush(force = false)
    }

    @Synchronized
    fun flush() {
        maybeFlush(force = true)
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        out.flush()
        out.close()
    }

    private fun writeGlobalHeader() {
        writeIntLE(PCAP_MAGIC_USEC)
        writeShortLE(2)
        writeShortLE(4)
        writeIntLE(0)
        writeIntLE(0)
        writeIntLE(65_535)
        writeIntLE(DLT_IEEE802_11)
    }

    private fun maybeFlush(force: Boolean) {
        val now = System.currentTimeMillis()
        if (force || packetsSinceFlush >= FLUSH_EVERY_PACKETS || now - lastFlushMs >= FLUSH_EVERY_MS) {
            out.flush()
            packetsSinceFlush = 0
            lastFlushMs = now
        }
    }

    private fun captureTimestampToUnixUs(timestampUs: Long): Long {
        val rawDeviceUs = timestampUs and DEVICE_TIMESTAMP_MASK
        val previous = previousDeviceUs
        if (previous != null && rawDeviceUs < previous) {
            if (previous - rawDeviceUs > DEVICE_TIMESTAMP_HALF_RANGE_US) {
                deviceWrapOffsetUs += DEVICE_TIMESTAMP_RANGE_US
            } else {
                captureEpochUs = null
                captureDeviceUs = null
                deviceWrapOffsetUs = 0L
            }
        }
        previousDeviceUs = rawDeviceUs

        val extendedDeviceUs = rawDeviceUs + deviceWrapOffsetUs
        val baseDeviceUs = captureDeviceUs ?: extendedDeviceUs.also {
            captureDeviceUs = it
            captureEpochUs = currentTimeUs()
        }
        return requireNotNull(captureEpochUs) + extendedDeviceUs - baseDeviceUs
    }

    private fun writeShortLE(value: Int) {
        out.write(value and 0xff)
        out.write((value ushr 8) and 0xff)
    }

    private fun writeIntLE(value: Int) {
        out.write(value and 0xff)
        out.write((value ushr 8) and 0xff)
        out.write((value ushr 16) and 0xff)
        out.write((value ushr 24) and 0xff)
    }

    companion object {
        private const val PCAP_MAGIC_USEC = 0xA1B2C3D4.toInt()
        private const val DLT_IEEE802_11 = 105
        private const val FLUSH_EVERY_PACKETS = 128
        private const val FLUSH_EVERY_MS = 2_000L
        private const val DEVICE_TIMESTAMP_RANGE_US = 1L shl 32
        private const val DEVICE_TIMESTAMP_HALF_RANGE_US = DEVICE_TIMESTAMP_RANGE_US / 2
        private const val DEVICE_TIMESTAMP_MASK = DEVICE_TIMESTAMP_RANGE_US - 1
    }
}
