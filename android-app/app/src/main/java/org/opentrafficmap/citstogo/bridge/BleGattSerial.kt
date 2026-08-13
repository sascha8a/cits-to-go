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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.max

@SuppressLint("MissingPermission")
class BleGattSerial(private val context: Context) : CtgByteTransport {
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
    @Volatile private var closed = false
    @Volatile private var pendingWrite: CountDownLatch? = null
    private var pendingRead: ByteArray? = null
    private var pendingReadOffset = 0

    override fun description(): String {
        val device = connectedDevice
        return if (device == null) "CITS-to-go BLE" else "CITS-to-go BLE ${device.address}"
    }

    fun connect(timeoutMs: Long = 15_000) {
        if (!adapter.isEnabled) throw IOException("Bluetooth is turned off")
        val scanner = adapter.bluetoothLeScanner ?: throw IOException("Bluetooth LE scanner unavailable")
        val found = CountDownLatch(1)
        val ready = CountDownLatch(1)
        val scanError = AtomicReference<String?>(null)

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                if (connectedDevice != null || closed) return
                // Normal operation only uses an OS-level BLE bond created during USB enrollment.
                if (result.device.bondState != BluetoothDevice.BOND_BONDED) return
                connectedDevice = result.device
                runCatching { scanner.stopScan(this) }
                gatt = result.device.connectGatt(context, false, gattCallback(ready), BluetoothDevice.TRANSPORT_LE)
                found.countDown()
            }

            override fun onScanFailed(errorCode: Int) {
                scanError.set("BLE scan failed: $errorCode")
                found.countDown()
                ready.countDown()
            }
        }

        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE_UUID)).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanner.startScan(listOf(filter), settings, callback)
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
            gatt?.requestMtu(517)
        } catch (e: Exception) {
            close()
            throw e
        } finally {
            runCatching { scanner.stopScan(callback) }
        }
    }

    private fun gattCallback(ready: CountDownLatch) = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS || newState == BluetoothProfile.STATE_DISCONNECTED) {
                if (!closed) connectError.compareAndSet(null, "Bluetooth disconnected (status $status)")
                ready.countDown()
                incoming.offer(DISCONNECTED)
                pendingWrite?.countDown()
                return
            }
            if (newState == BluetoothProfile.STATE_CONNECTED && !gatt.discoverServices()) {
                connectError.set("Bluetooth service discovery could not start")
                ready.countDown()
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                connectError.set("Bluetooth service discovery failed: $status")
                ready.countDown()
                return
            }
            val service: BluetoothGattService = gatt.getService(SERVICE_UUID) ?: run {
                connectError.set("CITS-to-go Bluetooth service not found")
                ready.countDown()
                return
            }
            rxCharacteristic = service.getCharacteristic(RX_UUID)
            txCharacteristic = service.getCharacteristic(TX_UUID)
            val tx = txCharacteristic
            if (rxCharacteristic == null || tx == null || !gatt.setCharacteristicNotification(tx, true)) {
                connectError.set("CITS-to-go Bluetooth characteristics unavailable")
                ready.countDown()
                return
            }
            val cccd = tx.getDescriptor(CCCD_UUID)
            if (cccd == null) {
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
                connectError.set("Could not enable Bluetooth notifications")
                ready.countDown()
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (descriptor.uuid != CCCD_UUID) return
            if (status != BluetoothGatt.GATT_SUCCESS) connectError.set("Could not enable Bluetooth notifications: $status")
            ready.countDown()
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) this@BleGattSerial.mtu = mtu
        }

        @Deprecated("Used on Android 12 and earlier")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (characteristic.uuid == TX_UUID) incoming.offer(characteristic.value.clone())
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            if (characteristic.uuid == TX_UUID) incoming.offer(value.clone())
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (characteristic.uuid == RX_UUID && status != BluetoothGatt.GATT_SUCCESS) {
                connectError.set("Bluetooth write failed: $status")
            }
            pendingWrite?.countDown()
        }
    }

    override fun read(buffer: ByteArray, timeoutMs: Int): Int {
        if (closed) throw IOException("Bluetooth transport is closed")
        var chunk = pendingRead
        if (chunk == null) {
            chunk = incoming.poll(timeoutMs.toLong(), TimeUnit.MILLISECONDS) ?: return 0
            if (chunk === DISCONNECTED || chunk.isEmpty()) throw IOException(connectError.get() ?: "Bluetooth disconnected")
            pendingRead = chunk
            pendingReadOffset = 0
        }
        val count = minOf(buffer.size, chunk.size - pendingReadOffset)
        System.arraycopy(chunk, pendingReadOffset, buffer, 0, count)
        pendingReadOffset += count
        if (pendingReadOffset >= chunk.size) {
            pendingRead = null
            pendingReadOffset = 0
        }
        return count
    }

    override fun writeAll(buffer: ByteArray, timeoutMs: Int) {
        synchronized(writeLock) {
            val gatt = gatt ?: throw IOException("Bluetooth is not connected")
            val characteristic = rxCharacteristic ?: throw IOException("Bluetooth RX characteristic unavailable")
            var offset = 0
            while (offset < buffer.size) {
                connectError.get()?.let { throw IOException(it) }
                val chunkLength = minOf(buffer.size - offset, max(20, mtu - 3))
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
                    pendingWrite = null
                    throw IOException("Bluetooth write could not start")
                }
                if (!latch.await(timeoutMs.toLong(), TimeUnit.MILLISECONDS)) {
                    pendingWrite = null
                    throw IOException("Bluetooth write timed out at $offset/${buffer.size}")
                }
                pendingWrite = null
                connectError.get()?.let { throw IOException(it) }
                offset += chunkLength
            }
        }
    }

    override fun close() {
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
        private val DISCONNECTED = ByteArray(0)
    }
}
