package org.opentrafficmap.citslogger;

import javax.net.SocketFactory;
import javax.net.ssl.SSLSocketFactory;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

final class MiniMqttClient implements Closeable {
    private static final int KEEPALIVE_SECONDS = 60;

    private Socket socket;
    private InputStream in;
    private BufferedOutputStream out;
    private Thread pingThread;
    private volatile boolean running;
    private String topicPrefix;

    synchronized void connect(String mqttUri, String nodeId, String hardwareVariant, String firmwareVersion) throws Exception {
        close();
        if (nodeId == null || nodeId.trim().isEmpty()) throw new IllegalArgumentException("Node ID is empty");

        URI uri = URI.create(mqttUri.trim());
        String scheme = uri.getScheme() == null ? "mqtt" : uri.getScheme().toLowerCase(Locale.ROOT);
        boolean tls;
        int defaultPort;
        if ("mqtt".equals(scheme)) {
            tls = false;
            defaultPort = 1883;
        } else if ("mqtts".equals(scheme) || "ssl".equals(scheme)) {
            tls = true;
            defaultPort = 8883;
        } else {
            throw new IllegalArgumentException("Use mqtt:// or mqtts:// URI");
        }
        String host = uri.getHost();
        if (host == null || host.isEmpty()) throw new IllegalArgumentException("MQTT host is empty");
        int port = uri.getPort() > 0 ? uri.getPort() : defaultPort;

        SocketFactory factory = tls ? SSLSocketFactory.getDefault() : SocketFactory.getDefault();
        socket = factory.createSocket(host, port);
        socket.setTcpNoDelay(true);
        socket.setKeepAlive(true);
        socket.setSoTimeout(15000);
        in = socket.getInputStream();
        out = new BufferedOutputStream(socket.getOutputStream(), 64 * 1024);

        topicPrefix = "its/" + nodeId.trim() + "/";
        String clientId = "cits-android-" + nodeId.trim();
        String user = null;
        String pass = null;
        if (uri.getUserInfo() != null) {
            int colon = uri.getUserInfo().indexOf(':');
            if (colon >= 0) {
                user = uri.getUserInfo().substring(0, colon);
                pass = uri.getUserInfo().substring(colon + 1);
            } else {
                user = uri.getUserInfo();
            }
        }

        sendConnect(clientId, topicPrefix + "status", "offline".getBytes(StandardCharsets.UTF_8), user, pass);
        readConnAck();
        running = true;
        startPingThread();

        publish(topicPrefix + "status", "online".getBytes(StandardCharsets.UTF_8), true, false);
        publish(topicPrefix + "info", makeInfoPayload(nodeId, hardwareVariant, firmwareVersion), false, false);
        publish(topicPrefix + "stats", "{}".getBytes(StandardCharsets.UTF_8), false, true);
    }

    synchronized void publishPacket(byte[] payload) throws IOException {
        if (!isConnected()) throw new IOException("MQTT is not connected");
        publish(topicPrefix + "packet", payload, false, false);
    }

    synchronized void flush() throws IOException {
        if (out != null) out.flush();
    }

    synchronized boolean isConnected() {
        return running && socket != null && socket.isConnected() && !socket.isClosed();
    }

    private byte[] makeInfoPayload(String nodeId, String hardwareVariant, String firmwareVersion) {
        String emac = nodeId;
        if (nodeId.matches("(?i)[0-9a-f]{12}")) {
            emac = nodeId.substring(0, 2) + ":" + nodeId.substring(2, 4) + ":" +
                    nodeId.substring(4, 6) + ":" + nodeId.substring(6, 8) + ":" +
                    nodeId.substring(8, 10) + ":" + nodeId.substring(10, 12);
        }
        String hwv = safeJson(hardwareVariant == null || hardwareVariant.isEmpty() ? "android-bridge" : hardwareVariant);
        String ver = safeJson(firmwareVersion == null || firmwareVersion.isEmpty() ? "android-bridge" : firmwareVersion);
        String json = "{\"emac\":\"" + safeJson(emac) + "\",\"ver\":\"" + ver + "\",\"hwv\":\"" + hwv + "\"}";
        return json.getBytes(StandardCharsets.UTF_8);
    }

    private static String safeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private void sendConnect(String clientId, String willTopic, byte[] willPayload,
                             String username, String password) throws IOException {
        ByteArrayOutputStream vh = new ByteArrayOutputStream();
        writeUtf8(vh, "MQTT");
        vh.write(4);
        int flags = 0x02 | 0x04 | 0x20; // clean session, will flag, retained will; QoS 0
        if (password != null) flags |= 0x40;
        if (username != null) flags |= 0x80;
        vh.write(flags);
        writeShort(vh, KEEPALIVE_SECONDS);

        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        writeUtf8(payload, clientId);
        writeUtf8(payload, willTopic);
        writeBinary(payload, willPayload);
        if (username != null) writeUtf8(payload, username);
        if (password != null) writeUtf8(payload, password);

        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(vh.toByteArray());
        body.write(payload.toByteArray());
        writePacket(0x10, body.toByteArray(), true);
    }

    private void readConnAck() throws IOException {
        DataInputStream din = new DataInputStream(in);
        int header = din.readUnsignedByte();
        if (header != 0x20) throw new IOException("Expected CONNACK, got 0x" + Integer.toHexString(header));
        int rem = readRemainingLength(din);
        if (rem != 2) throw new IOException("Bad CONNACK length " + rem);
        int flags = din.readUnsignedByte();
        int code = din.readUnsignedByte();
        if (code != 0) throw new IOException("MQTT CONNACK refused, code " + code + ", flags " + flags);
    }

    private synchronized void publish(String topic, byte[] payload, boolean retain, boolean flush) throws IOException {
        if (out == null) throw new IOException("MQTT is not connected");
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        writeUtf8(body, topic);
        body.write(payload);
        writePacket(retain ? 0x31 : 0x30, body.toByteArray(), flush);
    }

    private void writePacket(int fixedHeader, byte[] body, boolean flush) throws IOException {
        out.write(fixedHeader);
        writeRemainingLength(out, body.length);
        out.write(body);
        if (flush) out.flush();
    }

    private void startPingThread() {
        pingThread = new Thread(() -> {
            while (running) {
                try {
                    Thread.sleep(KEEPALIVE_SECONDS * 500L);
                    synchronized (MiniMqttClient.this) {
                        if (out != null && running) {
                            out.write(0xC0);
                            out.write(0x00);
                            out.flush();
                        }
                    }
                } catch (Exception ignored) {
                    running = false;
                    break;
                }
            }
        }, "mqtt-ping");
        pingThread.setDaemon(true);
        pingThread.start();
    }

    private static void writeUtf8(ByteArrayOutputStream out, String value) throws IOException {
        byte[] b = value.getBytes(StandardCharsets.UTF_8);
        writeShort(out, b.length);
        out.write(b);
    }

    private static void writeBinary(ByteArrayOutputStream out, byte[] value) throws IOException {
        writeShort(out, value.length);
        out.write(value);
    }

    private static void writeShort(ByteArrayOutputStream out, int value) {
        out.write((value >>> 8) & 0xff);
        out.write(value & 0xff);
    }

    private static void writeRemainingLength(OutputStream out, int value) throws IOException {
        do {
            int encodedByte = value % 128;
            value /= 128;
            if (value > 0) encodedByte |= 128;
            out.write(encodedByte);
        } while (value > 0);
    }

    private static int readRemainingLength(DataInputStream in) throws IOException {
        int multiplier = 1;
        int value = 0;
        int encodedByte;
        do {
            encodedByte = in.readUnsignedByte();
            value += (encodedByte & 127) * multiplier;
            multiplier *= 128;
            if (multiplier > 128 * 128 * 128) throw new IOException("Malformed MQTT remaining length");
        } while ((encodedByte & 128) != 0);
        return value;
    }

    @Override
    public synchronized void close() {
        running = false;
        try {
            if (out != null) {
                out.write(0xE0);
                out.write(0x00);
                out.flush();
            }
        } catch (Exception ignored) {}
        try { if (socket != null) socket.close(); } catch (Exception ignored) {}
        socket = null;
        in = null;
        out = null;
    }
}
