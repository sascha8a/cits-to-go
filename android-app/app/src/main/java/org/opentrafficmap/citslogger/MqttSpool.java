package org.opentrafficmap.citslogger;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.List;

/**
 * Persistent packet spool for MQTT retry/replay.
 *
 * Records are app-private and independent of the user-visible PCAP file:
 *   uint32_le payload_len, payload bytes
 *
 * The visible PCAP remains the primary capture artifact. This spool is the
 * MQTT cursor/retry journal so a broken pipe, lock-screen network drop, or
 * process restart does not require holding all packets in RAM.
 */
final class MqttSpool implements Closeable {
    static final class Record {
        final byte[] payload;
        final long nextOffset;

        Record(byte[] payload, long nextOffset) {
            this.payload = payload;
            this.nextOffset = nextOffset;
        }
    }

    private static final String PREFS = "mqtt_spool";
    private static final String KEY_READ_OFFSET = "read_offset";
    private static final String KEY_PENDING_COUNT = "pending_count";
    private static final int MAX_PAYLOAD_LEN = 65535;
    private static final long COMPACT_AFTER_BYTES = 1024L * 1024L;

    private final File file;
    private final SharedPreferences prefs;
    private BufferedOutputStream appendOut;
    private long writeOffset;
    private long readOffset;
    private long pendingCount;
    private int unflushedAppends;

    MqttSpool(Context context) throws IOException {
        this.file = new File(context.getFilesDir(), "mqtt-packet-spool.bin");
        this.prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        this.readOffset = prefs.getLong(KEY_READ_OFFSET, 0L);
        this.pendingCount = prefs.getLong(KEY_PENDING_COUNT, -1L);
        recover();
    }

    synchronized void append(byte[] payload) throws IOException {
        if (payload == null || payload.length == 0) return;
        if (payload.length > MAX_PAYLOAD_LEN) throw new IOException("MQTT payload too large for spool: " + payload.length);
        ensureAppendOpen();
        writeIntLE(appendOut, payload.length);
        appendOut.write(payload);
        writeOffset += 4L + payload.length;
        pendingCount++;
        unflushedAppends++;
        if (unflushedAppends >= 32) flush();
        savePrefs();
    }

    synchronized List<Record> readBatch(int maxPackets) throws IOException {
        flush();
        ArrayList<Record> out = new ArrayList<>();
        if (maxPackets <= 0 || readOffset >= file.length()) return out;

        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            raf.seek(readOffset);
            long offset = readOffset;
            for (int i = 0; i < maxPackets; i++) {
                if (offset + 4 > raf.length()) break;
                int len = readIntLE(raf);
                offset += 4;
                if (len <= 0 || len > MAX_PAYLOAD_LEN) {
                    throw new IOException("Corrupt MQTT spool record length " + len + " at offset " + (offset - 4));
                }
                if (offset + len > raf.length()) break;
                byte[] payload = new byte[len];
                raf.readFully(payload);
                offset += len;
                out.add(new Record(payload, offset));
            }
        }
        return out;
    }

    synchronized void ackBatch(long nextOffset, int recordCount) throws IOException {
        if (nextOffset <= readOffset || recordCount <= 0) return;
        readOffset = Math.min(nextOffset, file.length());
        pendingCount = Math.max(0L, pendingCount - recordCount);
        savePrefs();
        maybeCompact();
    }

    synchronized long pendingCount() {
        return Math.max(0L, pendingCount);
    }

    synchronized void clear() {
        closeQuietly();
        if (file.exists()) //noinspection ResultOfMethodCallIgnored
            file.delete();
        readOffset = 0;
        writeOffset = 0;
        pendingCount = 0;
        unflushedAppends = 0;
        savePrefs();
    }

    synchronized void flush() throws IOException {
        if (appendOut != null) {
            appendOut.flush();
            unflushedAppends = 0;
        }
    }

    @Override
    public synchronized void close() throws IOException {
        if (appendOut != null) {
            appendOut.flush();
            appendOut.close();
            appendOut = null;
        }
    }

    private void ensureAppendOpen() throws IOException {
        if (appendOut != null) return;
        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Could not create spool directory " + parent);
        }
        appendOut = new BufferedOutputStream(new FileOutputStream(file, true), 64 * 1024);
        writeOffset = file.length();
    }

    private void recover() throws IOException {
        if (!file.exists()) {
            readOffset = 0;
            writeOffset = 0;
            pendingCount = 0;
            savePrefs();
            return;
        }

        long len = file.length();
        if (readOffset < 0 || readOffset > len) readOffset = 0;

        long goodEnd = scanGoodEnd(0, len);
        if (goodEnd < len) {
            try (RandomAccessFile raf = new RandomAccessFile(file, "rw")) {
                raf.setLength(goodEnd);
            }
            len = goodEnd;
        }
        writeOffset = len;
        if (readOffset > writeOffset) readOffset = 0;

        pendingCount = countRecords(readOffset, writeOffset);
        maybeCompact();
        savePrefs();
    }

    private long scanGoodEnd(long start, long max) throws IOException {
        long offset = start;
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            raf.seek(start);
            while (offset + 4 <= max) {
                int len = readIntLE(raf);
                if (len <= 0 || len > MAX_PAYLOAD_LEN || offset + 4L + len > max) break;
                raf.seek(offset + 4L + len);
                offset += 4L + len;
            }
        }
        return offset;
    }

    private long countRecords(long start, long max) throws IOException {
        long count = 0;
        long offset = start;
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            raf.seek(start);
            while (offset + 4 <= max) {
                int len = readIntLE(raf);
                if (len <= 0 || len > MAX_PAYLOAD_LEN || offset + 4L + len > max) break;
                raf.seek(offset + 4L + len);
                offset += 4L + len;
                count++;
            }
        }
        return count;
    }

    private void maybeCompact() throws IOException {
        long len = file.exists() ? file.length() : 0;
        if (readOffset <= COMPACT_AFTER_BYTES || readOffset <= len / 2) return;
        flush();
        closeQuietly();

        File tmp = new File(file.getParentFile(), file.getName() + ".tmp");
        long copied = 0;
        try (FileInputStream in = new FileInputStream(file);
             FileOutputStream out = new FileOutputStream(tmp)) {
            long skipped = 0;
            while (skipped < readOffset) {
                long s = in.skip(readOffset - skipped);
                if (s <= 0) throw new EOFException("Could not skip to unread spool region");
                skipped += s;
            }
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) >= 0) {
                out.write(buf, 0, n);
                copied += n;
            }
        }
        if (!file.delete() || !tmp.renameTo(file)) {
            throw new IOException("Could not compact MQTT spool");
        }
        readOffset = 0;
        writeOffset = copied;
        pendingCount = countRecords(0, writeOffset);
        savePrefs();
    }

    private void savePrefs() {
        prefs.edit()
                .putLong(KEY_READ_OFFSET, readOffset)
                .putLong(KEY_PENDING_COUNT, pendingCount)
                .apply();
    }

    private void closeQuietly() {
        try { close(); } catch (Exception ignored) {}
    }

    private static void writeIntLE(BufferedOutputStream out, int v) throws IOException {
        out.write(v & 0xff);
        out.write((v >>> 8) & 0xff);
        out.write((v >>> 16) & 0xff);
        out.write((v >>> 24) & 0xff);
    }

    private static int readIntLE(RandomAccessFile raf) throws IOException {
        int b0 = raf.read();
        int b1 = raf.read();
        int b2 = raf.read();
        int b3 = raf.read();
        if ((b0 | b1 | b2 | b3) < 0) throw new EOFException();
        return b0 | (b1 << 8) | (b2 << 16) | (b3 << 24);
    }
}
