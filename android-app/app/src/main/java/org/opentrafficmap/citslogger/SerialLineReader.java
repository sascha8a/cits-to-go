package org.opentrafficmap.citslogger;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

final class SerialLineReader implements Runnable {
    interface Listener {
        void onSerialLine(String line);
        void onSerialError(Exception e);
    }

    private static final int MAX_LINE_BYTES = 32768;
    private final UsbCdcSerial serial;
    private final Listener listener;
    private volatile boolean running = true;

    SerialLineReader(UsbCdcSerial serial, Listener listener) {
        this.serial = serial;
        this.listener = listener;
    }

    void stop() { running = false; }

    @Override
    public void run() {
        byte[] buf = new byte[4096];
        ByteArrayOutputStream line = new ByteArrayOutputStream(8192);
        try {
            while (running) {
                int n = serial.read(buf, 500);
                for (int i = 0; i < n; i++) {
                    int b = buf[i] & 0xff;
                    if (b == '\n') {
                        String s = new String(line.toByteArray(), StandardCharsets.US_ASCII).trim();
                        line.reset();
                        if (!s.isEmpty()) listener.onSerialLine(s);
                    } else if (b != '\r') {
                        if (line.size() < MAX_LINE_BYTES) {
                            line.write(b);
                        } else {
                            line.reset();
                            listener.onSerialError(new Exception("Serial line exceeded " + MAX_LINE_BYTES + " bytes and was dropped"));
                        }
                    }
                }
            }
        } catch (Exception e) {
            if (running) listener.onSerialError(e);
        }
    }
}
