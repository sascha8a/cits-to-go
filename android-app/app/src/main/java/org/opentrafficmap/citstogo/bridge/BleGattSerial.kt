package org.opentrafficmap.citstogo.bridge

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import java.io.IOException
import java.util.UUID
import java.util.logging.Level
import java.util.logging.Logger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.max

@SuppressLint("MissingPermission")
class BleGattSerial(private val context: Context) : CtgByteTransport {
    private val logger = Logger.getLogger("org.opentrafficmap.citstogo.BLE.${ConnectionDiagnostics.nextId()}")
    @Volatile private var setupStage = "initializing"
    private val firstNotification = java.util.concurrent.atomic.AtomicBoolean()
    private var firstWriteCompleted = false
    private val bluetoothManager = context.getSystemService(BluetoothManager::class.java)
    private val adapter = bluetoothManager.adapter ?: throw IOException("Bluetooth is not supported")
    private val incoming = LinkedBlockingQueue<ByteArray>()
    private val writeLock = Any()
    private val connectError = AtomicReference<String?>(null)

    @Volatile private var gatt: BluetoothGatt? = null
    @Volatile private var rxCharacteristic: BluetoothGattCharacteristic? = null
    @Volatile private var txCharacteristic: BluetoothGattCharacteristic? = null
    @Volatile private var connectedDevice: BluetoothDevice? = null
    @Volatile private var mtu = 23
    @Volatile private var txPhy = 0
    @Volatile private var rxPhy = 0
    @Volatile private var highPriorityRequested = false
    @Volatile private var preferred2MPhyRequested = false
    @Volatile private var closed = false
    @Volatile private var pendingWrite: CountDownLatch? = null
    @Volatile private var notificationSetupStarted = false
    private var pendingRead: ByteArray? = null
    private var pendingReadOffset = 0

    override fun description(): String {
        val device = connectedDevice
        return if (device == null) "CITS-to-go BLE" else "CITS-to-go BLE ${device.address} (MTU $mtu)"
    }

    fun debugParameters(): BleDebugParameters = BleDebugParameters(
        deviceAddress = connectedDevice?.address.orEmpty(),
        mtu = mtu,
        txPhy = txPhy,
        rxPhy = rxPhy,
        highPriorityRequested = highPriorityRequested,
        preferred2MPhyRequested = preferred2MPhyRequested,
        queuedNotifications = incoming.size + if (pendingRead != null) 1 else 0,
    )

    fun connect(timeoutMs: Long = 15_000) {
        connectTarget(null, timeoutMs)
    }

    fun connect(device: BluetoothDevice, timeoutMs: Long = 15_000) {
        connectTarget(device, timeoutMs)
    }

    private fun connectTarget(targetDevice: BluetoothDevice?, timeoutMs: Long) {
        val startedAt = System.nanoTime()
        logger.info("BLE prerequisites: ${ConnectionDiagnostics.bluetoothPermissions(context)}")
        logger.info("BLE connection starting; direct=${targetDevice != null} enabled=${adapter.isEnabled} timeoutMs=$timeoutMs")
        if (!adapter.isEnabled) throw IOException("Bluetooth is turned off")
        val scanner = adapter.bluetoothLeScanner ?: throw IOException("Bluetooth LE scanner unavailable")
        logger.fine("BLE scanner available; bondedDevices=${adapter.bondedDevices.size}; targetBond=${targetDevice?.bondState}")
        val found = CountDownLatch(1)
        val ready = CountDownLatch(1)
        val scanError = AtomicReference<String?>(null)
        var reportedUnbondedDevice = false

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                if (connectedDevice != null || closed) return
                if (targetDevice != null && result.device.address != targetDevice.address) return
                // Normal operation only uses an OS-level BLE bond created during USB enrollment.
                if (result.device.bondState != BluetoothDevice.BOND_BONDED) {
                    if (!reportedUnbondedDevice) {
                        logger.warning("BLE service detected but device is not bonded; USB enrollment is required")
                        reportedUnbondedDevice = true
                    }
                    return
                }
                connectedDevice = result.device
                logger.info("Enrolled BLE device discovered; RSSI=${result.rssi} bondState=${result.device.bondState}; connecting GATT")
                runCatching { scanner.stopScan(this) }
                setupStage = "GATT connection"
                gatt = result.device.connectGatt(context, false, gattCallback(ready), BluetoothDevice.TRANSPORT_LE)
                logger.fine("connectGatt returned client=${gatt != null}; autoConnect=false transport=LE")
                found.countDown()
            }

            override fun onScanFailed(errorCode: Int) {
                logger.severe("BLE scan failed: code=$errorCode")
                scanError.set("BLE scan failed: $errorCode")
                found.countDown()
                ready.countDown()
            }
        }

        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE_UUID)).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        if (targetDevice == null) {
            setupStage = "service-filtered scan for bonded device"
            logger.fine("Starting BLE scan with service filter $SERVICE_UUID")
            scanner.startScan(listOf(filter), settings, callback)
        } else {
            setupStage = "direct GATT connection"
            connectedDevice = targetDevice
            gatt = targetDevice.connectGatt(context, false, gattCallback(ready), BluetoothDevice.TRANSPORT_LE)
            logger.fine("Direct connectGatt returned client=${gatt != null}; autoConnect=false transport=LE")
            found.countDown()
        }
        try {
            if (!found.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                throw IOException("No enrolled CITS-to-go Bluetooth device found; enroll this phone over USB first")
            }
            scanError.get()?.let { throw IOException(it) }
            if (!ready.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                throw IOException("Bluetooth connection timed out")
            }
            connectError.get()?.let { throw IOException(it) }
            if (rxCharacteristic == null || txCharacteristic == null || gatt == null) {
                throw IOException("CITS-to-go Bluetooth service unavailable")
            }
            setupStage = "ready"
            logger.info("BLE ready after ${(System.nanoTime() - startedAt) / 1_000_000}ms; MTU=$mtu highPriorityRequested=$highPriorityRequested; awaiting firmware bytes")
        } catch (e: Exception) {
            logger.log(Level.SEVERE, "BLE connection failed at stage=$setupStage after ${(System.nanoTime() - startedAt) / 1_000_000}ms", e)
            close()
            throw e
        } finally {
            runCatching { scanner.stopScan(callback) }
                .onSuccess { logger.fine("BLE scan stopped") }
                .onFailure { logger.log(Level.WARNING, "BLE stopScan failed", it) }
        }
    }

    private fun gattCallback(ready: CountDownLatch) = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            logger.log(if (status != BluetoothGatt.GATT_SUCCESS || (newState == BluetoothProfile.STATE_DISCONNECTED && !closed)) Level.WARNING else Level.INFO,
                "GATT connection status=$status newState=$newState locallyClosed=$closed")
            if (status != BluetoothGatt.GATT_SUCCESS || newState == BluetoothProfile.STATE_DISCONNECTED) {
                if (!closed) connectError.compareAndSet(null, "Bluetooth disconnected (status $status)")
                ready.countDown()
                incoming.offer(DISCONNECTED)
                pendingWrite?.countDown()
                return
            }
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                setupStage = "service discovery"
                highPriorityRequested = gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                logger.fine("GATT connected; highPriorityRequested=$highPriorityRequested; discovering services")
                // ESP32-C5 links can stop responding immediately after switching to LE 2M.
                // Keep the default 1M PHY; the large MTU and connection priority provide
                // the throughput improvement without destabilizing the controller.
                preferred2MPhyRequested = false
                if (!gatt.discoverServices()) {
                    logger.warning("GATT discoverServices rejected")
                    connectError.set("Bluetooth service discovery could not start")
                    ready.countDown()
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            logger.info("GATT service discovery status=$status")
            logger.fine("Discovered services: ${gatt.services.joinToString { it.uuid.toString() }}")
            if (status != BluetoothGatt.GATT_SUCCESS) {
                connectError.set("Bluetooth service discovery failed: $status")
                ready.countDown()
                return
            }
            val service: BluetoothGattService = gatt.getService(SERVICE_UUID) ?: run {
                logger.warning("Expected UART service $SERVICE_UUID missing")
                connectError.set("CITS-to-go Bluetooth service not found")
                ready.countDown()
                return
            }
            rxCharacteristic = service.getCharacteristic(RX_UUID)
            txCharacteristic = service.getCharacteristic(TX_UUID)
            logger.fine("GATT characteristics RX properties=${rxCharacteristic?.properties} TX properties=${txCharacteristic?.properties}")
            if (rxCharacteristic == null || txCharacteristic == null) {
                logger.warning("Required UART characteristics missing; available=${service.characteristics.joinToString { it.uuid.toString() }}")
                connectError.set("CITS-to-go Bluetooth characteristics unavailable")
                ready.countDown()
                return
            }

            /* Do not subscribe at the default 23-byte MTU and start a burst of
             * 20-byte notifications while the MTU request is still in flight.
             * Negotiate first; if the request cannot be started, use the default
             * MTU but still finish the connection normally. */
            logger.fine("Requesting MTU $PREFERRED_MTU")
            setupStage = "MTU negotiation"
            if (!gatt.requestMtu(PREFERRED_MTU)) {
                logger.warning("MTU request did not start; using MTU $mtu")
                startNotifications(gatt, ready)
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            logger.info("GATT descriptor write uuid=${descriptor.uuid} status=$status")
            if (descriptor.uuid != CCCD_UUID) return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                connectError.set("Could not enable Bluetooth notifications: $status")
            } else {
                gatt.readPhy()
            }
            ready.countDown()
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            logger.info("GATT MTU callback mtu=$mtu status=$status")
            if (status == BluetoothGatt.GATT_SUCCESS) this@BleGattSerial.mtu = mtu
            startNotifications(gatt, ready)
        }

        override fun onPhyUpdate(gatt: BluetoothGatt, txPhy: Int, rxPhy: Int, status: Int) {
            logger.fine("GATT PHY update tx=$txPhy rx=$rxPhy status=$status")
            if (status == BluetoothGatt.GATT_SUCCESS) {
                this@BleGattSerial.txPhy = txPhy
                this@BleGattSerial.rxPhy = rxPhy
            }
        }

        override fun onPhyRead(gatt: BluetoothGatt, txPhy: Int, rxPhy: Int, status: Int) {
            logger.fine("GATT PHY read tx=$txPhy rx=$rxPhy status=$status")
            if (status == BluetoothGatt.GATT_SUCCESS) {
                this@BleGattSerial.txPhy = txPhy
                this@BleGattSerial.rxPhy = rxPhy
            }
        }

        @Deprecated("Used on Android 12 and earlier")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (characteristic.uuid == TX_UUID) receiveNotification(characteristic.value.clone())
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            if (characteristic.uuid == TX_UUID) receiveNotification(value.clone())
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (characteristic.uuid == RX_UUID && status != BluetoothGatt.GATT_SUCCESS) {
                logger.warning("GATT characteristic write failed: status=$status")
                connectError.set("Bluetooth write failed: $status")
            }
            pendingWrite?.countDown()
        }
    }

    private fun receiveNotification(value: ByteArray) {
        if (firstNotification.compareAndSet(false, true)) {
            logger.info("First BLE notification received: bytes=${value.size} stage=$setupStage MTU=$mtu; awaiting valid CTG frame")
        }
        incoming.offer(value)
    }

    @Synchronized
    private fun startNotifications(gatt: BluetoothGatt, ready: CountDownLatch) {
        if (notificationSetupStarted) return
        notificationSetupStarted = true
        setupStage = "notification subscription"
        logger.fine("Enabling BLE notifications; MTU=$mtu")
        val tx = txCharacteristic
        if (tx == null || !gatt.setCharacteristicNotification(tx, true)) {
            logger.warning("Local notification enable failed; TX present=${tx != null}")
            connectError.set("CITS-to-go Bluetooth TX characteristic unavailable")
            ready.countDown()
            return
        }
        val cccd = tx.getDescriptor(CCCD_UUID)
        if (cccd == null) {
            logger.warning("CCCD $CCCD_UUID missing on TX characteristic")
            connectError.set("Bluetooth notification descriptor missing")
            ready.countDown()
            return
        }
        val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothGatt.GATT_SUCCESS
        } else {
            @Suppress("DEPRECATION")
            run {
                cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                gatt.writeDescriptor(cccd)
            }
        }
        if (!started) {
            logger.warning("CCCD write could not start")
            connectError.set("Could not enable Bluetooth notifications")
            ready.countDown()
        }
        logger.fine("Notification CCCD write submitted=$started; awaiting descriptor callback")
    }

    override fun read(buffer: ByteArray, timeoutMs: Int): Int {
        if (closed) throw IOException("Bluetooth transport is closed")
        if (buffer.isEmpty()) return 0

        var total = 0
        while (total < buffer.size) {
            var chunk = pendingRead
            if (chunk == null) {
                chunk = if (total == 0) {
                    incoming.poll(timeoutMs.toLong(), TimeUnit.MILLISECONDS) ?: return 0
                } else {
                    incoming.poll() ?: break
                }
                pendingRead = chunk
                pendingReadOffset = 0
            }

            if (chunk === DISCONNECTED || chunk.isEmpty()) {
                if (total > 0) return total
                pendingRead = null
                pendingReadOffset = 0
                throw IOException(connectError.get() ?: "Bluetooth disconnected")
            }

            val count = minOf(buffer.size - total, chunk.size - pendingReadOffset)
            System.arraycopy(chunk, pendingReadOffset, buffer, total, count)
            total += count
            pendingReadOffset += count
            if (pendingReadOffset >= chunk.size) {
                pendingRead = null
                pendingReadOffset = 0
            }
        }
        return total
    }

    override fun writeAll(buffer: ByteArray, timeoutMs: Int) {
        synchronized(writeLock) {
            val gatt = gatt ?: throw IOException("Bluetooth is not connected")
            val characteristic = rxCharacteristic ?: throw IOException("Bluetooth RX characteristic unavailable")
            var offset = 0
            while (offset < buffer.size) {
                connectError.get()?.let { throw IOException(it) }
                val chunkLength = minOf(buffer.size - offset, 512, max(20, mtu - 3))
                val chunk = buffer.copyOfRange(offset, offset + chunkLength)
                val latch = CountDownLatch(1)
                pendingWrite = latch
                val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    gatt.writeCharacteristic(
                        characteristic,
                        chunk,
                        BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
                    ) == BluetoothGatt.GATT_SUCCESS
                } else {
                    @Suppress("DEPRECATION")
                    run {
                        characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                        characteristic.value = chunk
                        gatt.writeCharacteristic(characteristic)
                    }
                }
                if (!started) {
                    logger.warning("BLE write rejected; offset=$offset bytes=${buffer.size} MTU=$mtu")
                    pendingWrite = null
                    throw IOException("Bluetooth write could not start")
                }
                if (!latch.await(timeoutMs.toLong(), TimeUnit.MILLISECONDS)) {
                    logger.warning("BLE write timeout; offset=$offset bytes=${buffer.size} timeoutMs=$timeoutMs MTU=$mtu")
                    pendingWrite = null
                    throw IOException("Bluetooth write timed out at $offset/${buffer.size}")
                }
                pendingWrite = null
                connectError.get()?.let { throw IOException(it) }
                offset += chunkLength
            }
            if (!firstWriteCompleted && buffer.isNotEmpty()) {
                firstWriteCompleted = true
                logger.info("First acknowledged BLE write completed: bytes=${buffer.size} MTU=$mtu")
            }
        }
    }

    override fun close() {
        logger.info("Closing BLE transport; stage=$setupStage receivedNotification=${firstNotification.get()} queuedNotifications=${incoming.size}")
        closed = true
        pendingWrite?.countDown()
        incoming.offer(DISCONNECTED)
        val current = gatt
        gatt = null
        runCatching { current?.disconnect() }
        runCatching { current?.close() }
        rxCharacteristic = null
        txCharacteristic = null
    }

    companion object {
        val SERVICE_UUID: UUID = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e")
        val RX_UUID: UUID = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e")
        val TX_UUID: UUID = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e")
        private val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private const val PREFERRED_MTU = 517
        private val DISCONNECTED = ByteArray(0)
    }
}
