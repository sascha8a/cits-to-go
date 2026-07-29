package org.opentrafficmap.citstogo

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.opentrafficmap.citstogo.bridge.BridgeStatus
import org.opentrafficmap.citstogo.bridge.CitsBridgeService
import org.opentrafficmap.citstogo.cam.StationType
import java.security.SecureRandom
import java.util.Locale
import kotlin.math.roundToLong

class MainActivity : ComponentActivity() {
    private lateinit var usbManager: UsbManager
    private var startAfterPermission: UsbDevice? = null

    private val devices = mutableStateListOf<UsbDevice>()
    private var selectedDeviceName by mutableStateOf<String?>(null)
    private var status by mutableStateOf(BridgeStatus())
    private var mqttUri by mutableStateOf("")
    private var nodeId by mutableStateOf("")
    private var maxQueueLength by mutableStateOf("")
    private var maxQueueAgeSeconds by mutableStateOf("")
    private var logLine by mutableStateOf("")
    private var camStationType by mutableStateOf(StationType.PEDESTRIAN)
    private var camIntervalMs by mutableStateOf(CitsBridgeService.DEFAULT_CAM_INTERVAL_MS.toString())
    private var enableCamAfterPermission = false

    private val usbPermissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != CitsBridgeService.ACTION_USB_PERMISSION) return
            val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
            val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
            refreshDevices()
            if (granted) {
                selectedDeviceName = device?.deviceName ?: selectedDeviceName
                startBridge()
            } else {
                logLine = "USB permission denied"
            }
            startAfterPermission = null
        }
    }

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != CitsBridgeService.ACTION_STATUS) return
            status = BridgeStatus(
                running = intent.getBooleanExtra(CitsBridgeService.EXTRA_RUNNING, false),
                usbState = intent.getStringExtra(CitsBridgeService.EXTRA_USB_STATE).orEmpty(),
                mqttState = intent.getStringExtra(CitsBridgeService.EXTRA_MQTT_STATE).orEmpty(),
                nodeId = intent.getStringExtra(CitsBridgeService.EXTRA_NODE_ID).orEmpty(),
                packetTopic = intent.getStringExtra(CitsBridgeService.EXTRA_PACKET_TOPIC).orEmpty(),
                packets = intent.getLongExtra(CitsBridgeService.EXTRA_PACKETS, 0),
                mqttPublished = intent.getLongExtra(CitsBridgeService.EXTRA_MQTT_PUBLISHED, 0),
                mqttQueued = intent.getLongExtra(CitsBridgeService.EXTRA_MQTT_QUEUED, 0),
                pcapRecording = intent.getBooleanExtra(CitsBridgeService.EXTRA_PCAP_RECORDING, false),
                pcapPackets = intent.getLongExtra(CitsBridgeService.EXTRA_PCAP_PACKETS, 0),
                discoveredDevices = intent.getLongExtra(CitsBridgeService.EXTRA_DISCOVERED_DEVICES, 0),
                truncated = intent.getLongExtra(CitsBridgeService.EXTRA_TRUNCATED, 0),
                protocolErrors = intent.getLongExtra(CitsBridgeService.EXTRA_PROTOCOL_ERRORS, 0),
                txRequested = intent.getLongExtra(CitsBridgeService.EXTRA_TX_REQUESTED, 0),
                txSuccessful = intent.getLongExtra(CitsBridgeService.EXTRA_TX_SUCCESSFUL, 0),
                txFailed = intent.getLongExtra(CitsBridgeService.EXTRA_TX_FAILED, 0),
                camEnabled = intent.getBooleanExtra(CitsBridgeService.EXTRA_CAM_ENABLED, false),
                camSent = intent.getLongExtra(CitsBridgeService.EXTRA_CAM_SENT, 0),
                lastTxSummary = intent.getStringExtra(CitsBridgeService.EXTRA_LAST_TX).orEmpty(),
                lastPacketSummary = intent.getStringExtra(CitsBridgeService.EXTRA_LAST_PACKET).orEmpty(),
                lastError = intent.getStringExtra(CitsBridgeService.EXTRA_LAST_ERROR).orEmpty(),
            )
            intent.getStringExtra(CitsBridgeService.EXTRA_LOG)?.let { logLine = it }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        usbManager = getSystemService(USB_SERVICE) as UsbManager
        val prefs = getSharedPreferences(CitsBridgeService.PREFS, MODE_PRIVATE)
        mqttUri = prefs.getString(CitsBridgeService.PREF_MQTT_URI, CitsBridgeService.DEFAULT_MQTT_URI).orEmpty()
        nodeId = prefs.getString(CitsBridgeService.PREF_NODE_ID, null) ?: createAndStoreNodeId()
        maxQueueLength = prefs.getInt(
            CitsBridgeService.PREF_MQTT_MAX_QUEUE_LENGTH,
            CitsBridgeService.DEFAULT_MQTT_MAX_QUEUE_LENGTH,
        ).toString()
        maxQueueAgeSeconds = formatQueueAgeSeconds(
            prefs.getLong(
                CitsBridgeService.PREF_MQTT_MAX_QUEUE_AGE_MS,
                CitsBridgeService.DEFAULT_MQTT_MAX_QUEUE_AGE_MS,
            ),
        )
        camStationType = StationType.selectableFromCode(
            prefs.getInt(CitsBridgeService.PREF_CAM_STATION_TYPE, StationType.PEDESTRIAN.code))
        camIntervalMs = prefs.getInt(
            CitsBridgeService.PREF_CAM_INTERVAL_MS,
            CitsBridgeService.DEFAULT_CAM_INTERVAL_MS,
        ).toString()
        status = status.copy(
            discoveredDevices = prefs.getStringSet(CitsBridgeService.PREF_DISCOVERED_MAC_ADDRESSES, emptySet())
                ?.size
                ?.toLong()
                ?: 0L,
        )
        registerReceiverCompat(usbPermissionReceiver, IntentFilter(CitsBridgeService.ACTION_USB_PERMISSION))
        registerReceiverCompat(statusReceiver, IntentFilter(CitsBridgeService.ACTION_STATUS))
        requestNotificationPermission()
        refreshDevices()
        selectedDeviceName = selectedDeviceName ?: devices.firstOrNull()?.deviceName
        setContent {
            CitsTheme {
                CitsApp(
                    devices = devices.toList(),
                    selectedDeviceName = selectedDeviceName,
                    onSelectDevice = { selectedDeviceName = it },
                    mqttUri = mqttUri,
                    onMqttUriChange = { mqttUri = it },
                    nodeId = nodeId,
                    onNodeIdChange = { nodeId = it },
                    maxQueueLength = maxQueueLength,
                    onMaxQueueLengthChange = { maxQueueLength = it },
                    maxQueueAgeSeconds = maxQueueAgeSeconds,
                    onMaxQueueAgeSecondsChange = { maxQueueAgeSeconds = it },
                    status = status,
                    logLine = logLine,
                    onRefresh = ::refreshDevices,
                    onStart = ::requestUsbThenStart,
                    onStop = ::stopBridge,
                    onStartPcap = ::choosePcapFile,
                    onStopPcap = ::stopPcap,
                    camStationType = camStationType,
                    onCamStationTypeChange = { camStationType = it },
                    camIntervalMs = camIntervalMs,
                    onCamIntervalChange = { camIntervalMs = it },
                    onConfigureCam = ::configureCam,
                    onSaveSettings = { updatedMqttUri, updatedNodeId, updatedMaxQueueLength, updatedMaxQueueAgeSeconds ->
                        mqttUri = updatedMqttUri
                        nodeId = updatedNodeId
                        maxQueueLength = updatedMaxQueueLength
                        maxQueueAgeSeconds = updatedMaxQueueAgeSeconds
                        saveSettings(
                            updatedMqttUri,
                            updatedNodeId,
                            updatedMaxQueueLength,
                            updatedMaxQueueAgeSeconds,
                        )
                    },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshDevices()
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(usbPermissionReceiver) }
        runCatching { unregisterReceiver(statusReceiver) }
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_LOCATION && enableCamAfterPermission) {
            enableCamAfterPermission = false
            if (grantResults.any { it == PackageManager.PERMISSION_GRANTED }) {
                configureCam(true)
            } else {
                logLine = "Location permission denied; CAM remains off"
            }
        }
    }

    @Deprecated("Deprecated Android callback kept to avoid an activity dependency.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_CREATE_PCAP || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        val flags = data.flags and
            (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        runCatching { contentResolver.takePersistableUriPermission(uri, flags) }
        startPcap(uri)
    }

    private fun requestUsbThenStart() {
        saveSettings()
        val device = devices.firstOrNull { it.deviceName == selectedDeviceName } ?: devices.firstOrNull()
        if (device == null) {
            startBridge()
            return
        }
        selectedDeviceName = device.deviceName
        if (usbManager.hasPermission(device)) {
            startBridge()
            return
        }
        startAfterPermission = device
        val intent = Intent(CitsBridgeService.ACTION_USB_PERMISSION).setPackage(packageName)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        val pendingIntent = PendingIntent.getBroadcast(this, 20, intent, flags)
        usbManager.requestPermission(device, pendingIntent)
        logLine = "Requesting USB permission"
    }

    private fun startBridge() {
        saveSettings()
        val intent = Intent(this, CitsBridgeService::class.java)
            .setAction(CitsBridgeService.ACTION_START)
            .putExtra(CitsBridgeService.EXTRA_DEVICE_NAME, selectedDeviceName.orEmpty())
            .putExtra(CitsBridgeService.EXTRA_MQTT_URI, mqttUri)
            .putExtra(CitsBridgeService.EXTRA_NODE_ID, nodeId)
            .putExtra(CitsBridgeService.EXTRA_MQTT_MAX_QUEUE_LENGTH, parseMaxQueueLength(maxQueueLength))
            .putExtra(CitsBridgeService.EXTRA_MQTT_MAX_QUEUE_AGE_MS, parseMaxQueueAgeMs(maxQueueAgeSeconds))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
    }

    private fun stopBridge() {
        sendServiceIntent(CitsBridgeService.ACTION_STOP)
    }

    private fun choosePcapFile() {
        val title = "cits-${System.currentTimeMillis()}.pcap"
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("application/vnd.tcpdump.pcap")
            .putExtra(Intent.EXTRA_TITLE, title)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        startActivityForResult(intent, REQUEST_CREATE_PCAP)
    }

    private fun startPcap(uri: Uri) {
        val intent = Intent(this, CitsBridgeService::class.java)
            .setAction(CitsBridgeService.ACTION_START_PCAP)
            .putExtra(CitsBridgeService.EXTRA_PCAP_URI, uri.toString())
        startService(intent)
    }

    private fun stopPcap() {
        sendServiceIntent(CitsBridgeService.ACTION_STOP_PCAP)
    }

    private fun configureCam(enabled: Boolean) {
        if (enabled &&
            checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED
        ) {
            enableCamAfterPermission = true
            requestPermissions(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                ),
                REQUEST_LOCATION,
            )
            return
        }
        val interval = camIntervalMs.toIntOrNull()
            ?.coerceIn(CitsBridgeService.MIN_CAM_INTERVAL_MS, CitsBridgeService.MAX_CAM_INTERVAL_MS)
            ?: CitsBridgeService.DEFAULT_CAM_INTERVAL_MS
        camIntervalMs = interval.toString()
        sendServiceIntent(CitsBridgeService.ACTION_CONFIGURE_CAM) {
            putExtra(CitsBridgeService.EXTRA_CAM_ENABLED, enabled)
            putExtra(CitsBridgeService.EXTRA_CAM_STATION_TYPE, camStationType.code)
            putExtra(CitsBridgeService.EXTRA_CAM_INTERVAL_MS, interval)
        }
    }

    private fun sendServiceIntent(action: String, configure: Intent.() -> Unit = {}) {
        val intent = Intent(this, CitsBridgeService::class.java).setAction(action).apply(configure)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && action == CitsBridgeService.ACTION_START) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun refreshDevices() {
        devices.clear()
        devices.addAll(usbManager.deviceList.values.sortedWith(compareByDescending<UsbDevice> { it.vendorId == 0x303A }.thenBy { it.deviceName }))
        if (selectedDeviceName == null || devices.none { it.deviceName == selectedDeviceName }) {
            selectedDeviceName = devices.firstOrNull()?.deviceName
        }
    }

    private fun saveSettings(
        mqttUriValue: String = mqttUri,
        nodeIdValue: String = nodeId,
        maxQueueLengthValue: String = maxQueueLength,
        maxQueueAgeSecondsValue: String = maxQueueAgeSeconds,
    ) {
        val parsedMaxQueueLength = parseMaxQueueLength(maxQueueLengthValue)
        val parsedMaxQueueAgeMs = parseMaxQueueAgeMs(maxQueueAgeSecondsValue)
        mqttUri = mqttUriValue.trim()
        nodeId = nodeIdValue.trim()
        maxQueueLength = parsedMaxQueueLength.toString()
        maxQueueAgeSeconds = formatQueueAgeSeconds(parsedMaxQueueAgeMs)
        getSharedPreferences(CitsBridgeService.PREFS, MODE_PRIVATE).edit()
            .putString(CitsBridgeService.PREF_MQTT_URI, mqttUri)
            .putString(CitsBridgeService.PREF_NODE_ID, nodeId)
            .putInt(CitsBridgeService.PREF_MQTT_MAX_QUEUE_LENGTH, parsedMaxQueueLength)
            .putLong(CitsBridgeService.PREF_MQTT_MAX_QUEUE_AGE_MS, parsedMaxQueueAgeMs)
            .apply()
    }

    private fun parseMaxQueueLength(value: String): Int =
        value.trim().toIntOrNull()?.coerceAtLeast(1) ?: CitsBridgeService.DEFAULT_MQTT_MAX_QUEUE_LENGTH

    private fun parseMaxQueueAgeMs(value: String): Long =
        value.trim().toDoubleOrNull()
            ?.takeIf { it.isFinite() }
            ?.let { (it * 1_000.0).roundToLong().coerceAtLeast(0L) }
            ?: CitsBridgeService.DEFAULT_MQTT_MAX_QUEUE_AGE_MS

    private fun formatQueueAgeSeconds(ageMs: Long): String {
        if (ageMs % 1_000L == 0L) return (ageMs / 1_000L).toString()
        return String.format(Locale.US, "%.3f", ageMs / 1_000.0).trimEnd('0').trimEnd('.')
    }

    private fun createAndStoreNodeId(): String {
        val bytes = ByteArray(6)
        SecureRandom().nextBytes(bytes)
        val generated = bytes.joinToString("") { "%02x".format(it) }
        getSharedPreferences(CitsBridgeService.PREFS, MODE_PRIVATE).edit()
            .putString(CitsBridgeService.PREF_NODE_ID, generated)
            .apply()
        return generated
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 10)
        }
    }

    private fun registerReceiverCompat(receiver: BroadcastReceiver, filter: IntentFilter) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(receiver, filter)
        }
    }

    companion object {
        private const val REQUEST_CREATE_PCAP = 1001
        private const val REQUEST_LOCATION = 1002
    }
}

@Composable
private fun CitsTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Color(0xFF0F766E),
            secondary = Color(0xFF475569),
            tertiary = Color(0xFFB45309),
            surface = Color(0xFFF8FAFC),
            background = Color(0xFFEFF6F5),
        ),
        content = content,
    )
}

@Composable
private fun CitsApp(
    devices: List<UsbDevice>,
    selectedDeviceName: String?,
    onSelectDevice: (String) -> Unit,
    mqttUri: String,
    onMqttUriChange: (String) -> Unit,
    nodeId: String,
    onNodeIdChange: (String) -> Unit,
    maxQueueLength: String,
    onMaxQueueLengthChange: (String) -> Unit,
    maxQueueAgeSeconds: String,
    onMaxQueueAgeSecondsChange: (String) -> Unit,
    status: BridgeStatus,
    logLine: String,
    onRefresh: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onStartPcap: () -> Unit,
    onStopPcap: () -> Unit,
    camStationType: StationType,
    onCamStationTypeChange: (StationType) -> Unit,
    camIntervalMs: String,
    onCamIntervalChange: (String) -> Unit,
    onConfigureCam: (Boolean) -> Unit,
    onSaveSettings: (String, String, String, String) -> Unit,
) {
    var selectedPage by rememberSaveable { mutableStateOf(AppPage.Home) }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        ModalNavigationDrawer(
            drawerState = drawerState,
            drawerContent = {
                ModalDrawerSheet(
                    drawerContainerColor = MaterialTheme.colorScheme.surface,
                    drawerContentColor = MaterialTheme.colorScheme.onSurface,
                ) {
                    Text(
                        "C-ITS to go",
                        modifier = Modifier.padding(20.dp),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    AppPage.entries.forEach { page ->
                        NavigationDrawerItem(
                            label = { Text(page.title) },
                            selected = page == selectedPage,
                            onClick = {
                                selectedPage = page
                                scope.launch { drawerState.close() }
                            },
                            modifier = Modifier.padding(horizontal = 12.dp),
                            colors = NavigationDrawerItemDefaults.colors(
                                selectedContainerColor = Color(0xFFE0F2F1),
                                unselectedContainerColor = MaterialTheme.colorScheme.surface,
                                selectedTextColor = MaterialTheme.colorScheme.primary,
                                unselectedTextColor = MaterialTheme.colorScheme.secondary,
                            ),
                        )
                    }
                }
            },
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                AppHeader(
                    title = selectedPage.title,
                    onOpenMenu = { scope.launch { drawerState.open() } },
                )
                when (selectedPage) {
                    AppPage.Home -> HomePage(
                        devices = devices,
                        selectedDeviceName = selectedDeviceName,
                        onSelectDevice = onSelectDevice,
                        status = status,
                        logLine = logLine,
                        onRefresh = onRefresh,
                        onStart = onStart,
                        onStop = onStop,
                        onStartPcap = onStartPcap,
                        onStopPcap = onStopPcap,
                    )
                    AppPage.CamBroadcast -> CamBroadcastPage(
                        status = status,
                        logLine = logLine,
                        stationType = camStationType,
                        onStationTypeChange = onCamStationTypeChange,
                        intervalMs = camIntervalMs,
                        onIntervalChange = onCamIntervalChange,
                        onConfigure = onConfigureCam,
                    )
                    AppPage.Settings -> SettingsPage(
                        mqttUri = mqttUri,
                        nodeId = nodeId,
                        maxQueueLength = maxQueueLength,
                        maxQueueAgeSeconds = maxQueueAgeSeconds,
                        onSave = { updatedMqttUri, updatedNodeId, updatedMaxQueueLength, updatedMaxQueueAgeSeconds ->
                            onMqttUriChange(updatedMqttUri)
                            onNodeIdChange(updatedNodeId)
                            onMaxQueueLengthChange(updatedMaxQueueLength)
                            onMaxQueueAgeSecondsChange(updatedMaxQueueAgeSeconds)
                            onSaveSettings(
                                updatedMqttUri,
                                updatedNodeId,
                                updatedMaxQueueLength,
                                updatedMaxQueueAgeSeconds,
                            )
                        },
                    )
                }
            }
        }
    }
}

private enum class AppPage(val title: String) {
    Home("Home"),
    CamBroadcast("CAM Broadcast"),
    Settings("Settings"),
}

@Composable
private fun HomePage(
    devices: List<UsbDevice>,
    selectedDeviceName: String?,
    onSelectDevice: (String) -> Unit,
    status: BridgeStatus,
    logLine: String,
    onRefresh: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onStartPcap: () -> Unit,
    onStopPcap: () -> Unit,
) {
    StatusBand(status)
    ConfigPanel(
        devices,
        selectedDeviceName,
        onSelectDevice,
        onRefresh,
    )
    ActionRow(status.running, onStart, onStop)
    RecordingRow(status, onStartPcap, onStopPcap)
    Metrics(status)
    if (logLine.isNotBlank() || status.lastError.isNotBlank()) {
        EventLog(logLine, status.lastError)
    }
}

@Composable
private fun CamBroadcastPage(
    status: BridgeStatus,
    logLine: String,
    stationType: StationType,
    onStationTypeChange: (StationType) -> Unit,
    intervalMs: String,
    onIntervalChange: (String) -> Unit,
    onConfigure: (Boolean) -> Unit,
) {
    CamPanel(
        status = status,
        stationType = stationType,
        onStationTypeChange = onStationTypeChange,
        intervalMs = intervalMs,
        onIntervalChange = onIntervalChange,
        onConfigure = onConfigure,
    )
    if (logLine.isNotBlank() || status.lastError.isNotBlank()) {
        EventLog(logLine, status.lastError)
    }
}

@Composable
private fun CamPanel(
    status: BridgeStatus,
    stationType: StationType,
    onStationTypeChange: (StationType) -> Unit,
    intervalMs: String,
    onIntervalChange: (String) -> Unit,
    onConfigure: (Boolean) -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(Color.White, RoundedCornerShape(8.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("CAM broadcast", style = MaterialTheme.typography.titleMedium)
        Text(
            "ETSI CAM Release 1 over GeoNetworking SHB/BTP-B. Location values come from Android; unavailable sensor values are explicitly encoded as unavailable.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.secondary,
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Type", modifier = Modifier.weight(1f))
            TextButton(onClick = {
                val types = StationType.selectable
                val index = (types.indexOf(stationType) - 1 + types.size) % types.size
                onStationTypeChange(types[index])
            }) { Text("‹") }
            Text(stationType.displayName, modifier = Modifier.weight(2f))
            TextButton(onClick = {
                val types = StationType.selectable
                onStationTypeChange(types[(types.indexOf(stationType) + 1) % types.size])
            }) { Text("›") }
        }
        OutlinedTextField(
            value = intervalMs,
            onValueChange = onIntervalChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            enabled = !status.camEnabled,
            label = { Text("Broadcast interval") },
            suffix = { Text("ms") },
            supportingText = { Text("Allowed CAM range: 100–1000 ms") },
        )
        Button(
            onClick = { onConfigure(!status.camEnabled) },
            enabled = status.running,
            modifier = Modifier.fillMaxWidth().height(48.dp),
            colors = if (status.camEnabled) {
                ButtonDefaults.buttonColors(containerColor = Color(0xFFB91C1C))
            } else {
                ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            },
            shape = RoundedCornerShape(8.dp),
        ) {
            Text(if (status.camEnabled) "Stop CAM broadcast" else "Start CAM broadcast")
        }
    }
}

@Composable
private fun AppHeader(title: String, onOpenMenu: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onOpenMenu) {
            Text("☰", style = MaterialTheme.typography.titleLarge)
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                title,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "Version ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary,
            )
        }
    }
}

@Composable
private fun StatusBand(status: BridgeStatus) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            if (status.running) "Capture active" else "Capture stopped",
            color = Color.White,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
        )
        Text(status.summary(), color = Color(0xFFE0F2F1), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun ConfigPanel(
    devices: List<UsbDevice>,
    selectedDeviceName: String?,
    onSelectDevice: (String) -> Unit,
    onRefresh: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("USB serial", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = onRefresh) { Text("Refresh") }
        }
        if (devices.isEmpty()) {
            Text("No USB devices detected", color = MaterialTheme.colorScheme.secondary)
        } else {
            devices.forEach { device ->
                val selected = device.deviceName == selectedDeviceName
                Button(
                    onClick = { onSelectDevice(device.deviceName) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = if (selected) ButtonDefaults.buttonColors() else ButtonDefaults.outlinedButtonColors(),
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Text(deviceLabel(device), maxLines = 2)
                }
            }
        }
    }
}

@Composable
private fun SettingsPage(
    mqttUri: String,
    nodeId: String,
    maxQueueLength: String,
    maxQueueAgeSeconds: String,
    onSave: (String, String, String, String) -> Unit,
) {
    var draftMqttUri by rememberSaveable(mqttUri) { mutableStateOf(mqttUri) }
    var draftNodeId by rememberSaveable(nodeId) { mutableStateOf(nodeId) }
    var draftMaxQueueLength by rememberSaveable(maxQueueLength) { mutableStateOf(maxQueueLength) }
    var draftMaxQueueAgeSeconds by rememberSaveable(maxQueueAgeSeconds) { mutableStateOf(maxQueueAgeSeconds) }

    Column(
        Modifier
            .fillMaxWidth()
            .background(Color.White, RoundedCornerShape(8.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OutlinedTextField(
            value = draftMqttUri,
            onValueChange = { draftMqttUri = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("MQTT broker") },
            placeholder = { Text("mqtt://broker.example:1883") },
        )
        OutlinedTextField(
            value = draftNodeId,
            onValueChange = { draftNodeId = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("Node ID") },
        )
        OutlinedTextField(
            value = draftMaxQueueLength,
            onValueChange = { draftMaxQueueLength = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("Max queue length") },
            placeholder = { Text("100") },
        )
        OutlinedTextField(
            value = draftMaxQueueAgeSeconds,
            onValueChange = { draftMaxQueueAgeSeconds = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("Max queue age") },
            placeholder = { Text("0.2") },
            suffix = { Text("s") },
        )
        Button(
            onClick = {
                onSave(
                    draftMqttUri,
                    draftNodeId,
                    draftMaxQueueLength,
                    draftMaxQueueAgeSeconds,
                )
            },
            modifier = Modifier.fillMaxWidth().height(48.dp),
            shape = RoundedCornerShape(8.dp),
        ) {
            Text("Save")
        }
    }
}

@Composable
private fun ActionRow(running: Boolean, onStart: () -> Unit, onStop: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Button(
            onClick = onStart,
            modifier = Modifier.weight(1f).height(48.dp),
            enabled = !running,
            shape = RoundedCornerShape(8.dp),
        ) { Text("Start") }
        Button(
            onClick = onStop,
            modifier = Modifier.weight(1f).height(48.dp),
            enabled = running,
            shape = RoundedCornerShape(8.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB91C1C)),
        ) { Text("Stop") }
    }
}

@Composable
private fun RecordingRow(status: BridgeStatus, onStartPcap: () -> Unit, onStopPcap: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Button(
            onClick = onStartPcap,
            modifier = Modifier.weight(1f).height(48.dp),
            enabled = status.running && !status.pcapRecording,
            shape = RoundedCornerShape(8.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB45309)),
        ) { Text("Record PCAP") }
        Button(
            onClick = onStopPcap,
            modifier = Modifier.weight(1f).height(48.dp),
            enabled = status.pcapRecording,
            shape = RoundedCornerShape(8.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF475569)),
        ) { Text("Stop PCAP") }
    }
}

@Composable
private fun Metrics(status: BridgeStatus) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            MetricCard("Packets", status.packets.toString(), Modifier.weight(1f))
            MetricCard("Published", status.mqttPublished.toString(), Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            MetricCard("Queued", status.mqttQueued.toString(), Modifier.weight(1f))
            MetricCard("PCAP", status.pcapPackets.toString(), Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            MetricCard("Discovered", status.discoveredDevices.toString(), Modifier.weight(1f))
            MetricCard("Truncated", status.truncated.toString(), Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            MetricCard("Errors", status.protocolErrors.toString(), Modifier.weight(1f))
            MetricCard("TX sent", status.txSuccessful.toString(), Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            MetricCard("TX failed", status.txFailed.toString(), Modifier.weight(1f))
            MetricCard("CAM sent", status.camSent.toString(), Modifier.weight(1f))
        }
        if (status.lastPacketSummary.isNotBlank()) {
            Text("Last packet: ${status.lastPacketSummary}", style = MaterialTheme.typography.bodyMedium)
        }
        if (status.lastTxSummary.isNotBlank()) {
            Text("Last TX: ${status.lastTxSummary}", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun MetricCard(label: String, value: String, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.height(78.dp),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Text(label, color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.labelMedium)
            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun EventLog(logLine: String, lastError: String) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(Color.White, RoundedCornerShape(8.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (logLine.isNotBlank()) Text(logLine, style = MaterialTheme.typography.bodyMedium)
        if (lastError.isNotBlank()) Text(lastError, color = Color(0xFFB91C1C), style = MaterialTheme.typography.bodyMedium)
    }
}

private fun deviceLabel(device: UsbDevice): String {
    val name = listOfNotNull(device.manufacturerName, device.productName).joinToString(" ").ifBlank { device.deviceName }
    return "$name  vid=%04x pid=%04x".format(device.vendorId, device.productId)
}
