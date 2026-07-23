package org.opentrafficmap.citstogo.bridge

import org.opentrafficmap.citstogo.protocol.CitsPacket
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.OutputStream

class PcapWriter(output: OutputStream) : Closeable {
    private val out = BufferedOutputStream(output, 64 * 1024)
    private var closed = false
    private var packetsSinceFlush = 0
    private var lastFlushMs = System.currentTimeMillis()

    init {
        writeGlobalHeader()
    }

    @Synchronized
    fun writePacket(packet: CitsPacket) {
        if (closed) return
        val seconds = (packet.timestampUs / 1_000_000L).toInt()
        val micros = (packet.timestampUs % 1_000_000L).toInt()
        writeIntLE(seconds)
        writeIntLE(micros)
        writeIntLE(packet.payload.size)
        writeIntLE(packet.payload.size)
        out.write(packet.payload)
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
    }
}
