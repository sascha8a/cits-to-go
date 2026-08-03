package org.opentrafficmap.citstogo.bridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.net.Uri
import org.opentrafficmap.citstogo.BuildConfig
import org.opentrafficmap.citstogo.MainActivity
import org.opentrafficmap.citstogo.cam.CamIdentity
import org.opentrafficmap.citstogo.cam.CamPosition
import org.opentrafficmap.citstogo.cam.ItsG5FrameBuilder
import org.opentrafficmap.citstogo.cam.StationType
import org.opentrafficmap.citstogo.intersection.IntersectionSnapshot
import org.opentrafficmap.citstogo.intersection.IntersectionSnapshotList
import org.opentrafficmap.citstogo.intersection.IntersectionStateStore
import org.opentrafficmap.citstogo.intersection.SsemDecoder
import org.opentrafficmap.citstogo.intersection.SsemResponseStatus
import org.opentrafficmap.citstogo.intersection.SsemStatus
import org.opentrafficmap.citstogo.protocol.CitsPacket
import org.opentrafficmap.citstogo.protocol.CtgFrameEncoder
import org.opentrafficmap.citstogo.protocol.CtgInboundFrame
import org.opentrafficmap.citstogo.protocol.Ieee80211Mac
import org.opentrafficmap.citstogo.protocol.SerialPacketReader
import org.opentrafficmap.citstogo.srem.SremPosition
import org.opentrafficmap.citstogo.srem.SremRequest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.min

class CitsBridgeService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val pcapLock = Any()
    private lateinit var usbManager: UsbManager
    private lateinit var locationManager: LocationManager
    private lateinit var spool: MqttSpool

    private var serial: UsbCdcSerial? = null
    private var serialReadThread: Thread? = null
    private var serialWriteThread: Thread? = null
    private var pcapWriter: PcapWriter? = null
    private var mqttClient = MiniMqttClient()
    private var wakeLock: PowerManager.WakeLock? = null

    private var usbWanted = false
    private var mqttEnabled = false
    private var mqttConnecting = AtomicBoolean(false)
    private var mqttFlushing = AtomicBoolean(false)
    private var mqttReconnectDelayMs = 1_000L
    private var selectedDeviceName: String? = null
    private var mqttUri = ""
    private var nodeId = ""
    private var mqttMaxQueueLength = DEFAULT_MQTT_MAX_QUEUE_LENGTH
    private var mqttMaxQueueAgeMs = DEFAULT_MQTT_MAX_QUEUE_AGE_MS
    private var mqttQueueFirstElapsedMs = 0L
    private var mqttQueueAgeFlushScheduled = false
    private var startElapsedMs = 0L
    private var lastStatsElapsedMs = 0L
    private var lastPacketStatusElapsedMs = 0L
    private var lastPacketStatusPackets = 0L
    private val txQueue = LinkedBlockingQueue<TxEnvelope>(MAX_TX_QUEUE)
    private val nextTxRequestId = AtomicLong(1)
    private val nextSremRequestId = AtomicLong(1)
    private val nextSremSequenceNumber = AtomicLong(1)
    private val pendingTx = ConcurrentHashMap<Long, PendingTx>()
    private val pendingSremRequests = ConcurrentHashMap<Int, PendingSremRequest>()
    private lateinit var camIdentity: CamIdentity
    @Volatile private var lastLocation: Location? = null
    private var camEnabled = false
    private var camStationType = StationType.PEDESTRIAN
    private var camIntervalMs = DEFAULT_CAM_INTERVAL_MS
    private var lastCamGeneratedElapsedMs = 0L
    private var lastCamLocation: Location? = null
    private val intersectionStore = IntersectionStateStore()
    private var intersectionSnapshot: IntersectionSnapshot? = null
    private var intersectionSnapshots: List<IntersectionSnapshot> = emptyList()

    private var status = BridgeStatus()
    private var packets = 0L
    private var mqttPublished = 0L
    private var pcapPackets = 0L
    private var discoveredDevices = 0L
    private var truncated = 0L
    private var protocolErrors = 0L
    private var txRequested = 0L
    private var txSuccessful = 0L
    private var txFailed = 0L
    private var camSent = 0L
    private var nextStationNotificationId = STATION_NOTIFICATION_ID_BASE
    private val discoveredMacAddresses = mutableSetOf<String>()

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                    if (device != null) {
                        handleDiscoveredDevice(device)
                        selectedDeviceName = device.deviceName
                    }
                    if (usbWanted && serial == null) scheduleUsbReconnect(0)
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                    if (device == null || device.deviceName == selectedDeviceName) {
                        closeSerial("USB device detached")
                        if (usbWanted) scheduleUsbReconnect(2_000)
                    }
                }
            }
        }
    }

    private val locationListener = LocationListener { location ->
        val previous = lastLocation
        if (previous == null || location.time >= previous.time ||
            (location.hasAccuracy() && (!previous.hasAccuracy() || location.accuracy < previous.accuracy))
        ) {
            lastLocation = location
        }
    }

    private val camBroadcaster = object : Runnable {
        override fun run() {
            if (!camEnabled) return
            val elapsedMs = SystemClock.elapsedRealtime()
            val wallClockMs = System.currentTimeMillis()
            val sampledLocation = lastLocation?.takeIf {
                it.time in (wallClockMs - MAX_LOCATION_AGE_MS)..(wallClockMs + 1_000L)
            }
            if (sampledLocation == null) {
                handler.postDelayed(this, CAM_TRIGGER_CHECK_MS)
                return
            }
            if (!camGenerationDue(elapsedMs, sampledLocation)) {
                handler.postDelayed(this, CAM_TRIGGER_CHECK_MS)
                return
            }
            val referenceTimeMs = sampledLocation.time
            val packet = ItsG5FrameBuilder.camFrame(
                camIdentity,
                camStationType,
                CamPosition.fromLocation(sampledLocation),
                referenceTimeMs,
            )
            if (queueTransmit(packet, TxKind.Cam)) {
                lastCamGeneratedElapsedMs = elapsedMs
                lastCamLocation = sampledLocation
            }
            handler.postDelayed(this, CAM_TRIGGER_CHECK_MS)
        }
    }

    private fun camGenerationDue(nowElapsedMs: Long, location: Location?): Boolean {
        val elapsed = nowElapsedMs - lastCamGeneratedElapsedMs
        if (lastCamGeneratedElapsedMs == 0L || elapsed >= camIntervalMs) return true
        if (elapsed < MIN_CAM_INTERVAL_MS) return false
        val previous = lastCamLocation ?: return false
        val current = location ?: return false
        if (previous.distanceTo(current) > 4f) return true
        if (previous.hasSpeed() && current.hasSpeed() &&
            kotlin.math.abs(previous.speed - current.speed) > 0.5f
        ) return true
        if (previous.hasBearing() && current.hasBearing()) {
            val raw = kotlin.math.abs(previous.bearing - current.bearing).mod(360f)
            if (minOf(raw, 360f - raw) > 4f) return true
        }
        return false
    }

    private val maintenance = object : Runnable {
        override fun run() {
            if (mqttEnabled) {
                if (!mqttClient.isConnected()) ensureMqttConnected()
                flushMqttSpool()
            }
            flushPcapQuietly()
            publishStatsIfDue()
            publishStatus(null)
            updateNotification()
            handler.postDelayed(this, MAINTENANCE_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        usbManager = getSystemService(USB_SERVICE) as UsbManager
        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        spool = MqttSpool(this)
        nodeId = loadOrCreateNodeId()
        camIdentity = loadOrCreateCamIdentity()
        loadCamSettings()
        loadMqttQueueSettings()
        discoveredMacAddresses.addAll(loadDiscoveredMacAddresses())
        discoveredDevices = discoveredMacAddresses.size.toLong()
        createNotificationChannel()
        registerReceiverCompat(usbReceiver, IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        })
        startInForeground("Starting")
        scanKnownUsbDevices()
        handler.post(maintenance)
        publishStatus("Bridge service started")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startBridge(
                intent.getStringExtra(EXTRA_DEVICE_NAME),
                intent.getStringExtra(EXTRA_MQTT_URI).orEmpty(),
                intent.getStringExtra(EXTRA_NODE_ID).orEmpty(),
                intent.getIntExtra(EXTRA_MQTT_MAX_QUEUE_LENGTH, mqttMaxQueueLength),
                intent.getLongExtra(EXTRA_MQTT_MAX_QUEUE_AGE_MS, mqttMaxQueueAgeMs),
            )
            ACTION_STOP -> {
                stopBridge()
                stopSelf()
            }
            ACTION_START_PCAP -> startPcap(intent.getStringExtra(EXTRA_PCAP_URI).orEmpty())
            ACTION_STOP_PCAP -> stopPcap(log = true)
            ACTION_REQUEST_STATUS -> publishStatus(null)
            ACTION_CONFIGURE_CAM -> configureCam(
                intent.getBooleanExtra(EXTRA_CAM_ENABLED, false),
                StationType.selectableFromCode(intent.getIntExtra(EXTRA_CAM_STATION_TYPE, StationType.PEDESTRIAN.code)),
                intent.getIntExtra(EXTRA_CAM_INTERVAL_MS, DEFAULT_CAM_INTERVAL_MS),
            )
            ACTION_SEND_SREM -> sendSrem(intent)
            ACTION_REVOKE_TX_APPROVAL -> revokeTxApproval()
            else -> publishStatus(null)
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        stopLocationUpdates()
        runCatching { unregisterReceiver(usbReceiver) }
        stopBridge()
        runCatching { spool.close() }
        super.onDestroy()
    }

    private fun startBridge(
        deviceName: String?,
        requestedMqttUri: String,
        requestedNodeId: String,
        requestedMaxQueueLength: Int,
        requestedMaxQueueAgeMs: Long,
    ) {
        startElapsedMs = SystemClock.elapsedRealtime()
        selectedDeviceName = deviceName?.takeIf { it.isNotBlank() } ?: selectedDeviceName
        if (requestedNodeId.isNotBlank()) {
            nodeId = requestedNodeId.trim()
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(PREF_NODE_ID, nodeId).apply()
        }
        updateMqttQueueSettings(requestedMaxQueueLength, requestedMaxQueueAgeMs)
        mqttUri = requestedMqttUri.trim()
        mqttEnabled = mqttUri.isNotEmpty()
        mqttReconnectDelayMs = 1_000
        usbWanted = true
        acquireWakeLock()
        openUsb()
        if (mqttEnabled) ensureMqttConnected()
        publishStatus("Capture started")
    }

    private fun stopBridge() {
        usbWanted = false
        mqttEnabled = false
        configureCam(false, camStationType, camIntervalMs)
        closeSerial("USB stopped")
        runCatching { mqttClient.close() }
        stopPcap(log = false)
        releaseWakeLock()
        status = status.copy(running = false, usbState = "Stopped", mqttState = "Disabled")
        publishStatus("Capture stopped")
    }

    private fun openUsb() {
        val device = findDevice(selectedDeviceName)
        if (device == null) {
            status = status.copy(running = usbWanted, usbState = "Waiting for USB device")
            scheduleUsbReconnect(2_000)
            return
        }
        selectedDeviceName = device.deviceName
        if (!usbManager.hasPermission(device)) {
            status = status.copy(running = usbWanted, usbState = "USB permission required")
            publishStatus("Grant USB permission in the app")
            return
        }

        closeSerial(null)
        try {
            val opened = UsbCdcSerial(usbManager, device).also { it.open() }
            serial = opened
            promoteConnectedDeviceForeground()
            status = status.copy(running = true, usbState = "Connected: ${opened.description()}")
            val reader = SerialPacketReader(::handlePacket, ::handleTxResult, ::handleProtocolError)
            serialReadThread = Thread({
                val buffer = ByteArray(16 * 1024)
                while (usbWanted && serial === opened) {
                    try {
                        val count = opened.read(buffer, USB_READ_TIMEOUT_MS)
                        if (count > 0) reader.accept(buffer, count)
                    } catch (e: Exception) {
                        handleSerialError(e)
                    }
                }
            }, "ctg-serial-read").apply { start() }
            serialWriteThread = Thread({
                while (usbWanted && serial === opened) {
                    try {
                        val tx = txQueue.take()
                        opened.writeAll(
                            CtgFrameEncoder.txRequest(tx.requestId, tx.packet),
                            USB_WRITE_TIMEOUT_MS,
                        )
                    } catch (e: InterruptedException) {
                        Thread.currentThread().interrupt()
                        break
                    } catch (e: Exception) {
                        handleSerialError(e)
                    }
                }
            }, "ctg-serial-write").apply { start() }
            publishStatus("USB connected")
        } catch (e: Exception) {
            closeSerial(null)
            status = status.copy(running = usbWanted, usbState = "USB error", lastError = "USB open failed: ${e.message}")
            publishStatus(status.lastError)
            scheduleUsbReconnect(2_000)
        }
    }

    private fun closeSerial(message: String?) {
        val old = serial
        serial = null
        serialReadThread?.interrupt()
        serialWriteThread?.interrupt()
        runCatching { old?.close() }
        serialReadThread = null
        serialWriteThread = null
        txQueue.clear()
        if (pendingTx.isNotEmpty()) {
            val pendingSrem = pendingTx.values.firstOrNull { it.kind == TxKind.Srem && it.sremRequestId != null }
            txFailed += pendingTx.size
            pendingTx.clear()
            if (pendingSrem?.sremRequestId != null) {
                pendingSremRequests.remove(pendingSrem.sremRequestId)
                status = status.copy(
                    lastSremState = SREM_STATE_FAILED,
                    lastSremSummary = "SREM transmit canceled: USB disconnected",
                    lastSremUpdatedAtMs = System.currentTimeMillis(),
                )
            }
        }
        if (message != null) {
            status = status.copy(usbState = if (usbWanted) "Waiting for USB device" else "Stopped")
            publishStatus(message)
        }
    }

    private fun handlePacket(packet: CitsPacket) {
        packets += 1
        if (packet.truncated) truncated += 1
        val discoveredMacAddress = Ieee80211Mac.sourceAddress(packet.payload)
        if (discoveredMacAddress != null && discoveredMacAddresses.add(discoveredMacAddress)) {
            discoveredDevices = discoveredMacAddresses.size.toLong()
            storeDiscoveredMacAddresses()
            sendStationDiscoveryNotification(discoveredMacAddress)
        }
        val summary = "#${packet.sequence} ${packet.payload.size}B ${packet.frequencyMhz}MHz ${packet.rssiDbm}dBm"
        queueMqttPacket(packet.payload)
        writePcap(packet)
        updateIntersection(packet.payload)
        updateSsem(packet.payload)
        status = status.copy(
            running = true,
            packets = packets,
            truncated = truncated,
            mqttPublished = mqttPublished,
            pcapRecording = pcapWriter != null,
            pcapPackets = pcapPackets,
            mqttQueued = spool.pendingCount(),
            discoveredDevices = discoveredDevices,
            lastPacketSummary = summary,
            packetTopic = "its/$nodeId/packet",
        )
        if (shouldPublishPacketStatus()) publishStatus("Packet $summary")
    }

    private fun updateIntersection(packet: ByteArray) {
        try {
            intersectionStore.accept(packet)?.let {
                refreshIntersectionSnapshots()
                intersectionSnapshot = intersectionSnapshots.firstOrNull() ?: it
            }
        } catch (_: Exception) {
            // MAPEM/SPATEM decoding is best-effort; malformed or unsupported messages should not affect capture.
        }
    }

    private fun updateSsem(packet: ByteArray) {
        try {
            val its = org.opentrafficmap.citstogo.intersection.ItsFrameExtractor.extract(packet) ?: return
            if (its.messageId != SsemDecoder.MESSAGE_ID_SSEM) return
            SsemDecoder.decode(its, System.currentTimeMillis()).forEach { ssem ->
                handleSsemStatus(ssem)
            }
        } catch (_: Exception) {
            // SSEM decoding is best-effort; unsupported optional branches should not affect capture.
        }
    }

    private fun handleSsemStatus(ssem: SsemStatus) {
        prunePendingSremRequests()
        val pending = matchingSremRequest(ssem) ?: return
        val state = when (ssem.responseStatus) {
            SsemResponseStatus.Granted -> SREM_STATE_GRANTED
            SsemResponseStatus.Rejected,
            SsemResponseStatus.MaxPresence,
            SsemResponseStatus.ReserviceLocked -> SREM_STATE_REJECTED
            SsemResponseStatus.Processing -> SREM_STATE_PROCESSING
            SsemResponseStatus.Requested -> SREM_STATE_ACKNOWLEDGED
            SsemResponseStatus.WatchOtherTraffic -> SREM_STATE_WATCH_OTHER_TRAFFIC
            SsemResponseStatus.Unknown,
            SsemResponseStatus.Unsupported -> SREM_STATE_UNKNOWN_RESPONSE
        }
        status = status.copy(
            lastSremState = state,
            lastSremSummary = "SSEM ${ssem.responseStatus.label.lowercase()} for request ${pending.requestId}",
            lastSremRequestId = pending.requestId,
            lastSremIntersectionId = pending.intersectionId,
            lastSremInboundLaneId = pending.inboundLaneId,
            lastSremOutboundLaneId = pending.outboundLaneId,
            lastSremUpdatedAtMs = ssem.receivedAtMs,
            lastError = if (state == SREM_STATE_REJECTED) ssem.responseStatus.label else status.lastError,
        )
        if (state in setOf(SREM_STATE_GRANTED, SREM_STATE_REJECTED)) {
            pendingSremRequests.remove(pending.requestId)
        }
        publishStatus(status.lastSremSummary)
    }

    private fun matchingSremRequest(ssem: SsemStatus): PendingSremRequest? {
        ssem.requestId?.let { requestId ->
            pendingSremRequests[requestId]?.takeIf { it.matches(ssem, requireLaneMatch = false) }?.let {
                return it
            }
        }
        return pendingSremRequests.values
            .filter { it.matches(ssem, requireLaneMatch = true) }
            .maxByOrNull { it.sentAtMs }
    }

    private fun prunePendingSremRequests(nowMs: Long = System.currentTimeMillis()) {
        pendingSremRequests.entries.removeAll { (_, request) ->
            nowMs - request.sentAtMs > PENDING_SREM_MAX_AGE_MS
        }
    }

    private fun queueTransmit(packet: ByteArray, kind: TxKind, sremRequestId: Int? = null): Boolean {
        if (!hasTxApproval()) {
            txFailed += 1
            status = status.copy(lastError = "TX approval is required before transmitting")
            publishStatus(status.lastError)
            return false
        }
        if (packet.isEmpty() || packet.size > CtgFrameEncoder.MAX_PACKET_BYTES) {
            txFailed += 1
            status = status.copy(lastError = "TX packet size ${packet.size} is outside 1..${CtgFrameEncoder.MAX_PACKET_BYTES}")
            publishStatus(status.lastError)
            return false
        }
        if (serial == null) {
            txFailed += 1
            status = status.copy(lastError = "TX requires a connected USB device")
            publishStatus(status.lastError)
            return false
        }
        val requestId = nextTxRequestId.getAndIncrement() and 0xffff_ffffL
        val packetCopy = packet.copyOf()
        val envelope = TxEnvelope(requestId, packetCopy)
        pendingTx[requestId] = PendingTx(packetCopy, kind, sremRequestId)
        if (!txQueue.offer(envelope)) {
            pendingTx.remove(requestId)
            txFailed += 1
            status = status.copy(lastError = "Android TX queue is full")
            publishStatus(status.lastError)
            return false
        }
        txRequested += 1
        return true
    }

    private fun handleTxResult(result: CtgInboundFrame.TxResult) {
        val pending = pendingTx.remove(result.requestId)
        val isCam = pending?.kind == TxKind.Cam
        val sremRequestId = pending?.sremRequestId
        val summary = "#${result.requestId} ${result.packetLength}B " +
            if (result.successful) "sent" else "failed (ESP 0x${result.status.toString(16)})"
        if (result.successful) {
            txSuccessful += 1
            if (isCam) camSent += 1
            if (sremRequestId != null) {
                status = status.copy(
                    lastSremState = SREM_STATE_TRANSMITTED,
                    lastSremSummary = "SREM transmitted for request $sremRequestId",
                    lastSremUpdatedAtMs = System.currentTimeMillis(),
                )
            }
            val packet = result.packet ?: pending?.packet
            queueMqttPacket(packet)
            writePcapPacket(packet)
        } else {
            txFailed += 1
            if (sremRequestId != null) {
                pendingSremRequests.remove(sremRequestId)
                status = status.copy(
                    lastSremState = SREM_STATE_FAILED,
                    lastSremSummary = "SREM transmit failed for request $sremRequestId",
                    lastSremUpdatedAtMs = System.currentTimeMillis(),
                )
            }
        }
        status = status.copy(lastTxSummary = summary, lastError = if (result.successful) "" else summary)
        publishStatus("TX $summary")
    }

    private fun sendSrem(intent: Intent) {
        val nowMs = System.currentTimeMillis()
        if (!hasTxApproval()) {
            status = status.copy(
                lastError = "TX approval is required before SREM",
                lastSremState = SREM_STATE_FAILED,
                lastSremSummary = "SREM blocked: TX approval required",
                lastSremUpdatedAtMs = nowMs,
            )
            publishStatus(status.lastError)
            return
        }
        if (serial == null) {
            status = status.copy(
                lastError = "SREM requires a connected USB device",
                lastSremState = SREM_STATE_FAILED,
                lastSremSummary = "SREM blocked: USB disconnected",
                lastSremUpdatedAtMs = nowMs,
            )
            publishStatus(status.lastError)
            return
        }

        val intersectionId = intent.getIntExtra(EXTRA_SREM_INTERSECTION_ID, -1)
        val inboundLaneId = intent.getIntExtra(EXTRA_SREM_INBOUND_LANE_ID, -1)
        val outboundLaneId = intent.getIntExtra(EXTRA_SREM_OUTBOUND_LANE_ID, -1)
        val latitude = intent.getIntExtra(EXTRA_SREM_LATITUDE_E7, 900_000_001)
        val longitude = intent.getIntExtra(EXTRA_SREM_LONGITUDE_E7, 1_800_000_001)
        val positionTimeMs = intent.getLongExtra(EXTRA_SREM_POSITION_TIME_MS, 0L)
        val region = intent.getIntExtra(EXTRA_SREM_REGION, -1).takeIf { it >= 0 }
        if (
            intersectionId !in 0..65_535 ||
            inboundLaneId !in 0..255 ||
            outboundLaneId !in 0..255 ||
            latitude !in -900_000_000..900_000_000 ||
            longitude !in -1_800_000_000..1_800_000_000 ||
            positionTimeMs !in (nowMs - MAX_LOCATION_AGE_MS)..(nowMs + 1_000L)
        ) {
            status = status.copy(
                lastError = "SREM requires a valid intersection, lane pair, and fresh location",
                lastSremState = SREM_STATE_FAILED,
                lastSremSummary = "SREM blocked: invalid request context",
                lastSremUpdatedAtMs = nowMs,
            )
            publishStatus(status.lastError)
            return
        }

        val requestId = ((nextSremRequestId.getAndIncrement() - 1) % 255 + 1).toInt()
        val sequenceNumber = (nextSremSequenceNumber.getAndIncrement() % 128).toInt()
        val request = SremRequest(
            region = region,
            intersectionId = intersectionId,
            requestId = requestId,
            sequenceNumber = sequenceNumber,
            inboundLaneId = inboundLaneId,
            outboundLaneId = outboundLaneId,
            position = SremPosition(latitude, longitude),
            nowUnixMs = nowMs,
        )
        val packet = try {
            ItsG5FrameBuilder.sremFrame(camIdentity, request)
        } catch (e: Exception) {
            status = status.copy(
                lastError = "SREM encode failed: ${e.message}",
                lastSremState = SREM_STATE_FAILED,
                lastSremSummary = "SREM encode failed",
                lastSremUpdatedAtMs = nowMs,
            )
            publishStatus(status.lastError)
            return
        }

        status = status.copy(
            lastSremState = SREM_STATE_QUEUED,
            lastSremSummary = "SREM queued for lane $inboundLaneId -> $outboundLaneId",
            lastSremRequestId = requestId,
            lastSremIntersectionId = intersectionId,
            lastSremInboundLaneId = inboundLaneId,
            lastSremOutboundLaneId = outboundLaneId,
            lastSremUpdatedAtMs = nowMs,
            lastError = "",
        )
        pendingSremRequests[requestId] = PendingSremRequest(
            requestId = requestId,
            sequenceNumber = sequenceNumber,
            stationId = camIdentity.stationId,
            region = region,
            intersectionId = intersectionId,
            inboundLaneId = inboundLaneId,
            outboundLaneId = outboundLaneId,
            sentAtMs = nowMs,
        )
        if (queueTransmit(packet, TxKind.Srem, requestId)) {
            publishStatus("SREM queued")
        } else {
            pendingSremRequests.remove(requestId)
            status = status.copy(
                lastSremState = SREM_STATE_FAILED,
                lastSremSummary = status.lastError.ifBlank { "SREM queue failed" },
                lastSremUpdatedAtMs = System.currentTimeMillis(),
            )
            publishStatus(status.lastError)
        }
    }

    private fun configureCam(enabled: Boolean, stationType: StationType, requestedIntervalMs: Int) {
        if (enabled && !hasTxApproval()) {
            camEnabled = false
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putBoolean(PREF_CAM_ENABLED, false)
                .apply()
            status = status.copy(lastError = "TX approval is required before CAM broadcast")
            publishStatus(status.lastError)
            return
        }
        camStationType = if (stationType in StationType.selectable) stationType else StationType.PEDESTRIAN
        camIntervalMs = requestedIntervalMs.coerceIn(MIN_CAM_INTERVAL_MS, MAX_CAM_INTERVAL_MS)
        camEnabled = enabled
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putBoolean(PREF_CAM_ENABLED, camEnabled)
            .putInt(PREF_CAM_STATION_TYPE, camStationType.code)
            .putInt(PREF_CAM_INTERVAL_MS, camIntervalMs)
            .apply()
        handler.removeCallbacks(camBroadcaster)
        if (camEnabled) {
            if (!startLocationUpdates()) {
                camEnabled = false
                status = status.copy(lastError = "Location permission is required for CAM broadcast")
                publishStatus(status.lastError)
                return
            }
            promoteCamForeground()
            lastCamGeneratedElapsedMs = 0
            lastCamLocation = null
            handler.post(camBroadcaster)
        } else {
            stopLocationUpdates()
        }
        publishStatus(if (camEnabled) "CAM broadcast enabled" else "CAM broadcast disabled")
    }

    private fun revokeTxApproval() {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putBoolean(PREF_TX_APPROVED, false)
            .putBoolean(PREF_CAM_ENABLED, false)
            .apply()
        handler.removeCallbacks(camBroadcaster)
        if (camEnabled) stopLocationUpdates()
        camEnabled = false
        txQueue.clear()
        if (pendingTx.isNotEmpty()) {
            pendingTx.values.mapNotNull { it.sremRequestId }.forEach { pendingSremRequests.remove(it) }
            txFailed += pendingTx.size
            pendingTx.clear()
        }
        status = status.copy(lastTxSummary = "", lastError = "")
        publishStatus("TX approval revoked")
    }

    private fun startLocationUpdates(): Boolean {
        if (checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED
        ) return false
        runCatching {
            locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.let { lastLocation = it }
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER, 100L, 0f, locationListener, Looper.getMainLooper())
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER, 250L, 0f, locationListener, Looper.getMainLooper())
            }
        }.onFailure {
            status = status.copy(lastError = "Location unavailable: ${it.message}")
            return false
        }
        return true
    }

    private fun stopLocationUpdates() {
        runCatching { locationManager.removeUpdates(locationListener) }
    }

    private fun shouldPublishPacketStatus(): Boolean {
        val now = SystemClock.elapsedRealtime()
        val elapsedSinceLastUpdate = now - lastPacketStatusElapsedMs
        val packetsSinceLastUpdate = packets - lastPacketStatusPackets
        if (
            lastPacketStatusElapsedMs != 0L &&
            elapsedSinceLastUpdate < PACKET_STATUS_INTERVAL_MS &&
            packetsSinceLastUpdate < PACKET_STATUS_PACKET_INTERVAL
        ) {
            return false
        }
        lastPacketStatusElapsedMs = now
        lastPacketStatusPackets = packets
        return true
    }

    private fun startPcap(uriString: String) {
        if (uriString.isBlank()) {
            status = status.copy(lastError = "PCAP destination missing")
            publishStatus(status.lastError)
            return
        }
        synchronized(pcapLock) {
            stopPcapLocked(log = false)
            try {
                val uri = Uri.parse(uriString)
                val stream = contentResolver.openOutputStream(uri, "w")
                    ?: throw IllegalStateException("Could not open output stream")
                pcapWriter = PcapWriter(stream)
                pcapPackets = 0
                status = status.copy(pcapRecording = true, pcapPackets = 0, lastError = "")
                publishStatus("PCAP recording started")
            } catch (e: Exception) {
                pcapWriter = null
                status = status.copy(pcapRecording = false, lastError = "PCAP open failed: ${e.message}")
                publishStatus(status.lastError)
            }
        }
    }

    private fun stopPcap(log: Boolean) {
        synchronized(pcapLock) {
            stopPcapLocked(log)
        }
    }

    private fun stopPcapLocked(log: Boolean) {
        val writer = pcapWriter ?: return
        pcapWriter = null
        runCatching { writer.close() }
            .onFailure { status = status.copy(lastError = "PCAP close failed: ${it.message}") }
        status = status.copy(pcapRecording = false, pcapPackets = pcapPackets)
        if (log) publishStatus("PCAP recording stopped")
    }

    private fun writePcap(packet: CitsPacket) {
        synchronized(pcapLock) {
            val writer = pcapWriter ?: return
            try {
                writer.writePacket(packet)
                pcapPackets += 1
            } catch (e: Exception) {
                pcapWriter = null
                runCatching { writer.close() }
                status = status.copy(pcapRecording = false, lastError = "PCAP write failed: ${e.message}")
                publishStatus(status.lastError)
            }
        }
    }

    private fun writePcapPacket(packet: ByteArray?) {
        if (packet == null) return
        synchronized(pcapLock) {
            val writer = pcapWriter ?: return
            try {
                writer.writeRawPacket(packet, System.currentTimeMillis() * 1_000L)
                pcapPackets += 1
            } catch (e: Exception) {
                pcapWriter = null
                runCatching { writer.close() }
                status = status.copy(pcapRecording = false, lastError = "PCAP write failed: ${e.message}")
                publishStatus(status.lastError)
            }
        }
    }

    private fun flushPcapQuietly() {
        synchronized(pcapLock) {
            runCatching { pcapWriter?.flush() }
        }
    }

    private fun handleProtocolError(message: String) {
        protocolErrors += 1
        status = status.copy(protocolErrors = protocolErrors, lastError = "Protocol: $message")
        if (protocolErrors % 10L == 1L) publishStatus(status.lastError)
    }

    private fun handleSerialError(error: Exception) {
        status = status.copy(usbState = "USB error", lastError = "Serial read failed: ${error.message}")
        publishStatus(status.lastError)
        closeSerial(null)
        if (usbWanted) scheduleUsbReconnect(2_000)
    }

    private fun queueMqttPacket(packet: ByteArray?) {
        if (!mqttEnabled || packet == null) return
        runCatching {
            spool.append(packet)
            updateQueueDrainSchedule()
        }.onFailure {
            status = status.copy(lastError = "MQTT spool failed: ${it.message}")
        }
    }

    private fun ensureMqttConnected() {
        if (!mqttEnabled || mqttClient.isConnected() || !mqttConnecting.compareAndSet(false, true)) return
        Thread({
            try {
                status = status.copy(mqttState = "Connecting")
                publishStatus(null)
                mqttClient.connect(mqttUri, nodeId, BuildConfig.VERSION_NAME)
                mqttReconnectDelayMs = 1_000
                status = status.copy(mqttState = "Connected", packetTopic = "its/$nodeId/packet", lastError = "")
                publishStatus("MQTT connected")
            } catch (e: Exception) {
                runCatching { mqttClient.close() }
                status = status.copy(mqttState = "Reconnecting", lastError = "MQTT connect failed: ${e.message}")
                publishStatus(status.lastError)
                scheduleMqttReconnect()
            } finally {
                mqttConnecting.set(false)
            }
        }, "mqtt-connect").start()
    }

    private fun flushMqttSpool() {
        if (!mqttEnabled || !mqttClient.isConnected() || !mqttFlushing.compareAndSet(false, true)) return
        Thread({
            var shouldContinueDraining = false
            try {
                val batch = spool.readBatch(MQTT_MAX_BATCH)
                if (batch.isEmpty()) return@Thread
                var sent = 0
                var nextOffset = 0L
                for (record in batch) {
                    mqttClient.publishPacket(record.payload)
                    sent += 1
                    nextOffset = record.nextOffset
                }
                mqttClient.flush()
                spool.ack(nextOffset, sent)
                mqttPublished += sent
                val pendingAfter = spool.pendingCount()
                if (pendingAfter == 0L) {
                    resetQueueDrainSchedule()
                } else {
                    mqttQueueFirstElapsedMs = SystemClock.elapsedRealtime()
                    shouldContinueDraining = pendingAfter > mqttMaxQueueLength
                    if (!shouldContinueDraining) scheduleQueueAgeFlush()
                }
                status = status.copy(
                    mqttState = "Connected",
                    mqttPublished = mqttPublished,
                    mqttQueued = spool.pendingCount(),
                )
            } catch (e: Exception) {
                runCatching { mqttClient.close() }
                status = status.copy(mqttState = "Offline, spooling", lastError = "MQTT publish failed: ${e.message}")
                publishStatus(status.lastError)
                scheduleMqttReconnect()
            } finally {
                mqttFlushing.set(false)
                if (shouldContinueDraining) flushMqttSpool()
            }
        }, "mqtt-flush").start()
    }

    private fun updateQueueDrainSchedule() {
        val pending = spool.pendingCount()
        if (pending <= 0L) {
            resetQueueDrainSchedule()
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (mqttQueueFirstElapsedMs == 0L) mqttQueueFirstElapsedMs = now
        if (pending > mqttMaxQueueLength) {
            flushMqttSpool()
            return
        }
        scheduleQueueAgeFlush()
    }

    private fun scheduleQueueAgeFlush() {
        if (mqttMaxQueueAgeMs <= 0L) {
            flushMqttSpool()
            return
        }
        if (mqttQueueAgeFlushScheduled || mqttQueueFirstElapsedMs == 0L) return
        val delayMs = (mqttQueueFirstElapsedMs + mqttMaxQueueAgeMs - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
        mqttQueueAgeFlushScheduled = true
        handler.postDelayed({
            mqttQueueAgeFlushScheduled = false
            val firstQueuedAt = mqttQueueFirstElapsedMs
            if (firstQueuedAt != 0L && SystemClock.elapsedRealtime() - firstQueuedAt >= mqttMaxQueueAgeMs) {
                flushMqttSpool()
            } else {
                scheduleQueueAgeFlush()
            }
        }, delayMs)
    }

    private fun resetQueueDrainSchedule() {
        mqttQueueFirstElapsedMs = 0L
        mqttQueueAgeFlushScheduled = false
    }

    private fun publishStatsIfDue() {
        if (!mqttEnabled || !mqttClient.isConnected()) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastStatsElapsedMs < 60_000) return
        lastStatsElapsedMs = now
        runCatching {
            val uptime = if (startElapsedMs == 0L) 0L else (now - startElapsedMs) / 1000L
            mqttClient.publishStats(uptime)
        }.onFailure {
            runCatching { mqttClient.close() }
            status = status.copy(mqttState = "Offline, spooling", lastError = "MQTT stats failed: ${it.message}")
            scheduleMqttReconnect()
        }
    }

    private fun scheduleUsbReconnect(delayMs: Long) {
        if (!usbWanted) return
        handler.postDelayed({ if (usbWanted && serial == null) openUsb() }, delayMs)
    }

    private fun scheduleMqttReconnect() {
        if (!mqttEnabled) return
        val delay = mqttReconnectDelayMs
        mqttReconnectDelayMs = min(mqttReconnectDelayMs * 2, 60_000L)
        handler.postDelayed({ ensureMqttConnected() }, delay)
    }

    private fun findDevice(deviceName: String?): UsbDevice? {
        val devices = usbManager.deviceList.values.toList()
        if (devices.isEmpty()) return null
        if (deviceName != null) {
            devices.firstOrNull { it.deviceName == deviceName }?.let { return it }
        }
        return devices.firstOrNull { it.vendorId == ESPRESSIF_VENDOR_ID } ?: devices.first()
    }

    private fun scanKnownUsbDevices() {
        usbManager.deviceList.values.forEach { handleDiscoveredDevice(it) }
    }

    private fun handleDiscoveredDevice(device: UsbDevice) {
        if (!device.isCitsDevice()) return
        val message = "C-ITS device discovered: ${device.discoveryLabel()}"
        publishStatus(message)
    }

    private fun UsbDevice.isCitsDevice(): Boolean = vendorId == ESPRESSIF_VENDOR_ID

    private fun UsbDevice.discoveryLabel(): String {
        val product = listOfNotNull(manufacturerName, productName).joinToString(" ").ifBlank { deviceName }
        return "$product (vid=%04x pid=%04x)".format(vendorId, productId)
    }

    private fun publishStatus(log: String?) {
        refreshIntersectionSnapshots()
        status = status.copy(
            running = usbWanted,
            nodeId = nodeId,
            packetTopic = "its/$nodeId/packet",
            mqttQueued = spool.pendingCount(),
            packets = packets,
            mqttPublished = mqttPublished,
            pcapRecording = pcapWriter != null,
            pcapPackets = pcapPackets,
            discoveredDevices = discoveredDevices,
            truncated = truncated,
            protocolErrors = protocolErrors,
            txRequested = txRequested,
            txSuccessful = txSuccessful,
            txFailed = txFailed,
            camEnabled = camEnabled,
            camSent = camSent,
            mqttState = when {
                !mqttEnabled -> "Disabled"
                mqttClient.isConnected() -> "Connected"
                status.mqttState.isBlank() -> "Offline, spooling"
                else -> status.mqttState
            },
        )
        val intent = Intent(ACTION_STATUS).setPackage(packageName)
        intent.putExtra(EXTRA_RUNNING, status.running)
        intent.putExtra(EXTRA_USB_STATE, status.usbState)
        intent.putExtra(EXTRA_MQTT_STATE, status.mqttState)
        intent.putExtra(EXTRA_NODE_ID, status.nodeId)
        intent.putExtra(EXTRA_PACKET_TOPIC, status.packetTopic)
        intent.putExtra(EXTRA_PACKETS, status.packets)
        intent.putExtra(EXTRA_MQTT_PUBLISHED, status.mqttPublished)
        intent.putExtra(EXTRA_MQTT_QUEUED, status.mqttQueued)
        intent.putExtra(EXTRA_PCAP_RECORDING, status.pcapRecording)
        intent.putExtra(EXTRA_PCAP_PACKETS, status.pcapPackets)
        intent.putExtra(EXTRA_DISCOVERED_DEVICES, status.discoveredDevices)
        intent.putExtra(EXTRA_TRUNCATED, status.truncated)
        intent.putExtra(EXTRA_PROTOCOL_ERRORS, status.protocolErrors)
        intent.putExtra(EXTRA_TX_REQUESTED, status.txRequested)
        intent.putExtra(EXTRA_TX_SUCCESSFUL, status.txSuccessful)
        intent.putExtra(EXTRA_TX_FAILED, status.txFailed)
        intent.putExtra(EXTRA_CAM_ENABLED, status.camEnabled)
        intent.putExtra(EXTRA_CAM_SENT, status.camSent)
        intent.putExtra(EXTRA_SREM_STATE, status.lastSremState)
        intent.putExtra(EXTRA_SREM_SUMMARY, status.lastSremSummary)
        intent.putExtra(EXTRA_SREM_REQUEST_ID, status.lastSremRequestId)
        intent.putExtra(EXTRA_SREM_INTERSECTION_ID, status.lastSremIntersectionId)
        intent.putExtra(EXTRA_SREM_INBOUND_LANE_ID, status.lastSremInboundLaneId)
        intent.putExtra(EXTRA_SREM_OUTBOUND_LANE_ID, status.lastSremOutboundLaneId)
        intent.putExtra(EXTRA_SREM_UPDATED_AT_MS, status.lastSremUpdatedAtMs)
        intent.putExtra(EXTRA_LAST_TX, status.lastTxSummary)
        intent.putExtra(EXTRA_LAST_PACKET, status.lastPacketSummary)
        intent.putExtra(EXTRA_LAST_ERROR, status.lastError)
        intent.putExtra(EXTRA_INTERSECTION_SNAPSHOTS, IntersectionSnapshotList(intersectionSnapshots))
        intersectionSnapshot?.let { intent.putExtra(EXTRA_INTERSECTION_SNAPSHOT, it) }
        if (!log.isNullOrBlank()) intent.putExtra(EXTRA_LOG, log)
        sendBroadcast(intent)
    }

    private fun refreshIntersectionSnapshots(nowMs: Long = System.currentTimeMillis()) {
        intersectionSnapshots = intersectionStore.activeSnapshots(nowMs, INTERSECTION_MAX_AGE_MS)
        intersectionSnapshot = intersectionSnapshots.firstOrNull()
    }

    private fun updateNotification() {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(status.summary()))
    }

    private fun sendStationDiscoveryNotification(macAddress: String) {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(
            nextStationNotificationId++,
            buildStationDiscoveryNotification(macAddress),
        )
    }

    private fun startInForeground(content: String) {
        val notification = buildNotification(content)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun promoteConnectedDeviceForeground() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                buildNotification(status.summary()),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
        }
    }

    private fun promoteCamForeground() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                buildNotification(status.summary()),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        }
    }

    private fun buildNotification(content: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val openPi = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = Intent(this, CitsBridgeService::class.java).setAction(ACTION_STOP)
        val stopPi = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("C-ITS to go")
            .setContentText(content)
            .setStyle(Notification.BigTextStyle().bigText(content))
            .setOngoing(true)
            .setContentIntent(openPi)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPi)
            .build()
    }

    private fun buildStationDiscoveryNotification(macAddress: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val openPi = PendingIntent.getActivity(
            this,
            2,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val content = "C-ITS station discovered: $macAddress"
        return Notification.Builder(this, STATION_DISCOVERY_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("New C-ITS station")
            .setContentText(content)
            .setStyle(Notification.BigTextStyle().bigText(content))
            .setAutoCancel(true)
            .setContentIntent(openPi)
            .build()
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            "C-ITS bridge",
            NotificationManager.IMPORTANCE_LOW,
        )
        channel.description = "Keeps USB serial capture and MQTT forwarding active while the phone is locked."
        manager.createNotificationChannel(channel)
        manager.deleteNotificationChannel(LEGACY_DEVICE_DISCOVERY_CHANNEL_ID)
        val stationDiscoveryChannel = NotificationChannel(
            STATION_DISCOVERY_CHANNEL_ID,
            "C-ITS station discovery",
            NotificationManager.IMPORTANCE_DEFAULT,
        )
        stationDiscoveryChannel.description = "Alerts when a new C-ITS station is discovered."
        manager.createNotificationChannel(stationDiscoveryChannel)
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "citstogo:bridge").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    private fun loadOrCreateNodeId(): String {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        prefs.getString(PREF_NODE_ID, null)?.takeIf { it.isNotBlank() }?.let { return it }
        val bytes = ByteArray(6)
        SecureRandom().nextBytes(bytes)
        val generated = bytes.joinToString("") { "%02x".format(it) }
        prefs.edit().putString(PREF_NODE_ID, generated).apply()
        return generated
    }

    private fun loadOrCreateCamIdentity(): CamIdentity {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val storedId = prefs.getLong(PREF_CAM_STATION_ID, -1L)
        val storedMac = prefs.getString(PREF_CAM_MAC, null)?.let(::parseMac)
        if (storedId in 0..0xffff_ffffL && storedMac != null) {
            return CamIdentity(storedId, storedMac)
        }
        val random = SecureRandom()
        val stationId = random.nextInt().toLong() and 0xffff_ffffL
        val mac = ByteArray(6).also(random::nextBytes)
        mac[0] = ((mac[0].toInt() and 0xfc) or 0x02).toByte()
        prefs.edit()
            .putLong(PREF_CAM_STATION_ID, stationId)
            .putString(PREF_CAM_MAC, mac.joinToString(":") { "%02x".format(it) })
            .apply()
        return CamIdentity(stationId, mac)
    }

    private fun parseMac(value: String): ByteArray? {
        val parts = value.split(":")
        if (parts.size != 6) return null
        return runCatching { ByteArray(6) { parts[it].toInt(16).toByte() } }.getOrNull()
    }

    private fun loadCamSettings() {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        camStationType = StationType.selectableFromCode(
            prefs.getInt(PREF_CAM_STATION_TYPE, StationType.PEDESTRIAN.code))
        camIntervalMs = prefs.getInt(PREF_CAM_INTERVAL_MS, DEFAULT_CAM_INTERVAL_MS)
            .coerceIn(MIN_CAM_INTERVAL_MS, MAX_CAM_INTERVAL_MS)
        // Never resume RF transmission merely because the process was recreated.
        camEnabled = false
    }

    private fun loadDiscoveredMacAddresses(): Set<String> =
        getSharedPreferences(PREFS, MODE_PRIVATE)
            .getStringSet(PREF_DISCOVERED_MAC_ADDRESSES, emptySet())
            ?.filterTo(mutableSetOf()) { it.isNotBlank() }
            ?: emptySet()

    private fun storeDiscoveredMacAddresses() {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putStringSet(PREF_DISCOVERED_MAC_ADDRESSES, discoveredMacAddresses.toSet())
            .apply()
    }

    private fun loadMqttQueueSettings() {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        mqttMaxQueueLength = prefs.getInt(PREF_MQTT_MAX_QUEUE_LENGTH, DEFAULT_MQTT_MAX_QUEUE_LENGTH).coerceAtLeast(1)
        mqttMaxQueueAgeMs = prefs.getLong(PREF_MQTT_MAX_QUEUE_AGE_MS, DEFAULT_MQTT_MAX_QUEUE_AGE_MS).coerceAtLeast(0L)
    }

    private fun updateMqttQueueSettings(maxQueueLength: Int, maxQueueAgeMs: Long) {
        mqttMaxQueueLength = maxQueueLength.coerceAtLeast(1)
        mqttMaxQueueAgeMs = maxQueueAgeMs.coerceAtLeast(0L)
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putInt(PREF_MQTT_MAX_QUEUE_LENGTH, mqttMaxQueueLength)
            .putLong(PREF_MQTT_MAX_QUEUE_AGE_MS, mqttMaxQueueAgeMs)
            .apply()
        if (spool.pendingCount() > 0L) updateQueueDrainSchedule()
    }

    private fun hasTxApproval(): Boolean =
        getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(PREF_TX_APPROVED, false)

    private fun registerReceiverCompat(receiver: BroadcastReceiver, filter: IntentFilter) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(receiver, filter)
        }
    }

    companion object {
        const val ACTION_START = "org.opentrafficmap.citstogo.action.START"
        const val ACTION_STOP = "org.opentrafficmap.citstogo.action.STOP"
        const val ACTION_START_PCAP = "org.opentrafficmap.citstogo.action.START_PCAP"
        const val ACTION_STOP_PCAP = "org.opentrafficmap.citstogo.action.STOP_PCAP"
        const val ACTION_REQUEST_STATUS = "org.opentrafficmap.citstogo.action.REQUEST_STATUS"
        const val ACTION_CONFIGURE_CAM = "org.opentrafficmap.citstogo.action.CONFIGURE_CAM"
        const val ACTION_SEND_SREM = "org.opentrafficmap.citstogo.action.SEND_SREM"
        const val ACTION_REVOKE_TX_APPROVAL = "org.opentrafficmap.citstogo.action.REVOKE_TX_APPROVAL"
        const val ACTION_STATUS = "org.opentrafficmap.citstogo.action.STATUS"
        const val ACTION_USB_PERMISSION = "org.opentrafficmap.citstogo.action.USB_PERMISSION"

        const val EXTRA_DEVICE_NAME = "deviceName"
        const val EXTRA_MQTT_URI = "mqttUri"
        const val EXTRA_NODE_ID = "nodeId"
        const val EXTRA_MQTT_MAX_QUEUE_LENGTH = "mqttMaxQueueLength"
        const val EXTRA_MQTT_MAX_QUEUE_AGE_MS = "mqttMaxQueueAgeMs"
        const val EXTRA_PCAP_URI = "pcapUri"
        const val EXTRA_RUNNING = "running"
        const val EXTRA_USB_STATE = "usbState"
        const val EXTRA_MQTT_STATE = "mqttState"
        const val EXTRA_PACKET_TOPIC = "packetTopic"
        const val EXTRA_PACKETS = "packets"
        const val EXTRA_MQTT_PUBLISHED = "mqttPublished"
        const val EXTRA_MQTT_QUEUED = "mqttQueued"
        const val EXTRA_PCAP_RECORDING = "pcapRecording"
        const val EXTRA_PCAP_PACKETS = "pcapPackets"
        const val EXTRA_DISCOVERED_DEVICES = "discoveredDevices"
        const val EXTRA_TRUNCATED = "truncated"
        const val EXTRA_PROTOCOL_ERRORS = "protocolErrors"
        const val EXTRA_LAST_PACKET = "lastPacket"
        const val EXTRA_LAST_ERROR = "lastError"
        const val EXTRA_LOG = "log"
        const val EXTRA_TX_REQUESTED = "txRequested"
        const val EXTRA_TX_SUCCESSFUL = "txSuccessful"
        const val EXTRA_TX_FAILED = "txFailed"
        const val EXTRA_LAST_TX = "lastTx"
        const val EXTRA_CAM_ENABLED = "camEnabled"
        const val EXTRA_CAM_SENT = "camSent"
        const val EXTRA_CAM_STATION_TYPE = "camStationType"
        const val EXTRA_CAM_INTERVAL_MS = "camIntervalMs"
        const val EXTRA_SREM_STATE = "sremState"
        const val EXTRA_SREM_SUMMARY = "sremSummary"
        const val EXTRA_SREM_REQUEST_ID = "sremRequestId"
        const val EXTRA_SREM_REGION = "sremRegion"
        const val EXTRA_SREM_INTERSECTION_ID = "sremIntersectionId"
        const val EXTRA_SREM_INBOUND_LANE_ID = "sremInboundLaneId"
        const val EXTRA_SREM_OUTBOUND_LANE_ID = "sremOutboundLaneId"
        const val EXTRA_SREM_LATITUDE_E7 = "sremLatitudeE7"
        const val EXTRA_SREM_LONGITUDE_E7 = "sremLongitudeE7"
        const val EXTRA_SREM_POSITION_TIME_MS = "sremPositionTimeMs"
        const val EXTRA_SREM_UPDATED_AT_MS = "sremUpdatedAtMs"
        const val EXTRA_INTERSECTION_SNAPSHOT = "intersectionSnapshot"
        const val EXTRA_INTERSECTION_SNAPSHOTS = "intersectionSnapshots"
        const val SREM_STATE_QUEUED = "queued"
        const val SREM_STATE_TRANSMITTED = "transmitted"
        const val SREM_STATE_ACKNOWLEDGED = "acknowledged"
        const val SREM_STATE_PROCESSING = "processing"
        const val SREM_STATE_WATCH_OTHER_TRAFFIC = "watchOtherTraffic"
        const val SREM_STATE_GRANTED = "granted"
        const val SREM_STATE_REJECTED = "rejected"
        const val SREM_STATE_UNKNOWN_RESPONSE = "unknownResponse"
        const val SREM_STATE_FAILED = "failed"

        const val PREFS = "cits_to_go"
        const val PREF_NODE_ID = "node_id"
        const val PREF_MQTT_URI = "mqtt_uri"
        const val PREF_MQTT_MAX_QUEUE_LENGTH = "mqtt_max_queue_length"
        const val PREF_MQTT_MAX_QUEUE_AGE_MS = "mqtt_max_queue_age_ms"
        const val PREF_DISCOVERED_MAC_ADDRESSES = "discovered_mac_addresses"
        const val PREF_CAM_ENABLED = "cam_enabled"
        const val PREF_CAM_STATION_TYPE = "cam_station_type"
        const val PREF_CAM_INTERVAL_MS = "cam_interval_ms"
        const val PREF_CAM_STATION_ID = "cam_station_id"
        const val PREF_CAM_MAC = "cam_mac"
        const val PREF_TX_APPROVED = "tx_approved"
        const val DEFAULT_MQTT_URI = "mqtts://cits1.opentrafficmap.org"
        const val DEFAULT_MQTT_MAX_QUEUE_LENGTH = 100
        const val DEFAULT_MQTT_MAX_QUEUE_AGE_MS = 200L
        const val DEFAULT_CAM_INTERVAL_MS = 500
        const val MIN_CAM_INTERVAL_MS = 100
        const val MAX_CAM_INTERVAL_MS = 1_000
        const val INTERSECTION_MAX_AGE_MS = 30_000L

        private const val CHANNEL_ID = "cits_bridge"
        private const val STATION_DISCOVERY_CHANNEL_ID = "cits_station_discovery"
        private const val LEGACY_DEVICE_DISCOVERY_CHANNEL_ID = "cits_device_discovery"
        private const val NOTIFICATION_ID = 2301
        private const val STATION_NOTIFICATION_ID_BASE = 2400
        private const val USB_READ_TIMEOUT_MS = 5_000
        private const val USB_WRITE_TIMEOUT_MS = 2_000
        private const val MAX_TX_QUEUE = 64
        private const val MAX_LOCATION_AGE_MS = 5_000L
        private const val PENDING_SREM_MAX_AGE_MS = 60_000L
        private const val CAM_TRIGGER_CHECK_MS = 100L
        private const val MAINTENANCE_INTERVAL_MS = 2_000L
        private const val PACKET_STATUS_INTERVAL_MS = 100L
        private const val PACKET_STATUS_PACKET_INTERVAL = 10L
        private const val MQTT_MAX_BATCH = 100
        private const val ESPRESSIF_VENDOR_ID = 0x303A
    }

    private data class TxEnvelope(val requestId: Long, val packet: ByteArray)
    private data class PendingTx(val packet: ByteArray, val kind: TxKind, val sremRequestId: Int?)
    private data class PendingSremRequest(
        val requestId: Int,
        val sequenceNumber: Int,
        val stationId: Long,
        val region: Int?,
        val intersectionId: Int,
        val inboundLaneId: Int,
        val outboundLaneId: Int,
        val sentAtMs: Long,
    ) {
        fun matches(ssem: SsemStatus, requireLaneMatch: Boolean): Boolean {
            if (ssem.requesterStationId != null && ssem.requesterStationId != stationId) return false
            if (ssem.requestId != null && ssem.requestId != requestId) return false
            if (ssem.requestSequenceNumber != null && ssem.requestSequenceNumber != sequenceNumber) return false
            if (ssem.intersectionKey.id != intersectionId) return false
            if (ssem.intersectionKey.region != null && region != null && ssem.intersectionKey.region != region) return false
            if (requireLaneMatch || ssem.inboundLaneId != null) {
                if (ssem.inboundLaneId != null && ssem.inboundLaneId != inboundLaneId) return false
            }
            if (requireLaneMatch || ssem.outboundLaneId != null) {
                if (ssem.outboundLaneId != null && ssem.outboundLaneId != outboundLaneId) return false
            }
            return true
        }
    }

    private enum class TxKind {
        Cam,
        Srem,
    }
}
