package org.opentrafficmap.citslogger;

import android.Manifest;
import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.ContextCompat;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final String ACTION_USB_PERMISSION = "org.opentrafficmap.citslogger.USB_PERMISSION";
    private static final int REQUEST_CREATE_PCAP = 42;
    private static final int REQUEST_NOTIFICATIONS = 43;
    private static final String PREFS = "cits_bridge";
    private static final String PREF_LAST_NODE_ID = "last_node_id";

    private UsbManager usbManager;
    private SharedPreferences prefs;

    private EditText mqttUriText;
    private EditText nodeIdText;
    private CheckBox deviceNotificationsCheck;
    private TextView statusText;
    private TextView logText;

    private boolean usbConnected;
    private boolean pcapRecording;
    private boolean mqttConnected;
    private boolean mqttEnabled;
    private int mqttQueue;
    private long packetCount;
    private long pcapCount;
    private long mqttCount;
    private long truncatedCount;
    private long mqttDropCount;
    private int seenDeviceCount;
    private long newDeviceCount;
    private boolean deviceNotificationsEnabled;
    private boolean updatingUi;
    private boolean nodeIdManuallyEdited;
    private String usbState = "disconnected";
    private String mqttState = "disabled";
    private String nodeIdSource = "unknown";
    private String packetTopic = "its/unknown/packet";
    private String lastError = "none";
    private long lastMetaAgeMs = -1;
    private long lastPacketAgeMs = -1;
    private long lastMqttPublishAgeMs = -1;

    private final BroadcastReceiver usbPermissionReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!ACTION_USB_PERMISSION.equals(intent.getAction())) return;
            UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
            boolean granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
            if (granted && device != null) {
                startForegroundBridge(startServiceAction(CitsBridgeService.ACTION_START_USB)
                        .putExtra(CitsBridgeService.EXTRA_DEVICE_NAME, device.getDeviceName()));
            } else {
                appendLog("USB permission denied");
            }
        }
    };

    private final BroadcastReceiver statusReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!CitsBridgeService.ACTION_STATUS.equals(intent.getAction())) return;
            usbConnected = intent.getBooleanExtra("usb", usbConnected);
            usbState = intent.getStringExtra("usbState") == null ? usbState : intent.getStringExtra("usbState");
            pcapRecording = intent.getBooleanExtra("pcap", pcapRecording);
            mqttConnected = intent.getBooleanExtra("mqtt", mqttConnected);
            mqttEnabled = intent.getBooleanExtra("mqttEnabled", mqttEnabled);
            mqttState = intent.getStringExtra("mqttState") == null ? mqttState : intent.getStringExtra("mqttState");
            mqttQueue = intent.getIntExtra("mqttQueue", mqttQueue);
            packetCount = intent.getLongExtra("packetCount", packetCount);
            pcapCount = intent.getLongExtra("pcapCount", pcapCount);
            mqttCount = intent.getLongExtra("mqttCount", mqttCount);
            truncatedCount = intent.getLongExtra("truncatedCount", truncatedCount);
            mqttDropCount = intent.getLongExtra("mqttDropCount", mqttDropCount);
            seenDeviceCount = intent.getIntExtra("seenDeviceCount", seenDeviceCount);
            newDeviceCount = intent.getLongExtra("newDeviceCount", newDeviceCount);
            deviceNotificationsEnabled = intent.getBooleanExtra("deviceNotificationsEnabled", deviceNotificationsEnabled);
            packetTopic = intent.getStringExtra("packetTopic") == null ? packetTopic : intent.getStringExtra("packetTopic");
            nodeIdSource = intent.getStringExtra("nodeIdSource") == null ? nodeIdSource : intent.getStringExtra("nodeIdSource");
            lastError = intent.getStringExtra("lastError") == null ? lastError : intent.getStringExtra("lastError");
            lastMetaAgeMs = intent.getLongExtra("lastMetaAgeMs", lastMetaAgeMs);
            lastPacketAgeMs = intent.getLongExtra("lastPacketAgeMs", lastPacketAgeMs);
            lastMqttPublishAgeMs = intent.getLongExtra("lastMqttPublishAgeMs", lastMqttPublishAgeMs);

            if (deviceNotificationsCheck != null && deviceNotificationsCheck.isChecked() != deviceNotificationsEnabled) {
                updatingUi = true;
                deviceNotificationsCheck.setChecked(deviceNotificationsEnabled);
                updatingUi = false;
            }
            String nodeId = intent.getStringExtra("nodeId");
            if (nodeId != null && !nodeId.isEmpty() && !"unknown".equals(nodeId) && !nodeIdManuallyEdited) {
                updatingUi = true;
                nodeIdText.setText(nodeId);
                updatingUi = false;
            }
            String log = intent.getStringExtra("log");
            if (log != null) appendLog(log);
            updateStatus();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        usbManager = (UsbManager) getSystemService(USB_SERVICE);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        buildUi();
        registerUsbReceiver();
        requestNotificationPermissionIfNeeded();
    }

    @Override
    protected void onStart() {
        super.onStart();
        IntentFilter filter = new IntentFilter(CitsBridgeService.ACTION_STATUS);
        ContextCompat.registerReceiver(this, statusReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED);
    }

    @Override
    protected void onStop() {
        super.onStop();
        try { unregisterReceiver(statusReceiver); } catch (Exception ignored) {}
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try { unregisterReceiver(usbPermissionReceiver); } catch (Exception ignored) {}
    }

    private void registerUsbReceiver() {
        IntentFilter filter = new IntentFilter(ACTION_USB_PERMISSION);
        ContextCompat.registerReceiver(
                this,
                usbPermissionReceiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED);
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQUEST_NOTIFICATIONS);
        }
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(24, 24, 24, 24);

        TextView title = new TextView(this);
        title.setText("CITS-to-go");
        title.setTextSize(22);
        title.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(title);

        mqttUriText = new EditText(this);
        mqttUriText.setHint("MQTT URI, e.g. mqtts://cits1.opentrafficmap.org");
        mqttUriText.setSingleLine(true);
        mqttUriText.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        mqttUriText.setText("mqtts://cits1.opentrafficmap.org");
        root.addView(mqttUriText);

        nodeIdText = new EditText(this);
        nodeIdText.setHint("Node ID; auto-filled from CITSMETA if available");
        nodeIdText.setSingleLine(true);
        nodeIdText.setInputType(InputType.TYPE_CLASS_TEXT);
        String rememberedNode = prefs.getString(PREF_LAST_NODE_ID, "");
        if (rememberedNode != null && !rememberedNode.trim().isEmpty()) {
            updatingUi = true;
            nodeIdText.setText(rememberedNode.trim());
            updatingUi = false;
            nodeIdSource = "remembered";
        }
        nodeIdText.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (!updatingUi && nodeIdText.hasFocus()) nodeIdManuallyEdited = true;
            }
            @Override public void afterTextChanged(Editable s) {}
        });
        root.addView(nodeIdText);

        LinearLayout row1 = row();
        row1.addView(button("Connect USB", v -> connectUsb()), weightParams());
        row1.addView(button("Disconnect USB", v -> sendAction(CitsBridgeService.ACTION_STOP_USB)), weightParams());
        root.addView(row1);

        LinearLayout row2 = row();
        row2.addView(button("Create PCAP", v -> createPcapFile()), weightParams());
        row2.addView(button("Close PCAP", v -> sendAction(CitsBridgeService.ACTION_STOP_PCAP)), weightParams());
        root.addView(row2);

        LinearLayout row3 = row();
        row3.addView(button("Connect MQTT", v -> connectMqtt()), weightParams());
        row3.addView(button("Disconnect MQTT", v -> sendAction(CitsBridgeService.ACTION_STOP_MQTT)), weightParams());
        root.addView(row3);

        LinearLayout row4 = row();
        row4.addView(button("Stop Bridge", v -> sendAction(CitsBridgeService.ACTION_STOP_ALL)), weightParams());
        row4.addView(button("Clear Seen Devices", v -> sendAction(CitsBridgeService.ACTION_CLEAR_SEEN_DEVICES)), weightParams());
        root.addView(row4);

        deviceNotificationsCheck = new CheckBox(this);
        deviceNotificationsCheck.setText("Notify once for each newly discovered C-ITS MAC address");
        deviceNotificationsCheck.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (updatingUi) return;
            startForegroundBridge(startServiceAction(CitsBridgeService.ACTION_SET_DEVICE_NOTIFICATIONS)
                    .putExtra(CitsBridgeService.EXTRA_DEVICE_NOTIFICATIONS_ENABLED, isChecked));
        });
        root.addView(deviceNotificationsCheck);

        statusText = new TextView(this);
        statusText.setTextSize(14);
        statusText.setText("USB: disconnected | PCAP: closed | MQTT: disabled");
        root.addView(statusText);

        logText = new TextView(this);
        logText.setTextSize(12);
        ScrollView scroller = new ScrollView(this);
        scroller.addView(logText);
        root.addView(scroller, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
        updateStatus();
    }

    private LinearLayout row() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        return row;
    }

    private LinearLayout.LayoutParams weightParams() {
        return new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
    }

    private Button button(String text, View.OnClickListener listener) {
        Button b = new Button(this);
        b.setText(text);
        b.setOnClickListener(listener);
        return b;
    }

    private void connectUsb() {
        ArrayList<UsbDevice> devices = new ArrayList<>(usbManager.getDeviceList().values());
        if (devices.isEmpty()) {
            toast("No USB devices found. Use an OTG cable and power the XIAO ESP32-C5.");
            startForegroundBridge(startServiceAction(CitsBridgeService.ACTION_START_USB));
            return;
        }
        UsbDevice best = devices.get(0);
        for (UsbDevice d : devices) {
            if (d.getVendorId() == 0x303A) {
                best = d;
                break;
            }
        }
        if (usbManager.hasPermission(best)) {
            startForegroundBridge(startServiceAction(CitsBridgeService.ACTION_START_USB)
                    .putExtra(CitsBridgeService.EXTRA_DEVICE_NAME, best.getDeviceName()));
        } else {
            Intent permissionIntent = new Intent(ACTION_USB_PERMISSION).setPackage(getPackageName());
            PendingIntent pi = PendingIntent.getBroadcast(
                    this, 0, permissionIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
            usbManager.requestPermission(best, pi);
        }
    }

    private void createPcapFile() {
        String ts = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION |
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION |
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        intent.setType("application/vnd.tcpdump.pcap");
        intent.putExtra(Intent.EXTRA_TITLE, "cits-" + ts + ".pcap");
        startActivityForResult(intent, REQUEST_CREATE_PCAP);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_CREATE_PCAP && resultCode == RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (uri == null) return;
            try {
                final int returnedFlags = data.getFlags();
                if ((returnedFlags & Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0) {
                    getContentResolver().takePersistableUriPermission(uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION);
                }
                if ((returnedFlags & Intent.FLAG_GRANT_WRITE_URI_PERMISSION) != 0) {
                    getContentResolver().takePersistableUriPermission(uri,
                            Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                }
            } catch (SecurityException ignored) {}
            startForegroundBridge(startServiceAction(CitsBridgeService.ACTION_START_PCAP)
                    .putExtra(CitsBridgeService.EXTRA_PCAP_URI, uri.toString()));
        }
    }

    private void connectMqtt() {
        String uri = mqttUriText.getText().toString().trim();
        String nodeId = nodeIdText.getText().toString().trim();
        // If the current value was auto-filled from CITSMETA/remembered state, allow future
        // CITSMETA heartbeats to replace it. If the user edited the field, it is manual.
        startForegroundBridge(startServiceAction(CitsBridgeService.ACTION_START_MQTT)
                .putExtra(CitsBridgeService.EXTRA_MQTT_URI, uri)
                .putExtra(CitsBridgeService.EXTRA_NODE_ID, nodeIdManuallyEdited ? nodeId : ""));
    }

    private Intent startServiceAction(String action) {
        return new Intent(this, CitsBridgeService.class).setAction(action);
    }

    private void sendAction(String action) {
        startForegroundBridge(startServiceAction(action));
    }

    private void startForegroundBridge(Intent intent) {
        ContextCompat.startForegroundService(this, intent);
    }

    private void updateStatus() {
        if (statusText == null) return;
        String s = "USB: " + usbState +
                " | PCAP: " + (pcapRecording ? "recording" : "closed") +
                "\nMQTT: " + mqttState +
                " | spool: " + mqttQueue +
                " | topic: " + packetTopic +
                "\nNode ID: " + nodeIdText.getText().toString().trim() +
                " (" + nodeIdSource + ")" +
                " | firmware heartbeat: " + formatAge(lastMetaAgeMs) +
                "\nPackets: " + packetCount +
                " | last packet: " + formatAge(lastPacketAgeMs) +
                " | PCAP: " + pcapCount +
                " | MQTT: " + mqttCount +
                " | last MQTT publish: " + formatAge(lastMqttPublishAgeMs) +
                "\nTruncated: " + truncatedCount +
                " | MQTT dropped: " + mqttDropCount +
                " | last error: " + lastError +
                "\nSeen C-ITS devices: " + seenDeviceCount +
                " | New this run: " + newDeviceCount +
                " | notifications: " + (deviceNotificationsEnabled ? "on" : "off");
        statusText.setText(s);
    }

    private String formatAge(long ageMs) {
        if (ageMs < 0) return "never";
        if (ageMs < 1000) return ageMs + "ms ago";
        if (ageMs < 60000) return String.format(Locale.US, "%.1fs ago", ageMs / 1000.0);
        return String.format(Locale.US, "%.1fmin ago", ageMs / 60000.0);
    }

    private void appendLog(String msg) {
        String current = logText == null ? "" : logText.getText().toString();
        String ts = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
        String next = ts + " " + msg + "\n" + current;
        if (next.length() > 12000) next = next.substring(0, 12000);
        if (logText != null) logText.setText(next);
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
        appendLog(msg);
    }
}
