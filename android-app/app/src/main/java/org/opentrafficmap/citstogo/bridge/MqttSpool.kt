package org.opentrafficmap.citstogo.bridge

import android.content.Context
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile

class MqttSpool(context: Context) : Closeable {
    data class Record(val payload: ByteArray, val nextOffset: Long)

    private val file = File(context.filesDir, "mqtt-packet-spool.bin")
    private val prefs = context.getSharedPreferences("mqtt_spool", Context.MODE_PRIVATE)
    private var appendOut: BufferedOutputStream? = null
    private var readOffset = prefs.getLong(KEY_READ_OFFSET, 0L)
    private var writeOffset = 0L
    private var pending = prefs.getLong(KEY_PENDING_COUNT, -1L)
    private var unflushed = 0

    init {
        recover()
    }

    @Synchronized
    fun append(payload: ByteArray) {
        if (payload.isEmpty()) return
        if (payload.size > MAX_PAYLOAD_LEN) throw IOException("MQTT payload too large: ${payload.size}")
        ensureAppendOpen()
        val out = appendOut ?: throw IOException("Spool is closed")
        writeIntLE(out, payload.size)
        out.write(payload)
        writeOffset += 4L + payload.size
        pending += 1
        unflushed += 1
        if (unflushed >= 32) flush()
        save()
    }

    @Synchronized
    fun readBatch(maxPackets: Int): List<Record> {
        flush()
        if (maxPackets <= 0 || readOffset >= file.length()) return emptyList()
        val records = ArrayList<Record>(maxPackets)
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(readOffset)
            var offset = readOffset
            repeat(maxPackets) {
                if (offset + 4 > raf.length()) return@repeat
                val len = readIntLE(raf)
                offset += 4
                if (len <= 0 || len > MAX_PAYLOAD_LEN) throw IOException("Corrupt MQTT spool record length $len")
                if (offset + len > raf.length()) return@repeat
                val payload = ByteArray(len)
                raf.readFully(payload)
                offset += len
                records += Record(payload, offset)
            }
        }
        return records
    }

    @Synchronized
    fun ack(nextOffset: Long, count: Int) {
        if (count <= 0 || nextOffset <= readOffset) return
        readOffset = nextOffset.coerceAtMost(file.length())
        pending = (pending - count).coerceAtLeast(0L)
        compactIfUseful()
        save()
    }

    @Synchronized
    fun pendingCount(): Long = pending.coerceAtLeast(0L)

    @Synchronized
    fun clear() {
        closeQuietly()
        if (file.exists()) file.delete()
        readOffset = 0
        writeOffset = 0
        pending = 0
        unflushed = 0
        save()
    }

    @Synchronized
    fun flush() {
        appendOut?.flush()
        unflushed = 0
    }

    override fun close() {
        appendOut?.flush()
        appendOut?.close()
        appendOut = null
    }

    private fun ensureAppendOpen() {
        if (appendOut != null) return
        file.parentFile?.mkdirs()
        appendOut = BufferedOutputStream(FileOutputStream(file, true), 64 * 1024)
        writeOffset = file.length()
    }

    private fun recover() {
        if (!file.exists()) {
            readOffset = 0
            writeOffset = 0
            pending = 0
            save()
            return
        }
        var len = file.length()
        if (readOffset < 0 || readOffset > len) readOffset = 0
        val goodEnd = scanGoodEnd(0, len)
        if (goodEnd < len) {
            RandomAccessFile(file, "rw").use { it.setLength(goodEnd) }
            len = goodEnd
        }
        writeOffset = len
        pending = countRecords(readOffset, writeOffset)
        compactIfUseful()
        save()
    }

    private fun scanGoodEnd(start: Long, max: Long): Long {
        var offset = start
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(start)
            while (offset + 4 <= max) {
                val len = readIntLE(raf)
                if (len <= 0 || len > MAX_PAYLOAD_LEN || offset + 4L + len > max) break
                offset += 4L + len
                raf.seek(offset)
            }
        }
        return offset
    }

    private fun countRecords(start: Long, max: Long): Long {
        var count = 0L
        var offset = start
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(start)
            while (offset + 4 <= max) {
                val len = readIntLE(raf)
                if (len <= 0 || len > MAX_PAYLOAD_LEN || offset + 4L + len > max) break
                offset += 4L + len
                raf.seek(offset)
                count += 1
            }
        }
        return count
    }

    private fun compactIfUseful() {
        val len = if (file.exists()) file.length() else 0L
        if (readOffset <= COMPACT_AFTER_BYTES || readOffset <= len / 2) return
        flush()
        closeQuietly()
        val tmp = File(file.parentFile, "${file.name}.tmp")
        FileInputStream(file).use { input ->
            FileOutputStream(tmp).use { output ->
                var skipped = 0L
                while (skipped < readOffset) {
                    val n = input.skip(readOffset - skipped)
                    if (n <= 0) throw EOFException("Could not skip spool prefix")
                    skipped += n
                }
                input.copyTo(output, 64 * 1024)
            }
        }
        if (!file.delete() || !tmp.renameTo(file)) throw IOException("Could not compact MQTT spool")
        readOffset = 0
        writeOffset = file.length()
        pending = countRecords(0, writeOffset)
    }

    private fun save() {
        prefs.edit()
            .putLong(KEY_READ_OFFSET, readOffset)
            .putLong(KEY_PENDING_COUNT, pending)
            .apply()
    }

    private fun closeQuietly() = runCatching { close() }.let {}

    companion object {
        private const val KEY_READ_OFFSET = "read_offset"
        private const val KEY_PENDING_COUNT = "pending_count"
        private const val MAX_PAYLOAD_LEN = 65_535
        private const val COMPACT_AFTER_BYTES = 1024L * 1024L

        private fun writeIntLE(out: BufferedOutputStream, value: Int) {
            out.write(value and 0xff)
            out.write((value ushr 8) and 0xff)
            out.write((value ushr 16) and 0xff)
            out.write((value ushr 24) and 0xff)
        }

        private fun readIntLE(raf: RandomAccessFile): Int {
            val b0 = raf.read()
            val b1 = raf.read()
            val b2 = raf.read()
            val b3 = raf.read()
            if (b0 or b1 or b2 or b3 < 0) throw EOFException()
            return b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
        }
    }
}
