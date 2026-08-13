package org.opentrafficmap.citstogo.bridge

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.ParcelUuid
import org.opentrafficmap.citstogo.protocol.CtgFrameDecoder
import org.opentrafficmap.citstogo.protocol.CtgFrameEncoder
import org.opentrafficmap.citstogo.protocol.CtgInboundFrame
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** USB-gated enrollment only. Normal BLE authentication remains entirely SMP/bond based. */
@SuppressLint("MissingPermission")
class BluetoothEnrollment(private val context: Context) {
    private val usbManager = context.getSystemService(UsbManager::class.java)
    private val bluetoothManager = context.getSystemService(BluetoothManager::class.java)
    private val adapter = bluetoothManager.adapter ?: throw IOException("Bluetooth is not supported")

    fun enroll(usbDevice: UsbDevice, timeoutMs: Long = 30_000): String {
        if (!adapter.isEnabled) throw IOException("Bluetooth is turned off")
        armOverUsb(usbDevice)

        val scanner = adapter.bluetoothLeScanner ?: throw IOException("Bluetooth LE scanner unavailable")
        val done = CountDownLatch(1)
        val error = AtomicReference<String?>(null)
        val bonded = AtomicReference<BluetoothDevice?>(null)
        val target = AtomicReference<BluetoothDevice?>(null)

        val bondReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val device = if (android.os.Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                } ?: return
                val expectedDevice = target.get() ?: return
                if (device.address != expectedDevice.address) return
                if (intent.action != BluetoothDevice.ACTION_BOND_STATE_CHANGED) return
                when (intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)) {
                    BluetoothDevice.BOND_BONDED -> {
                        bonded.set(device)
                        done.countDown()
                    }
                    BluetoothDevice.BOND_NONE -> {
                        val previous = intent.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, BluetoothDevice.ERROR)
                        if (previous == BluetoothDevice.BOND_BONDING) {
                            error.set("Bluetooth pairing was rejected or failed")
                            done.countDown()
                        }
                    }
                }
            }
        }
        context.registerReceiver(bondReceiver, IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED))

        val scanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                if (!target.compareAndSet(null, result.device)) return
                runCatching { scanner.stopScan(this) }
                if (result.device.bondState == BluetoothDevice.BOND_BONDED) {
                    bonded.set(result.device)
                    done.countDown()
                } else if (!result.device.createBond()) {
                    error.set("Bluetooth pairing could not start")
                    done.countDown()
                }
            }

            override fun onScanFailed(errorCode: Int) {
                error.set("BLE scan failed: $errorCode")
                done.countDown()
            }
        }

        try {
            val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(BleGattSerial.SERVICE_UUID)).build()
            val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
            scanner.startScan(listOf(filter), settings, scanCallback)
            if (!done.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                throw IOException("Bluetooth enrollment timed out")
            }
            error.get()?.let { throw IOException(it) }
            val device = bonded.get() ?: throw IOException("Bluetooth bond was not created")
            // A bond broadcast alone does not prove that this board accepted and
            // persisted the bond. Verify the exact scan target over secured GATT.
            BleGattSerial(context).use {
                it.connect(device, 10_000)
                // RX requires encryption. An empty CTG record is ignored by the
                // parser but forces Android to prove possession of the bond key.
                it.writeAll(byteArrayOf(0), 10_000)
            }
            return "Bluetooth enrolled: ${device.address}"
        } finally {
            runCatching { scanner.stopScan(scanCallback) }
            runCatching { context.unregisterReceiver(bondReceiver) }
        }
    }

    private fun armOverUsb(device: UsbDevice) {
        UsbCdcSerial(usbManager, device).use { serial ->
            serial.open()
            serial.writeAll(CtgFrameEncoder.bluetoothEnrollmentRequest(), 2_000)
            val decoder = CtgFrameDecoder()
            val encoded = ByteArray(128)
            var encodedLength = 0
            val input = ByteArray(128)
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            while (System.nanoTime() < deadline) {
                val count = serial.read(input, 250)
                for (i in 0 until count) {
                    if (input[i].toInt() == 0) {
                        if (encodedLength > 0) {
                            val frame = runCatching { decoder.decode(encoded, encodedLength) }.getOrNull()
                            if (frame is CtgInboundFrame.BluetoothEnrollmentResult) {
                                if (!frame.successful) throw IOException("Firmware refused Bluetooth enrollment (${frame.status})")
                                return
                            }
                        }
                        encodedLength = 0
                    } else if (encodedLength < encoded.size) {
                        encoded[encodedLength++] = input[i]
                    } else {
                        encodedLength = 0
                    }
                }
            }
            throw IOException("Firmware did not acknowledge Bluetooth enrollment")
        }
    }
}
