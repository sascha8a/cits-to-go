package org.opentrafficmap.citstogo

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Paint
import android.graphics.Typeface
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import android.provider.OpenableColumns
import java.io.Serializable
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.opentrafficmap.citstogo.bridge.BridgeStatus
import org.opentrafficmap.citstogo.bridge.CitsBridgeService
import org.opentrafficmap.citstogo.bridge.UsbCdcSerial
import org.opentrafficmap.citstogo.cam.StationType
import org.opentrafficmap.citstogo.flashing.CodebergReleaseClient
import org.opentrafficmap.citstogo.flashing.Esp32RomFlasher
import org.opentrafficmap.citstogo.flashing.EspFlashTransport
import org.opentrafficmap.citstogo.flashing.FirmwareFileReader
import org.opentrafficmap.citstogo.flashing.FirmwareRelease
import org.opentrafficmap.citstogo.intersection.IntersectionSnapshot
import org.opentrafficmap.citstogo.intersection.IntersectionSnapshotList
import org.opentrafficmap.citstogo.intersection.LaneConnection
import org.opentrafficmap.citstogo.intersection.LaneNode
import org.opentrafficmap.citstogo.intersection.LaneType
import org.opentrafficmap.citstogo.intersection.MapIntersection
import org.opentrafficmap.citstogo.intersection.MapLane
import org.opentrafficmap.citstogo.intersection.MovementPhaseState
import org.opentrafficmap.citstogo.intersection.SpatIntersection
import org.opentrafficmap.citstogo.intersection.CountdownLabelBounds
import org.opentrafficmap.citstogo.intersection.countdownLaneRepresentatives
import org.opentrafficmap.citstogo.intersection.countdownSignalGroupsForSelection
import org.opentrafficmap.citstogo.intersection.countdownSideOffset
import org.opentrafficmap.citstogo.intersection.connectedSremLaneIds
import org.opentrafficmap.citstogo.intersection.intersectionConnectionVisible
import org.opentrafficmap.citstogo.intersection.intersectionLaneSelectionAlpha
import org.opentrafficmap.citstogo.intersection.placeCountdownLabel
import org.opentrafficmap.citstogo.intersection.roadConnectionControlPoints
import org.opentrafficmap.citstogo.intersection.secondsUntilChange
import org.opentrafficmap.citstogo.intersection.resolveSremLaneDirection
import org.opentrafficmap.citstogo.srem.SremProfile
import org.opentrafficmap.citstogo.srem.estimateSremRequestTimeMs
import java.security.SecureRandom
import java.util.Locale
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sqrt

class MainActivity : ComponentActivity(), SensorEventListener {
    private lateinit var usbManager: UsbManager
    private lateinit var locationManager: LocationManager
    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null
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
    private var intersectionSnapshots by mutableStateOf<List<IntersectionSnapshot>>(emptyList())
    private var intersectionSortMode by mutableStateOf(IntersectionSortMode.FirstReceived)
    private var currentPosition by mutableStateOf<DevicePosition?>(null)
    private var camStationType by mutableStateOf(StationType.PEDESTRIAN)
    private var sremProfile by mutableStateOf(SremProfile.PEDESTRIAN)
    private var camIntervalMs by mutableStateOf(CitsBridgeService.DEFAULT_CAM_INTERVAL_MS.toString())
    private var txApproved by mutableStateOf(false)
    private var txApprovalPromptState by mutableStateOf(TxApprovalPromptState.Hidden)
    private var enableCamAfterPermission = false
    private var wantsIntersectionLocation = false
    private var intersectionLocationActive = false
    private var lastShakeElapsedMs = 0L
    private var firmwareRelease: FirmwareRelease? = null
    private var customFirmwareUri: Uri? = null
    private var releaseLookupRunning = false
    private var flashAfterPermission = false
    private var flashingState by mutableStateOf(FirmwareFlashingState.initial(BuildConfig.VERSION_NAME))

    private val intersectionLocationListener = LocationListener { location ->
        updateCurrentPosition(location)
    }

    private val usbPermissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != CitsBridgeService.ACTION_USB_PERMISSION) return
            val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
            val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
            refreshDevices()
            if (granted) {
                selectedDeviceName = device?.deviceName ?: selectedDeviceName
                if (flashAfterPermission && device != null) {
                    startFirmwareFlash(device)
                } else {
                    startBridge()
                }
            } else {
                if (flashAfterPermission) {
                    flashingState = flashingState.copy(
                        phase = FirmwareFlashingPhase.Error,
                        message = "USB permission denied",
                    )
                } else {
                    logLine = "USB permission denied"
                }
            }
            flashAfterPermission = false
            startAfterPermission = null
        }
    }

    private val usbDeviceReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != UsbManager.ACTION_USB_DEVICE_ATTACHED &&
                intent.action != UsbManager.ACTION_USB_DEVICE_DETACHED
            ) return
            refreshDevices()
            updateFlashingDeviceState()
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
                replaying = intent.getBooleanExtra(CitsBridgeService.EXTRA_REPLAYING, false),
                replayPackets = intent.getLongExtra(CitsBridgeService.EXTRA_REPLAY_PACKETS, 0),
                discoveredDevices = intent.getLongExtra(CitsBridgeService.EXTRA_DISCOVERED_DEVICES, 0),
                truncated = intent.getLongExtra(CitsBridgeService.EXTRA_TRUNCATED, 0),
                protocolErrors = intent.getLongExtra(CitsBridgeService.EXTRA_PROTOCOL_ERRORS, 0),
                txRequested = intent.getLongExtra(CitsBridgeService.EXTRA_TX_REQUESTED, 0),
                txSuccessful = intent.getLongExtra(CitsBridgeService.EXTRA_TX_SUCCESSFUL, 0),
                txFailed = intent.getLongExtra(CitsBridgeService.EXTRA_TX_FAILED, 0),
                camEnabled = intent.getBooleanExtra(CitsBridgeService.EXTRA_CAM_ENABLED, false),
                camSent = intent.getLongExtra(CitsBridgeService.EXTRA_CAM_SENT, 0),
                lastSremState = intent.getStringExtra(CitsBridgeService.EXTRA_SREM_STATE).orEmpty(),
                lastSremSummary = intent.getStringExtra(CitsBridgeService.EXTRA_SREM_SUMMARY).orEmpty(),
                lastSremRequestId = intent.getIntExtra(CitsBridgeService.EXTRA_SREM_REQUEST_ID, -1),
                lastSremIntersectionId = intent.getIntExtra(CitsBridgeService.EXTRA_SREM_INTERSECTION_ID, -1),
                lastSremInboundLaneId = intent.getIntExtra(CitsBridgeService.EXTRA_SREM_INBOUND_LANE_ID, -1),
                lastSremOutboundLaneId = intent.getIntExtra(CitsBridgeService.EXTRA_SREM_OUTBOUND_LANE_ID, -1),
                lastSremUpdatedAtMs = intent.getLongExtra(CitsBridgeService.EXTRA_SREM_UPDATED_AT_MS, 0L),
                lastTxSummary = intent.getStringExtra(CitsBridgeService.EXTRA_LAST_TX).orEmpty(),
                lastPacketSummary = intent.getStringExtra(CitsBridgeService.EXTRA_LAST_PACKET).orEmpty(),
                lastError = intent.getStringExtra(CitsBridgeService.EXTRA_LAST_ERROR).orEmpty(),
            )
            intent.getStringExtra(CitsBridgeService.EXTRA_LOG)?.let { logLine = it }
            serializableExtra<IntersectionSnapshotList>(intent, CitsBridgeService.EXTRA_INTERSECTION_SNAPSHOTS)?.let {
                intersectionSnapshots = it.snapshots
                intersectionSnapshot = it.snapshots.firstOrNull()
            }
            serializableExtra<IntersectionSnapshot>(intent, CitsBridgeService.EXTRA_INTERSECTION_SNAPSHOT)?.let {
                intersectionSnapshot = it
                if (intersectionSnapshots.isEmpty()) intersectionSnapshots = listOf(it)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        usbManager = getSystemService(USB_SERVICE) as UsbManager
        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val prefs = getSharedPreferences(CitsBridgeService.PREFS, MODE_PRIVATE)
        txApproved = prefs.getBoolean(CitsBridgeService.PREF_TX_APPROVED, false)
        intersectionSortMode = IntersectionSortMode.fromPreference(
            prefs.getString(PREF_INTERSECTION_SORT_MODE, null),
        )
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
        sremProfile = SremProfile.fromPreferenceCode(
            prefs.getInt(CitsBridgeService.PREF_SREM_PROFILE, SremProfile.PEDESTRIAN.preferenceCode),
        )
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
        registerReceiverCompat(usbDeviceReceiver, IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        })
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
                    intersectionSnapshots = intersectionSnapshots.ifEmpty { listOfNotNull(intersectionSnapshot) },
                    intersectionSortMode = intersectionSortMode,
                    onIntersectionSortModeChange = ::updateIntersectionSortMode,
                    currentPosition = currentPosition,
                    onRefresh = ::refreshDevices,
                    onStart = ::requestUsbThenStart,
                    onStop = ::stopBridge,
                    onStartPcap = ::choosePcapFile,
                    onStopPcap = ::stopPcap,
                    onStartReplay = ::chooseReplayFile,
                    onStopReplay = ::stopReplay,
                    camStationType = camStationType,
                    onCamStationTypeChange = { camStationType = it },
                    camIntervalMs = camIntervalMs,
                    onCamIntervalChange = { camIntervalMs = it },
                    onConfigureCam = ::configureCam,
                    sremProfile = sremProfile,
                    onSremProfileChange = { sremProfile = it },
                    txApproved = txApproved,
                    txApprovalPromptState = txApprovalPromptState,
                    onGrantTxApproval = ::grantTxApproval,
                    onDismissTxApproval = { txApprovalPromptState = TxApprovalPromptState.Hidden },
                    onFinishTxApproval = { txApprovalPromptState = TxApprovalPromptState.Hidden },
                    onRevokeTxApproval = ::revokeTxApproval,
                    onSendSrem = ::sendSrem,
                    onIntersectionLocationActiveChange = ::setIntersectionLocationActive,
                    flashingState = flashingState,
                    onFlashingPageActive = ::setFlashingPageActive,
                    onRetryFirmwareRelease = { loadFirmwareRelease(force = true) },
                    onChooseCustomFirmware = ::chooseCustomFirmware,
                    onUseReleaseFirmware = ::useReleaseFirmware,
                    onFlashFirmware = ::requestFirmwareFlash,
                    onSaveSettings = { updatedMqttUri, updatedNodeId, updatedMaxQueueLength, updatedMaxQueueAgeSeconds, updatedSremProfile ->
                        mqttUri = updatedMqttUri
                        nodeId = updatedNodeId
                        maxQueueLength = updatedMaxQueueLength
                        maxQueueAgeSeconds = updatedMaxQueueAgeSeconds
                        sremProfile = updatedSremProfile
                        saveSettings(
                            updatedMqttUri,
                            updatedNodeId,
                            updatedMaxQueueLength,
                            updatedMaxQueueAgeSeconds,
                            updatedSremProfile,
                        )
                    },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshDevices()
        if (!txApproved) startTxShakeDetection()
        if (wantsIntersectionLocation) {
            startOrRequestIntersectionLocation()
        }
    }

    override fun onPause() {
        stopTxShakeDetection()
        stopIntersectionLocationUpdates()
        super.onPause()
    }

    override fun onDestroy() {
        stopIntersectionLocationUpdates()
        runCatching { unregisterReceiver(usbPermissionReceiver) }
        runCatching { unregisterReceiver(statusReceiver) }
        runCatching { unregisterReceiver(usbDeviceReceiver) }
        super.onDestroy()
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (txApproved ||
            txApprovalPromptState != TxApprovalPromptState.Hidden ||
            event.sensor.type != Sensor.TYPE_ACCELEROMETER
        ) return
        val x = event.values.getOrNull(0) ?: return
        val y = event.values.getOrNull(1) ?: return
        val z = event.values.getOrNull(2) ?: return
        val gForce = sqrt((x * x + y * y + z * z).toDouble()).toFloat() / SensorManager.GRAVITY_EARTH
        val now = SystemClock.elapsedRealtime()
        if (gForce >= TX_SHAKE_THRESHOLD_G && now - lastShakeElapsedMs >= TX_SHAKE_COOLDOWN_MS) {
            lastShakeElapsedMs = now
            txApprovalPromptState = TxApprovalPromptState.Ready
            logLine = "Shake detected; TX approval slider unlocked"
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

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
        if (requestCode == REQUEST_LOCATION && wantsIntersectionLocation) {
            if (grantResults.any { it == PackageManager.PERMISSION_GRANTED }) {
                startIntersectionLocationUpdates()
            } else {
                logLine = "Location permission denied; position marker disabled"
            }
        }
    }

    @Deprecated("Deprecated Android callback kept to avoid an activity dependency.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode !in setOf(REQUEST_CREATE_PCAP, REQUEST_OPEN_PCAP, REQUEST_OPEN_FIRMWARE) ||
            resultCode != RESULT_OK
        ) return
        val uri = data?.data ?: return
        val flags = data.flags and
            (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        runCatching { contentResolver.takePersistableUriPermission(uri, flags) }
        when (requestCode) {
            REQUEST_CREATE_PCAP -> startPcap(uri)
            REQUEST_OPEN_PCAP -> startReplay(uri)
            REQUEST_OPEN_FIRMWARE -> selectCustomFirmware(uri)
        }
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

    private fun setFlashingPageActive(active: Boolean) {
        if (!active) return
        refreshDevices()
        updateFlashingDeviceState()
        if (firmwareRelease == null && customFirmwareUri == null && !releaseLookupRunning) loadFirmwareRelease()
    }

    private fun loadFirmwareRelease(force: Boolean = false) {
        if (releaseLookupRunning || (!force && firmwareRelease != null)) return
        releaseLookupRunning = true
        firmwareRelease = null
        flashingState = FirmwareFlashingState.initial(BuildConfig.VERSION_NAME)
        Thread {
            runCatching { CodebergReleaseClient().findFirmware(BuildConfig.VERSION_NAME) }
                .onSuccess { release ->
                    runOnUiThread {
                        releaseLookupRunning = false
                        firmwareRelease = release
                        if (customFirmwareUri != null) return@runOnUiThread
                        flashingState = flashingState.copy(
                            releaseTag = release.tag,
                            firmwareName = release.firmwareName,
                            customFirmware = false,
                            phase = FirmwareFlashingPhase.WaitingForDevice,
                            message = "Firmware release found. Connect an ESP32-C5 over USB.",
                        )
                        updateFlashingDeviceState()
                    }
                }
                .onFailure { error ->
                    runOnUiThread {
                        releaseLookupRunning = false
                        if (customFirmwareUri != null) return@runOnUiThread
                        flashingState = flashingState.copy(
                            phase = FirmwareFlashingPhase.Error,
                            message = error.message ?: "Unable to load the matching Codeberg release",
                        )
                    }
                }
        }.start()
    }

    private fun updateFlashingDeviceState() {
        if (flashingState.busy || (firmwareRelease == null && customFirmwareUri == null)) return
        val device = connectedEspressifDevice()
        val sourceMessage = if (customFirmwareUri != null) {
            "Custom firmware selected. Connect an ESP32-C5 over USB."
        } else {
            "Firmware release found. Connect an ESP32-C5 over USB."
        }
        flashingState = if (device == null) {
            flashingState.copy(
                deviceName = null,
                phase = FirmwareFlashingPhase.WaitingForDevice,
                message = sourceMessage,
                progress = 0f,
            )
        } else {
            flashingState.copy(
                deviceName = device.productName ?: device.deviceName,
                phase = FirmwareFlashingPhase.Ready,
                message = "ESP32-C5 USB interface detected. Pull the slider fully to flash.",
                progress = 0f,
            )
        }
    }

    private fun requestFirmwareFlash() {
        val device = connectedEspressifDevice() ?: run {
            updateFlashingDeviceState()
            return
        }
        if (status.running) {
            flashingState = flashingState.copy(
                phase = FirmwareFlashingPhase.Error,
                message = "Stop the receiver on the Home page before flashing.",
            )
            return
        }
        if (!usbManager.hasPermission(device)) {
            flashAfterPermission = true
            val intent = Intent(CitsBridgeService.ACTION_USB_PERMISSION).setPackage(packageName)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
            usbManager.requestPermission(device, PendingIntent.getBroadcast(this, 21, intent, flags))
            flashingState = flashingState.copy(message = "Grant USB permission to continue flashing.")
            return
        }
        startFirmwareFlash(device)
    }

    private fun startFirmwareFlash(device: UsbDevice) {
        val customUri = customFirmwareUri
        val release = firmwareRelease
        if (customUri == null && release == null) return
        if (flashingState.busy) return
        flashingState = flashingState.copy(
            phase = FirmwareFlashingPhase.Downloading,
            message = if (customUri != null) {
                "Reading ${flashingState.firmwareName ?: "custom firmware"}…"
            } else {
                "Downloading and verifying ${release!!.firmwareName}…"
            },
            progress = 0f,
        )
        Thread {
            runCatching {
                val firmware = if (customUri != null) {
                    contentResolver.openInputStream(customUri)?.use { input ->
                        FirmwareFileReader.read(input)
                    } ?: throw java.io.IOException("Unable to open the custom firmware file")
                } else {
                    CodebergReleaseClient().downloadAndVerify(release!!) { progress ->
                        runOnUiThread { flashingState = flashingState.copy(progress = progress * 0.2f) }
                    }
                }
                runOnUiThread {
                    flashingState = flashingState.copy(
                        phase = FirmwareFlashingPhase.Flashing,
                        message = "Flashing ESP32-C5. Keep the cable connected…",
                        progress = 0.2f,
                    )
                }
                UsbCdcSerial(usbManager, device).use { serial ->
                    serial.open(115_200)
                    val transport = object : EspFlashTransport {
                        override fun write(data: ByteArray) = serial.writeAll(data, 10_000)
                        override fun read(buffer: ByteArray, timeoutMs: Int): Int = serial.read(buffer, timeoutMs)
                        override fun setControlLines(dtr: Boolean, rts: Boolean) = serial.setControlLines(dtr, rts)
                    }
                    Esp32RomFlasher(transport).flash(firmware) { progress ->
                        runOnUiThread { flashingState = flashingState.copy(progress = 0.2f + progress * 0.8f) }
                    }
                }
            }.onSuccess {
                runOnUiThread {
                    flashingState = flashingState.copy(
                        phase = FirmwareFlashingPhase.Complete,
                        message = "Firmware flashed and verified successfully.",
                        progress = 1f,
                    )
                }
            }.onFailure { error ->
                runOnUiThread {
                    flashingState = flashingState.copy(
                        phase = FirmwareFlashingPhase.Error,
                        message = error.message ?: "Firmware flashing failed",
                    )
                }
            }
        }.start()
    }

    private fun chooseCustomFirmware() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("application/octet-stream")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        startActivityForResult(intent, REQUEST_OPEN_FIRMWARE)
    }

    private fun selectCustomFirmware(uri: Uri) {
        customFirmwareUri = uri
        val name = runCatching {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull() ?: uri.lastPathSegment ?: "firmware.bin"
        flashingState = flashingState.copy(
            releaseTag = null,
            firmwareName = name,
            customFirmware = true,
            phase = FirmwareFlashingPhase.WaitingForDevice,
            message = "Custom firmware selected. Connect an ESP32-C5 over USB.",
            progress = 0f,
        )
        updateFlashingDeviceState()
    }

    private fun useReleaseFirmware() {
        if (flashingState.busy) return
        customFirmwareUri = null
        val release = firmwareRelease
        if (release == null) {
            flashingState = FirmwareFlashingState.initial(BuildConfig.VERSION_NAME)
            loadFirmwareRelease(force = true)
            return
        }
        flashingState = flashingState.copy(
            releaseTag = release.tag,
            firmwareName = release.firmwareName,
            customFirmware = false,
            phase = FirmwareFlashingPhase.WaitingForDevice,
            message = "Firmware release found. Connect an ESP32-C5 over USB.",
            progress = 0f,
        )
        updateFlashingDeviceState()
    }

    private fun connectedEspressifDevice(): UsbDevice? = devices.firstOrNull {
        it.vendorId == ESPRESSIF_USB_VENDOR_ID && it.productId == ESPRESSIF_USB_JTAG_SERIAL_PRODUCT_ID
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

    private fun chooseReplayFile() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("*/*")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        startActivityForResult(intent, REQUEST_OPEN_PCAP)
    }

    private fun startReplay(uri: Uri) {
        saveSettings()
        val intent = Intent(this, CitsBridgeService::class.java)
            .setAction(CitsBridgeService.ACTION_START_REPLAY)
            .putExtra(CitsBridgeService.EXTRA_PCAP_URI, uri.toString())
            .putExtra(CitsBridgeService.EXTRA_MQTT_URI, mqttUri)
            .putExtra(CitsBridgeService.EXTRA_NODE_ID, nodeId)
            .putExtra(CitsBridgeService.EXTRA_MQTT_MAX_QUEUE_LENGTH, parseMaxQueueLength(maxQueueLength))
            .putExtra(CitsBridgeService.EXTRA_MQTT_MAX_QUEUE_AGE_MS, parseMaxQueueAgeMs(maxQueueAgeSeconds))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
    }

    private fun stopReplay() {
        sendServiceIntent(CitsBridgeService.ACTION_STOP_REPLAY)
    }

    private fun configureCam(enabled: Boolean) {
        if (enabled && !txApproved) {
            logLine = "TX approval is required before CAM broadcast"
            return
        }
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

    private fun sendSrem(snapshot: IntersectionSnapshot, inboundLaneId: Int, outboundLaneId: Int) {
        if (!txApproved) {
            logLine = "TX approval is required before SREM"
            return
        }
        val map = snapshot.map ?: run {
            logLine = "SREM requires MAPEM geometry"
            return
        }
        val position = currentPosition ?: run {
            logLine = "SREM requires a fresh location"
            return
        }
        val nowMs = System.currentTimeMillis()
        if (position.timeMs !in (nowMs - SREM_MAX_LOCATION_AGE_MS)..(nowMs + 1_000L)) {
            logLine = "SREM requires a fresh location"
            return
        }
        sendServiceIntent(CitsBridgeService.ACTION_SEND_SREM) {
            map.key.region?.let { putExtra(CitsBridgeService.EXTRA_SREM_REGION, it) }
            putExtra(CitsBridgeService.EXTRA_SREM_INTERSECTION_ID, map.key.id)
            putExtra(CitsBridgeService.EXTRA_SREM_INBOUND_LANE_ID, inboundLaneId)
            putExtra(CitsBridgeService.EXTRA_SREM_OUTBOUND_LANE_ID, outboundLaneId)
            putExtra(CitsBridgeService.EXTRA_SREM_LATITUDE_E7, position.latitudeE7)
            putExtra(CitsBridgeService.EXTRA_SREM_LONGITUDE_E7, position.longitudeE7)
            putExtra(CitsBridgeService.EXTRA_SREM_POSITION_TIME_MS, position.timeMs)
            putExtra(CitsBridgeService.EXTRA_SREM_HEADING, position.heading)
            putExtra(CitsBridgeService.EXTRA_SREM_POSITION_ACCURATE, position.accuracyM != null)
            putExtra(CitsBridgeService.EXTRA_SREM_PROFILE, sremProfile.preferenceCode)
            putExtra(
                CitsBridgeService.EXTRA_SREM_PACKAGE_REQUEST_TIME_MS,
                sremPackageRequestTimeMs(map, inboundLaneId, position, sremProfile, nowMs),
            )
        }
        logLine = "SREM request submitted"
    }

    private fun grantTxApproval() {
        txApproved = true
        txApprovalPromptState = TxApprovalPromptState.Granting
        getSharedPreferences(CitsBridgeService.PREFS, MODE_PRIVATE).edit()
            .putBoolean(CitsBridgeService.PREF_TX_APPROVED, true)
            .apply()
        stopTxShakeDetection()
        logLine = "TX approval granted"
    }

    private fun revokeTxApproval() {
        txApproved = false
        txApprovalPromptState = TxApprovalPromptState.Hidden
        getSharedPreferences(CitsBridgeService.PREFS, MODE_PRIVATE).edit()
            .putBoolean(CitsBridgeService.PREF_TX_APPROVED, false)
            .apply()
        if (status.running || status.camEnabled) {
            sendServiceIntent(CitsBridgeService.ACTION_REVOKE_TX_APPROVAL)
        }
        startTxShakeDetection()
        logLine = "TX approval revoked"
    }

    private fun startTxShakeDetection() {
        val sensor = accelerometer ?: run {
            logLine = "Accelerometer unavailable; TX approval cannot be granted on this device"
            return
        }
        sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_UI)
    }

    private fun stopTxShakeDetection() {
        if (::sensorManager.isInitialized) {
            sensorManager.unregisterListener(this)
        }
    }

    private fun setIntersectionLocationActive(active: Boolean) {
        wantsIntersectionLocation = active
        if (active) {
            startOrRequestIntersectionLocation()
        } else {
            stopIntersectionLocationUpdates()
        }
    }

    private fun updateIntersectionSortMode(sortMode: IntersectionSortMode) {
        intersectionSortMode = sortMode
        getSharedPreferences(CitsBridgeService.PREFS, MODE_PRIVATE).edit()
            .putString(PREF_INTERSECTION_SORT_MODE, sortMode.name)
            .apply()
    }

    private fun startOrRequestIntersectionLocation() {
        if (!hasLocationPermission()) {
            requestPermissions(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                ),
                REQUEST_LOCATION,
            )
            return
        }
        startIntersectionLocationUpdates()
    }

    private fun startIntersectionLocationUpdates() {
        if (!hasLocationPermission() || intersectionLocationActive) return
        var requestedProvider = false
        runCatching {
            listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).forEach { provider ->
                runCatching { locationManager.getLastKnownLocation(provider) }
                    .getOrNull()
                    ?.let { updateCurrentPosition(it) }
                if (locationManager.isProviderEnabled(provider)) {
                    locationManager.requestLocationUpdates(
                        provider,
                        INTERSECTION_LOCATION_MIN_TIME_MS,
                        0f,
                        intersectionLocationListener,
                        Looper.getMainLooper(),
                    )
                    requestedProvider = true
                }
            }
        }.onFailure {
            logLine = "Location unavailable: ${it.message}"
            return
        }
        intersectionLocationActive = requestedProvider
        if (!requestedProvider) {
            logLine = "Location provider disabled; position marker unavailable"
        }
    }

    private fun stopIntersectionLocationUpdates() {
        if (!::locationManager.isInitialized || !intersectionLocationActive) return
        runCatching { locationManager.removeUpdates(intersectionLocationListener) }
        intersectionLocationActive = false
    }

    private fun updateCurrentPosition(location: Location) {
        val previous = currentPosition
        if (previous == null ||
            location.time >= previous.timeMs ||
            (location.hasAccuracy() && (previous.accuracyM == null || location.accuracy < previous.accuracyM))
        ) {
            currentPosition = DevicePosition.from(location)
        }
    }

    private fun hasLocationPermission(): Boolean =
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

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
        sremProfileValue: SremProfile = sremProfile,
    ) {
        val parsedMaxQueueLength = parseMaxQueueLength(maxQueueLengthValue)
        val parsedMaxQueueAgeMs = parseMaxQueueAgeMs(maxQueueAgeSecondsValue)
        mqttUri = mqttUriValue.trim()
        nodeId = nodeIdValue.trim()
        maxQueueLength = parsedMaxQueueLength.toString()
        maxQueueAgeSeconds = formatQueueAgeSeconds(parsedMaxQueueAgeMs)
        sremProfile = sremProfileValue
        getSharedPreferences(CitsBridgeService.PREFS, MODE_PRIVATE).edit()
            .putString(CitsBridgeService.PREF_MQTT_URI, mqttUri)
            .putString(CitsBridgeService.PREF_NODE_ID, nodeId)
            .putInt(CitsBridgeService.PREF_MQTT_MAX_QUEUE_LENGTH, parsedMaxQueueLength)
            .putLong(CitsBridgeService.PREF_MQTT_MAX_QUEUE_AGE_MS, parsedMaxQueueAgeMs)
            .putInt(CitsBridgeService.PREF_SREM_PROFILE, sremProfile.preferenceCode)
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
        private const val REQUEST_OPEN_PCAP = 1002
        private const val REQUEST_OPEN_FIRMWARE = 1003
        private const val REQUEST_LOCATION = 1002
        private const val PREF_INTERSECTION_SORT_MODE = "intersection_sort_mode"
        private const val INTERSECTION_LOCATION_MIN_TIME_MS = 500L
        private const val TX_SHAKE_THRESHOLD_G = 2.7f
        private const val TX_SHAKE_COOLDOWN_MS = 1_200L
        private const val ESPRESSIF_USB_VENDOR_ID = 0x303a
        private const val ESPRESSIF_USB_JTAG_SERIAL_PRODUCT_ID = 0x1001
    }
}

private enum class FirmwareFlashingPhase {
    LoadingRelease,
    WaitingForDevice,
    Ready,
    Downloading,
    Flashing,
    Complete,
    Error,
}

private data class FirmwareFlashingState(
    val appVersion: String,
    val releaseTag: String? = null,
    val firmwareName: String? = null,
    val customFirmware: Boolean = false,
    val deviceName: String? = null,
    val phase: FirmwareFlashingPhase = FirmwareFlashingPhase.LoadingRelease,
    val message: String = "Looking for a matching Codeberg release…",
    val progress: Float = 0f,
) {
    val busy: Boolean get() = phase == FirmwareFlashingPhase.Downloading || phase == FirmwareFlashingPhase.Flashing

    companion object {
        fun initial(appVersion: String) = FirmwareFlashingState(appVersion = appVersion)
    }
}

private data class DevicePosition(
    val latitudeE7: Int,
    val longitudeE7: Int,
    val accuracyM: Float?,
    val speedMetersPerSecond: Float?,
    val heading: Int,
    val timeMs: Long,
) {
    companion object {
        fun from(location: Location): DevicePosition = DevicePosition(
            latitudeE7 = (location.latitude * 10_000_000.0).toInt(),
            longitudeE7 = (location.longitude * 10_000_000.0).toInt(),
            accuracyM = location.takeIf { it.hasAccuracy() }?.accuracy,
            speedMetersPerSecond = location.takeIf { it.hasSpeed() && it.speed >= 0.5f }?.speed,
            heading = location.takeIf { it.hasBearing() }
                ?.let { (it.bearing.mod(360f) * 10f).roundToLong().toInt().coerceIn(0, 3_600) }
                ?: 0,
            timeMs = location.time,
        )
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
    intersectionSnapshots: List<IntersectionSnapshot>,
    intersectionSortMode: IntersectionSortMode,
    onIntersectionSortModeChange: (IntersectionSortMode) -> Unit,
    currentPosition: DevicePosition?,
    onRefresh: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onStartPcap: () -> Unit,
    onStopPcap: () -> Unit,
    onStartReplay: () -> Unit,
    onStopReplay: () -> Unit,
    camStationType: StationType,
    onCamStationTypeChange: (StationType) -> Unit,
    camIntervalMs: String,
    onCamIntervalChange: (String) -> Unit,
    onConfigureCam: (Boolean) -> Unit,
    sremProfile: SremProfile,
    onSremProfileChange: (SremProfile) -> Unit,
    txApproved: Boolean,
    txApprovalPromptState: TxApprovalPromptState,
    onGrantTxApproval: () -> Unit,
    onDismissTxApproval: () -> Unit,
    onFinishTxApproval: () -> Unit,
    onRevokeTxApproval: () -> Unit,
    onSendSrem: (IntersectionSnapshot, Int, Int) -> Unit,
    onIntersectionLocationActiveChange: (Boolean) -> Unit,
    flashingState: FirmwareFlashingState,
    onFlashingPageActive: (Boolean) -> Unit,
    onRetryFirmwareRelease: () -> Unit,
    onChooseCustomFirmware: () -> Unit,
    onUseReleaseFirmware: () -> Unit,
    onFlashFirmware: () -> Unit,
    onSaveSettings: (String, String, String, String, SremProfile) -> Unit,
) {
    var selectedPage by rememberSaveable { mutableStateOf(AppPage.Home) }
    var confettiRun by rememberSaveable { mutableStateOf(0) }
    var sliderDragging by remember { mutableStateOf(false) }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val visiblePages = remember(txApproved) {
        AppPage.entries.filter { it.visibleWithTxApproval(txApproved) }
    }
    LaunchedEffect(txApproved) {
        if (selectedPage !in visiblePages) selectedPage = AppPage.Home
    }
    LaunchedEffect(selectedPage) {
        onIntersectionLocationActiveChange(selectedPage == AppPage.IntersectionView)
        onFlashingPageActive(selectedPage == AppPage.Flashing)
    }
    LaunchedEffect(txApprovalPromptState) {
        if (txApprovalPromptState != TxApprovalPromptState.Hidden) {
            drawerState.close()
        }
        if (txApprovalPromptState == TxApprovalPromptState.Granting) {
            delay(500L)
            selectedPage = AppPage.Home
            onFinishTxApproval()
            confettiRun += 1
        }
    }

    CompositionLocalProvider(LocalSliderDragStateChange provides { sliderDragging = it }) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Box(Modifier.fillMaxSize()) {
                ModalNavigationDrawer(
                drawerState = drawerState,
                gesturesEnabled = !sliderDragging && txApprovalPromptState == TxApprovalPromptState.Hidden,
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
                        visiblePages.forEach { page ->
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
                val mainScrollState = rememberScrollState()
                val contentModifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(20.dp)
                    .then(
                        if (selectedPage == AppPage.IntersectionView) {
                            Modifier
                        } else {
                            Modifier.verticalScroll(mainScrollState)
                        },
                    )
                Column(
                    modifier = contentModifier,
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
                            onStartReplay = onStartReplay,
                            onStopReplay = onStopReplay,
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
                            snapshots = intersectionSnapshots,
                            sortMode = intersectionSortMode,
                            onSortModeChange = onIntersectionSortModeChange,
                            status = status,
                            currentPosition = currentPosition,
                            txApproved = txApproved,
                            sremProfile = sremProfile,
                            onSendSrem = onSendSrem,
                            modifier = Modifier.weight(1f),
                        )
                        AppPage.Flashing -> FlashingPage(
                            state = flashingState,
                            bridgeRunning = status.running,
                            onRetryRelease = onRetryFirmwareRelease,
                            onChooseCustomFirmware = onChooseCustomFirmware,
                            onUseReleaseFirmware = onUseReleaseFirmware,
                            onFlash = onFlashFirmware,
                        )
                        AppPage.Settings -> SettingsPage(
                            mqttUri = mqttUri,
                            nodeId = nodeId,
                            maxQueueLength = maxQueueLength,
                            maxQueueAgeSeconds = maxQueueAgeSeconds,
                            txApproved = txApproved,
                            sremProfile = sremProfile,
                            onRevokeTxApproval = onRevokeTxApproval,
                            onSave = { updatedMqttUri, updatedNodeId, updatedMaxQueueLength, updatedMaxQueueAgeSeconds, updatedSremProfile ->
                                onMqttUriChange(updatedMqttUri)
                                onNodeIdChange(updatedNodeId)
                                onMaxQueueLengthChange(updatedMaxQueueLength)
                                onMaxQueueAgeSecondsChange(updatedMaxQueueAgeSeconds)
                                onSremProfileChange(updatedSremProfile)
                                onSaveSettings(
                                    updatedMqttUri,
                                    updatedNodeId,
                                    updatedMaxQueueLength,
                                    updatedMaxQueueAgeSeconds,
                                    updatedSremProfile,
                                )
                            },
                        )
                    }
                }
            }
                TxApprovalOverlay(
                    state = txApprovalPromptState,
                    onGrantApproval = onGrantTxApproval,
                    onDismiss = onDismissTxApproval,
                )
                ConfettiOverlay(run = confettiRun)
            }
        }
    }
}

private val LocalSliderDragStateChange = compositionLocalOf<(Boolean) -> Unit> { {} }

private enum class TxApprovalPromptState {
    Hidden,
    Ready,
    Granting,
}

private enum class AppPage(val title: String) {
    Home("Home"),
    CamBroadcast("CAM Broadcast"),
    IntersectionView("Intersection View"),
    Flashing("Flashing"),
    Settings("Settings"),
    ;

    fun visibleWithTxApproval(txApproved: Boolean): Boolean = when (this) {
        CamBroadcast -> txApproved
        else -> true
    }
}

@Composable
private fun FlashingPage(
    state: FirmwareFlashingState,
    bridgeRunning: Boolean,
    onRetryRelease: () -> Unit,
    onChooseCustomFirmware: () -> Unit,
    onUseReleaseFirmware: () -> Unit,
    onFlash: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(Color.White, RoundedCornerShape(8.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("ESP32-C5 firmware", style = MaterialTheme.typography.titleMedium)
        Text(
            "Install the firmware artifact matching this app version, or select a custom merged firmware.bin file.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.secondary,
        )
        FlashingDetail("App version", state.appVersion)
        FlashingDetail("Source", if (state.customFirmware) "Custom file" else state.releaseTag ?: "Checking…")
        FlashingDetail("Firmware", state.firmwareName ?: "Checking…")
        FlashingDetail("Device", state.deviceName ?: "Waiting for ESP32-C5…")
        Text(
            if (bridgeRunning && state.phase == FirmwareFlashingPhase.Ready) {
                "Stop the receiver on the Home page before flashing."
            } else {
                state.message
            },
            style = MaterialTheme.typography.bodyMedium,
            color = when (state.phase) {
                FirmwareFlashingPhase.Error -> Color(0xFFB91C1C)
                FirmwareFlashingPhase.Complete -> Color(0xFF047857)
                else -> MaterialTheme.colorScheme.secondary
            },
        )
        if (state.busy || state.phase == FirmwareFlashingPhase.Complete) {
            LinearProgressIndicator(
                progress = { state.progress.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
            )
            Text("${(state.progress * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
        }
        if (state.phase == FirmwareFlashingPhase.Error && state.releaseTag == null && !state.customFirmware) {
            Button(onClick = onRetryRelease, modifier = Modifier.fillMaxWidth()) {
                Text("Retry release lookup")
            }
        }
        Button(
            onClick = onChooseCustomFirmware,
            enabled = !state.busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (state.customFirmware) "Choose another firmware.bin" else "Choose custom firmware.bin")
        }
        if (state.customFirmware) {
            TextButton(
                onClick = onUseReleaseFirmware,
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Use matching release instead")
            }
        }
    }
    if (state.phase == FirmwareFlashingPhase.Ready ||
        (state.phase == FirmwareFlashingPhase.Error && state.firmwareName != null && state.deviceName != null)
    ) {
        var position by remember(state.deviceName, state.message) { mutableStateOf(0f) }
        var submitted by remember(state.deviceName, state.message) { mutableStateOf(false) }
        Text(
            "Put the ESP32-C5 into boot mode before flashing: hold the BOOT button while connecting or resetting the board, and keep it held until flashing starts. Keep USB connected until verification finishes.",
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFFB45309),
            fontWeight = FontWeight.SemiBold,
        )
        TxApprovalSlider(
            position = position,
            onPositionChange = {
                position = it
                if (!submitted && it >= 0.995f) {
                    submitted = true
                    position = 1f
                    onFlash()
                }
            },
            enabled = !bridgeRunning,
            modifier = Modifier.fillMaxWidth(),
        )
        Text("Slide fully to flash", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun FlashingDetail(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, modifier = Modifier.width(88.dp), fontWeight = FontWeight.SemiBold)
        Text(value, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
    }
}

private enum class IntersectionSortMode(val label: String) {
    FirstReceived("first received"),
    Distance("distance"),
    ;

    companion object {
        fun fromPreference(value: String?): IntersectionSortMode =
            entries.firstOrNull { it.name == value } ?: FirstReceived
    }
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
    onStartReplay: () -> Unit,
    onStopReplay: () -> Unit,
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
    ReplayRow(status, onStartReplay, onStopReplay)
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
private fun TxApprovalOverlay(
    state: TxApprovalPromptState,
    onGrantApproval: () -> Unit,
    onDismiss: () -> Unit,
) {
    if (state == TxApprovalPromptState.Hidden) return
    var sliderPosition by rememberSaveable(state) {
        mutableStateOf(if (state == TxApprovalPromptState.Granting) 1f else 0f)
    }
    var submitted by rememberSaveable(state) { mutableStateOf(state == TxApprovalPromptState.Granting) }
    val granting = state == TxApprovalPromptState.Granting
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFEFF6F5))
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .pointerInput(state) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Final)
                        event.changes.forEach { it.consume() }
                        if (event.changes.none { it.pressed }) break
                    }
                }
            },
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(20.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(Color.White, RoundedCornerShape(8.dp))
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("TX approval", style = MaterialTheme.typography.titleMedium)
                Text(
                    "You are responsible for any regulatory matters when transmitting data. Consult local legislation before transmitting.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFFB45309),
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    if (granting) {
                        "TX approval granted. Returning home..."
                    } else {
                        "Pull the red handle fully from left to right to approve TX."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary,
                )
                TextButton(
                    onClick = onDismiss,
                    enabled = !granting,
                ) {
                    Text("Cancel")
                }
            }
            TxApprovalSlider(
                position = sliderPosition,
                onPositionChange = {
                    sliderPosition = it
                    if (!submitted && it >= 0.995f) {
                        submitted = true
                        sliderPosition = 1f
                        onGrantApproval()
                    }
                },
                enabled = !granting,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun TxApprovalSlider(
    position: Float,
    onPositionChange: (Float) -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    var sliderSize by remember { mutableStateOf(IntSize.Zero) }
    val onDragStateChange = LocalSliderDragStateChange.current
    val constrainedPosition = position.coerceIn(0f, 1f)
    Canvas(
        modifier = modifier
            .height(96.dp)
            .onSizeChanged { sliderSize = it }
            .pointerInput(enabled, sliderSize.width) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    val width = sliderSize.width.toFloat()
                    if (!enabled || width <= 0f) return@awaitEachGesture
                    val thumbRadius = 30.dp.toPx()
                    val startX = thumbRadius
                    val endX = width - thumbRadius
                    val thumbX = startX + (endX - startX) * position.coerceIn(0f, 1f)
                    val hitSlop = 18.dp.toPx()
                    if (kotlin.math.abs(down.position.x - thumbX) > thumbRadius + hitSlop) {
                        return@awaitEachGesture
                    }
                    down.consume()
                    onDragStateChange(true)
                    try {
                        val startPosition = position.coerceIn(0f, 1f)
                        val downX = down.position.x
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            event.changes.forEach { it.consume() }
                            val active = event.changes.firstOrNull { it.pressed }
                            if (active == null) break
                            val dragFraction = (active.position.x - downX) / (endX - startX)
                            onPositionChange((startPosition + dragFraction).coerceIn(0f, 1f))
                        }
                    } finally {
                        onDragStateChange(false)
                    }
                }
            },
    ) {
        val trackHeight = 66.dp.toPx()
        val thumbRadius = 30.dp.toPx()
        val centerY = size.height / 2f
        val startX = thumbRadius
        val endX = size.width - thumbRadius
        val thumbX = startX + (endX - startX) * constrainedPosition
        drawLine(
            color = if (enabled) Color(0xFFFECACA) else Color(0xFFFEE2E2),
            start = Offset(startX, centerY),
            end = Offset(endX, centerY),
            strokeWidth = trackHeight,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = Color(0xFFB91C1C),
            start = Offset(startX, centerY),
            end = Offset(thumbX, centerY),
            strokeWidth = trackHeight,
            cap = StrokeCap.Round,
        )
        drawCircle(Color.White, radius = thumbRadius + 3.dp.toPx(), center = Offset(thumbX, centerY))
        drawCircle(Color(0xFFB91C1C), radius = thumbRadius, center = Offset(thumbX, centerY))
        val arrowLength = 22.dp.toPx()
        val arrowHead = 8.dp.toPx()
        val arrowStart = Offset(thumbX - arrowLength / 2f, centerY)
        val arrowEnd = Offset(thumbX + arrowLength / 2f, centerY)
        drawLine(
            color = Color.White,
            start = arrowStart,
            end = arrowEnd,
            strokeWidth = 4.dp.toPx(),
            cap = StrokeCap.Round,
        )
        drawLine(
            color = Color.White,
            start = arrowEnd,
            end = Offset(arrowEnd.x - arrowHead, arrowEnd.y - arrowHead),
            strokeWidth = 4.dp.toPx(),
            cap = StrokeCap.Round,
        )
        drawLine(
            color = Color.White,
            start = arrowEnd,
            end = Offset(arrowEnd.x - arrowHead, arrowEnd.y + arrowHead),
            strokeWidth = 4.dp.toPx(),
            cap = StrokeCap.Round,
        )
    }
}

@Composable
private fun ConfettiOverlay(run: Int) {
    if (run <= 0) return
    var progress by remember(run) { mutableStateOf(0f) }
    val confetti = remember(run) {
        List(72) { index ->
            ConfettiPiece(
                startX = ((index * 37) % 100) / 100f,
                drift = (((index * 17) % 41) - 20) / 100f,
                spin = ((index % 9) + 2).toFloat(),
                color = CONFETTI_COLORS[index % CONFETTI_COLORS.size],
            )
        }
    }
    LaunchedEffect(run) {
        val startMs = SystemClock.elapsedRealtime()
        do {
            progress = ((SystemClock.elapsedRealtime() - startMs) / CONFETTI_DURATION_MS.toFloat()).coerceIn(0f, 1f)
            delay(16L)
        } while (progress < 1f)
    }
    if (progress >= 1f) return
    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        val eased = 1f - (1f - progress) * (1f - progress)
        confetti.forEachIndexed { index, piece ->
            val x = size.width * (
                piece.startX +
                    piece.drift * eased +
                    sin((progress * piece.spin + index) * 2.1f) * 0.025f
                )
            val y = size.height * (-0.1f + eased * 1.15f) - ((index % 6) * 22.dp.toPx())
            if (y < -24.dp.toPx() || y > size.height + 24.dp.toPx()) return@forEachIndexed
            drawRoundRect(
                color = piece.color.copy(alpha = (1f - progress).coerceIn(0.2f, 1f)),
                topLeft = Offset(x, y),
                size = Size(8.dp.toPx(), 16.dp.toPx()),
                cornerRadius = CornerRadius(2.dp.toPx(), 2.dp.toPx()),
            )
        }
    }
}

private data class ConfettiPiece(
    val startX: Float,
    val drift: Float,
    val spin: Float,
    val color: Color,
)

private val CONFETTI_COLORS = listOf(
    Color(0xFF0F766E),
    Color(0xFF2563EB),
    Color(0xFFB45309),
    Color(0xFFDC2626),
    Color(0xFF7C3AED),
    Color(0xFF16A34A),
)

private const val CONFETTI_DURATION_MS = 1_600L

@Composable
private fun IntersectionViewPage(
    snapshots: List<IntersectionSnapshot>,
    sortMode: IntersectionSortMode,
    onSortModeChange: (IntersectionSortMode) -> Unit,
    status: BridgeStatus,
    currentPosition: DevicePosition?,
    txApproved: Boolean,
    sremProfile: SremProfile,
    onSendSrem: (IntersectionSnapshot, Int, Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (snapshots.isEmpty()) {
        Column(
            modifier = modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            IntersectionPageContent(null, status, currentPosition, txApproved, sremProfile, onSendSrem)
        }
        return
    }

    val sortedSnapshots = remember(snapshots, sortMode, currentPosition) {
        when (sortMode) {
            IntersectionSortMode.FirstReceived -> snapshots.sortedWith(
                compareBy<IntersectionSnapshot> { it.firstReceivedAtMs }
                    .thenBy { it.updatedAtMs },
            )
            IntersectionSortMode.Distance -> snapshots.sortedWith(
                compareBy<IntersectionSnapshot> {
                    it.distanceTo(currentPosition) ?: Double.POSITIVE_INFINITY
                }.thenBy { it.firstReceivedAtMs },
            )
        }
    }
    val pagerState = rememberPagerState(pageCount = { sortedSnapshots.size })
    LaunchedEffect(snapshots.size) {
        if (pagerState.currentPage >= sortedSnapshots.size) {
            pagerState.scrollToPage(sortedSnapshots.lastIndex)
        }
    }
    LaunchedEffect(sortMode) {
        pagerState.scrollToPage(0)
    }

    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (sortedSnapshots.size > 1) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "${pagerState.currentPage + 1} / ${sortedSnapshots.size}",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary,
                )
                TextButton(
                    onClick = {
                        onSortModeChange(when (sortMode) {
                            IntersectionSortMode.FirstReceived -> IntersectionSortMode.Distance
                            IntersectionSortMode.Distance -> IntersectionSortMode.FirstReceived
                        })
                    },
                ) {
                    Text("Sort: ${sortMode.label}")
                }
            }
        }
        if (sortedSnapshots.size > 1 && sortMode == IntersectionSortMode.Distance && currentPosition == null) {
            Text(
                "Waiting for location fix; showing first-received order.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary,
            )
        }
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) { page ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 2.dp),
            ) {
                IntersectionPageContent(sortedSnapshots[page], status, currentPosition, txApproved, sremProfile, onSendSrem)
            }
        }
    }
}

@Composable
private fun IntersectionPageContent(
    snapshot: IntersectionSnapshot?,
    status: BridgeStatus,
    currentPosition: DevicePosition?,
    txApproved: Boolean,
    sremProfile: SremProfile,
    onSendSrem: (IntersectionSnapshot, Int, Int) -> Unit,
) {
    val map = snapshot?.map
    val spat = snapshot?.spat
    var selectedCrosswalkLaneIds by rememberSaveable(map?.key.toString(), map?.revision) {
        mutableStateOf<List<Int>>(emptyList())
    }
    val selectedPair = selectedCrosswalkLaneIds.takeIf { it.size == 2 }
    val sremUiState = sremUiState(status, snapshot, selectedCrosswalkLaneIds, spat)
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
                snapshot.updatedAtMs.takeIf { it > 0L }?.let { "last ${formatIntersectionAge(it)}" },
                "SREM ${sremProfile.displayName}",
            ).joinToString(" • "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.secondary,
        )
        if (map == null) {
            Text("SPATEM received; waiting for matching MAPEM geometry.", color = MaterialTheme.colorScheme.secondary)
        } else {
            IntersectionRenderer(
                map = map,
                spat = spat,
                currentPosition = currentPosition,
                selectedCrosswalkLaneIds = selectedCrosswalkLaneIds,
                onCrosswalkLaneTap = { lane ->
                    selectedCrosswalkLaneIds = nextCrosswalkSelection(map, selectedCrosswalkLaneIds, lane.id)
                },
            )
            SremRequestPanel(
                snapshot = snapshot,
                selectedLaneIds = selectedCrosswalkLaneIds,
                state = sremUiState,
                txApproved = txApproved,
                status = status,
                currentPosition = currentPosition,
                onClear = { selectedCrosswalkLaneIds = emptyList() },
                onSend = {
                    val pair = selectedPair ?: return@SremRequestPanel
                    onSendSrem(snapshot, pair[0], pair[1])
                },
            )
        }
    }
    SignalTimingPanel(snapshot, Modifier.padding(top = 12.dp))
}

private enum class SremRequestUiState(
    val label: String,
    val detail: String,
    val color: Color,
) {
    SelectFirst("Select inbound lane", "Tap any MAPEM lane in the intersection view.", Color(0xFF475569)),
    SelectSecond("Select connected outbound lane", "Lanes without a declared local connection are dimmed.", Color(0xFF0F766E)),
    NotReady("Cannot request yet", "Start capture, approve TX, and wait for a fresh location.", Color(0xFFD97706)),
    Ready("Slide left to request green", "The request will be sent as an SREM.", Color(0xFF0F766E)),
    Queued("Request queued", "Waiting for firmware transmit acknowledgement.", Color(0xFF2563EB)),
    Transmitted("SREM transmitted", "Waiting for response or signal change.", Color(0xFF2563EB)),
    Acknowledged("Request acknowledged", "The controller reported that it received the request.", Color(0xFF2563EB)),
    Processing("Controller processing", "The controller is processing the request.", Color(0xFF2563EB)),
    WatchOtherTraffic("Watch other traffic", "The controller granted limited priority with caution.", Color(0xFFD97706)),
    Granted("Request granted", "Waiting for the requested movement to become active.", Color(0xFF16A34A)),
    WalkActive("Requested movement active", "SPATEM reports a permitted movement phase.", Color(0xFF16A34A)),
    Rejected("Request rejected", "The controller rejected this request.", Color(0xFFB91C1C)),
    UnknownResponse("Response unclear", "The controller response could not be classified.", Color(0xFFD97706)),
    Failed("Request failed", "The SREM could not be transmitted.", Color(0xFFB91C1C)),
    TimedOut("No response observed", "No matching signal change was seen in time.", Color(0xFFD97706)),
}

@Composable
private fun SremRequestPanel(
    snapshot: IntersectionSnapshot,
    selectedLaneIds: List<Int>,
    state: SremRequestUiState,
    txApproved: Boolean,
    status: BridgeStatus,
    currentPosition: DevicePosition?,
    onClear: () -> Unit,
    onSend: () -> Unit,
) {
    val selectedPair = selectedLaneIds.takeIf { it.size == 2 }
    val sliderEnabled = selectedPair != null &&
        state == SremRequestUiState.Ready &&
        txApproved &&
        status.running &&
        currentPosition?.isFreshForSrem() == true
    val displayState = if (selectedPair != null && state == SremRequestUiState.Ready && !sliderEnabled) {
        SremRequestUiState.NotReady
    } else {
        state
    }
    if (selectedLaneIds.isEmpty()) {
        Text(
            state.detail,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.secondary,
        )
        return
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFFF8FAFC), RoundedCornerShape(8.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                selectedPair?.let { "Lane ${it[0]} -> ${it[1]}" } ?: "Lane ${selectedLaneIds.first()} selected",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            TextButton(onClick = onClear) {
                Text("Clear")
            }
        }
        Text(
            displayState.label,
            style = MaterialTheme.typography.bodyMedium,
            color = displayState.color,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            requestDetailText(displayState, status, snapshot, selectedPair),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.secondary,
        )
        if (selectedPair != null) {
            SremRequestSlider(
                state = displayState,
                enabled = sliderEnabled,
                onSubmit = onSend,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

private fun requestDetailText(
    state: SremRequestUiState,
    status: BridgeStatus,
    snapshot: IntersectionSnapshot,
    selectedPair: List<Int>?,
): String {
    if (selectedPair == null) return state.detail
    val requestId = status.lastSremRequestId.takeIf { it >= 0 }?.let { "request $it" }
    val updated = status.lastSremUpdatedAtMs.takeIf { it > 0L }?.let { "last ${formatIntersectionAge(it)}" }
    val sremDetail = listOfNotNull(requestId, updated).joinToString(" • ")
    val base = when (state) {
        SremRequestUiState.Queued,
        SremRequestUiState.Transmitted,
        SremRequestUiState.Acknowledged,
        SremRequestUiState.Processing,
        SremRequestUiState.WatchOtherTraffic,
        SremRequestUiState.Granted,
        SremRequestUiState.Rejected,
        SremRequestUiState.UnknownResponse,
        SremRequestUiState.Failed,
        SremRequestUiState.TimedOut -> status.lastSremSummary.ifBlank { state.detail }
        else -> state.detail
    }
    val id = snapshot.map?.key?.let { "intersection $it" }
    return listOfNotNull(base, sremDetail, id).filter { it.isNotBlank() }.joinToString(" • ")
}

@Composable
private fun SremRequestSlider(
    state: SremRequestUiState,
    enabled: Boolean,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var sliderPosition by rememberSaveable(state, enabled) {
        mutableStateOf(
            if (state in setOf(
                    SremRequestUiState.Queued,
                    SremRequestUiState.Transmitted,
                    SremRequestUiState.Acknowledged,
                    SremRequestUiState.Processing,
                    SremRequestUiState.WatchOtherTraffic,
                    SremRequestUiState.Granted,
                    SremRequestUiState.Rejected,
                    SremRequestUiState.UnknownResponse,
                    SremRequestUiState.WalkActive,
                )
            ) {
                1f
            } else {
                0f
            },
        )
    }
    var submitted by rememberSaveable(state) { mutableStateOf(false) }
    var animateReset by remember { mutableStateOf(false) }
    val targetPosition by remember(state, sliderPosition, animateReset) {
        mutableFloatStateOf(
            if (animateReset && state == SremRequestUiState.Ready) 0f else sliderPosition
        )
    }
    val animatedPosition by animateFloatAsState(
        targetValue = targetPosition,
        animationSpec = tween(durationMillis = 300),
        label = "sliderReset",
    )
    val constrainedPosition = animatedPosition.coerceIn(0f, 1f)
    var sliderSize by remember { mutableStateOf(IntSize.Zero) }
    val onDragStateChange = LocalSliderDragStateChange.current
    val scope = rememberCoroutineScope()
    Canvas(
        modifier = modifier
            .height(96.dp)
            .onSizeChanged { sliderSize = it }
            .pointerInput(enabled, sliderSize.width, state) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    val width = sliderSize.width.toFloat()
                    if (!enabled || width <= 0f) return@awaitEachGesture
                    val thumbRadius = 30.dp.toPx()
                    val startX = thumbRadius
                    val endX = width - thumbRadius
                    val thumbX = endX - (endX - startX) * constrainedPosition
                    val hitSlop = 18.dp.toPx()
                    if (kotlin.math.abs(down.position.x - thumbX) > thumbRadius + hitSlop) {
                        return@awaitEachGesture
                    }
                    down.consume()
                    onDragStateChange(true)
                    try {
                        val startPosition = constrainedPosition
                        val downX = down.position.x
                        animateReset = false
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            event.changes.forEach { it.consume() }
                            val active = event.changes.firstOrNull { it.pressed }
                            if (active == null) break
                            val dragFraction = (downX - active.position.x) / (endX - startX)
                            val nextPosition = (startPosition + dragFraction).coerceIn(0f, 1f)
                            sliderPosition = nextPosition
                            if (!submitted && nextPosition >= 0.995f) {
                                submitted = true
                                sliderPosition = 1f
                                onSubmit()
                            }
                        }
                        if (!submitted && state == SremRequestUiState.Ready) {
                            animateReset = true
                        }
                    } finally {
                        onDragStateChange(false)
                    }
                }
            },
    ) {
        val trackHeight = 66.dp.toPx()
        val thumbRadius = 30.dp.toPx()
        val centerY = size.height / 2f
        val startX = thumbRadius
        val endX = size.width - thumbRadius
        val thumbX = endX - (endX - startX) * constrainedPosition
        val trackColor = if (enabled) state.color.copy(alpha = 0.22f) else Color(0xFFE2E8F0)
        val fillColor = if (enabled || constrainedPosition > 0f) state.color else Color(0xFF94A3B8)
        drawLine(
            color = trackColor,
            start = Offset(startX, centerY),
            end = Offset(endX, centerY),
            strokeWidth = trackHeight,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = fillColor,
            start = Offset(thumbX, centerY),
            end = Offset(endX, centerY),
            strokeWidth = trackHeight,
            cap = StrokeCap.Round,
        )
        drawCircle(Color.White, radius = thumbRadius + 3.dp.toPx(), center = Offset(thumbX, centerY))
        drawCircle(fillColor, radius = thumbRadius, center = Offset(thumbX, centerY))
        drawSremSliderIcon(state, Offset(thumbX, centerY), thumbRadius)
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawSremSliderIcon(
    state: SremRequestUiState,
    center: Offset,
    radius: Float,
) {
    val white = Color.White
    val strokeWidth = 4.dp.toPx()
    when (state) {
        SremRequestUiState.WalkActive,
        SremRequestUiState.Granted -> {
            drawLine(white, center + Offset(-10.dp.toPx(), 0f), center + Offset(-2.dp.toPx(), 9.dp.toPx()), strokeWidth, cap = StrokeCap.Round)
            drawLine(white, center + Offset(-2.dp.toPx(), 9.dp.toPx()), center + Offset(13.dp.toPx(), -10.dp.toPx()), strokeWidth, cap = StrokeCap.Round)
        }
        SremRequestUiState.Failed,
        SremRequestUiState.Rejected -> {
            drawLine(white, center + Offset(-10.dp.toPx(), -10.dp.toPx()), center + Offset(10.dp.toPx(), 10.dp.toPx()), strokeWidth, cap = StrokeCap.Round)
            drawLine(white, center + Offset(10.dp.toPx(), -10.dp.toPx()), center + Offset(-10.dp.toPx(), 10.dp.toPx()), strokeWidth, cap = StrokeCap.Round)
        }
        SremRequestUiState.Queued,
        SremRequestUiState.Transmitted,
        SremRequestUiState.Acknowledged,
        SremRequestUiState.Processing,
        SremRequestUiState.WatchOtherTraffic,
        SremRequestUiState.UnknownResponse,
        SremRequestUiState.TimedOut -> {
            drawCircle(white, radius = radius * 0.34f, center = center, style = Stroke(width = strokeWidth))
            drawLine(white, center, center + Offset(0f, -12.dp.toPx()), strokeWidth, cap = StrokeCap.Round)
            drawLine(white, center, center + Offset(10.dp.toPx(), 4.dp.toPx()), strokeWidth, cap = StrokeCap.Round)
        }
        else -> {
            val arrowLength = 22.dp.toPx()
            val arrowHead = 8.dp.toPx()
            val arrowStart = Offset(center.x + arrowLength / 2f, center.y)
            val arrowEnd = Offset(center.x - arrowLength / 2f, center.y)
            drawLine(white, arrowStart, arrowEnd, strokeWidth, cap = StrokeCap.Round)
            drawLine(white, arrowEnd, Offset(arrowEnd.x + arrowHead, arrowEnd.y - arrowHead), strokeWidth, cap = StrokeCap.Round)
            drawLine(white, arrowEnd, Offset(arrowEnd.x + arrowHead, arrowEnd.y + arrowHead), strokeWidth, cap = StrokeCap.Round)
        }
    }
}

private fun sremUiState(
    status: BridgeStatus,
    snapshot: IntersectionSnapshot?,
    selectedLaneIds: List<Int>,
    spat: SpatIntersection?,
): SremRequestUiState {
    val selectedPair = selectedLaneIds.takeIf { it.size == 2 }
    if (selectedPair == null) {
        return if (selectedLaneIds.size == 1) SremRequestUiState.SelectSecond else SremRequestUiState.SelectFirst
    }
    val map = snapshot?.map
    val matchingStatus = map != null &&
        status.lastSremIntersectionId == map.key.id &&
        status.lastSremInboundLaneId == selectedPair[0] &&
        status.lastSremOutboundLaneId == selectedPair[1]
    if (matchingStatus) {
        when (status.lastSremState) {
            CitsBridgeService.SREM_STATE_FAILED -> return SremRequestUiState.Failed
            CitsBridgeService.SREM_STATE_REJECTED -> return SremRequestUiState.Rejected
            CitsBridgeService.SREM_STATE_GRANTED -> {
                return if (isSelectedCrossingWalkActive(map, selectedPair, spat)) {
                    SremRequestUiState.WalkActive
                } else {
                    SremRequestUiState.Granted
                }
            }
            CitsBridgeService.SREM_STATE_ACKNOWLEDGED -> return SremRequestUiState.Acknowledged
            CitsBridgeService.SREM_STATE_PROCESSING -> return SremRequestUiState.Processing
            CitsBridgeService.SREM_STATE_WATCH_OTHER_TRAFFIC -> return SremRequestUiState.WatchOtherTraffic
            CitsBridgeService.SREM_STATE_UNKNOWN_RESPONSE -> return SremRequestUiState.UnknownResponse
            CitsBridgeService.SREM_STATE_QUEUED -> return SremRequestUiState.Queued
            CitsBridgeService.SREM_STATE_TRANSMITTED -> {
                val ageMs = System.currentTimeMillis() - status.lastSremUpdatedAtMs
                return if (ageMs > SREM_RESPONSE_TIMEOUT_MS) {
                    SremRequestUiState.TimedOut
                } else {
                    SremRequestUiState.Transmitted
                }
            }
        }
    }
    if (isSelectedCrossingWalkActive(snapshot?.map, selectedPair, spat)) return SremRequestUiState.WalkActive
    return if (status.running) SremRequestUiState.Ready else SremRequestUiState.NotReady
}

private fun isSelectedCrossingWalkActive(
    map: MapIntersection?,
    selectedPair: List<Int>,
    spat: SpatIntersection?,
): Boolean {
    val lanesById = map?.lanes?.associateBy { it.id } ?: return false
    val first = lanesById[selectedPair[0]] ?: return false
    val second = lanesById[selectedPair[1]] ?: return false
    val signalGroups = spat?.movementsBySignalGroup.orEmpty()
    val signalGroup = first.connections.firstOrNull {
        it.remoteIntersection == null && it.laneId == second.id
    }?.signalGroup
        ?: second.connections.firstOrNull {
            it.remoteIntersection == null && it.laneId == first.id
        }?.signalGroup
        ?: return false
    return signalGroups[signalGroup]?.currentEvent?.state in setOf(
        MovementPhaseState.PermissiveAllowed,
        MovementPhaseState.ProtectedAllowed,
    )
}

private fun DevicePosition.isFreshForSrem(nowMs: Long = System.currentTimeMillis()): Boolean =
    timeMs in (nowMs - SREM_MAX_LOCATION_AGE_MS)..(nowMs + 1_000L)

private fun formatIntersectionAge(updatedAtMs: Long, nowMs: Long = System.currentTimeMillis()): String {
    val ageSeconds = ((nowMs - updatedAtMs).coerceAtLeast(0L) / 1_000L)
    return when {
        ageSeconds < 2L -> "just now"
        ageSeconds < 60L -> "${ageSeconds}s ago"
        else -> "${ageSeconds / 60L}m ${ageSeconds % 60L}s ago"
    }
}

private fun IntersectionSnapshot.distanceTo(position: DevicePosition?): Double? {
    val map = map ?: return null
    val devicePosition = position ?: return null
    return map.distanceTo(devicePosition.latitudeE7, devicePosition.longitudeE7)
}

private fun DevicePosition.toIntersectionOffsetCm(map: MapIntersection): Offset {
    val latitudeScaleCm = E7_DEGREE_TO_CM
    val longitudeScaleCm = E7_DEGREE_TO_CM * cos(Math.toRadians(map.latitude / 10_000_000.0)).toFloat()
    return Offset(
        x = (longitudeE7 - map.longitude) * longitudeScaleCm,
        y = (latitudeE7 - map.latitude) * latitudeScaleCm,
    )
}

private fun sremPackageRequestTimeMs(
    map: MapIntersection,
    inboundLaneId: Int,
    position: DevicePosition,
    profile: SremProfile,
    nowMs: Long,
): Long {
    val lane = map.lanes.firstOrNull { it.id == inboundLaneId }
    val positionCm = position.toIntersectionOffsetCm(map)
    val distanceMeters = lane
        ?.nodes
        ?.takeIf { it.isNotEmpty() }
        ?.let { nodes ->
            val distanceCm = if (nodes.size == 1) {
                hypot(
                    (positionCm.x - nodes[0].xCm).toDouble(),
                    (positionCm.y - nodes[0].yCm).toDouble(),
                )
            } else {
                nodes.zipWithNext().minOf { (start, end) ->
                    distanceToSegment(
                        positionCm,
                        Offset(start.xCm.toFloat(), start.yCm.toFloat()),
                        Offset(end.xCm.toFloat(), end.yCm.toFloat()),
                    ).toDouble()
                }
            }
            distanceCm / 100.0
        }
        ?: map.distanceTo(position.latitudeE7, position.longitudeE7)
    return estimateSremRequestTimeMs(nowMs, distanceMeters, position.speedMetersPerSecond, profile)
}

private const val INTERSECTION_MAX_ZOOM = 6f
private const val LANE_TIMING_ZOOM_THRESHOLD = 2.2f
private const val E7_DEGREE_TO_CM = 1.1132f
private const val DOUBLE_TAP_TIMEOUT_MS = 300L
private const val TAP_TIMEOUT_MS = 220L
private const val QUICK_SCALE_SENSITIVITY = 0.006f
private const val SREM_MAX_LOCATION_AGE_MS = 5_000L
private const val SREM_RESPONSE_TIMEOUT_MS = 15_000L

private fun clampIntersectionPan(
    pan: Offset,
    zoomScale: Float,
    viewportWidth: Float,
    viewportHeight: Float,
): Offset {
    if (zoomScale <= 1.0001f || viewportWidth <= 0f || viewportHeight <= 0f) return Offset.Zero
    return Offset(
        x = pan.x.coerceIn(viewportWidth - viewportWidth * zoomScale, 0f),
        y = pan.y.coerceIn(viewportHeight - viewportHeight * zoomScale, 0f),
    )
}

private fun nextCrosswalkSelection(
    map: MapIntersection,
    selectedLaneIds: List<Int>,
    tappedLaneId: Int,
): List<Int> {
    val tappedLane = map.lanes.firstOrNull { it.id == tappedLaneId }
        ?: return selectedLaneIds
    if (selectedLaneIds.isEmpty()) return listOf(tappedLane.id)
    val firstLaneId = selectedLaneIds.first()
    if (tappedLane.id == firstLaneId) return emptyList()
    if (selectedLaneIds.size == 1) {
        return if (tappedLane.id in connectedSremLaneIds(map, firstLaneId)) {
            resolveSremLaneDirection(map, firstLaneId, tappedLane.id)
        } else {
            listOf(tappedLane.id)
        }
    }
    return listOf(tappedLane.id)
}

private fun connectedCrosswalkLaneIds(map: MapIntersection, laneId: Int): Set<Int> {
    return connectedSremLaneIds(map, laneId)
}

private fun hitTestCrosswalkLane(
    map: MapIntersection,
    canvasSize: IntSize,
    zoomScale: Float,
    pan: Offset,
    tap: Offset,
    paddingPx: Float,
    hitSlopPx: Float,
): MapLane? {
    if (canvasSize.width <= 0 || canvasSize.height <= 0) return null
    val allNodes = map.lanes.flatMap { it.nodes }
    if (allNodes.isEmpty()) return null
    val minX = allNodes.minOf { it.xCm }.toFloat()
    val maxX = allNodes.maxOf { it.xCm }.toFloat()
    val minY = allNodes.minOf { it.yCm }.toFloat()
    val maxY = allNodes.maxOf { it.yCm }.toFloat()
    val width = (maxX - minX).coerceAtLeast(1f)
    val height = (maxY - minY).coerceAtLeast(1f)
    val scale = minOf((canvasSize.width - paddingPx * 2) / width, (canvasSize.height - paddingPx * 2) / height)

    fun point(node: LaneNode): Offset {
        val base = Offset(
            x = paddingPx + (node.xCm - minX) * scale,
            y = canvasSize.height - paddingPx - (node.yCm - minY) * scale,
        )
        return Offset(
            x = base.x * zoomScale + pan.x,
            y = base.y * zoomScale + pan.y,
        )
    }

    return map.lanes
        .filter { it.nodes.size >= 2 }
        .mapNotNull { lane ->
            val distance = lane.nodes.zipWithNext().minOf { (start, end) ->
                distanceToSegment(tap, point(start), point(end)).toDouble()
            }.toFloat()
            if (distance <= hitSlopPx) lane to distance else null
        }
        .minByOrNull { it.second }
        ?.first
}

private fun distanceToSegment(point: Offset, start: Offset, end: Offset): Float {
    val dx = end.x - start.x
    val dy = end.y - start.y
    val lengthSquared = dx * dx + dy * dy
    if (lengthSquared <= 0.0001f) {
        return hypot((point.x - start.x).toDouble(), (point.y - start.y).toDouble()).toFloat()
    }
    val t = (((point.x - start.x) * dx + (point.y - start.y) * dy) / lengthSquared).coerceIn(0f, 1f)
    val projection = Offset(start.x + dx * t, start.y + dy * t)
    return hypot((point.x - projection.x).toDouble(), (point.y - projection.y).toDouble()).toFloat()
}

@Composable
private fun IntersectionRenderer(
    map: MapIntersection,
    spat: SpatIntersection?,
    currentPosition: DevicePosition?,
    selectedCrosswalkLaneIds: List<Int>,
    onCrosswalkLaneTap: (MapLane) -> Unit,
) {
    val signalGroups = spat?.movementsBySignalGroup.orEmpty()
    val canvasBackground = Color(0xFFF8FAFC)
    var zoomScale by rememberSaveable(map.key.toString(), map.revision) { mutableStateOf(1f) }
    var panX by rememberSaveable(map.key.toString(), map.revision) { mutableStateOf(0f) }
    var panY by rememberSaveable(map.key.toString(), map.revision) { mutableStateOf(0f) }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    var lastTapUpMs by remember { mutableStateOf<Long?>(null) }
    var lastTapPosition by remember { mutableStateOf<Offset?>(null) }
    var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }
    val firstSelectedLaneId = selectedCrosswalkLaneIds.firstOrNull()
    val selectableSecondLaneIds = remember(map, firstSelectedLaneId) {
        firstSelectedLaneId?.let { connectedCrosswalkLaneIds(map, it) }.orEmpty()
    }
    fun updateTransform(nextScale: Float, nextPan: Offset) {
        val constrainedScale = nextScale.coerceIn(1f, INTERSECTION_MAX_ZOOM)
        val constrainedPan = clampIntersectionPan(
            pan = nextPan,
            zoomScale = constrainedScale,
            viewportWidth = canvasSize.width.toFloat(),
            viewportHeight = canvasSize.height.toFloat(),
        )
        zoomScale = constrainedScale
        panX = constrainedPan.x
        panY = constrainedPan.y
    }
    LaunchedEffect(spat?.receivedAtMs) {
        while (true) {
            nowMs = System.currentTimeMillis()
            delay(1_000L)
        }
    }
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(420.dp)
            .onSizeChanged { canvasSize = it }
            .clip(RoundedCornerShape(8.dp))
            .background(canvasBackground)
            .pointerInput(map.key, map.revision) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    down.consume()
                    val downPosition = down.position
                    val downTimeMs = down.uptimeMillis
                    val tapSlop = 18.dp.toPx()
                    val doubleTapSlop = 56.dp.toPx()
                    val isQuickScale = lastTapUpMs
                        ?.let { downTimeMs - it <= DOUBLE_TAP_TIMEOUT_MS }
                        ?.takeIf { it }
                        ?.let {
                            lastTapPosition?.let { previousTap ->
                                hypot(
                                    (downPosition.x - previousTap.x).toDouble(),
                                    (downPosition.y - previousTap.y).toDouble(),
                                ) <= doubleTapSlop
                            }
                        } == true
                    if (isQuickScale) {
                        lastTapUpMs = null
                        lastTapPosition = null
                    }
                    var movedDistance = 0f
                    var lastQuickScaleY = downPosition.y
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val pressedChanges = event.changes.filter { it.pressed }
                        event.changes.forEach { it.consume() }
                        if (pressedChanges.isEmpty()) {
                            if (!isQuickScale && movedDistance <= tapSlop && event.changes.any { !it.pressed }) {
                                val upTimeMs = event.changes.maxOf { it.uptimeMillis }
                                if (upTimeMs - downTimeMs <= TAP_TIMEOUT_MS) {
                                    hitTestCrosswalkLane(
                                        map = map,
                                        canvasSize = canvasSize,
                                        zoomScale = zoomScale,
                                        pan = Offset(panX, panY),
                                        tap = downPosition,
                                        paddingPx = 28.dp.toPx(),
                                        hitSlopPx = 18.dp.toPx(),
                                    )?.let { lane ->
                                        onCrosswalkLaneTap(lane)
                                    }
                                    lastTapUpMs = upTimeMs
                                    lastTapPosition = downPosition
                                }
                            }
                            break
                        }

                        if (isQuickScale && pressedChanges.size == 1) {
                            val currentY = pressedChanges.first().position.y
                            val dy = currentY - lastQuickScaleY
                            val scaleChange = exp((dy * QUICK_SCALE_SENSITIVITY).toDouble()).toFloat()
                            val nextScale = (zoomScale * scaleChange).coerceIn(1f, INTERSECTION_MAX_ZOOM)
                            val actualScaleChange = nextScale / zoomScale
                            updateTransform(
                                nextScale = nextScale,
                                nextPan = downPosition - (downPosition - Offset(panX, panY)) * actualScaleChange,
                            )
                            movedDistance += kotlin.math.abs(dy)
                            lastQuickScaleY = currentY
                            continue
                        }

                        if (pressedChanges.size >= 2) {
                            val zoom = event.calculateZoom()
                            val pan = event.calculatePan()
                            val centroid = event.calculateCentroid()
                            val nextScale = (zoomScale * zoom).coerceIn(1f, INTERSECTION_MAX_ZOOM)
                            val scaleChange = nextScale / zoomScale
                            val currentPan = Offset(panX, panY)
                            updateTransform(
                                nextScale = nextScale,
                                nextPan = centroid - (centroid - currentPan) * scaleChange + pan,
                            )
                            movedDistance += hypot(pan.x.toDouble(), pan.y.toDouble()).toFloat()
                            continue
                        }

                        val change = pressedChanges.first()
                        val pan = change.position - change.previousPosition
                        updateTransform(
                            nextScale = zoomScale,
                            nextPan = Offset(panX + pan.x, panY + pan.y),
                        )
                        movedDistance += hypot(pan.x.toDouble(), pan.y.toDouble()).toFloat()
                    }
                }
            },
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
        val panOffset = Offset(panX, panY)

        fun point(x: Float, y: Float): Offset {
            val base = Offset(
                x = padding + (x - minX) * scale,
                y = size.height - padding - (y - minY) * scale,
            )
            return Offset(
                x = base.x * zoomScale + panOffset.x,
                y = base.y * zoomScale + panOffset.y,
            )
        }

        fun point(x: Int, y: Int): Offset = point(x.toFloat(), y.toFloat())

        fun drawLocationDot(center: Offset, accuracyM: Float?) {
            accuracyM?.let { accuracy ->
                val accuracyRadius = (accuracy * 100f * scale * zoomScale)
                    .coerceIn(10.dp.toPx(), 48.dp.toPx())
                drawCircle(
                    color = Color(0xFF2563EB).copy(alpha = 0.08f),
                    radius = accuracyRadius,
                    center = center,
                )
                drawCircle(
                    color = Color(0xFF2563EB).copy(alpha = 0.24f),
                    radius = accuracyRadius,
                    center = center,
                    style = Stroke(width = 1.dp.toPx()),
                )
            }
            drawCircle(Color.White, radius = 8.dp.toPx(), center = center)
            drawCircle(Color(0xFF2563EB), radius = 6.dp.toPx(), center = center)
            drawCircle(Color.White, radius = 2.dp.toPx(), center = center)
        }

        fun drawLocationEdgeArrow(direction: Offset) {
            val margin = 16.dp.toPx()
            val halfWidth = (size.width / 2f - margin).coerceAtLeast(1f)
            val halfHeight = (size.height / 2f - margin).coerceAtLeast(1f)
            val vectorLength = sqrt(direction.x * direction.x + direction.y * direction.y).coerceAtLeast(0.001f)
            val unit = Offset(direction.x / vectorLength, direction.y / vectorLength)
            val edgeScale = minOf(
                halfWidth / kotlin.math.abs(unit.x).coerceAtLeast(0.001f),
                halfHeight / kotlin.math.abs(unit.y).coerceAtLeast(0.001f),
            )
            val tip = Offset(size.width / 2f, size.height / 2f) + unit * edgeScale
            val markerLength = 22.dp.toPx()
            val markerHalfWidth = 9.dp.toPx()
            val base = tip - unit * markerLength
            val normal = Offset(-unit.y, unit.x)
            val arrow = Path().apply {
                moveTo(tip.x, tip.y)
                lineTo(base.x + normal.x * markerHalfWidth, base.y + normal.y * markerHalfWidth)
                lineTo(base.x - normal.x * markerHalfWidth, base.y - normal.y * markerHalfWidth)
                close()
            }
            drawCircle(Color.White.copy(alpha = 0.92f), radius = 17.dp.toPx(), center = tip - unit * 9.dp.toPx())
            drawPath(arrow, Color.White, style = Stroke(width = 5.dp.toPx(), join = StrokeJoin.Round))
            drawPath(arrow, Color(0xFF2563EB))
        }

        val lanesById = map.lanes.associateBy { it.id }
        fun connectionColorFor(lane: MapLane, connection: LaneConnection): Color {
            val phase = connection.signalGroup?.let { signalGroups[it]?.currentEvent?.state }
            return phase?.phaseColor() ?: lane.laneType.baseColor()
        }

        fun laneSelectionAlpha(lane: MapLane): Float {
            return intersectionLaneSelectionAlpha(
                laneId = lane.id,
                selectedLaneIds = selectedCrosswalkLaneIds,
                selectableLaneIds = selectableSecondLaneIds,
            )
        }

        fun laneLabelPoint(
            lane: MapLane,
            labelWidth: Float,
            labelHeight: Float,
            laneWidth: Float,
        ): Offset {
            val lanePoints = lane.nodes.map { node -> point(node.xCm, node.yCm) }
            val startPoint = lanePoints.first()
            val endPoint = lanePoints.last()
            val center = Offset((startPoint.x + endPoint.x) / 2f, (startPoint.y + endPoint.y) / 2f)
            if (lane.laneType != LaneType.Crosswalk) return center

            val laneLength = lanePoints.zipWithNext().sumOf { (start, end) ->
                hypot((end.x - start.x).toDouble(), (end.y - start.y).toDouble())
            }.toFloat()
            val sideOffset = countdownSideOffset(
                directionX = endPoint.x - startPoint.x,
                directionY = endPoint.y - startPoint.y,
                laneLength = laneLength,
                labelWidth = labelWidth,
                labelHeight = labelHeight,
                laneWidth = laneWidth,
                gap = 5.dp.toPx(),
            )
            if (sideOffset.first == 0f && sideOffset.second == 0f) return center

            val firstSide = center + Offset(sideOffset.first, sideOffset.second)
            val secondSide = center - Offset(sideOffset.first, sideOffset.second)
            fun viewportOverflow(labelCenter: Offset): Float {
                val left = labelCenter.x - labelWidth / 2f
                val right = labelCenter.x + labelWidth / 2f
                val top = labelCenter.y - labelHeight / 2f
                val bottom = labelCenter.y + labelHeight / 2f
                return (-left).coerceAtLeast(0f) +
                    (right - size.width).coerceAtLeast(0f) +
                    (-top).coerceAtLeast(0f) +
                    (bottom - size.height).coerceAtLeast(0f)
            }
            return if (viewportOverflow(firstSide) <= viewportOverflow(secondSide)) firstSide else secondSide
        }

        fun lanePath(lane: MapLane): Path = Path().apply {
            val first = lane.nodes.first()
            moveTo(point(first.xCm, first.yCm).x, point(first.xCm, first.yCm).y)
            lane.nodes.drop(1).forEach { node ->
                val p = point(node.xCm, node.yCm)
                lineTo(p.x, p.y)
            }
        }

        class LaneEndpoint(val point: Offset, val adjacent: Offset)

        fun laneEndpoints(lane: MapLane): List<LaneEndpoint> = listOf(
            LaneEndpoint(
                point = point(lane.nodes.first().xCm, lane.nodes.first().yCm),
                adjacent = point(lane.nodes[1].xCm, lane.nodes[1].yCm),
            ),
            LaneEndpoint(
                point = point(lane.nodes.last().xCm, lane.nodes.last().yCm),
                adjacent = point(lane.nodes[lane.nodes.lastIndex - 1].xCm, lane.nodes[lane.nodes.lastIndex - 1].yCm),
            ),
        )

        fun closestEndpointPair(first: MapLane, second: MapLane): Pair<LaneEndpoint, LaneEndpoint> {
            val firstEndpoints = laneEndpoints(first)
            val secondEndpoints = laneEndpoints(second)
            return firstEndpoints.flatMap { firstPoint ->
                secondEndpoints.map { secondPoint -> firstPoint to secondPoint }
            }.minBy { (firstPoint, secondPoint) ->
                val dx = firstPoint.point.x - secondPoint.point.x
                val dy = firstPoint.point.y - secondPoint.point.y
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
            LaneType.Vehicle -> LaneVisualStyle(width = 4.5.dp.toPx(), unsignalizedAlpha = 0.58f)
            LaneType.Crosswalk -> LaneVisualStyle(
                width = 5.dp.toPx(),
                pathEffect = crosswalkDash,
                backingWidth = 8.dp.toPx(),
                backingColor = Color.White.copy(alpha = 0.76f),
                unsignalizedAlpha = 0.76f,
            )
            LaneType.Bike -> LaneVisualStyle(
                width = 3.5.dp.toPx(),
                pathEffect = bikeDash,
                backingWidth = 5.5.dp.toPx(),
                backingColor = Color.White.copy(alpha = 0.58f),
                unsignalizedAlpha = 0.42f,
            )
            LaneType.Sidewalk -> LaneVisualStyle(
                width = 3.dp.toPx(),
                pathEffect = sidewalkDash,
                backingWidth = 5.dp.toPx(),
                backingColor = Color.White.copy(alpha = 0.5f),
                unsignalizedAlpha = 0.28f,
            )
            LaneType.Median -> LaneVisualStyle(
                width = 6.dp.toPx(),
                pathEffect = medianDash,
                unsignalizedAlpha = 0.22f,
            )
            LaneType.Striping -> LaneVisualStyle(
                width = 2.5.dp.toPx(),
                pathEffect = stripingDash,
                unsignalizedAlpha = 0.28f,
            )
            LaneType.TrackedVehicle -> LaneVisualStyle(
                width = 6.dp.toPx(),
                backingWidth = 8.dp.toPx(),
                backingColor = Color.White.copy(alpha = 0.54f),
                centerGapWidth = 3.dp.toPx(),
                unsignalizedAlpha = 0.4f,
            )
            LaneType.Parking -> LaneVisualStyle(
                width = 3.dp.toPx(),
                pathEffect = parkingDash,
                unsignalizedAlpha = 0.25f,
            )
            LaneType.Other -> LaneVisualStyle(
                width = 2.5.dp.toPx(),
                pathEffect = otherDash,
                unsignalizedAlpha = 0.22f,
            )
        }

        fun drawCenterGap(path: Path, style: LaneVisualStyle) {
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

        fun drawStyledPath(
            path: Path,
            style: LaneVisualStyle,
            color: Color,
            colorAlpha: Float,
            styleAlpha: Float,
        ) {
            style.backingWidth?.let { backingWidth ->
                drawPath(
                    path = path,
                    color = style.backingColor.copy(alpha = style.backingColor.alpha * styleAlpha),
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
                color = color.copy(alpha = colorAlpha),
                style = Stroke(
                    width = style.width,
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round,
                    pathEffect = style.pathEffect,
                ),
            )
            drawCenterGap(path, style)
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
            val color = lane.laneType.baseColor()
            val path = lanePath(lane)
            val style = styleFor(lane.laneType)
            val selectionAlpha = laneSelectionAlpha(lane)
            drawStyledPath(
                path = path,
                style = style,
                color = color,
                colorAlpha = style.unsignalizedAlpha * selectionAlpha,
                styleAlpha = selectionAlpha,
            )
            if (lane.id in selectedCrosswalkLaneIds) {
                drawPath(
                    path = path,
                    color = Color(0xFF0F766E),
                    style = Stroke(
                        width = style.width + 7.dp.toPx(),
                        cap = StrokeCap.Round,
                        join = StrokeJoin.Round,
                    ),
                )
                drawPath(
                    path = path,
                    color = Color.White,
                    style = Stroke(
                        width = style.width + 3.dp.toPx(),
                        cap = StrokeCap.Round,
                        join = StrokeJoin.Round,
                    ),
                )
                drawPath(
                    path = path,
                    color = color,
                    style = Stroke(
                        width = style.width,
                        cap = StrokeCap.Round,
                        join = StrokeJoin.Round,
                        pathEffect = style.pathEffect,
                    ),
                )
                drawCenterGap(path, style)
            }
        }
        val drawnConnections = mutableSetOf<Pair<Int, Int>>()
        map.lanes.forEach { lane ->
            val style = styleFor(lane.laneType)
            lane.connections.forEach { connection ->
                if (connection.remoteIntersection != null) return@forEach
                val connectedLane = lanesById[connection.laneId]
                if (connectedLane == null) return@forEach
                if (lane.nodes.size < 2 || connectedLane.nodes.size < 2) return@forEach
                val connectionIsVisible = intersectionConnectionVisible(
                    laneId = lane.id,
                    connectedLaneId = connectedLane.id,
                    signalized = connection.signalGroup?.let(signalGroups::containsKey) == true,
                    selectedLaneIds = selectedCrosswalkLaneIds,
                )
                if (!connectionIsVisible) return@forEach
                val connectionKey = minOf(lane.id, connectedLane.id) to maxOf(lane.id, connectedLane.id)
                if (!drawnConnections.add(connectionKey)) return@forEach
                val (start, end) = closestEndpointPair(lane, connectedLane)
                val controls = roadConnectionControlPoints(
                    startX = start.point.x,
                    startY = start.point.y,
                    startAdjacentX = start.adjacent.x,
                    startAdjacentY = start.adjacent.y,
                    endX = end.point.x,
                    endY = end.point.y,
                    endAdjacentX = end.adjacent.x,
                    endAdjacentY = end.adjacent.y,
                    maxControlDistance = 96.dp.toPx() * zoomScale,
                )
                val path = Path().apply {
                    moveTo(start.point.x, start.point.y)
                    cubicTo(
                        controls.startX,
                        controls.startY,
                        controls.endX,
                        controls.endY,
                        end.point.x,
                        end.point.y,
                    )
                }
                val connectionAlpha = if (selectedCrosswalkLaneIds.isEmpty()) 0.58f else 0.92f
                drawStyledPath(
                    path = path,
                    style = style,
                    color = connectionColorFor(lane, connection),
                    colorAlpha = connectionAlpha,
                    styleAlpha = connectionAlpha,
                )
            }
        }
        if (zoomScale >= LANE_TIMING_ZOOM_THRESHOLD || selectedCrosswalkLaneIds.isNotEmpty()) {
            val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.White.toArgb()
                textAlign = Paint.Align.LEFT
                textSize = 12.dp.toPx()
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            }
            val horizontalPadding = 6.dp.toPx()
            val verticalPadding = 3.dp.toPx()
            val occupiedLabels = mutableListOf<CountdownLabelBounds>()
            val representatives = countdownLaneRepresentatives(
                lanes = map.lanes,
                availableSignalGroups = signalGroups.keys,
                selectedLaneIds = selectedCrosswalkLaneIds.toSet(),
                selectableLaneIds = selectableSecondLaneIds,
            )
            val emphasizedSignalGroups = countdownSignalGroupsForSelection(
                lanes = map.lanes,
                selectedLaneIds = selectedCrosswalkLaneIds,
                availableSignalGroups = signalGroups.keys,
            )
            representatives.forEach { representative ->
                val lane = representative.lane
                if (lane.nodes.size < 2) return@forEach
                if (zoomScale < LANE_TIMING_ZOOM_THRESHOLD &&
                    lane.id !in selectedCrosswalkLaneIds && lane.id !in selectableSecondLaneIds
                ) return@forEach
                val event = signalGroups[representative.signalGroup]?.currentEvent ?: return@forEach
                val seconds = event.secondsUntilChange(spat, nowMs) ?: return@forEach
                val label = "${seconds}s"
                val textWidth = textPaint.measureText(label)
                val labelWidth = textWidth + horizontalPadding * 2
                val labelHeight = textPaint.textSize + verticalPadding * 2
                val labelPoint = laneLabelPoint(
                    lane = lane,
                    labelWidth = labelWidth,
                    labelHeight = labelHeight,
                    laneWidth = styleFor(lane.laneType).width,
                )
                val bounds = placeCountdownLabel(
                    preferredX = labelPoint.x,
                    preferredY = labelPoint.y,
                    labelWidth = labelWidth,
                    labelHeight = labelHeight,
                    viewportWidth = size.width,
                    viewportHeight = size.height,
                    occupied = occupiedLabels,
                    gap = 4.dp.toPx(),
                ) ?: return@forEach
                occupiedLabels += bounds
                val topLeft = Offset(bounds.left, bounds.top)
                val deemphasized = emphasizedSignalGroups.isNotEmpty() &&
                    representative.signalGroup !in emphasizedSignalGroups
                drawRoundRect(
                    color = event.state.phaseColor().copy(alpha = if (deemphasized) 0.16f else 0.94f),
                    topLeft = topLeft,
                    size = Size(labelWidth, labelHeight),
                    cornerRadius = CornerRadius(5.dp.toPx(), 5.dp.toPx()),
                )
                textPaint.color = (if (deemphasized) Color(0xFF64748B) else Color.White).toArgb()
                drawContext.canvas.nativeCanvas.drawText(
                    label,
                    topLeft.x + horizontalPadding,
                    topLeft.y + verticalPadding + textPaint.textSize * 0.82f,
                    textPaint,
                )
            }
        }

        currentPosition?.let { position ->
            val location = position.toIntersectionOffsetCm(map)
            val laneWidthPadding = ((map.laneWidthCm ?: 300) / 2f).coerceAtLeast(150f)
            val withinIntersection =
                location.x in (minX - laneWidthPadding)..(maxX + laneWidthPadding) &&
                    location.y in (minY - laneWidthPadding)..(maxY + laneWidthPadding)
            if (withinIntersection) {
                drawLocationDot(point(location.x, location.y), position.accuracyM)
            } else {
                val centerX = (minX + maxX) / 2f
                val centerY = (minY + maxY) / 2f
                drawLocationEdgeArrow(
                    Offset(
                        x = location.x - centerX,
                        y = centerY - location.y,
                    ),
                )
            }
        }
    }
}

@Composable
private fun SignalTimingPanel(snapshot: IntersectionSnapshot?, modifier: Modifier = Modifier) {
    val map = snapshot?.map
    val spat = snapshot?.spat
    val movements = spat?.movements.orEmpty()
    if (movements.isEmpty()) return
    var expanded by rememberSaveable(map?.key.toString(), spat?.revision) { mutableStateOf(false) }
    var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(spat?.receivedAtMs, expanded) {
        while (expanded) {
            nowMs = System.currentTimeMillis()
            delay(1_000L)
        }
    }
    Column(
        modifier
            .fillMaxWidth()
            .background(Color.White, RoundedCornerShape(8.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Signal phases", style = MaterialTheme.typography.titleMedium)
                Text(
                    listOfNotNull(
                        "${movements.size} groups",
                        map?.let { "${it.lanes.size} lanes" },
                        map?.let { intersection -> "${intersection.lanes.count { it.connections.isNotEmpty() }} linked" },
                    ).joinToString(" • "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
            TextButton(onClick = { expanded = !expanded }) {
                Text(if (expanded) "Hide" else "Show")
            }
        }
        if (!expanded) return@Column
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Group", modifier = Modifier.weight(0.6f), style = MaterialTheme.typography.labelMedium)
            Text("Phase", modifier = Modifier.weight(1.2f), style = MaterialTheme.typography.labelMedium)
            Text("Next change", modifier = Modifier.weight(1.0f), style = MaterialTheme.typography.labelMedium)
        }
        movements.sortedBy { it.signalGroup }.forEach { movement ->
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
                    event?.secondsUntilChange(spat, nowMs)?.let { "${it}s" } ?: "No timing",
                    modifier = Modifier.weight(1.0f),
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
        }
    }
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
    txApproved: Boolean,
    sremProfile: SremProfile,
    onRevokeTxApproval: () -> Unit,
    onSave: (String, String, String, String, SremProfile) -> Unit,
) {
    var draftMqttUri by rememberSaveable(mqttUri) { mutableStateOf(mqttUri) }
    var draftNodeId by rememberSaveable(nodeId) { mutableStateOf(nodeId) }
    var draftMaxQueueLength by rememberSaveable(maxQueueLength) { mutableStateOf(maxQueueLength) }
    var draftMaxQueueAgeSeconds by rememberSaveable(maxQueueAgeSeconds) { mutableStateOf(maxQueueAgeSeconds) }
    var draftSremProfile by rememberSaveable(sremProfile) { mutableStateOf(sremProfile) }

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
        Text("SREM vehicle profile", style = MaterialTheme.typography.labelLarge)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = {
                val index = SremProfile.entries.indexOf(draftSremProfile)
                draftSremProfile = SremProfile.entries[(index - 1 + SremProfile.entries.size) % SremProfile.entries.size]
            }) { Text("Previous") }
            Text(draftSremProfile.displayName, fontWeight = FontWeight.SemiBold)
            TextButton(onClick = {
                val index = SremProfile.entries.indexOf(draftSremProfile)
                draftSremProfile = SremProfile.entries[(index + 1) % SremProfile.entries.size]
            }) { Text("Next") }
        }
        Button(
            onClick = onRevokeTxApproval,
            modifier = Modifier.fillMaxWidth().height(48.dp),
            enabled = txApproved,
            shape = RoundedCornerShape(8.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB91C1C)),
        ) {
            Text(if (txApproved) "Revoke TX Approval" else "TX Approval not active")
        }
        Button(
            onClick = {
                onSave(
                    draftMqttUri,
                    draftNodeId,
                    draftMaxQueueLength,
                    draftMaxQueueAgeSeconds,
                    draftSremProfile,
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
private fun ReplayRow(status: BridgeStatus, onStartReplay: () -> Unit, onStopReplay: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Button(
            onClick = onStartReplay,
            modifier = Modifier.weight(1f).height(48.dp),
            enabled = !status.running,
            shape = RoundedCornerShape(8.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0369A1)),
        ) { Text("Replay PCAP") }
        Button(
            onClick = onStopReplay,
            modifier = Modifier.weight(1f).height(48.dp),
            enabled = status.replaying,
            shape = RoundedCornerShape(8.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF475569)),
        ) { Text("Stop replay") }
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
            MetricCard(if (status.replaying || status.replayPackets > 0) "Replayed" else "PCAP", if (status.replaying || status.replayPackets > 0) status.replayPackets.toString() else status.pcapPackets.toString(), Modifier.weight(1f))
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
