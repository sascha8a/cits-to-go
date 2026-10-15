package org.opentrafficmap.citstogo.bridge

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.location.LocationManager
import android.os.Build
import java.util.concurrent.atomic.AtomicLong

internal object ConnectionDiagnostics {
    private val sequence = AtomicLong()
    fun nextId(): Long = sequence.incrementAndGet()

    fun usb(device: UsbDevice, manager: UsbManager): String =
        "device=${device.deviceName} id=${device.deviceId} " +
            "vid=%04x pid=%04x".format(device.vendorId, device.productId) +
            " class=${device.deviceClass} subclass=${device.deviceSubclass}" +
            " interfaces=${device.interfaceCount} permission=${manager.hasPermission(device)}"

    fun bluetoothPermissions(context: Context): String {
        val permissions = if (Build.VERSION.SDK_INT >= 31) {
            listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            listOf(Manifest.permission.BLUETOOTH, Manifest.permission.BLUETOOTH_ADMIN, Manifest.permission.ACCESS_FINE_LOCATION)
        }
        return permissions.joinToString { permission ->
            "${permission.substringAfterLast('.')}=${context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED}"
        } + "; locationEnabled=" + runCatching {
            val manager = context.getSystemService(LocationManager::class.java)
            if (Build.VERSION.SDK_INT >= 28) manager.isLocationEnabled
            else manager.isProviderEnabled(LocationManager.GPS_PROVIDER) || manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        }.getOrNull()
    }
}
