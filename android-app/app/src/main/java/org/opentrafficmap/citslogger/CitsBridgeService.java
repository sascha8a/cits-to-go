package org.opentrafficmap.citslogger;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
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
import android.os.SystemClock;

import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;
import androidx.core.content.ContextCompat;

import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class CitsBridgeService extends Service implements SerialLineReader.Listener {
    static final String ACTION_START_USB = "org.opentrafficmap.citslogger.action.START_USB";
    static final String ACTION_STOP_USB = "org.opentrafficmap.citslogger.action.STOP_USB";
    static final String ACTION_START_MQTT = "org.opentrafficmap.citslogger.action.START_MQTT";
    static final String ACTION_STOP_MQTT = "org.opentrafficmap.citslogger.action.STOP_MQTT";
    static final String ACTION_START_PCAP = "org.opentrafficmap.citslogger.action.START_PCAP";
    static final String ACTION_STOP_PCAP = "org.opentrafficmap.citslogger.action.STOP_PCAP";
    static final String ACTION_STOP_ALL = "org.opentrafficmap.citslogger.action.STOP_ALL";
    static final String ACTION_SET_DEVICE_NOTIFICATIONS = "org.opentrafficmap.citslogger.action.SET_DEVICE_NOTIFICATIONS";
    static final String ACTION_CLEAR_SEEN_DEVICES = "org.opentrafficmap.citslogger.action.CLEAR_SEEN_DEVICES";
    static final String ACTION_REQUEST_STATUS = "org.opentrafficmap.citslogger.action.REQUEST_STATUS";
    static final String ACTION_STATUS = "org.opentrafficmap.citslogger.action.STATUS";

    static final String EXTRA_DEVICE_NAME = "deviceName";
    static final String EXTRA_MQTT_URI = "mqttUri";
    static final String EXTRA_NODE_ID = "nodeId";
    static final String EXTRA_PCAP_URI = "pcapUri";
    static final String EXTRA_DEVICE_NOTIFICATIONS_ENABLED = "deviceNotificationsEnabled";

    private static final String CHANNEL_ID = "cits_bridge";
    private static final String DEVICE_CHANNEL_ID = "cits_device_discovery";
    private static final int NOTIFICATION_ID = 23;
    private static final int DEVICE_NOTIFICATION_BASE_ID = 10000;
    private static final int MQTT_FLUSH_INTERVAL_MS = 250;
    private static final int MQTT_MAX_PACKETS_PER_FLUSH = 100;
    private static final int MQTT_MAX_BACKOFF_MS = 60000;
    private static final int USB_HEALTH_INTERVAL_MS = 2000;
    private static final int USB_RECONNECT_DELAY_MS = 2000;
    private static final long USB_META_STALE_MS = 15000L;

    private static final String PREFS = "cits_bridge";
    private static final String PREF_LAST_NODE_ID = "last_node_id";
    private static final String PREF_LAST_PACKET_TOPIC = "last_packet_topic";
    private static final String PREF_LAST_HARDWARE_VARIANT = "last_hardware_variant";
    private static final String PREF_LAST_FIRMWARE_VERSION = "last_firmware_version";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Object pcapLock = new Object();
    private final Object mqttLock = new Object();

    private UsbManager usbManager;
    private UsbCdcSerial serial;
    private SerialLineReader reader;
    private Thread readerThread;
    private PcapWriter pcapWriter;
    private MqttSpool mqttSpool;
    private MiniMqttClient mqttClient = new MiniMqttClient();
    private CitsDeviceTracker deviceTracker;
    private SharedPreferences prefs;

    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;

    private String firmwareHardwareVariant = "android-bridge";
    private String firmwareVersion = "android-bridge";
    private String currentNodeId = "unknown";
    private String currentPacketTopic = "its/unknown/packet";
    private String nodeIdSource = "unknown";
    private String mqttUri = "";
    private String mqttNodeId = "";
    private String mqttEffectiveNodeId = "unknown";
    private String usbState = "disconnected";
    private String mqttState = "disabled";
    private String lastError = "none";
    private String lastDeviceName;
    private boolean usbUserWanted;
    private boolean usbReconnectScheduled;
    private boolean mqttNodeIdManual;
    private boolean mqttEnabled;
    private boolean mqttConnecting;
    private boolean mqttFlushRunning;
    private long mqttReconnectDelayMs = 1000;
    private long lastMetaElapsedMs;
    private long lastPacketElapsedMs;
    private long lastMqttPublishElapsedMs;
    private long lastProtocolLogElapsedMs;

    private long packetCount;
    private long pcapCount;
    private long mqttCount;
    private long truncatedCount;
    private long mqttDropCount;
    private long newDeviceCount;

    private final BroadcastReceiver usbAttachDetachReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)) {
                UsbDevice d = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                if (d != null) lastDeviceName = d.getDeviceName();
                broadcastStatus("USB device attached" + (d == null ? "" : ": " + d.getDeviceName()));
                if (usbUserWanted) scheduleUsbReconnect(0);
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
                UsbDevice d = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                boolean wasCurrent = d == null || serial == null || d.getDeviceName().equals(lastDeviceName);
                if (wasCurrent) {
                    closeSerialInternal(false, false);
                    usbState = "disconnected";
                    broadcastStatus("USB device detached" + (usbUserWanted ? "; waiting for reconnect" : ""));
                    if (usbUserWanted) scheduleUsbReconnect(USB_RECONNECT_DELAY_MS);
                }
            }
        }
    };

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
            updateUsbHealthState();
            if (mqttEnabled && !mqttClient.isConnected()) ensureMqttConnected();
            flushPcapQuietly();
            updateNotification();
            broadcastStatus(null);
            handler.postDelayed(this, USB_HEALTH_INTERVAL_MS);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        usbManager = (UsbManager) getSystemService(USB_SERVICE);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        loadRememberedMetadata();
        deviceTracker = new CitsDeviceTracker(this);
        try {
            mqttSpool = new MqttSpool(this);
        } catch (Exception e) {
            mqttSpool = null;
            mqttDropCount++;
            setLastError("MQTT spool unavailable: " + e.getMessage());
            broadcastStatus(lastError);
        }
        createNotificationChannel();
        registerUsbAttachDetachReceiver();
        startAsForeground();
        acquireLocks();
        handler.postDelayed(mqttFlushRunnable, MQTT_FLUSH_INTERVAL_MS);
        handler.postDelayed(healthRunnable, USB_HEALTH_INTERVAL_MS);
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
            usbUserWanted = true;
            openUsbByName(intent.getStringExtra(EXTRA_DEVICE_NAME));
        } else if (ACTION_STOP_USB.equals(action)) {
            usbUserWanted = false;
            closeSerial();
        } else if (ACTION_START_MQTT.equals(action)) {
            startMqtt(intent.getStringExtra(EXTRA_MQTT_URI), intent.getStringExtra(EXTRA_NODE_ID));
        } else if (ACTION_STOP_MQTT.equals(action)) {
            stopMqtt(true);
        } else if (ACTION_START_PCAP.equals(action)) {
            startPcap(intent.getStringExtra(EXTRA_PCAP_URI));
        } else if (ACTION_STOP_PCAP.equals(action)) {
            closePcap();
        } else if (ACTION_SET_DEVICE_NOTIFICATIONS.equals(action)) {
            setDeviceNotifications(intent.getBooleanExtra(EXTRA_DEVICE_NOTIFICATIONS_ENABLED, false));
        } else if (ACTION_CLEAR_SEEN_DEVICES.equals(action)) {
            clearSeenDevices();
        } else if (ACTION_REQUEST_STATUS.equals(action)) {
            broadcastStatus(null);
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
        try { unregisterReceiver(usbAttachDetachReceiver); } catch (Exception ignored) {}
        stopEverything();
        releaseLocks();
        try { if (mqttSpool != null) mqttSpool.close(); } catch (Exception ignored) {}
        super.onDestroy();
    }

    private void loadRememberedMetadata() {
        String rememberedNode = prefs.getString(PREF_LAST_NODE_ID, "");
        if (rememberedNode != null && !rememberedNode.trim().isEmpty()) {
            currentNodeId = rememberedNode.trim();
            currentPacketTopic = prefs.getString(PREF_LAST_PACKET_TOPIC, "its/" + currentNodeId + "/packet");
            firmwareHardwareVariant = prefs.getString(PREF_LAST_HARDWARE_VARIANT, firmwareHardwareVariant);
            firmwareVersion = prefs.getString(PREF_LAST_FIRMWARE_VERSION, firmwareVersion);
            nodeIdSource = "remembered";
        }
    }

    private void rememberMetadata(CitsLineParser.Meta meta) {
        prefs.edit()
                .putString(PREF_LAST_NODE_ID, meta.nodeId)
                .putString(PREF_LAST_PACKET_TOPIC, meta.packetTopic)
                .putString(PREF_LAST_HARDWARE_VARIANT, meta.hardwareVariant)
                .putString(PREF_LAST_FIRMWARE_VERSION, meta.firmwareVersion)
                .apply();
    }

    private void registerUsbAttachDetachReceiver() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        ContextCompat.registerReceiver(this, usbAttachDetachReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED);
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
                .setContentTitle("CITS-to-go")
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
                "CITS-to-go bridge",
                NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("Keeps USB serial capture and MQTT forwarding active while the phone is locked.");
        nm.createNotificationChannel(ch);

        NotificationChannel deviceCh = new NotificationChannel(
                DEVICE_CHANNEL_ID,
                "New C-ITS devices",
                NotificationManager.IMPORTANCE_DEFAULT);
        deviceCh.setDescription("Notifications for newly discovered C-ITS source MAC addresses.");
        nm.createNotificationChannel(deviceCh);
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
        closeSerialInternal(false, false);
        lastDeviceName = deviceName == null || deviceName.isEmpty() ? lastDeviceName : deviceName;
        UsbDevice device = findDevice(lastDeviceName);
        if (device == null) {
            usbState = usbUserWanted ? "disconnected; waiting for device" : "disconnected";
            broadcastStatus("USB device not found" + (usbUserWanted ? "; will retry" : ""));
            if (usbUserWanted) scheduleUsbReconnect(USB_RECONNECT_DELAY_MS);
            return;
        }
        lastDeviceName = device.getDeviceName();
        if (!usbManager.hasPermission(device)) {
            usbState = "permission required";
            broadcastStatus("USB permission missing for " + device.getDeviceName());
            return;
        }
        try {
            serial = new UsbCdcSerial(usbManager, device);
            serial.open(115200);
            reader = new SerialLineReader(serial, this);
            readerThread = new Thread(reader, "serial-line-reader");
            readerThread.start();
            lastMetaElapsedMs = 0;
            usbState = "connected, waiting for firmware";
            usbReconnectScheduled = false;
            broadcastStatus("USB connected: " + serial.describe());
        } catch (Exception e) {
            closeSerialInternal(false, false);
            usbState = "error";
            setLastError("USB open failed: " + e.getMessage());
            broadcastStatus(lastError);
            if (usbUserWanted) scheduleUsbReconnect(USB_RECONNECT_DELAY_MS);
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
        closeSerialInternal(true, false);
    }

    private void closeSerialInternal(boolean log, boolean scheduleReconnect) {
        if (reader != null) reader.stop();
        reader = null;
        if (serial != null) serial.close();
        serial = null;
        readerThread = null;
        if (log) {
            usbState = "disconnected";
            broadcastStatus("USB disconnected");
        }
        if (scheduleReconnect && usbUserWanted) scheduleUsbReconnect(USB_RECONNECT_DELAY_MS);
    }

    private void scheduleUsbReconnect(long delayMs) {
        if (!usbUserWanted || usbReconnectScheduled) return;
        usbReconnectScheduled = true;
        handler.postDelayed(() -> {
            usbReconnectScheduled = false;
            if (usbUserWanted && serial == null) openUsbByName(lastDeviceName);
        }, delayMs);
    }

    private void updateUsbHealthState() {
        if (serial == null) {
            if (usbUserWanted && !"permission required".equals(usbState)) {
                usbState = "disconnected; waiting for device";
                scheduleUsbReconnect(USB_RECONNECT_DELAY_MS);
            }
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (lastMetaElapsedMs == 0) {
            usbState = "connected, waiting for firmware";
        } else if (now - lastMetaElapsedMs > USB_META_STALE_MS) {
            usbState = "stale; last firmware heartbeat " + formatAge(now - lastMetaElapsedMs) + " ago";
        } else {
            usbState = "firmware alive";
        }
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
                setLastError("PCAP open failed: " + e.getMessage());
                broadcastStatus(lastError);
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

    private void setDeviceNotifications(boolean enabled) {
        if (deviceTracker != null) deviceTracker.setNotificationsEnabled(enabled);
        broadcastStatus("New-device notifications " + (enabled ? "enabled" : "disabled"));
    }

    private void clearSeenDevices() {
        if (deviceTracker != null) deviceTracker.clearSeenDevices();
        newDeviceCount = 0;
        broadcastStatus("Seen C-ITS device MAC list cleared");
    }

    private void maybeNotifyNewDevice(CitsDeviceTracker.Discovery discovery) {
        if (discovery == null || deviceTracker == null || !deviceTracker.isNotificationsEnabled()) return;

        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) return;

        Intent openIntent = new Intent(this, MainActivity.class);
        PendingIntent openPi = PendingIntent.getActivity(this, 2, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        String title = "New C-ITS device discovered";
        String text = discovery.sourceMac + " • " + discovery.frequencyMhz + " MHz • " +
                discovery.rssiDbm + " dBm";
        StringBuilder details = new StringBuilder(text);
        if (discovery.transmitterMac != null && !discovery.transmitterMac.isEmpty()
                && !discovery.transmitterMac.equals(discovery.sourceMac)) {
            details.append("\nTransmitter: ").append(discovery.transmitterMac);
        }
        if (discovery.receiverMac != null && !discovery.receiverMac.isEmpty()) {
            details.append("\nReceiver: ").append(discovery.receiverMac);
        }
        details.append("\nSeen devices: ").append(deviceTracker.seenCount());

        Notification n = new NotificationCompat.Builder(this, DEVICE_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(details.toString()))
                .setContentIntent(openPi)
                .setAutoCancel(true)
                .build();

        nm.notify(DEVICE_NOTIFICATION_BASE_ID + Math.abs(discovery.sourceMac.hashCode() % 50000), n);
    }

    private void startMqtt(String uri, String nodeId) {
        mqttUri = uri == null ? "" : uri.trim();
        mqttNodeIdManual = nodeId != null && !nodeId.trim().isEmpty();
        mqttNodeId = mqttNodeIdManual ? nodeId.trim() : bestAutomaticNodeId();
        mqttEnabled = true;
        mqttReconnectDelayMs = 1000;
        mqttState = "connecting";
        broadcastStatus("MQTT forwarding enabled; packets are spooled and published every " + MQTT_FLUSH_INTERVAL_MS + " ms");
        ensureMqttConnected();
    }

    private String bestAutomaticNodeId() {
        if (currentNodeId != null && !currentNodeId.trim().isEmpty() && !"unknown".equals(currentNodeId)) {
            return currentNodeId.trim();
        }
        String remembered = prefs == null ? "" : prefs.getString(PREF_LAST_NODE_ID, "");
        if (remembered != null && !remembered.trim().isEmpty()) return remembered.trim();
        return "unknown";
    }

    private void stopMqtt(boolean clearSpool) {
        mqttEnabled = false;
        mqttState = "disabled";
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
            mqttState = "connecting";
        }
        new Thread(() -> {
            try {
                String nid = mqttNodeId == null || mqttNodeId.trim().isEmpty() ? bestAutomaticNodeId() : mqttNodeId.trim();
                mqttClient.connect(mqttUri, nid, firmwareHardwareVariant, firmwareVersion);
                mqttEffectiveNodeId = nid;
                mqttReconnectDelayMs = 1000;
                mqttState = "connected";
                setLastError("none");
                broadcastStatus("MQTT connected; packet topic: its/" + nid + "/packet");
            } catch (Exception e) {
                mqttState = "reconnecting";
                setLastError("MQTT connect failed: " + e.getMessage());
                broadcastStatus(lastError);
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
        mqttState = mqttSpool != null && mqttSpool.pendingCount() > 0 ? "offline, spooling" : "reconnecting";
        long delay = mqttReconnectDelayMs;
        mqttReconnectDelayMs = Math.min(mqttReconnectDelayMs * 2, MQTT_MAX_BACKOFF_MS);
        handler.postDelayed(this::ensureMqttConnected, delay);
    }

    private void reconnectMqttForNewNode(String newNodeId) {
        if (!mqttEnabled || mqttNodeIdManual || newNodeId == null || newNodeId.isEmpty()) return;
        if (newNodeId.equals(mqttEffectiveNodeId) && mqttClient.isConnected()) return;
        mqttNodeId = newNodeId;
        try { mqttClient.close(); } catch (Exception ignored) {}
        mqttState = "reconnecting with detected node ID";
        mqttReconnectDelayMs = 1000;
        ensureMqttConnected();
    }

    private void spoolMqtt(byte[] payload) {
        if (!mqttEnabled) return;
        if (mqttSpool == null) {
            mqttDropCount++;
            return;
        }
        try {
            mqttSpool.append(payload);
            if (!mqttClient.isConnected()) mqttState = "offline, spooling";
        } catch (Exception e) {
            mqttDropCount++;
            setLastError("MQTT spool append failed: " + e.getMessage());
            broadcastStatus(lastError);
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
                        mqttClient.flush();
                        MqttSpool.Record last = sent.get(sent.size() - 1);
                        mqttSpool.ackBatch(last.nextOffset, sent.size());
                        mqttCount += sent.size();
                        lastMqttPublishElapsedMs = SystemClock.elapsedRealtime();
                        mqttState = "connected";
                    }
                } catch (Exception e) {
                    try { mqttClient.close(); } catch (Exception ignored) {}
                    mqttState = "offline, spooling";
                    setLastError("MQTT publish failed: " + e.getMessage());
                    broadcastStatus(lastError + "; keeping packet in spool and reconnecting");
                    scheduleMqttReconnect();
                    return;
                }
                if (!sent.isEmpty() && mqttSpool.pendingCount() > 0) {
                    broadcastStatus(null);
                }
            } catch (Exception e) {
                mqttState = "offline, spooling";
                setLastError("MQTT spool read failed: " + e.getMessage());
                broadcastStatus(lastError);
                scheduleMqttReconnect();
            } finally {
                synchronized (mqttLock) { mqttFlushRunning = false; }
                updateNotification();
                broadcastStatus(null);
            }
        }, "mqtt-spool-flush").start();
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
        setLastError("Serial error: " + e.getMessage());
        usbState = "error";
        broadcastStatus(lastError + "; reconnecting USB");
        closeSerialInternal(false, true);
    }

    private void handleSerialLine(String line) {
        CitsLineParser.Meta meta = CitsLineParser.parseMeta(line);
        if (meta != null) {
            handleMeta(meta);
            return;
        }
        if (line.startsWith("CITSPROTO,")) {
            long now = SystemClock.elapsedRealtime();
            if (now - lastProtocolLogElapsedMs > 60000L) {
                lastProtocolLogElapsedMs = now;
                broadcastStatus("USB protocol: " + line.substring("CITSPROTO,".length()));
            } else {
                broadcastStatus(null);
            }
            return;
        }
        if (line.startsWith("I (") || line.contains(") ")) return;
        broadcastStatus(line.length() > 120 ? line.substring(0, 120) + "…" : line);
    }

    private void handleMeta(CitsLineParser.Meta meta) {
        long now = SystemClock.elapsedRealtime();
        boolean firstMeta = lastMetaElapsedMs == 0;
        boolean nodeChanged = meta.nodeId != null && !meta.nodeId.equals(currentNodeId);
        boolean topicChanged = meta.packetTopic != null && !meta.packetTopic.equals(currentPacketTopic);

        lastMetaElapsedMs = now;
        usbState = "firmware alive";
        firmwareHardwareVariant = meta.hardwareVariant;
        firmwareVersion = meta.firmwareVersion;
        if (meta.nodeId != null && !meta.nodeId.trim().isEmpty()) {
            currentNodeId = meta.nodeId.trim();
            nodeIdSource = "detected from firmware";
        }
        if (meta.packetTopic != null && !meta.packetTopic.trim().isEmpty()) {
            currentPacketTopic = meta.packetTopic.trim();
        } else {
            currentPacketTopic = "its/" + currentNodeId + "/packet";
        }
        rememberMetadata(meta);

        if (!mqttNodeIdManual && currentNodeId != null && !currentNodeId.isEmpty() && !"unknown".equals(currentNodeId)) {
            boolean mqttWasUnknown = mqttNodeId == null || mqttNodeId.isEmpty() || "unknown".equals(mqttNodeId);
            mqttNodeId = currentNodeId;
            if (mqttEnabled && (mqttWasUnknown || !currentNodeId.equals(mqttEffectiveNodeId))) {
                reconnectMqttForNewNode(currentNodeId);
            }
        }

        String log = (firstMeta || nodeChanged || topicChanged) ?
                "Firmware heartbeat: nodeId=" + currentNodeId + " topic=" + currentPacketTopic : null;
        broadcastStatus(log);
        updateNotification();
    }

    private void handlePacketLine(String line) {
        CitsPacket packet;
        try {
            packet = CitsLineParser.parsePacket(line);
        } catch (Exception e) {
            setLastError("Bad CITS line: " + e.getMessage());
            broadcastStatus(lastError);
            return;
        }
        if (packet == null) return;
        handlePacket(packet);
    }

    private void handlePacket(CitsPacket packet) {
        packetCount++;
        lastPacketElapsedMs = SystemClock.elapsedRealtime();
        if (packet.truncated) truncatedCount++;

        CitsDeviceTracker.Discovery discovery = deviceTracker == null ? null : deviceTracker.notePacket(packet);
        if (discovery != null) {
            newDeviceCount++;
            maybeNotifyNewDevice(discovery);
            broadcastStatus("New C-ITS device: " + discovery.sourceMac +
                    " rssi=" + discovery.rssiDbm + "dBm freq=" + discovery.frequencyMhz + "MHz");
        }

        synchronized (pcapLock) {
            if (pcapWriter != null) {
                try {
                    pcapWriter.writePacket(packet);
                    pcapCount++;
                } catch (Exception e) {
                    closePcapLocked(false);
                    setLastError("PCAP write failed: " + e.getMessage());
                    broadcastStatus(lastError);
                }
            }
        }

        spoolMqtt(packet.payload);

        if ((packetCount % 25) == 1) {
            broadcastStatus("Packets=" + packetCount + " last=" + packet.payload.length + "B rssi=" + packet.rssiDbm + "dBm freq=" + packet.frequencyMhz + "MHz");
        } else {
            broadcastStatus(null);
        }
        updateNotification();
    }

    private void stopEverything() {
        usbUserWanted = false;
        closeSerial();
        closePcap();
        stopMqtt(false);
    }

    private String summaryText() {
        long queued = mqttSpool == null ? 0 : mqttSpool.pendingCount();
        return "USB " + usbState +
                " • Node " + currentNodeId +
                " • MQTT " + mqttState +
                " • packets " + packetCount +
                " • devices " + (deviceTracker == null ? 0 : deviceTracker.seenCount()) +
                (queued > 0 ? " • spool " + queued : "");
    }

    private void broadcastStatus(String log) {
        Intent i = new Intent(ACTION_STATUS).setPackage(getPackageName());
        i.putExtra("usb", serial != null);
        i.putExtra("usbState", usbState);
        i.putExtra("pcap", pcapWriter != null);
        i.putExtra("mqtt", mqttClient.isConnected());
        i.putExtra("mqttEnabled", mqttEnabled);
        i.putExtra("mqttState", mqttState);
        i.putExtra("mqttEffectiveNodeId", mqttEffectiveNodeId);
        long queued = mqttSpool == null ? 0 : mqttSpool.pendingCount();
        i.putExtra("mqttQueue", queued > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) queued);
        i.putExtra("packetCount", packetCount);
        i.putExtra("pcapCount", pcapCount);
        i.putExtra("mqttCount", mqttCount);
        i.putExtra("truncatedCount", truncatedCount);
        i.putExtra("mqttDropCount", mqttDropCount);
        i.putExtra("seenDeviceCount", deviceTracker == null ? 0 : deviceTracker.seenCount());
        i.putExtra("newDeviceCount", newDeviceCount);
        i.putExtra("deviceNotificationsEnabled", deviceTracker != null && deviceTracker.isNotificationsEnabled());
        i.putExtra("nodeId", currentNodeId);
        i.putExtra("packetTopic", currentPacketTopic);
        i.putExtra("nodeIdSource", nodeIdSource);
        i.putExtra("lastError", lastError);
        i.putExtra("lastMetaAgeMs", ageOrMinusOne(lastMetaElapsedMs));
        i.putExtra("lastPacketAgeMs", ageOrMinusOne(lastPacketElapsedMs));
        i.putExtra("lastMqttPublishAgeMs", ageOrMinusOne(lastMqttPublishElapsedMs));
        if (log != null && !log.isEmpty()) i.putExtra("log", log);
        sendBroadcast(i);
    }

    private long ageOrMinusOne(long timestampElapsedMs) {
        if (timestampElapsedMs <= 0) return -1L;
        return Math.max(0L, SystemClock.elapsedRealtime() - timestampElapsedMs);
    }

    private void setLastError(String error) {
        lastError = error == null || error.isEmpty() ? "none" : error;
    }

    private static String formatAge(long ageMs) {
        if (ageMs < 0) return "never";
        if (ageMs < 1000) return ageMs + "ms";
        if (ageMs < 60000) return String.format(Locale.US, "%.1fs", ageMs / 1000.0);
        return String.format(Locale.US, "%.1fmin", ageMs / 60000.0);
    }
}
