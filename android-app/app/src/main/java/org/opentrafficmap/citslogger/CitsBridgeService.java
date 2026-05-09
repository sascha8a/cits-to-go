package org.opentrafficmap.citslogger;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;

import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

public class CitsBridgeService extends Service implements SerialCitsReader.Listener {
    static final String ACTION_START_USB = "org.opentrafficmap.citslogger.action.START_USB";
    static final String ACTION_STOP_USB = "org.opentrafficmap.citslogger.action.STOP_USB";
    static final String ACTION_START_MQTT = "org.opentrafficmap.citslogger.action.START_MQTT";
    static final String ACTION_STOP_MQTT = "org.opentrafficmap.citslogger.action.STOP_MQTT";
    static final String ACTION_START_PCAP = "org.opentrafficmap.citslogger.action.START_PCAP";
    static final String ACTION_STOP_PCAP = "org.opentrafficmap.citslogger.action.STOP_PCAP";
    static final String ACTION_STOP_ALL = "org.opentrafficmap.citslogger.action.STOP_ALL";
    static final String ACTION_STATUS = "org.opentrafficmap.citslogger.action.STATUS";

    static final String EXTRA_DEVICE_NAME = "deviceName";
    static final String EXTRA_MQTT_URI = "mqttUri";
    static final String EXTRA_NODE_ID = "nodeId";
    static final String EXTRA_PCAP_URI = "pcapUri";

    private static final String CHANNEL_ID = "cits_bridge";
    private static final int NOTIFICATION_ID = 23;
    private static final int MQTT_FLUSH_INTERVAL_MS = 250;
    private static final int MQTT_MAX_PACKETS_PER_FLUSH = 100;
    private static final int MQTT_MAX_BACKOFF_MS = 60000;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Object pcapLock = new Object();
    private final Object mqttLock = new Object();

    private UsbManager usbManager;
    private UsbCdcSerial serial;
    private SerialCitsReader reader;
    private Thread readerThread;
    private PcapWriter pcapWriter;
    private MqttSpool mqttSpool;
    private MiniMqttClient mqttClient = new MiniMqttClient();

    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;

    private String firmwareHardwareVariant = "android-bridge";
    private String firmwareVersion = "android-bridge";
    private String currentNodeId = "unknown";
    private String mqttUri = "";
    private String mqttNodeId = "";
    private boolean mqttEnabled;
    private boolean mqttConnecting;
    private boolean mqttFlushRunning;
    private long mqttReconnectDelayMs = 1000;

    private long packetCount;
    private long pcapCount;
    private long mqttCount;
    private long truncatedCount;
    private long mqttDropCount;
    private long binaryPacketCount;

    private final Runnable mqttFlushRunnable = new Runnable() {
        @Override
        public void run() {
            if (mqttEnabled) {
                if (!mqttClient.isConnected()) ensureMqttConnected();
                flushMqttSpoolAsync();
            }
            handler.postDelayed(this, MQTT_FLUSH_INTERVAL_MS);
        }
    };

    private final Runnable healthRunnable = new Runnable() {
        @Override
        public void run() {
            if (mqttEnabled && !mqttClient.isConnected()) ensureMqttConnected();
            flushPcapQuietly();
            broadcastStatus(null);
            handler.postDelayed(this, 15000);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        usbManager = (UsbManager) getSystemService(USB_SERVICE);
        try {
            mqttSpool = new MqttSpool(this);
        } catch (Exception e) {
            mqttSpool = null;
            mqttDropCount++;
            broadcastStatus("MQTT spool unavailable: " + e.getMessage());
        }
        createNotificationChannel();
        startAsForeground();
        acquireLocks();
        handler.postDelayed(mqttFlushRunnable, MQTT_FLUSH_INTERVAL_MS);
        handler.postDelayed(healthRunnable, 15000);
        broadcastStatus("Bridge service started");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || intent.getAction() == null) {
            broadcastStatus(null);
            return START_STICKY;
        }

        String action = intent.getAction();
        if (ACTION_START_USB.equals(action)) {
            openUsbByName(intent.getStringExtra(EXTRA_DEVICE_NAME));
        } else if (ACTION_STOP_USB.equals(action)) {
            closeSerial();
        } else if (ACTION_START_MQTT.equals(action)) {
            startMqtt(intent.getStringExtra(EXTRA_MQTT_URI), intent.getStringExtra(EXTRA_NODE_ID));
        } else if (ACTION_STOP_MQTT.equals(action)) {
            stopMqtt(true);
        } else if (ACTION_START_PCAP.equals(action)) {
            startPcap(intent.getStringExtra(EXTRA_PCAP_URI));
        } else if (ACTION_STOP_PCAP.equals(action)) {
            closePcap();
        } else if (ACTION_STOP_ALL.equals(action)) {
            stopEverything();
            stopSelf();
        }

        updateNotification();
        broadcastStatus(null);
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        stopEverything();
        releaseLocks();
        try { if (mqttSpool != null) mqttSpool.close(); } catch (Exception ignored) {}
        super.onDestroy();
    }

    private void startAsForeground() {
        Notification n = buildNotification("Starting");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, n,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        } else {
            startForeground(NOTIFICATION_ID, n);
        }
    }

    private void updateNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(NOTIFICATION_ID, buildNotification(summaryText()));
    }

    private Notification buildNotification(String content) {
        Intent openIntent = new Intent(this, MainActivity.class);
        PendingIntent openPi = PendingIntent.getActivity(this, 0, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent stopIntent = new Intent(this, CitsBridgeService.class).setAction(ACTION_STOP_ALL);
        PendingIntent stopPi = PendingIntent.getService(this, 1, stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentTitle("C-ITS USB Bridge")
                .setContentText(content)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(content))
                .setOngoing(true)
                .setContentIntent(openPi)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPi)
                .build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) return;
        NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID,
                "C-ITS bridge",
                NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("Keeps USB serial capture and MQTT forwarding active while the phone is locked.");
        nm.createNotificationChannel(ch);
    }

    private void acquireLocks() {
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm != null) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "citslogger:bridge");
                wakeLock.setReferenceCounted(false);
                wakeLock.acquire();
            }
        } catch (Exception ignored) {}

        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
            if (wm != null) {
                wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "citslogger:mqtt");
                wifiLock.setReferenceCounted(false);
                wifiLock.acquire();
            }
        } catch (Exception ignored) {}
    }

    private void releaseLocks() {
        try { if (wakeLock != null && wakeLock.isHeld()) wakeLock.release(); } catch (Exception ignored) {}
        try { if (wifiLock != null && wifiLock.isHeld()) wifiLock.release(); } catch (Exception ignored) {}
        wakeLock = null;
        wifiLock = null;
    }

    private void openUsbByName(String deviceName) {
        closeSerial();
        UsbDevice device = findDevice(deviceName);
        if (device == null) {
            broadcastStatus("USB device not found");
            return;
        }
        if (!usbManager.hasPermission(device)) {
            broadcastStatus("USB permission missing for " + device.getDeviceName());
            return;
        }
        try {
            serial = new UsbCdcSerial(usbManager, device);
            serial.open(115200);
            reader = new SerialCitsReader(serial, this);
            readerThread = new Thread(reader, "serial-cits-reader");
            readerThread.start();
            broadcastStatus("USB connected: " + serial.describe());
        } catch (Exception e) {
            closeSerial();
            broadcastStatus("USB open failed: " + e.getMessage());
        }
    }

    private UsbDevice findDevice(String deviceName) {
        ArrayList<UsbDevice> devices = new ArrayList<>(usbManager.getDeviceList().values());
        if (devices.isEmpty()) return null;
        if (deviceName != null) {
            for (UsbDevice d : devices) {
                if (deviceName.equals(d.getDeviceName())) return d;
            }
        }
        UsbDevice best = devices.get(0);
        for (UsbDevice d : devices) {
            if (d.getVendorId() == 0x303A) {
                best = d;
                break;
            }
        }
        return best;
    }

    private void closeSerial() {
        if (reader != null) reader.stop();
        reader = null;
        if (serial != null) serial.close();
        serial = null;
        readerThread = null;
        broadcastStatus("USB disconnected");
    }

    private void startPcap(String uriString) {
        if (uriString == null || uriString.isEmpty()) {
            broadcastStatus("PCAP URI missing");
            return;
        }
        synchronized (pcapLock) {
            closePcapLocked(false);
            try {
                Uri uri = Uri.parse(uriString);
                OutputStream out = getContentResolver().openOutputStream(uri, "w");
                if (out == null) throw new Exception("Could not open output stream");
                pcapWriter = new PcapWriter(out);
                broadcastStatus("PCAP recording started: " + uri);
            } catch (Exception e) {
                pcapWriter = null;
                broadcastStatus("PCAP open failed: " + e.getMessage());
            }
        }
    }

    private void closePcap() {
        synchronized (pcapLock) {
            closePcapLocked(true);
        }
    }

    private void closePcapLocked(boolean log) {
        if (pcapWriter != null) {
            try { pcapWriter.close(); } catch (Exception ignored) {}
            pcapWriter = null;
            if (log) broadcastStatus("PCAP closed");
        }
    }

    private void flushPcapQuietly() {
        synchronized (pcapLock) {
            if (pcapWriter != null) {
                try { pcapWriter.flush(); } catch (Exception ignored) {}
            }
        }
    }

    private void startMqtt(String uri, String nodeId) {
        mqttUri = uri == null ? "" : uri.trim();
        mqttNodeId = nodeId == null || nodeId.trim().isEmpty() ? currentNodeId : nodeId.trim();
        mqttEnabled = true;
        mqttReconnectDelayMs = 1000;
        broadcastStatus("MQTT forwarding enabled; packets are spooled and published every " + MQTT_FLUSH_INTERVAL_MS + " ms");
        ensureMqttConnected();
    }

    private void stopMqtt(boolean clearSpool) {
        mqttEnabled = false;
        synchronized (mqttLock) {
            mqttConnecting = false;
            mqttFlushRunning = false;
        }
        try { mqttClient.close(); } catch (Exception ignored) {}
        if (clearSpool && mqttSpool != null) mqttSpool.clear();
        broadcastStatus("MQTT disconnected" + (clearSpool ? "; spool cleared" : ""));
    }

    private void ensureMqttConnected() {
        synchronized (mqttLock) {
            if (!mqttEnabled || mqttConnecting || mqttClient.isConnected()) return;
            mqttConnecting = true;
        }
        new Thread(() -> {
            try {
                String nid = mqttNodeId == null || mqttNodeId.trim().isEmpty() ? currentNodeId : mqttNodeId.trim();
                mqttClient.connect(mqttUri, nid, firmwareHardwareVariant, firmwareVersion);
                mqttReconnectDelayMs = 1000;
                broadcastStatus("MQTT connected; packet topic: its/" + nid + "/packet");
            } catch (Exception e) {
                broadcastStatus("MQTT connect failed: " + e.getMessage());
                scheduleMqttReconnect();
            } finally {
                synchronized (mqttLock) { mqttConnecting = false; }
                updateNotification();
                broadcastStatus(null);
            }
        }, "mqtt-connect").start();
    }

    private void scheduleMqttReconnect() {
        if (!mqttEnabled) return;
        long delay = mqttReconnectDelayMs;
        mqttReconnectDelayMs = Math.min(mqttReconnectDelayMs * 2, MQTT_MAX_BACKOFF_MS);
        handler.postDelayed(this::ensureMqttConnected, delay);
    }

    private void spoolMqtt(byte[] payload) {
        if (!mqttEnabled) return;
        if (mqttSpool == null) {
            mqttDropCount++;
            return;
        }
        try {
            mqttSpool.append(payload);
        } catch (Exception e) {
            mqttDropCount++;
            broadcastStatus("MQTT spool append failed: " + e.getMessage());
        }
    }

    private void flushMqttSpoolAsync() {
        synchronized (mqttLock) {
            if (!mqttEnabled || mqttFlushRunning) return;
            mqttFlushRunning = true;
        }
        new Thread(() -> {
            try {
                if (!mqttEnabled) return;
                if (!mqttClient.isConnected()) {
                    ensureMqttConnected();
                    return;
                }
                if (mqttSpool == null) return;

                List<MqttSpool.Record> batch = mqttSpool.readBatch(MQTT_MAX_PACKETS_PER_FLUSH);
                if (batch.isEmpty()) return;

                ArrayList<MqttSpool.Record> sent = new ArrayList<>();
                try {
                    for (MqttSpool.Record record : batch) {
                        if (!mqttEnabled || !mqttClient.isConnected()) break;
                        mqttClient.publishPacket(record.payload);
                        sent.add(record);
                    }
                    if (!sent.isEmpty()) {
                        // Flush once per interval/burst instead of once per packet.
                        mqttClient.flush();
                        MqttSpool.Record last = sent.get(sent.size() - 1);
                        mqttSpool.ackBatch(last.nextOffset, sent.size());
                        mqttCount += sent.size();
                    }
                } catch (Exception e) {
                    try { mqttClient.close(); } catch (Exception ignored) {}
                    broadcastStatus("MQTT publish failed: " + e.getMessage() + "; keeping packet in spool and reconnecting");
                    scheduleMqttReconnect();
                    return;
                }
                if (!sent.isEmpty() && mqttSpool.pendingCount() > 0) {
                    // More data remains; the interval timer will send the next burst.
                    broadcastStatus(null);
                }
            } catch (Exception e) {
                broadcastStatus("MQTT spool read failed: " + e.getMessage());
                scheduleMqttReconnect();
            } finally {
                synchronized (mqttLock) { mqttFlushRunning = false; }
                updateNotification();
                broadcastStatus(null);
            }
        }, "mqtt-spool-flush").start();
    }

    @Override
    public void onSerialPacket(CitsPacket packet) {
        handlePacket(packet, true);
    }

    @Override
    public void onSerialLine(String line) {
        if (line.contains("CITS,")) {
            handlePacketLine(line);
        } else {
            handleSerialLine(line);
        }
    }

    @Override
    public void onSerialError(Exception e) {
        broadcastStatus("Serial error: " + e.getMessage());
    }

    private void handleSerialLine(String line) {
        CitsLineParser.Meta meta = CitsLineParser.parseMeta(line);
        if (meta != null) {
            firmwareHardwareVariant = meta.hardwareVariant;
            firmwareVersion = meta.firmwareVersion;
            currentNodeId = meta.nodeId;
            if (mqttNodeId == null || mqttNodeId.isEmpty() || "unknown".equals(mqttNodeId)) {
                mqttNodeId = meta.nodeId;
            }
            broadcastStatus("Metadata: nodeId=" + meta.nodeId + " packetTopic=" + meta.packetTopic);
            return;
        }
        if (line.startsWith("CITSPROTO,")) {
            broadcastStatus("USB protocol: " + line.substring("CITSPROTO,".length()));
            return;
        }
        if (line.startsWith("I (") || line.contains(") ")) return;
        broadcastStatus(line.length() > 120 ? line.substring(0, 120) + "…" : line);
    }

    private void handlePacketLine(String line) {
        CitsPacket packet;
        try {
            packet = CitsLineParser.parsePacket(line);
        } catch (Exception e) {
            broadcastStatus("Bad CITS line: " + e.getMessage());
            return;
        }
        if (packet == null) return;
        handlePacket(packet, false);
    }

    private void handlePacket(CitsPacket packet, boolean binary) {
        packetCount++;
        if (binary) binaryPacketCount++;
        if (packet.truncated) truncatedCount++;

        synchronized (pcapLock) {
            if (pcapWriter != null) {
                try {
                    pcapWriter.writePacket(packet);
                    pcapCount++;
                } catch (Exception e) {
                    closePcapLocked(false);
                    broadcastStatus("PCAP write failed: " + e.getMessage());
                }
            }
        }

        spoolMqtt(packet.payload);

        if ((packetCount % 25) == 1) {
            broadcastStatus("Packets=" + packetCount + " last=" + packet.payload.length + "B rssi=" + packet.rssiDbm + "dBm freq=" + packet.frequencyMhz + "MHz" + (binary ? " binary" : " ascii"));
        } else {
            broadcastStatus(null);
        }
        updateNotification();
    }

    private void stopEverything() {
        closeSerial();
        closePcap();
        stopMqtt(false);
    }

    private String summaryText() {
        long queued = mqttSpool == null ? 0 : mqttSpool.pendingCount();
        return "USB " + (serial != null ? "connected" : "off") +
                " • PCAP " + (pcapWriter != null ? "recording" : "off") +
                " • MQTT " + (mqttClient.isConnected() ? "connected" : (mqttEnabled ? "reconnecting" : "off")) +
                " • packets " + packetCount +
                (queued > 0 ? " • spool " + queued : "");
    }

    private void broadcastStatus(String log) {
        Intent i = new Intent(ACTION_STATUS).setPackage(getPackageName());
        i.putExtra("usb", serial != null);
        i.putExtra("pcap", pcapWriter != null);
        i.putExtra("mqtt", mqttClient.isConnected());
        i.putExtra("mqttEnabled", mqttEnabled);
        long queued = mqttSpool == null ? 0 : mqttSpool.pendingCount();
        i.putExtra("mqttQueue", queued > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) queued);
        i.putExtra("packetCount", packetCount);
        i.putExtra("pcapCount", pcapCount);
        i.putExtra("mqttCount", mqttCount);
        i.putExtra("truncatedCount", truncatedCount);
        i.putExtra("mqttDropCount", mqttDropCount);
        i.putExtra("binaryPacketCount", binaryPacketCount);
        i.putExtra("nodeId", currentNodeId);
        if (log != null && !log.isEmpty()) i.putExtra("log", log);
        sendBroadcast(i);
    }
}
