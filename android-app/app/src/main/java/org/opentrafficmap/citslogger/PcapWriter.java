package org.opentrafficmap.citslogger;

import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;

final class PcapWriter implements Closeable {
    private static final int DLT_IEEE802_11 = 105;
    private static final int FLUSH_EVERY_PACKETS = 128;
    private static final long FLUSH_EVERY_MS = 2000;

    private final BufferedOutputStream out;
    private boolean closed;
    private long packetsSinceFlush;
    private long lastFlushMs;

    PcapWriter(OutputStream out) throws IOException {
        this.out = new BufferedOutputStream(out, 64 * 1024);
        writeGlobalHeader();
        lastFlushMs = System.currentTimeMillis();
    }

    synchronized void writePacket(CitsPacket packet) throws IOException {
        if (closed) return;
        writeIntLE((int) packet.seconds);
        writeIntLE(packet.microseconds);
        writeIntLE(packet.payload.length);
        writeIntLE(packet.payload.length);
        out.write(packet.payload);
        packetsSinceFlush++;
        maybeFlush(false);
    }

    synchronized void maybeFlush(boolean force) throws IOException {
        if (closed) return;
        long now = System.currentTimeMillis();
        if (force || packetsSinceFlush >= FLUSH_EVERY_PACKETS || now - lastFlushMs >= FLUSH_EVERY_MS) {
            out.flush();
            packetsSinceFlush = 0;
            lastFlushMs = now;
        }
    }

    synchronized void flush() throws IOException {
        maybeFlush(true);
    }

    private void writeGlobalHeader() throws IOException {
        writeIntLE(0xA1B2C3D4);
        writeShortLE(2);
        writeShortLE(4);
        writeIntLE(0);
        writeIntLE(0);
        writeIntLE(65535);
        writeIntLE(DLT_IEEE802_11);
    }

    private void writeShortLE(int v) throws IOException {
        out.write(v & 0xff);
        out.write((v >>> 8) & 0xff);
    }

    private void writeIntLE(int v) throws IOException {
        out.write(v & 0xff);
        out.write((v >>> 8) & 0xff);
        out.write((v >>> 16) & 0xff);
        out.write((v >>> 24) & 0xff);
    }

    @Override
    public synchronized void close() throws IOException {
        if (closed) return;
        closed = true;
        out.flush();
        out.close();
    }
}
