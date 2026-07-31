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
import java.io.Serializable
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.opentrafficmap.citstogo.bridge.BridgeStatus
import org.opentrafficmap.citstogo.bridge.CitsBridgeService
import org.opentrafficmap.citstogo.cam.StationType
import org.opentrafficmap.citstogo.intersection.IntersectionSnapshot
import org.opentrafficmap.citstogo.intersection.LaneConnection
import org.opentrafficmap.citstogo.intersection.LaneType
import org.opentrafficmap.citstogo.intersection.MapIntersection
import org.opentrafficmap.citstogo.intersection.MapLane
import org.opentrafficmap.citstogo.intersection.MovementPhaseState
import org.opentrafficmap.citstogo.intersection.SelectionSource
import org.opentrafficmap.citstogo.intersection.SignalEvent
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
    private var intersectionSnapshot by mutableStateOf<IntersectionSnapshot?>(null)
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
            serializableExtra<IntersectionSnapshot>(intent, CitsBridgeService.EXTRA_INTERSECTION_SNAPSHOT)?.let {
                intersectionSnapshot = it
            }
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
                    intersectionSnapshot = intersectionSnapshot,
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

private inline fun <reified T : Serializable> serializableExtra(intent: Intent, key: String): T? {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        intent.getSerializableExtra(key, T::class.java)
    } else {
        @Suppress("DEPRECATION")
        intent.getSerializableExtra(key) as? T
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
    intersectionSnapshot: IntersectionSnapshot?,
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
                    AppPage.IntersectionView -> IntersectionViewPage(
                        snapshot = intersectionSnapshot,
                        status = status,
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
    IntersectionView("Intersection View"),
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
private fun IntersectionViewPage(
    snapshot: IntersectionSnapshot?,
    status: BridgeStatus,
) {
    val map = snapshot?.map
    val spat = snapshot?.spat
    Column(
        Modifier
            .fillMaxWidth()
            .background(Color.White, RoundedCornerShape(8.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (snapshot?.available != true) {
            Text("Waiting for MAPEM/SPATEM", style = MaterialTheme.typography.titleMedium)
            Text(
                if (status.running) "No intersection message has been decoded yet." else "Start capture to receive intersection messages.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.secondary,
            )
            return@Column
        }
        val title = map?.name?.takeIf { it.isNotBlank() } ?: "Intersection ${snapshot.map?.key ?: snapshot.spat?.key}"
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(
            listOfNotNull(
                map?.key?.let { "id $it" },
                map?.revision?.let { "MAP rev $it" },
                spat?.revision?.let { "SPAT rev $it" },
                if (snapshot.source == SelectionSource.DeviceLocation) "nearest to device" else "latest observed",
            ).joinToString(" • "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.secondary,
        )
        if (map == null) {
            Text("SPATEM received; waiting for matching MAPEM geometry.", color = MaterialTheme.colorScheme.secondary)
        } else {
            IntersectionRenderer(map, spat)
            Text(
                "${map.lanes.size} lanes • ${map.lanes.count { it.connections.isNotEmpty() }} signalized lane links",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary,
            )
        }
    }
    SignalTimingPanel(snapshot)
}

@Composable
private fun IntersectionRenderer(
    map: MapIntersection,
    spat: org.opentrafficmap.citstogo.intersection.SpatIntersection?,
) {
    val signalGroups = spat?.movementsBySignalGroup.orEmpty()
    val canvasBackground = Color(0xFFF8FAFC)
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(420.dp)
            .background(canvasBackground, RoundedCornerShape(8.dp)),
    ) {
        val allNodes = map.lanes.flatMap { it.nodes }
        if (allNodes.isEmpty()) return@Canvas
        val minX = allNodes.minOf { it.xCm }.toFloat()
        val maxX = allNodes.maxOf { it.xCm }.toFloat()
        val minY = allNodes.minOf { it.yCm }.toFloat()
        val maxY = allNodes.maxOf { it.yCm }.toFloat()
        val padding = 28.dp.toPx()
        val width = (maxX - minX).coerceAtLeast(1f)
        val height = (maxY - minY).coerceAtLeast(1f)
        val scale = minOf((size.width - padding * 2) / width, (size.height - padding * 2) / height)

        fun point(x: Int, y: Int): Offset = Offset(
            x = padding + (x - minX) * scale,
            y = size.height - padding - (y - minY) * scale,
        )

        val lanesById = map.lanes.associateBy { it.id }
        fun phaseFor(lane: MapLane, connection: LaneConnection? = null): MovementPhaseState? {
            return connection?.signalGroup?.let { signalGroups[it]?.currentEvent?.state }
                ?: lane.connections.firstNotNullOfOrNull { laneConnection ->
                    laneConnection.signalGroup?.let { signalGroups[it]?.currentEvent?.state }
                }
        }

        fun signalColorFor(lane: MapLane, connection: LaneConnection? = null): Color {
            val phase = phaseFor(lane, connection)
            return phase?.phaseColor() ?: lane.laneType.baseColor()
        }

        fun lanePath(lane: MapLane): Path = Path().apply {
            val first = lane.nodes.first()
            moveTo(point(first.xCm, first.yCm).x, point(first.xCm, first.yCm).y)
            lane.nodes.drop(1).forEach { node ->
                val p = point(node.xCm, node.yCm)
                lineTo(p.x, p.y)
            }
        }

        fun closestEndpointPair(first: MapLane, second: MapLane): Pair<Offset, Offset> {
            val firstEndpoints = listOf(first.nodes.first(), first.nodes.last()).map { point(it.xCm, it.yCm) }
            val secondEndpoints = listOf(second.nodes.first(), second.nodes.last()).map { point(it.xCm, it.yCm) }
            return firstEndpoints.flatMap { firstPoint ->
                secondEndpoints.map { secondPoint -> firstPoint to secondPoint }
            }.minBy { (firstPoint, secondPoint) ->
                val dx = firstPoint.x - secondPoint.x
                val dy = firstPoint.y - secondPoint.y
                dx * dx + dy * dy
            }
        }

        val crosswalkDash = PathEffect.dashPathEffect(floatArrayOf(10.dp.toPx(), 8.dp.toPx()))
        val bikeDash = PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 7.dp.toPx()))
        val sidewalkDash = PathEffect.dashPathEffect(floatArrayOf(16.dp.toPx(), 8.dp.toPx()))
        val medianDash = PathEffect.dashPathEffect(floatArrayOf(18.dp.toPx(), 10.dp.toPx()))
        val stripingDash = PathEffect.dashPathEffect(floatArrayOf(10.dp.toPx(), 4.dp.toPx(), 2.dp.toPx(), 4.dp.toPx()))
        val parkingDash = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 6.dp.toPx()))
        val otherDash = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 8.dp.toPx()))

        class LaneVisualStyle(
            val width: Float,
            val pathEffect: PathEffect? = null,
            val backingWidth: Float? = null,
            val backingColor: Color = Color.White.copy(alpha = 0.84f),
            val centerGapWidth: Float? = null,
            val unsignalizedAlpha: Float = 0.72f,
        )

        fun styleFor(type: LaneType): LaneVisualStyle = when (type) {
            LaneType.Vehicle -> LaneVisualStyle(width = 5.dp.toPx())
            LaneType.Crosswalk -> LaneVisualStyle(
                width = 6.dp.toPx(),
                pathEffect = crosswalkDash,
                backingWidth = 9.dp.toPx(),
                backingColor = Color.White.copy(alpha = 0.9f),
            )
            LaneType.Bike -> LaneVisualStyle(
                width = 4.dp.toPx(),
                pathEffect = bikeDash,
                backingWidth = 6.dp.toPx(),
                backingColor = Color.White.copy(alpha = 0.72f),
            )
            LaneType.Sidewalk -> LaneVisualStyle(
                width = 3.5.dp.toPx(),
                pathEffect = sidewalkDash,
                backingWidth = 6.dp.toPx(),
                backingColor = Color.White.copy(alpha = 0.68f),
            )
            LaneType.Median -> LaneVisualStyle(
                width = 8.dp.toPx(),
                pathEffect = medianDash,
                unsignalizedAlpha = 0.46f,
            )
            LaneType.Striping -> LaneVisualStyle(
                width = 3.dp.toPx(),
                pathEffect = stripingDash,
                unsignalizedAlpha = 0.62f,
            )
            LaneType.TrackedVehicle -> LaneVisualStyle(
                width = 7.dp.toPx(),
                backingWidth = 9.dp.toPx(),
                backingColor = Color.White.copy(alpha = 0.68f),
                centerGapWidth = 3.dp.toPx(),
            )
            LaneType.Parking -> LaneVisualStyle(
                width = 4.dp.toPx(),
                pathEffect = parkingDash,
                unsignalizedAlpha = 0.68f,
            )
            LaneType.Other -> LaneVisualStyle(
                width = 3.dp.toPx(),
                pathEffect = otherDash,
                unsignalizedAlpha = 0.56f,
            )
        }

        fun renderOrder(type: LaneType): Int = when (type) {
            LaneType.Median -> 0
            LaneType.Striping -> 1
            LaneType.Parking -> 2
            LaneType.Sidewalk -> 3
            LaneType.Bike -> 4
            LaneType.TrackedVehicle -> 5
            LaneType.Vehicle -> 6
            LaneType.Other -> 7
            LaneType.Crosswalk -> 8
        }

        map.lanes.sortedBy { renderOrder(it.laneType) }.forEach { lane ->
            if (lane.nodes.size < 2) return@forEach
            val phase = phaseFor(lane)
            val color = signalColorFor(lane)
            val path = lanePath(lane)
            val style = styleFor(lane.laneType)
            style.backingWidth?.let { backingWidth ->
                drawPath(
                    path = path,
                    color = style.backingColor,
                    style = Stroke(
                        width = backingWidth,
                        cap = StrokeCap.Round,
                        join = StrokeJoin.Round,
                        pathEffect = style.pathEffect,
                    ),
                )
            }
            drawPath(
                path = path,
                color = color.copy(alpha = if (phase == null) style.unsignalizedAlpha else 0.92f),
                style = Stroke(
                    width = style.width,
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round,
                    pathEffect = style.pathEffect,
                ),
            )
            style.centerGapWidth?.let { centerGapWidth ->
                drawPath(
                    path = path,
                    color = canvasBackground,
                    style = Stroke(
                        width = centerGapWidth,
                        cap = StrokeCap.Round,
                        join = StrokeJoin.Round,
                        pathEffect = style.pathEffect,
                    ),
                )
            }
        }
        map.lanes.filter { it.laneType == LaneType.Crosswalk }.forEach { lane ->
            val style = styleFor(LaneType.Crosswalk)
            lane.connections.forEach { connection ->
                val connectedLane = lanesById[connection.laneId]
                if (connectedLane?.laneType != LaneType.Crosswalk || lane.id > connectedLane.id) return@forEach
                val (start, end) = closestEndpointPair(lane, connectedLane)
                style.backingWidth?.let { backingWidth ->
                    drawLine(
                        color = style.backingColor,
                        start = start,
                        end = end,
                        strokeWidth = backingWidth,
                        cap = StrokeCap.Round,
                        pathEffect = style.pathEffect,
                    )
                }
                drawLine(
                    color = signalColorFor(lane, connection).copy(alpha = 0.92f),
                    start = start,
                    end = end,
                    strokeWidth = style.width,
                    cap = StrokeCap.Round,
                    pathEffect = style.pathEffect,
                )
            }
        }
    }
}

@Composable
private fun SignalTimingPanel(snapshot: IntersectionSnapshot?) {
    val movements = snapshot?.spat?.movements.orEmpty()
    if (movements.isEmpty()) return
    Column(
        Modifier
            .fillMaxWidth()
            .background(Color.White, RoundedCornerShape(8.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Signal phases", style = MaterialTheme.typography.titleMedium)
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Group", modifier = Modifier.weight(0.6f), style = MaterialTheme.typography.labelMedium)
            Text("Phase", modifier = Modifier.weight(1.2f), style = MaterialTheme.typography.labelMedium)
            Text("Next change", modifier = Modifier.weight(1.0f), style = MaterialTheme.typography.labelMedium)
        }
        movements.sortedBy { it.signalGroup }.take(16).forEach { movement ->
            val event = movement.currentEvent
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "SG ${movement.signalGroup}",
                    modifier = Modifier.weight(0.6f),
                    fontWeight = FontWeight.SemiBold,
                )
                Row(
                    modifier = Modifier.weight(1.2f),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Canvas(Modifier.width(18.dp).height(18.dp)) {
                        drawCircle(event?.state?.phaseColor() ?: Color(0xFF94A3B8), radius = 6.dp.toPx())
                    }
                    Text(event?.state?.label ?: "Unknown")
                }
                Text(
                    event?.nextChangeLabel() ?: "No timing",
                    modifier = Modifier.weight(1.0f),
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
        }
    }
}

private fun SignalEvent.nextChangeLabel(): String {
    val likely = likelyTime?.let(::formatTimeMark)
    val min = minEndTime?.let(::formatTimeMark)
    val max = maxEndTime?.let(::formatTimeMark)
    return when {
        likely != null -> likely
        min != null && max != null -> "$min-$max"
        min != null -> min
        else -> "No timing"
    }
}

private fun formatTimeMark(value: Int): String {
    if (value >= 36001) return "unknown"
    val totalTenths = value.coerceAtLeast(0)
    val minutes = totalTenths / 600
    val seconds = (totalTenths / 10) % 60
    val tenths = totalTenths % 10
    return "%02d:%02d.%d".format(minutes, seconds, tenths)
}

private fun MovementPhaseState.phaseColor(): Color = when (this) {
    MovementPhaseState.StopAndRemain,
    MovementPhaseState.StopThenProceed -> Color(0xFFDC2626)
    MovementPhaseState.PreMovement,
    MovementPhaseState.PermissiveClearance,
    MovementPhaseState.ProtectedClearance,
    MovementPhaseState.CautionConflictingTraffic -> Color(0xFFD97706)
    MovementPhaseState.PermissiveAllowed,
    MovementPhaseState.ProtectedAllowed -> Color(0xFF16A34A)
    MovementPhaseState.Dark,
    MovementPhaseState.Unavailable,
    MovementPhaseState.Unknown -> Color(0xFF64748B)
}

private fun LaneType.baseColor(): Color = when (this) {
    LaneType.Vehicle -> Color(0xFF334155)
    LaneType.Crosswalk -> Color(0xFF7C3AED)
    LaneType.Bike -> Color(0xFF0891B2)
    LaneType.Sidewalk -> Color(0xFF64748B)
    LaneType.TrackedVehicle -> Color(0xFFA16207)
    LaneType.Parking -> Color(0xFF475569)
    LaneType.Median,
    LaneType.Striping,
    LaneType.Other -> Color(0xFF94A3B8)
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
