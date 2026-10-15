package org.opentrafficmap.citstogo

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.IntentFilter
import android.content.pm.PackageManager
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
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.OpenableColumns
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.security.SecureRandom
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToLong
import kotlin.math.sqrt
import kotlinx.coroutines.delay
import org.opentrafficmap.citstogo.bridge.BluetoothEnrollment
import org.opentrafficmap.citstogo.bridge.BleDebugParameters
import org.opentrafficmap.citstogo.bridge.BridgeStatus
import org.opentrafficmap.citstogo.bridge.CitsBridgeService
import org.opentrafficmap.citstogo.bridge.ConnectionMode
import org.opentrafficmap.citstogo.cam.VehicleDimensions
import org.opentrafficmap.citstogo.flashing.CodebergReleaseClient
import org.opentrafficmap.citstogo.flashing.Esp32RomFlasher
import org.opentrafficmap.citstogo.flashing.EspFlashTransport
import org.opentrafficmap.citstogo.flashing.FirmwareFileReader
import org.opentrafficmap.citstogo.flashing.FirmwareFlashLog
import org.opentrafficmap.citstogo.flashing.FirmwareFlashLogEntry
import org.opentrafficmap.citstogo.flashing.FirmwareRelease
import org.opentrafficmap.citstogo.flashing.formatFirmwareSize
import org.opentrafficmap.citstogo.intersection.IntersectionDiagnostics
import org.opentrafficmap.citstogo.intersection.IntersectionSnapshot
import org.opentrafficmap.citstogo.intersection.IntersectionSnapshotList
import org.opentrafficmap.citstogo.livemap.LiveMapSnapshot
import org.opentrafficmap.citstogo.livemap.MapHistoryDays
import org.opentrafficmap.citstogo.livemap.MapStationTimeout
import org.opentrafficmap.citstogo.protocol.FirmwareStatistics
import org.opentrafficmap.citstogo.bridge.ConnectionDiagnostics
import org.opentrafficmap.citstogo.srem.SremProfile
import org.opentrafficmap.citstogo.srem.estimateSremRequestTimeMs
import org.opentrafficmap.citstogo.station.DiscoveredStation
import org.opentrafficmap.citstogo.station.DiscoveredStationList
import org.opentrafficmap.citstogo.station.StationDiscoveryStore
import org.opentrafficmap.citstogo.station.StationTableState
import org.opentrafficmap.citstogo.bridge.UsbCdcSerial
import org.opentrafficmap.citstogo.update.CodebergAppUpdateChecker
import org.opentrafficmap.citstogo.util.parcelableExtra
import org.opentrafficmap.citstogo.util.serializableExtra

class MainActivity : ComponentActivity(), SensorEventListener {
    private lateinit var usbManager: UsbManager
    private lateinit var locationManager: LocationManager
    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null
    private var startAfterPermission: UsbDevice? = null
    private var enrollmentAfterPermission = false
    private var enrollmentAfterBluetoothPermission = false
    private var bluetoothEnrollmentRunning by mutableStateOf(false)
    private var bluetoothEnrollmentMessage by mutableStateOf("")
    private var bluetoothEnrollmentError by mutableStateOf(false)

    private val devices = mutableStateListOf<UsbDevice>()
    private var selectedDeviceName by mutableStateOf<String?>(null)
    private var connectionMode by mutableStateOf(ConnectionMode.USB)
    private var status by mutableStateOf(BridgeStatus())
    private var discoveredStations by mutableStateOf<List<DiscoveredStation>>(emptyList())
    private var stationTableState by mutableStateOf(StationTableState.DEFAULT)
    private var mqttUri by mutableStateOf("")
    private var mqttEnabled by mutableStateOf(true)
    private var nodeId by mutableStateOf("")
    private var maxQueueLength by mutableStateOf("")
    private var maxQueueAgeSeconds by mutableStateOf("")
    private var logLine by mutableStateOf("")
    private var intersectionSnapshot by mutableStateOf<IntersectionSnapshot?>(null)
    private var intersectionSnapshots by mutableStateOf<List<IntersectionSnapshot>>(emptyList())
    private var intersectionSortMode by mutableStateOf(IntersectionSortMode.FirstReceived)
    private var liveMapSnapshot by mutableStateOf<LiveMapSnapshot?>(null)
    private var mapStationTimeoutSeconds by mutableStateOf(MapStationTimeout.DEFAULT_SECONDS)
    private var roadSideUnits by mutableStateOf<List<DiscoveredStation>>(emptyList())
    private var mapHistoryShown by mutableStateOf(false)
    private var mapHistoryDays by mutableStateOf(MapHistoryDays.ALL)
    private var currentPosition by mutableStateOf<DevicePosition?>(null)
    private var sremProfile by mutableStateOf(SremProfile.PEDESTRIAN)
    private var vehicleDimensions by mutableStateOf<Map<SremProfile, VehicleDimensions>>(emptyMap())
    private var camIntervalMs by mutableStateOf(CitsBridgeService.DEFAULT_CAM_INTERVAL_MS.toString())
    private var camRandomizationEnabled by mutableStateOf(true)
    private var camRandomizationIntervalSeconds by mutableStateOf(
        (CitsBridgeService.DEFAULT_CAM_RANDOMIZATION_INTERVAL_MS / 1000).toString(),
    )
    private var txApproved by mutableStateOf(false)
    private var debugMenuEnabled by mutableStateOf(false)
    private var themeMode by mutableStateOf(ThemeMode.System)
    private var displayAlwaysOnEnabled by mutableStateOf(false)
    private var stationDiscoveryNotificationEnabled by mutableStateOf(true)
    private var appUpdateNotificationEnabled by mutableStateOf(true)
    private var txApprovalPromptState by mutableStateOf(TxApprovalPromptState.Hidden)
    private var enableCamAfterPermission = false
    private var wantsIntersectionLocation = false
    private var intersectionLocationActive = false
    private var lastShakeElapsedMs = 0L
    private var firmwareRelease: FirmwareRelease? = null
    private var customFirmwareUri: Uri? = null
    private var releaseLookupRunning = false
    private var flashAfterPermission = false
    private var flashAfterStop = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private val flashStopTimeout = Runnable {
        if (!flashAfterStop) return@Runnable
        flashAfterStop = false
        logFlash(ActivityLevel.ERROR, "Timed out waiting for the receiver to stop.")
        flashingState = flashingState.copy(
            phase = FirmwareFlashingPhase.Error,
            message = "Could not stop the receiver. Stop it on the Home page and retry.",
        )
    }
    private var flashingState by mutableStateOf(FirmwareFlashingState.initial(BuildConfig.VERSION_NAME))
    private var flashingLog by mutableStateOf<List<FirmwareFlashLogEntry>>(emptyList())

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        val granted = grants.values.any { it }
        if (enableCamAfterPermission) {
            enableCamAfterPermission = false
            if (granted) {
                configureCam(true)
            } else {
                logLine = "Location permission denied; CAM remains off"
            }
        }
        if (wantsIntersectionLocation) {
            if (granted) {
                startIntersectionLocationUpdates()
            } else {
                logLine = "Location permission denied; position marker disabled"
            }
        }
    }

    private val bluetoothPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        java.util.logging.Logger.getLogger("org.opentrafficmap.citstogo.Permissions").info("Bluetooth permission result: $grants")
        if (grants.isNotEmpty() && grants.values.all { it }) {
            if (enrollmentAfterBluetoothPermission) requestEnrollmentUsb() else startBridge()
        } else if (enrollmentAfterBluetoothPermission) {
            bluetoothEnrollmentRunning = false
            bluetoothEnrollmentError = true
            bluetoothEnrollmentMessage = "Bluetooth permission was denied. Allow it in Android settings and retry."
        } else {
            logLine = "Bluetooth permission denied"
        }
        enrollmentAfterBluetoothPermission = false
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }

    private val createPcapLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result -> handleDocumentResult(result, ::startPcap) }

    private val openPcapLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result -> handleDocumentResult(result, ::startReplay) }

    private val openFirmwareLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result -> handleDocumentResult(result, ::selectCustomFirmware) }

    private val intersectionLocationListener = LocationListener { location ->
        updateCurrentPosition(location)
    }

    private val usbPermissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != CitsBridgeService.ACTION_USB_PERMISSION) return
            val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
            java.util.logging.Logger.getLogger("org.opentrafficmap.citstogo.Permissions").info("USB permission result: granted=$granted")
            val device = intent.parcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
            java.util.logging.Logger.getLogger("org.opentrafficmap.citstogo.Permissions").info(
                "USB permission callback: device=${device?.deviceName} pending=${startAfterPermission?.deviceName} " +
                    "enrollment=$enrollmentAfterPermission flashing=$flashAfterPermission " +
                    "currentGrant=${device?.let { usbManager.hasPermission(it) }}",
            )
            refreshDevices()
            if (granted) {
                selectedDeviceName = device?.deviceName ?: selectedDeviceName
                if (flashAfterPermission && device != null) {
                    if (status.running || status.replaying) {
                        logFlash(ActivityLevel.INFO, "Receiver became active. Stopping it before flashing…")
                        beginStopForFlash()
                    } else {
                        logFlash(ActivityLevel.INFO, "USB permission granted. Continuing flash.")
                        startFirmwareFlash(device)
                    }
                } else if (enrollmentAfterPermission) {
                    if (device != null) {
                        startBluetoothEnrollment(device)
                    } else {
                        bluetoothEnrollmentRunning = false
                        bluetoothEnrollmentError = true
                        bluetoothEnrollmentMessage = "USB device disappeared. Reconnect it and retry enrollment."
                    }
                } else {
                    startBridge()
                }
            } else {
                if (flashAfterPermission) {
                    logFlash(ActivityLevel.ERROR, "USB permission denied. Flashing cancelled.")
                    flashingState = flashingState.copy(
                        phase = FirmwareFlashingPhase.Error,
                        message = "USB permission denied",
                    )
                } else if (enrollmentAfterPermission) {
                    bluetoothEnrollmentRunning = false
                    bluetoothEnrollmentError = true
                    bluetoothEnrollmentMessage = "USB permission was denied. Reconnect the device and retry enrollment."
                } else {
                    logLine = "USB permission denied"
                }
            }
            flashAfterPermission = false
            enrollmentAfterPermission = false
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
                transportDropped = intent.getLongExtra(CitsBridgeService.EXTRA_TRANSPORT_DROPPED, 0),
                protocolErrors = intent.getLongExtra(CitsBridgeService.EXTRA_PROTOCOL_ERRORS, 0),
                txRequested = intent.getLongExtra(CitsBridgeService.EXTRA_TX_REQUESTED, 0),
                txSuccessful = intent.getLongExtra(CitsBridgeService.EXTRA_TX_SUCCESSFUL, 0),
                txFailed = intent.getLongExtra(CitsBridgeService.EXTRA_TX_FAILED, 0),
                camEnabled = intent.getBooleanExtra(CitsBridgeService.EXTRA_CAM_ENABLED, false),
                camSent = intent.getLongExtra(CitsBridgeService.EXTRA_CAM_SENT, 0),
                firmwareStatistics = intent.serializableExtra<FirmwareStatistics>(CitsBridgeService.EXTRA_FIRMWARE_STATISTICS),
                bleDebugParameters = intent.serializableExtra<BleDebugParameters>(CitsBridgeService.EXTRA_BLE_DEBUG_PARAMETERS),
                intersectionDiagnostics = intent.serializableExtra<IntersectionDiagnostics>(CitsBridgeService.EXTRA_INTERSECTION_DIAGNOSTICS),
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
            intent.serializableExtra<DiscoveredStationList>(CitsBridgeService.EXTRA_DISCOVERED_STATIONS)?.let {
                discoveredStations = it.stations
            }
            intent.serializableExtra<DiscoveredStationList>(CitsBridgeService.EXTRA_ROAD_SIDE_UNITS)?.let {
                roadSideUnits = it.stations
            }
            intent.serializableExtra<IntersectionSnapshotList>(CitsBridgeService.EXTRA_INTERSECTION_SNAPSHOTS)?.let {
                intersectionSnapshots = it.snapshots
                intersectionSnapshot = it.snapshots.firstOrNull()
            }
            intent.serializableExtra<IntersectionSnapshot>(CitsBridgeService.EXTRA_INTERSECTION_SNAPSHOT)?.let {
                intersectionSnapshot = it
                if (intersectionSnapshots.isEmpty()) intersectionSnapshots = listOf(it)
            }
            intent.serializableExtra<LiveMapSnapshot>(CitsBridgeService.EXTRA_LIVE_MAP_SNAPSHOT)?.let {
                liveMapSnapshot = it
            }
            if (flashAfterStop && !status.running && !status.replaying) {
                flashAfterStop = false
                mainHandler.removeCallbacks(flashStopTimeout)
                logFlash(ActivityLevel.INFO, "Receiver stopped. Continuing flash.")
                connectedEspressifDevice()?.let(::ensureFlashUsbPermission) ?: run {
                    logFlash(ActivityLevel.ERROR, "Cannot flash: no ESP32-C5 is connected.")
                    updateFlashingDeviceState()
                }
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
        connectionMode = ConnectionMode.fromWireValue(prefs.getString(PREF_CONNECTION_MODE, null))
        txApproved = prefs.getBoolean(CitsBridgeService.PREF_TX_APPROVED, false)
        debugMenuEnabled = prefs.getBoolean(PREF_DEBUG_MENU_ENABLED, false)
        themeMode = ThemeMode.fromPreference(prefs.getString(PREF_THEME_MODE, null))
        displayAlwaysOnEnabled = prefs.getBoolean(PREF_DISPLAY_ALWAYS_ON, false)
        applyDisplayAlwaysOn()
        stationDiscoveryNotificationEnabled = prefs.getBoolean(CitsBridgeService.PREF_NOTIFY_STATION_DISCOVERY, true)
        appUpdateNotificationEnabled = prefs.getBoolean(CitsBridgeService.PREF_NOTIFY_APP_UPDATE, true)
        intersectionSortMode = IntersectionSortMode.fromPreference(
            prefs.getString(PREF_INTERSECTION_SORT_MODE, null),
        )
        stationTableState = StationTableState.decode(prefs.getString(PREF_STATION_TABLE_STATE, null))
        // Show the stored discovery table even before the bridge service publishes its own snapshot.
        val storedStations = StationDiscoveryStore().apply {
            restore(prefs.getString(CitsBridgeService.PREF_DISCOVERED_STATIONS, null))
        }
        discoveredStations = storedStations.snapshot(DiscoveredStationList.BROADCAST_LIMIT)
        roadSideUnits = storedStations.roadSideUnits()
        mapStationTimeoutSeconds = MapStationTimeout.clamp(
            prefs.getInt(PREF_MAP_STATION_TIMEOUT_S, MapStationTimeout.DEFAULT_SECONDS),
        )
        mapHistoryShown = prefs.getBoolean(PREF_MAP_HISTORY_SHOWN, false)
        mapHistoryDays = MapHistoryDays.clamp(prefs.getInt(PREF_MAP_HISTORY_DAYS, MapHistoryDays.ALL))
        mqttUri = prefs.getString(CitsBridgeService.PREF_MQTT_URI, CitsBridgeService.DEFAULT_MQTT_URI).orEmpty()
        mqttEnabled = prefs.getBoolean(CitsBridgeService.PREF_MQTT_ENABLED, true)
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
        sremProfile = SremProfile.fromPreferenceCode(
            when {
                prefs.contains(CitsBridgeService.PREF_VEHICLE_TYPE) ->
                    prefs.getInt(CitsBridgeService.PREF_VEHICLE_TYPE, SremProfile.PEDESTRIAN.preferenceCode)
                prefs.contains(CitsBridgeService.PREF_SREM_PROFILE) ->
                    prefs.getInt(CitsBridgeService.PREF_SREM_PROFILE, SremProfile.PEDESTRIAN.preferenceCode)
                else -> prefs.getInt(
                    CitsBridgeService.PREF_CAM_STATION_TYPE,
                    SremProfile.PEDESTRIAN.preferenceCode,
                )
            },
        )
        vehicleDimensions = SremProfile.entries.associateWith { loadVehicleDimensions(prefs, it) }
        camIntervalMs = prefs.getInt(
            CitsBridgeService.PREF_CAM_INTERVAL_MS,
            CitsBridgeService.DEFAULT_CAM_INTERVAL_MS,
        ).toString()
        camRandomizationEnabled = prefs.getBoolean(CitsBridgeService.PREF_CAM_RANDOMIZE_MAC, true)
        camRandomizationIntervalSeconds = (prefs.getInt(
            CitsBridgeService.PREF_CAM_RANDOMIZATION_INTERVAL_MS,
            CitsBridgeService.DEFAULT_CAM_RANDOMIZATION_INTERVAL_MS,
        ) / 1_000).toString()
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
        checkForAppUpdate()
        refreshDevices()
        selectedDeviceName = selectedDeviceName ?: devices.firstOrNull()?.deviceName
        setContent {
            CitsTheme(themeMode = themeMode) {
                CitsApp(
                    devices = devices.toList(),
                    selectedDeviceName = selectedDeviceName,
                    onSelectDevice = { selectedDeviceName = it },
                    connectionMode = connectionMode,
                    onConnectionModeChange = {
                        connectionMode = it
                        getSharedPreferences(CitsBridgeService.PREFS, MODE_PRIVATE).edit()
                            .putString(PREF_CONNECTION_MODE, it.wireValue)
                            .apply()
                    },
                    mqttUri = mqttUri,
                    onMqttUriChange = { mqttUri = it },
                    mqttEnabled = mqttEnabled,
                    onMqttEnabledChange = ::updateMqttEnabled,
                    nodeId = nodeId,
                    onNodeIdChange = { nodeId = it },
                    maxQueueLength = maxQueueLength,
                    onMaxQueueLengthChange = { maxQueueLength = it },
                    maxQueueAgeSeconds = maxQueueAgeSeconds,
                    onMaxQueueAgeSecondsChange = { maxQueueAgeSeconds = it },
                    status = status,
                    discoveredStations = discoveredStations,
                    stationTableState = stationTableState,
                    onStationTableStateChange = ::updateStationTableState,
                    onStationColumnWidthChange = { column, width ->
                        // Live preview while dragging; the value is stored when the finger lifts.
                        stationTableState = stationTableState.withWidth(column, width)
                    },
                    onStationColumnWidthChangeFinished = ::persistStationTableState,
                    onClearDiscoveredStations = { includeRoadSideUnits ->
                        // Clear locally right away; the service confirms with its next status.
                        if (includeRoadSideUnits) {
                            discoveredStations = emptyList()
                            roadSideUnits = emptyList()
                        } else {
                            discoveredStations = discoveredStations.filter { it.roadSideUnit }
                        }
                        sendServiceIntent(CitsBridgeService.ACTION_CLEAR_DISCOVERED_STATIONS) {
                            putExtra(CitsBridgeService.EXTRA_INCLUDE_ROAD_SIDE_UNITS, includeRoadSideUnits)
                        }
                    },
                    logLine = logLine,
                    intersectionSnapshots = intersectionSnapshots.ifEmpty { listOfNotNull(intersectionSnapshot) },
                    liveMapSnapshot = liveMapSnapshot,
                    mapStationTimeoutSeconds = mapStationTimeoutSeconds,
                    roadSideUnits = roadSideUnits,
                    mapHistoryShown = mapHistoryShown,
                    onMapHistoryShownChange = ::updateMapHistoryShown,
                    mapHistoryDays = mapHistoryDays,
                    intersectionSortMode = intersectionSortMode,
                    onIntersectionSortModeChange = ::updateIntersectionSortMode,
                    currentPosition = currentPosition,
                    bluetoothEnrollmentRunning = bluetoothEnrollmentRunning,
                    bluetoothEnrollmentMessage = bluetoothEnrollmentMessage,
                    bluetoothEnrollmentError = bluetoothEnrollmentError,
                    onRefresh = ::refreshDevices,
                    onEnrollBluetooth = ::requestBluetoothEnrollment,
                    onStart = ::requestConnectionThenStart,
                    onStop = ::stopBridge,
                    onStartPcap = ::choosePcapFile,
                    onStopPcap = ::stopPcap,
                    onStartReplay = ::chooseReplayFile,
                    onStopReplay = ::stopReplay,
                    camIntervalMs = camIntervalMs,
                    onCamIntervalChange = { camIntervalMs = it },
                    camRandomizationEnabled = camRandomizationEnabled,
                    onCamRandomizationEnabledChange = { camRandomizationEnabled = it },
                    camRandomizationIntervalSeconds = camRandomizationIntervalSeconds,
                    onCamRandomizationIntervalChange = { camRandomizationIntervalSeconds = it },
                    onConfigureCam = ::configureCam,
                    sremProfile = sremProfile,
                    onSremProfileChange = { sremProfile = it },
                    onSelectVehicleProfile = ::selectVehicleProfile,
                    vehicleDimensions = vehicleDimensions,
                    onSaveVehicleDimensions = ::saveVehicleDimensions,
                    txApproved = txApproved,
                    debugMenuEnabled = debugMenuEnabled,
                    onDebugMenuEnabledChange = { enabled ->
                        debugMenuEnabled = enabled
                        getSharedPreferences(CitsBridgeService.PREFS, MODE_PRIVATE).edit()
                            .putBoolean(PREF_DEBUG_MENU_ENABLED, enabled)
                            .apply()
                    },
                    themeMode = themeMode,
                    onThemeModeChange = { mode ->
                        themeMode = mode
                        getSharedPreferences(CitsBridgeService.PREFS, MODE_PRIVATE).edit()
                            .putString(PREF_THEME_MODE, mode.name)
                            .apply()
                    },
                    displayAlwaysOnEnabled = displayAlwaysOnEnabled,
                    onDisplayAlwaysOnEnabledChange = { enabled ->
                        displayAlwaysOnEnabled = enabled
                        getSharedPreferences(CitsBridgeService.PREFS, MODE_PRIVATE).edit()
                            .putBoolean(PREF_DISPLAY_ALWAYS_ON, enabled)
                            .apply()
                        applyDisplayAlwaysOn()
                    },
                    stationDiscoveryNotificationEnabled = stationDiscoveryNotificationEnabled,
                    onStationDiscoveryNotificationChange = { enabled ->
                        stationDiscoveryNotificationEnabled = enabled
                        getSharedPreferences(CitsBridgeService.PREFS, MODE_PRIVATE).edit()
                            .putBoolean(CitsBridgeService.PREF_NOTIFY_STATION_DISCOVERY, enabled)
                            .apply()
                    },
                    appUpdateNotificationEnabled = appUpdateNotificationEnabled,
                    onAppUpdateNotificationChange = { enabled ->
                        appUpdateNotificationEnabled = enabled
                        getSharedPreferences(CitsBridgeService.PREFS, MODE_PRIVATE).edit()
                            .putBoolean(CitsBridgeService.PREF_NOTIFY_APP_UPDATE, enabled)
                            .apply()
                    },
                    txApprovalPromptState = txApprovalPromptState,
                    onGrantTxApproval = ::grantTxApproval,
                    onDismissTxApproval = { txApprovalPromptState = TxApprovalPromptState.Hidden },
                    onFinishTxApproval = { txApprovalPromptState = TxApprovalPromptState.Hidden },
                    onRevokeTxApproval = ::revokeTxApproval,
                    onSendSrem = ::sendSrem,
                    onIntersectionLocationActiveChange = ::setIntersectionLocationActive,
                    flashingState = flashingState,
                    flashingLog = flashingLog,
                    onFlashingPageActive = ::setFlashingPageActive,
                    onRetryFirmwareRelease = { loadReleaseList(force = true) },
                    onSelectFirmwareRelease = ::selectFirmwareRelease,
                    onChooseCustomFirmware = ::chooseCustomFirmware,
                    onUseReleaseFirmware = ::useReleaseFirmware,
                    onFlashFirmware = ::requestFirmwareFlash,
                    onSaveSettings = { updatedMqttUri, updatedNodeId, updatedMaxQueueLength, updatedMaxQueueAgeSeconds, updatedSremProfile, updatedMapStationTimeoutSeconds, updatedMapHistoryDays ->
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
                            MapStationTimeout.parse(updatedMapStationTimeoutSeconds),
                            MapHistoryDays.parse(updatedMapHistoryDays),
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
        mainHandler.removeCallbacks(flashStopTimeout)
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

    private fun handleDocumentResult(result: ActivityResult, onUriSelected: (Uri) -> Unit) {
        if (result.resultCode != RESULT_OK) return
        val data = result.data ?: return
        val uri = data.data ?: return
        val flags = data.flags and
            (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        runCatching { contentResolver.takePersistableUriPermission(uri, flags) }
        onUriSelected(uri)
    }

    private fun requestBluetoothEnrollment() {
        if (status.running) {
            bluetoothEnrollmentRunning = false
            bluetoothEnrollmentError = true
            bluetoothEnrollmentMessage = "Stop the active connection before enrollment."
            return
        }
        bluetoothEnrollmentRunning = true
        bluetoothEnrollmentError = false
        bluetoothEnrollmentMessage = "Preparing Bluetooth enrollment…"
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        val missing = permissions.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) {
            enrollmentAfterBluetoothPermission = true
            bluetoothEnrollmentMessage = "Bluetooth permission is required to enroll this phone."
            bluetoothPermissionLauncher.launch(missing.toTypedArray())
            return
        }
        requestEnrollmentUsb()
    }

    private fun requestEnrollmentUsb() {
        val device = devices.firstOrNull { it.deviceName == selectedDeviceName } ?: devices.firstOrNull()
        if (device == null) {
            bluetoothEnrollmentRunning = false
            bluetoothEnrollmentError = true
            bluetoothEnrollmentMessage = "Connect CITS-to-go by USB, then try again."
            return
        }
        if (device.vendorId != ESPRESSIF_USB_VENDOR_ID ||
            device.productId != ESPRESSIF_USB_JTAG_SERIAL_PRODUCT_ID
        ) {
            bluetoothEnrollmentRunning = false
            bluetoothEnrollmentError = true
            bluetoothEnrollmentMessage = "Select the connected CITS-to-go USB device, then try again."
            return
        }
        selectedDeviceName = device.deviceName
        if (usbManager.hasPermission(device)) {
            startBluetoothEnrollment(device)
            return
        }
        enrollmentAfterPermission = true
        startAfterPermission = device
        val intent = Intent(CitsBridgeService.ACTION_USB_PERMISSION).setPackage(packageName)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        val pendingIntent = PendingIntent.getBroadcast(this, 22, intent, flags)
        java.util.logging.Logger.getLogger("org.opentrafficmap.citstogo.Permissions").info(
            "USB enrollment permission request: ${ConnectionDiagnostics.usb(device, usbManager)} requestCode=22 flags=$flags",
        )
        usbManager.requestPermission(device, pendingIntent)
        bluetoothEnrollmentMessage = "USB permission is required to enroll this phone."
    }

    private fun startBluetoothEnrollment(device: UsbDevice) {
        bluetoothEnrollmentRunning = true
        bluetoothEnrollmentError = false
        bluetoothEnrollmentMessage = "Enrolling this phone… Keep USB connected."
        Thread {
            val result = runCatching { BluetoothEnrollment(this).enroll(device) }
            runOnUiThread {
                bluetoothEnrollmentRunning = false
                result.fold(
                    onSuccess = {
                        bluetoothEnrollmentError = false
                        bluetoothEnrollmentMessage = "Bluetooth enrollment complete. This phone can now connect securely."
                        logLine = "Bluetooth enrollment complete"
                    },
                    onFailure = { error ->
                        java.util.logging.Logger.getLogger("org.opentrafficmap.citstogo.BluetoothEnrollment").log(java.util.logging.Level.SEVERE, "Enrollment failed", error)
                        bluetoothEnrollmentError = true
                        bluetoothEnrollmentMessage = enrollmentErrorMessage(error)
                        logLine = bluetoothEnrollmentMessage
                    },
                )
            }
        }.start()
    }

    private fun enrollmentErrorMessage(error: Throwable): String {
        val detail = error.message?.trim().orEmpty()
        return when {
            detail.contains("turned off", ignoreCase = true) -> "Turn on Bluetooth, then try again."
            detail.contains("not supported", ignoreCase = true) -> "Bluetooth is not supported on this phone."
            detail.contains("scanner unavailable", ignoreCase = true) -> "Bluetooth scanning is unavailable. Turn Bluetooth off and on, then retry."
            detail.contains("timed out", ignoreCase = true) -> "Enrollment timed out. Keep USB connected and the device nearby, then retry."
            detail.contains("rejected", ignoreCase = true) || detail.contains("pairing", ignoreCase = true) -> "Bluetooth pairing failed or was cancelled. Retry enrollment."
            detail.contains("Firmware refused", ignoreCase = true) -> "The device refused enrollment. Reconnect USB and try again."
            detail.contains("acknowledge", ignoreCase = true) -> "The device did not acknowledge enrollment. Check the USB connection and firmware."
            detail.contains("USB", ignoreCase = true) -> "USB enrollment failed. Reconnect the device and try again."
            detail.isNotBlank() -> "Enrollment failed: $detail"
            else -> "Bluetooth enrollment failed. Try again."
        }
    }

    private fun requestConnectionThenStart() {
        if (connectionMode == ConnectionMode.BLUETOOTH) {
            requestBluetoothThenStart()
        } else {
            requestUsbThenStart()
        }
    }

    private fun requestBluetoothThenStart() {
        java.util.logging.Logger.getLogger("org.opentrafficmap.citstogo.Permissions").info(
            "Bluetooth start requested: ${ConnectionDiagnostics.bluetoothPermissions(this)}",
        )
        saveSettings()
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        val missing = permissions.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) {
            startBridge()
        } else {
            bluetoothPermissionLauncher.launch(missing.toTypedArray())
            java.util.logging.Logger.getLogger("org.opentrafficmap.citstogo.Permissions").info("Bluetooth permission prompt launched: missing=$missing")
            logLine = "Requesting Bluetooth permission"
        }
    }

    private fun requestUsbThenStart() {
        java.util.logging.Logger.getLogger("org.opentrafficmap.citstogo.Permissions").info("USB start requested; selected=$selectedDeviceName")
        saveSettings()
        refreshDevices()
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
        java.util.logging.Logger.getLogger("org.opentrafficmap.citstogo.Permissions").info(
            "USB capture permission request: ${ConnectionDiagnostics.usb(device, usbManager)} requestCode=20 flags=$flags",
        )
        usbManager.requestPermission(device, pendingIntent)
        logLine = "Requesting USB permission"
    }

    private fun setFlashingPageActive(active: Boolean) {
        if (!active) return
        refreshDevices()
        updateFlashingDeviceState()
        if (flashingState.availableReleases.isEmpty() && !releaseLookupRunning) loadReleaseList()
    }

    private fun selectFirmwareRelease(release: FirmwareRelease) {
        if (flashingState.busy || flashingState.customFirmware) return
        firmwareRelease = release
        flashingState = flashingState.copy(
            releaseTag = release.tag,
            firmwareName = release.firmwareName,
            customFirmware = false,
            phase = FirmwareFlashingPhase.WaitingForDevice,
            message = "Firmware release selected. Connect an ESP32-C5 over USB.",
            progress = 0f,
        )
        logFlash(
            ActivityLevel.INFO,
            "Selected release ${release.tag}: ${release.firmwareName} " +
                "(${formatFirmwareSize(release.firmwareSize)}).",
        )
        updateFlashingDeviceState()
    }

    private fun loadReleaseList(force: Boolean = false) {
        if (releaseLookupRunning || (!force && flashingState.availableReleases.isNotEmpty())) return
        releaseLookupRunning = true
        if (!force) firmwareRelease = null
        flashingState = flashingState.copy(
            phase = FirmwareFlashingPhase.LoadingRelease,
            message = "Loading the list of firmware releases…",
            progress = 0f,
        )
        logFlash(ActivityLevel.INFO, "Loading the list of Codeberg firmware releases…")
        Thread {
            runCatching {
                CodebergReleaseClient().listReleases { status ->
                    runOnUiThread { logFlash(ActivityLevel.INFO, status) }
                }
            }
                .onSuccess { releases ->
                    runOnUiThread {
                        releaseLookupRunning = false
                        if (firmwareRelease != null && releases.none { it.tag == firmwareRelease?.tag }) {
                            firmwareRelease = null
                        }
                        flashingState = flashingState.copy(availableReleases = releases)
                        logFlash(ActivityLevel.INFO, "Found ${releases.size} firmware release(s).")
                        if (customFirmwareUri != null) return@runOnUiThread
                        if (firmwareRelease == null) applyDefaultReleaseSelection(releases)
                    }
                }
                .onFailure { error ->
                    runOnUiThread {
                        releaseLookupRunning = false
                        logFlash(
                            ActivityLevel.ERROR,
                            "Release list failed: ${error.message ?: "unknown error"}",
                        )
                        if (customFirmwareUri != null) return@runOnUiThread
                        flashingState = flashingState.copy(
                            phase = FirmwareFlashingPhase.Error,
                            message = error.message ?: "Unable to load the Codeberg release list",
                        )
                    }
                }
        }.start()
    }

    private fun applyDefaultReleaseSelection(releases: List<FirmwareRelease>) {
        val matching = releases.firstOrNull { it.tag.removePrefix("v") == BuildConfig.VERSION_NAME }
        val chosen = matching ?: releases.firstOrNull()
        if (chosen == null) {
            flashingState = flashingState.copy(
                phase = FirmwareFlashingPhase.Error,
                message = "No downloadable firmware release was found.",
            )
            return
        }
        if (matching == null) {
            logFlash(
                ActivityLevel.WARN,
                "No release matches app version ${BuildConfig.VERSION_NAME}; defaulting to ${chosen.tag}.",
            )
        }
        selectFirmwareRelease(chosen)
    }

    private fun updateFlashingDeviceState() {
        if (flashingState.busy || (firmwareRelease == null && customFirmwareUri == null)) return
        val device = connectedEspressifDevice()
        val previousDeviceName = flashingState.deviceName
        val sourceMessage = if (customFirmwareUri != null) {
            "Custom firmware selected. Connect an ESP32-C5 over USB."
        } else {
            "Firmware release selected. Connect an ESP32-C5 over USB."
        }
        val deviceName = device?.let { it.productName ?: it.deviceName }
        flashingState = if (device == null) {
            flashingState.copy(
                deviceName = null,
                phase = FirmwareFlashingPhase.WaitingForDevice,
                message = sourceMessage,
                progress = 0f,
            )
        } else {
            flashingState.copy(
                deviceName = deviceName,
                phase = FirmwareFlashingPhase.Ready,
                message = "ESP32-C5 USB interface detected. Pull the slider fully to flash.",
                progress = 0f,
            )
        }
        when {
            device == null && previousDeviceName != null ->
                logFlash(ActivityLevel.WARN, "ESP32-C5 disconnected. Reconnect it over USB.")
            device != null && previousDeviceName == null ->
                logFlash(ActivityLevel.INFO, "ESP32-C5 detected: $deviceName.")
            device != null && previousDeviceName != null && previousDeviceName != deviceName ->
                logFlash(ActivityLevel.INFO, "ESP32-C5 changed to: $deviceName.")
        }
    }

    private fun requestFirmwareFlash() {
        logFlash(ActivityLevel.INFO, "Flash requested.")
        if (flashAfterStop) return
        val device = connectedEspressifDevice() ?: run {
            logFlash(ActivityLevel.ERROR, "Cannot flash: no ESP32-C5 is connected.")
            updateFlashingDeviceState()
            return
        }
        if (status.running || status.replaying) {
            logFlash(ActivityLevel.INFO, "Receiver is active. Stopping it automatically before flashing.")
            beginStopForFlash()
            return
        }
        ensureFlashUsbPermission(device)
    }

    /** Sends the stop command and defers the flash until the service reports running = false. */
    private fun beginStopForFlash() {
        flashingState = flashingState.copy(
            phase = FirmwareFlashingPhase.Ready,
            message = "Stopping the receiver…",
            progress = 0f,
        )
        flashAfterStop = true
        mainHandler.postDelayed(flashStopTimeout, FLASH_STOP_TIMEOUT_MS)
        sendServiceIntent(CitsBridgeService.ACTION_STOP)
    }

    private fun ensureFlashUsbPermission(device: UsbDevice) {
        if (usbManager.hasPermission(device)) {
            startFirmwareFlash(device)
            return
        }
        logFlash(ActivityLevel.INFO, "Requesting USB permission for the ESP32-C5…")
        flashAfterPermission = true
        val intent = Intent(CitsBridgeService.ACTION_USB_PERMISSION).setPackage(packageName)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        usbManager.requestPermission(device, PendingIntent.getBroadcast(this, 21, intent, flags))
        flashingState = flashingState.copy(message = "Grant USB permission to continue flashing.")
    }

    private fun startFirmwareFlash(device: UsbDevice) {
        val customUri = customFirmwareUri
        val release = firmwareRelease
        if (customUri == null && release == null) return
        if (flashingState.busy) return
        flashAfterStop = false
        mainHandler.removeCallbacks(flashStopTimeout)
        flashingState = flashingState.copy(
            phase = FirmwareFlashingPhase.Downloading,
            message = if (customUri != null) {
                "Reading ${flashingState.firmwareName ?: "custom firmware"}…"
            } else {
                "Downloading and verifying ${release!!.firmwareName}…"
            },
            progress = 0f,
        )
        logFlash(
            ActivityLevel.INFO,
            if (customUri != null) {
                "Reading custom firmware file ${flashingState.firmwareName ?: "firmware.bin"}…"
            } else {
                "Preparing to download ${release!!.firmwareName}…"
            },
        )
        Thread {
            runCatching {
                val firmware = if (customUri != null) {
                    contentResolver.openInputStream(customUri)?.use { input ->
                        FirmwareFileReader.read(input) { bytesRead ->
                            runOnUiThread {
                                logFlashProgress(
                                    "read",
                                    ActivityLevel.INFO,
                                    "Read ${formatFirmwareSize(bytesRead)} from the firmware file…",
                                )
                            }
                        }
                    } ?: throw java.io.IOException("Unable to open the custom firmware file")
                } else {
                    CodebergReleaseClient().downloadAndVerify(
                        release = release!!,
                        onProgress = { progress ->
                            val percent = (progress * 100).toInt().coerceIn(0, 100)
                            runOnUiThread {
                                flashingState = flashingState.copy(progress = progress * 0.2f)
                                logFlashProgress("download", ActivityLevel.INFO, "Downloading firmware — $percent%…")
                            }
                        },
                        onStatus = { status ->
                            runOnUiThread { logFlash(ActivityLevel.INFO, status) }
                        },
                    )
                }
                runOnUiThread {
                    flashingState = flashingState.copy(
                        phase = FirmwareFlashingPhase.Flashing,
                        message = "Flashing ESP32-C5. Keep the cable connected…",
                        progress = 0.2f,
                    )
                    logFlash(ActivityLevel.INFO, "Opening the USB serial interface…")
                }
                UsbCdcSerial(usbManager, device).use { serial ->
                    serial.open(115_200)
                    val transport = object : EspFlashTransport {
                        override fun write(data: ByteArray) = serial.writeAll(data, 10_000)
                        override fun read(buffer: ByteArray, timeoutMs: Int): Int = serial.read(buffer, timeoutMs)
                        override fun setControlLines(dtr: Boolean, rts: Boolean) = serial.setControlLines(dtr, rts)
                    }
                    Esp32RomFlasher(transport).flash(
                        firmware = firmware,
                        onProgress = { progress ->
                            val percent = (progress * 100).toInt().coerceIn(0, 100)
                            runOnUiThread {
                                flashingState = flashingState.copy(progress = 0.2f + progress * 0.8f)
                                logFlashProgress("write", ActivityLevel.INFO, "Writing firmware — $percent%…")
                            }
                        },
                        onStatus = { status ->
                            runOnUiThread { logFlash(ActivityLevel.INFO, status) }
                        },
                    )
                }
            }.onSuccess {
                runOnUiThread {
                    flashingState = flashingState.copy(
                        phase = FirmwareFlashingPhase.Complete,
                        message = "Firmware flashed and verified successfully. Restart the receiver from the Home page.",
                        progress = 1f,
                    )
                    logFlash(ActivityLevel.INFO, "Firmware flashed and verified successfully.")
                }
            }.onFailure { error ->
                runOnUiThread {
                    flashingState = flashingState.copy(
                        phase = FirmwareFlashingPhase.Error,
                        message = error.message ?: "Firmware flashing failed",
                    )
                    logFlash(ActivityLevel.ERROR, error.message ?: "Firmware flashing failed")
                }
            }
        }.start()
    }

    private fun chooseCustomFirmware() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("application/octet-stream")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        openFirmwareLauncher.launch(intent)
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
        logFlash(ActivityLevel.INFO, "Custom firmware selected: $name.")
        updateFlashingDeviceState()
    }

    private fun useReleaseFirmware() {
        if (flashingState.busy) return
        customFirmwareUri = null
        flashingState = flashingState.copy(customFirmware = false)
        val release = firmwareRelease
        when {
            release != null -> selectFirmwareRelease(release)
            flashingState.availableReleases.isNotEmpty() ->
                applyDefaultReleaseSelection(flashingState.availableReleases)
            else -> loadReleaseList(force = true)
        }
    }

    private fun connectedEspressifDevice(): UsbDevice? = devices.firstOrNull {
        it.vendorId == ESPRESSIF_USB_VENDOR_ID && it.productId == ESPRESSIF_USB_JTAG_SERIAL_PRODUCT_ID
    }

    private fun flashingTimestamp(): String =
        LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))

    private fun logFlash(level: ActivityLevel, message: String) {
        flashingLog = FirmwareFlashLog.add(flashingLog, flashingTimestamp(), level, message.trim())
    }

    private fun logFlashProgress(key: String, level: ActivityLevel, message: String) {
        flashingLog = FirmwareFlashLog.addProgress(flashingLog, flashingTimestamp(), key, level, message.trim())
    }

    private fun startBridge() {
        saveSettings()
        val intent = Intent(this, CitsBridgeService::class.java)
            .setAction(CitsBridgeService.ACTION_START)
            .putExtra(CitsBridgeService.EXTRA_CONNECTION_MODE, connectionMode.wireValue)
            .putExtra(CitsBridgeService.EXTRA_DEVICE_NAME, selectedDeviceName.orEmpty())
            .putExtra(CitsBridgeService.EXTRA_MQTT_URI, mqttUri)
            .putExtra(CitsBridgeService.EXTRA_MQTT_ENABLED, mqttEnabled)
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
        createPcapLauncher.launch(intent)
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
            .setType("application/vnd.tcpdump.pcap")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        openPcapLauncher.launch(intent)
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
            locationPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                ),
            )
            return
        }
        val interval = camIntervalMs.toIntOrNull()
            ?.coerceIn(CitsBridgeService.MIN_CAM_INTERVAL_MS, CitsBridgeService.MAX_CAM_INTERVAL_MS)
            ?: CitsBridgeService.DEFAULT_CAM_INTERVAL_MS
        camIntervalMs = interval.toString()
        val randomizationIntervalMs = camRandomizationIntervalSeconds.toLongOrNull()
            ?.times(1_000L)
            ?.coerceIn(
                CitsBridgeService.MIN_CAM_RANDOMIZATION_INTERVAL_MS.toLong(),
                CitsBridgeService.MAX_CAM_RANDOMIZATION_INTERVAL_MS.toLong(),
            )
            ?.toInt()
            ?: CitsBridgeService.DEFAULT_CAM_RANDOMIZATION_INTERVAL_MS
        camRandomizationIntervalSeconds = (randomizationIntervalMs / 1_000).toString()
        sendServiceIntent(CitsBridgeService.ACTION_CONFIGURE_CAM) {
            putExtra(CitsBridgeService.EXTRA_CAM_ENABLED, enabled)
            putExtra(CitsBridgeService.EXTRA_CAM_STATION_TYPE, sremProfile.stationType.code)
            putExtra(CitsBridgeService.EXTRA_CAM_INTERVAL_MS, interval)
            putExtra(CitsBridgeService.EXTRA_CAM_RANDOMIZE_MAC, camRandomizationEnabled)
            putExtra(CitsBridgeService.EXTRA_CAM_RANDOMIZATION_INTERVAL_MS, randomizationIntervalMs)
            val dimensions = dimensionsOf(sremProfile)
            putExtra(CitsBridgeService.EXTRA_CAM_VEHICLE_LENGTH_DM, dimensions.lengthDm ?: 0)
            putExtra(CitsBridgeService.EXTRA_CAM_VEHICLE_WIDTH_DM, dimensions.widthDm ?: 0)
            putExtra(CitsBridgeService.EXTRA_CAM_TRAILER, dimensions.trailer.code)
        }
    }

    /** The size CAMs of [profile] carry: its stored or default dimensions, none for pedestrians and cyclists. */
    private fun dimensionsOf(profile: SremProfile): VehicleDimensions = when {
        !VehicleDimensions.applies(profile) -> VehicleDimensions.UNAVAILABLE
        else -> vehicleDimensions[profile] ?: VehicleDimensions.defaults(profile)
    }

    private fun loadVehicleDimensions(prefs: SharedPreferences, profile: SremProfile): VehicleDimensions {
        val code = profile.preferenceCode
        if (!prefs.contains(PREF_VEHICLE_LENGTH_DM_PREFIX + code)) return VehicleDimensions.defaults(profile)
        return VehicleDimensions(
            lengthDm = prefs.getInt(PREF_VEHICLE_LENGTH_DM_PREFIX + code, 0).takeIf { it > 0 },
            widthDm = prefs.getInt(PREF_VEHICLE_WIDTH_DM_PREFIX + code, 0).takeIf { it > 0 },
            trailer = VehicleDimensions.Trailer.fromCode(prefs.getInt(PREF_VEHICLE_TRAILER_PREFIX + code, 0)),
        )
    }

    /** Stores the size of [profile]; a running CAM broadcast of that profile takes it over at once. */
    private fun saveVehicleDimensions(profile: SremProfile, dimensions: VehicleDimensions) {
        vehicleDimensions = vehicleDimensions + (profile to dimensions)
        val code = profile.preferenceCode
        getSharedPreferences(CitsBridgeService.PREFS, MODE_PRIVATE).edit()
            .putInt(PREF_VEHICLE_LENGTH_DM_PREFIX + code, dimensions.lengthDm ?: 0)
            .putInt(PREF_VEHICLE_WIDTH_DM_PREFIX + code, dimensions.widthDm ?: 0)
            .putInt(PREF_VEHICLE_TRAILER_PREFIX + code, dimensions.trailer.code)
            .apply()
        if (profile == sremProfile && status.camEnabled) configureCam(true)
    }

    /** A profile picked outside the settings is stored at once, and a running CAM broadcast switches to it. */
    private fun selectVehicleProfile(profile: SremProfile) {
        sremProfile = profile
        getSharedPreferences(CitsBridgeService.PREFS, MODE_PRIVATE).edit()
            .putInt(CitsBridgeService.PREF_VEHICLE_TYPE, profile.preferenceCode)
            .apply()
        if (status.camEnabled) configureCam(true)
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

    /**
     * Applies the Display Always On preference. The window flag keeps the screen awake only while
     * this activity is in the foreground, so the phone sleeps normally as soon as the app leaves it.
     */
    private fun applyDisplayAlwaysOn() {
        if (displayAlwaysOnEnabled) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
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

    private fun updateMapHistoryShown(shown: Boolean) {
        mapHistoryShown = shown
        getSharedPreferences(CitsBridgeService.PREFS, MODE_PRIVATE).edit()
            .putBoolean(PREF_MAP_HISTORY_SHOWN, shown)
            .apply()
    }

    /** Keeps the discovery table's sorting, filters and column widths across visits and restarts. */
    private fun updateStationTableState(state: StationTableState) {
        stationTableState = state
        persistStationTableState()
    }

    /** Stores the current view as it is, for the end of a column-width drag. */
    private fun persistStationTableState() {
        getSharedPreferences(CitsBridgeService.PREFS, MODE_PRIVATE).edit()
            .putString(PREF_STATION_TABLE_STATE, stationTableState.encode())
            .apply()
    }

    private fun startOrRequestIntersectionLocation() {
        if (!hasLocationPermission()) {
            locationPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                ),
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
        val logger = java.util.logging.Logger.getLogger("org.opentrafficmap.citstogo.USB.Discovery")
        logger.fine("Refreshing USB inventory; previous selection=$selectedDeviceName USB host=${packageManager.hasSystemFeature(PackageManager.FEATURE_USB_HOST)}")
        devices.clear()
        devices.addAll(usbManager.deviceList.values.sortedWith(compareByDescending<UsbDevice> { it.vendorId == 0x303A }.thenBy { it.deviceName }))
        if (selectedDeviceName == null || devices.none { it.deviceName == selectedDeviceName }) {
            selectedDeviceName = devices.firstOrNull()?.deviceName
        }
        logger.fine("USB inventory: count=${devices.size} selected=$selectedDeviceName")
        devices.forEach { logger.fine(ConnectionDiagnostics.usb(it, usbManager)) }
        if (devices.isEmpty()) logger.warning("Android UsbManager reports no attached USB devices")
    }

    private fun saveSettings(
        mqttUriValue: String = mqttUri,
        nodeIdValue: String = nodeId,
        maxQueueLengthValue: String = maxQueueLength,
        maxQueueAgeSecondsValue: String = maxQueueAgeSeconds,
        sremProfileValue: SremProfile = sremProfile,
        mapStationTimeoutSecondsValue: Int = mapStationTimeoutSeconds,
        mapHistoryDaysValue: Int = mapHistoryDays,
    ) {
        val parsedMaxQueueLength = parseMaxQueueLength(maxQueueLengthValue)
        val parsedMaxQueueAgeMs = parseMaxQueueAgeMs(maxQueueAgeSecondsValue)
        mqttUri = mqttUriValue.trim()
        nodeId = nodeIdValue.trim()
        maxQueueLength = parsedMaxQueueLength.toString()
        maxQueueAgeSeconds = formatQueueAgeSeconds(parsedMaxQueueAgeMs)
        sremProfile = sremProfileValue
        mapStationTimeoutSeconds = mapStationTimeoutSecondsValue
        mapHistoryDays = mapHistoryDaysValue
        getSharedPreferences(CitsBridgeService.PREFS, MODE_PRIVATE).edit()
            .putString(CitsBridgeService.PREF_MQTT_URI, mqttUri)
            .putString(CitsBridgeService.PREF_NODE_ID, nodeId)
            .putInt(CitsBridgeService.PREF_MQTT_MAX_QUEUE_LENGTH, parsedMaxQueueLength)
            .putLong(CitsBridgeService.PREF_MQTT_MAX_QUEUE_AGE_MS, parsedMaxQueueAgeMs)
            .putInt(CitsBridgeService.PREF_VEHICLE_TYPE, sremProfile.preferenceCode)
            .putInt(PREF_MAP_STATION_TIMEOUT_S, mapStationTimeoutSeconds)
            .putInt(PREF_MAP_HISTORY_DAYS, mapHistoryDays)
            .apply()
    }

    /**
     * Stores the MQTT switch and tells a running capture to connect or disconnect right away, so
     * CAM only reception tests do not need a restart. The value is read again on the next start.
     */
    private fun updateMqttEnabled(enabled: Boolean) {
        mqttEnabled = enabled
        getSharedPreferences(CitsBridgeService.PREFS, MODE_PRIVATE).edit()
            .putBoolean(CitsBridgeService.PREF_MQTT_ENABLED, enabled)
            .apply()
        if (status.running) {
            sendServiceIntent(CitsBridgeService.ACTION_SET_MQTT_ENABLED) {
                putExtra(CitsBridgeService.EXTRA_MQTT_ENABLED, enabled)
            }
        }
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

    private fun checkForAppUpdate() {
        val prefs = getSharedPreferences(CitsBridgeService.PREFS, MODE_PRIVATE)
        Thread {
            if (!prefs.getBoolean(CitsBridgeService.PREF_NOTIFY_APP_UPDATE, true)) return@Thread
            val update = runCatching { CodebergAppUpdateChecker().findUpdate(BuildConfig.VERSION_NAME) }.getOrNull()
                ?: return@Thread
            if (prefs.getString(PREF_LAST_NOTIFIED_UPDATE_TAG, null) == update.tag) return@Thread
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) return@Thread
            val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(
                NotificationChannel(
                    UPDATE_CHANNEL_ID,
                    "App updates",
                    NotificationManager.IMPORTANCE_DEFAULT,
                ),
            )
            val releaseIntent = Intent(Intent.ACTION_VIEW, Uri.parse(update.releaseUrl))
            val pendingIntent = PendingIntent.getActivity(
                this,
                0,
                releaseIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val notification = Notification.Builder(this, UPDATE_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("C-ITS to go ${update.version} is available")
                .setContentText("Tap to open the new Codeberg release.")
                .setStyle(Notification.BigTextStyle().bigText("A newer C-ITS to go release (${update.version}) is available on Codeberg. Tap to view the release and download the update."))
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .build()
            manager.notify(UPDATE_NOTIFICATION_ID, notification)
            prefs.edit().putString(PREF_LAST_NOTIFIED_UPDATE_TAG, update.tag).apply()
        }.start()
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
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
        private const val PREF_CONNECTION_MODE = "connectionMode"
        private const val PREF_INTERSECTION_SORT_MODE = "intersection_sort_mode"
        private const val PREF_STATION_TABLE_STATE = "station_table_state"
        private const val PREF_DEBUG_MENU_ENABLED = "debug_menu_enabled"
        private const val PREF_THEME_MODE = "theme_mode"
        private const val PREF_DISPLAY_ALWAYS_ON = "display_always_on"
        private const val PREF_MAP_STATION_TIMEOUT_S = "map_station_timeout_s"
        private const val PREF_MAP_HISTORY_SHOWN = "map_history_shown"
        private const val PREF_MAP_HISTORY_DAYS = "map_history_days"
        private const val PREF_VEHICLE_LENGTH_DM_PREFIX = "vehicle_length_dm_"
        private const val PREF_VEHICLE_WIDTH_DM_PREFIX = "vehicle_width_dm_"
        private const val PREF_VEHICLE_TRAILER_PREFIX = "vehicle_trailer_"
        private const val INTERSECTION_LOCATION_MIN_TIME_MS = 500L
        private const val TX_SHAKE_THRESHOLD_G = 2.7f
        private const val TX_SHAKE_COOLDOWN_MS = 1_200L
        private const val FLASH_STOP_TIMEOUT_MS = 5_000L
        private const val ESPRESSIF_USB_VENDOR_ID = 0x303a
        private const val ESPRESSIF_USB_JTAG_SERIAL_PRODUCT_ID = 0x1001
        private const val PREF_LAST_NOTIFIED_UPDATE_TAG = "last_notified_app_update_tag"
        private const val UPDATE_CHANNEL_ID = "app_updates"
        private const val UPDATE_NOTIFICATION_ID = 4102
    }
}
