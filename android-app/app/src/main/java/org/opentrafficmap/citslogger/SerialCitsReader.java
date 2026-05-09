package org.opentrafficmap.citslogger;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.zip.CRC32;

/**
 * Dedicated USB reader/parser for the ESP32-C5 bridge stream.
 *
 * It accepts both protocols:
 *   - old ASCII lines beginning with CITS,
 *   - optimized binary frames beginning with "CITS" followed by version byte 1.
 *
 * Binary frame layout is little-endian:
 *   magic[4]="CITS", version[1]=1, flags[1], hdrLen[2], sec[4], usec[4],
 *   freqMHz[2], rssi[1], reserved[1], caplen[2], origLen[2], crc32[4], payload[].
 */
final class SerialCitsReader implements Runnable {
    interface Listener {
        void onSerialPacket(CitsPacket packet);
        void onSerialLine(String line);
        void onSerialError(Exception e);
    }

    private static final byte[] MAGIC = new byte[]{'C', 'I', 'T', 'S'};
    private static final int BINARY_VERSION = 1;
    private static final int MIN_BINARY_HEADER = 28;
    private static final int MAX_TEXT_LINE_BYTES = 32768;
    private static final int MAX_PENDING_BYTES = 512 * 1024;

    private final UsbCdcSerial serial;
    private final Listener listener;
    private volatile boolean running = true;
    private byte[] pending = new byte[32768];
    private int pendingLen;

    SerialCitsReader(UsbCdcSerial serial, Listener listener) {
        this.serial = serial;
        this.listener = listener;
    }

    void stop() {
        running = false;
    }

    @Override
    public void run() {
        byte[] buf = new byte[16384];
        try {
            while (running) {
                int n = serial.read(buf, 500);
                if (n > 0) {
                    append(buf, n);
                    processPending();
                }
            }
        } catch (Exception e) {
            if (running) listener.onSerialError(e);
        }
    }

    private void append(byte[] src, int n) {
        if (pendingLen + n > MAX_PENDING_BYTES) {
            int keep = Math.min(pendingLen, 4);
            if (keep > 0) System.arraycopy(pending, pendingLen - keep, pending, 0, keep);
            pendingLen = keep;
            listener.onSerialError(new Exception("USB parser buffer exceeded " + MAX_PENDING_BYTES + " bytes; resyncing"));
        }
        ensureCapacity(pendingLen + n);
        System.arraycopy(src, 0, pending, pendingLen, n);
        pendingLen += n;
    }

    private void ensureCapacity(int needed) {
        if (needed <= pending.length) return;
        int next = pending.length;
        while (next < needed) next *= 2;
        pending = Arrays.copyOf(pending, next);
    }

    private void processPending() {
        while (running && pendingLen > 0) {
            int magicAt = indexOfMagic();
            if (magicAt > 0) {
                int nl = indexOfByte((byte) '\n', 0, magicAt);
                if (nl >= 0) {
                    emitAsciiLine(nl);
                    consume(nl + 1);
                } else {
                    consume(magicAt);
                }
                continue;
            }

            if (magicAt < 0) {
                int nl = indexOfByte((byte) '\n', 0, pendingLen);
                if (nl >= 0) {
                    emitAsciiLine(nl);
                    consume(nl + 1);
                } else if (pendingLen > 4) {
                    consume(pendingLen - 4);
                }
                return;
            }

            // pending starts with "CITS".
            if (pendingLen < 5) return;
            int discriminator = pending[4] & 0xff;
            if (discriminator == BINARY_VERSION) {
                if (!tryEmitBinaryFrame()) return;
            } else if (discriminator == ',' || discriminator == 'M' || discriminator == 'P') {
                int nl = indexOfByte((byte) '\n', 0, pendingLen);
                if (nl < 0) {
                    if (pendingLen > MAX_TEXT_LINE_BYTES) {
                        consume(1);
                        listener.onSerialError(new Exception("CITS text line exceeded " + MAX_TEXT_LINE_BYTES + " bytes; resyncing"));
                    }
                    return;
                }
                emitAsciiLine(nl);
                consume(nl + 1);
            } else {
                // Looks like stale/corrupt bytes. Drop one byte and keep looking for the next magic.
                consume(1);
            }
        }
    }

    private boolean tryEmitBinaryFrame() {
        if (pendingLen < MIN_BINARY_HEADER) return false;

        int headerLen = u16le(6);
        if (headerLen < MIN_BINARY_HEADER || headerLen > 128) {
            consume(1);
            listener.onSerialError(new Exception("Bad CITS binary header length " + headerLen + "; resyncing"));
            return true;
        }
        if (pendingLen < headerLen) return false;

        int caplen = u16le(20);
        int origLen = u16le(22);
        int total = headerLen + caplen;
        if (caplen > 65535 || total < headerLen) {
            consume(1);
            listener.onSerialError(new Exception("Bad CITS binary payload length " + caplen + "; resyncing"));
            return true;
        }
        if (pendingLen < total) return false;

        long expectedCrc = u32le(24);
        if (expectedCrc != 0) {
            CRC32 crc = new CRC32();
            crc.update(pending, headerLen, caplen);
            if ((crc.getValue() & 0xffffffffL) != expectedCrc) {
                consume(1);
                listener.onSerialError(new Exception("Bad CITS binary CRC; resyncing"));
                return true;
            }
        }

        long seconds = u32le(8);
        int usec = (int) u32le(12);
        int freq = u16le(16);
        int rssi = (byte) pending[18];
        boolean truncated = (pending[5] & 0x01) != 0 || origLen != caplen;
        byte[] payload = Arrays.copyOfRange(pending, headerLen, total);
        consume(total);
        listener.onSerialPacket(new CitsPacket(seconds, usec, freq, rssi, caplen, origLen, truncated, payload));
        return true;
    }

    private void emitAsciiLine(int newlineIndex) {
        int len = newlineIndex;
        if (len > 0 && pending[len - 1] == '\r') len--;
        if (len <= 0) return;
        String line = new String(pending, 0, len, StandardCharsets.US_ASCII).trim();
        if (!line.isEmpty()) listener.onSerialLine(line);
    }

    private int indexOfMagic() {
        outer:
        for (int i = 0; i <= pendingLen - MAGIC.length; i++) {
            for (int j = 0; j < MAGIC.length; j++) {
                if (pending[i + j] != MAGIC[j]) continue outer;
            }
            return i;
        }
        return -1;
    }

    private int indexOfByte(byte value, int start, int endExclusive) {
        for (int i = start; i < endExclusive && i < pendingLen; i++) {
            if (pending[i] == value) return i;
        }
        return -1;
    }

    private void consume(int n) {
        if (n <= 0) return;
        if (n >= pendingLen) {
            pendingLen = 0;
            return;
        }
        System.arraycopy(pending, n, pending, 0, pendingLen - n);
        pendingLen -= n;
    }

    private int u16le(int off) {
        return (pending[off] & 0xff) | ((pending[off + 1] & 0xff) << 8);
    }

    private long u32le(int off) {
        return ((long) pending[off] & 0xffL) |
                (((long) pending[off + 1] & 0xffL) << 8) |
                (((long) pending[off + 2] & 0xffL) << 16) |
                (((long) pending[off + 3] & 0xffL) << 24);
    }
}
