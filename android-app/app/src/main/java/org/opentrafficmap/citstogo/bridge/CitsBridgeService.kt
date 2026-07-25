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
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.net.Uri
import org.opentrafficmap.citstogo.BuildConfig
import org.opentrafficmap.citstogo.MainActivity
import org.opentrafficmap.citstogo.protocol.CitsPacket
import org.opentrafficmap.citstogo.protocol.Ieee80211Mac
import org.opentrafficmap.citstogo.protocol.SerialPacketReader
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min

class CitsBridgeService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val pcapLock = Any()
    private lateinit var usbManager: UsbManager
    private lateinit var spool: MqttSpool

    private var serial: UsbCdcSerial? = null
    private var serialThread: Thread? = null
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

    private var status = BridgeStatus()
    private var packets = 0L
    private var mqttPublished = 0L
    private var pcapPackets = 0L
    private var discoveredDevices = 0L
    private var truncated = 0L
    private var protocolErrors = 0L
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
        spool = MqttSpool(this)
        nodeId = loadOrCreateNodeId()
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
            else -> publishStatus(null)
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
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
            val running = AtomicBoolean(true)
            val reader = SerialPacketReader(::handlePacket, ::handleProtocolError)
            serialThread = Thread({
                val buffer = ByteArray(16 * 1024)
                while (usbWanted && running.get()) {
                    try {
                        val count = opened.read(buffer, USB_READ_TIMEOUT_MS)
                        if (count > 0) reader.accept(buffer, count)
                    } catch (e: Exception) {
                        running.set(false)
                        handleSerialError(e)
                    }
                }
            }, "ctg-serial").apply { start() }
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
        runCatching { old?.close() }
        serialThread = null
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
        }
        val summary = "#${packet.sequence} ${packet.payload.size}B ${packet.frequencyMhz}MHz ${packet.rssiDbm}dBm"
        if (mqttEnabled) {
            runCatching {
                spool.append(packet.payload)
                updateQueueDrainSchedule()
            }.onFailure {
                status = status.copy(lastError = "MQTT spool failed: ${it.message}")
            }
        }
        writePcap(packet)
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
        sendDiscoveryNotification(device, message)
        publishStatus(message)
    }

    private fun UsbDevice.isCitsDevice(): Boolean = vendorId == ESPRESSIF_VENDOR_ID

    private fun UsbDevice.discoveryLabel(): String {
        val product = listOfNotNull(manufacturerName, productName).joinToString(" ").ifBlank { deviceName }
        return "$product (vid=%04x pid=%04x)".format(vendorId, productId)
    }

    private fun publishStatus(log: String?) {
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
        intent.putExtra(EXTRA_LAST_PACKET, status.lastPacketSummary)
        intent.putExtra(EXTRA_LAST_ERROR, status.lastError)
        if (!log.isNullOrBlank()) intent.putExtra(EXTRA_LOG, log)
        sendBroadcast(intent)
    }

    private fun updateNotification() {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(status.summary()))
    }

    private fun sendDiscoveryNotification(device: UsbDevice, message: String) {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(
            DISCOVERY_NOTIFICATION_ID_BASE + device.deviceName.hashCode().mod(1_000),
            buildDiscoveryNotification(message),
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

    private fun buildDiscoveryNotification(content: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val openPi = PendingIntent.getActivity(
            this,
            2,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, DISCOVERY_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("C-ITS device discovered")
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
        val discoveryChannel = NotificationChannel(
            DISCOVERY_CHANNEL_ID,
            "C-ITS device discovery",
            NotificationManager.IMPORTANCE_DEFAULT,
        )
        discoveryChannel.description = "Alerts when a C-ITS USB device is discovered."
        manager.createNotificationChannel(discoveryChannel)
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

        const val PREFS = "cits_to_go"
        const val PREF_NODE_ID = "node_id"
        const val PREF_MQTT_URI = "mqtt_uri"
        const val PREF_MQTT_MAX_QUEUE_LENGTH = "mqtt_max_queue_length"
        const val PREF_MQTT_MAX_QUEUE_AGE_MS = "mqtt_max_queue_age_ms"
        const val PREF_DISCOVERED_MAC_ADDRESSES = "discovered_mac_addresses"
        const val DEFAULT_MQTT_URI = "mqtts://cits1.opentrafficmap.org"
        const val DEFAULT_MQTT_MAX_QUEUE_LENGTH = 100
        const val DEFAULT_MQTT_MAX_QUEUE_AGE_MS = 200L

        private const val CHANNEL_ID = "cits_bridge"
        private const val DISCOVERY_CHANNEL_ID = "cits_device_discovery"
        private const val NOTIFICATION_ID = 2301
        private const val DISCOVERY_NOTIFICATION_ID_BASE = 2400
        private const val USB_READ_TIMEOUT_MS = 5_000
        private const val MAINTENANCE_INTERVAL_MS = 2_000L
        private const val PACKET_STATUS_INTERVAL_MS = 100L
        private const val PACKET_STATUS_PACKET_INTERVAL = 10L
        private const val MQTT_MAX_BATCH = 100
        private const val ESPRESSIF_VENDOR_ID = 0x303A
    }
}
