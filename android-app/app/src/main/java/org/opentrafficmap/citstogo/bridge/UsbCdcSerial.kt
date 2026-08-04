package org.opentrafficmap.citstogo.bridge

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import java.io.Closeable
import java.io.IOException

class UsbCdcSerial(
    private val usbManager: UsbManager,
    val device: UsbDevice,
) : Closeable {
    private var connection: UsbDeviceConnection? = null
    private var inEndpoint: UsbEndpoint? = null
    private var outEndpoint: UsbEndpoint? = null
    private var controlInterfaceId: Int? = null
    private val claimedInterfaces = mutableListOf<UsbInterface>()

    fun description(): String = "${device.deviceName} vid=%04x pid=%04x".format(device.vendorId, device.productId)

    fun open(baudRate: Int = 921_600) {
        val conn = usbManager.openDevice(device) ?: throw IOException("Unable to open USB device")
        connection = conn
        var controlInterface: UsbInterface? = null

        for (i in 0 until device.interfaceCount) {
            val intf = device.getInterface(i)
            if (!conn.claimInterface(intf, true)) continue
            claimedInterfaces += intf
            if (controlInterface == null &&
                (intf.interfaceClass == UsbConstants.USB_CLASS_COMM ||
                    intf.interfaceClass == UsbConstants.USB_CLASS_CDC_DATA ||
                    intf.interfaceClass == UsbConstants.USB_CLASS_VENDOR_SPEC)
            ) {
                controlInterface = intf
            }
            for (e in 0 until intf.endpointCount) {
                val endpoint = intf.getEndpoint(e)
                if (endpoint.type == UsbConstants.USB_ENDPOINT_XFER_BULK &&
                    endpoint.direction == UsbConstants.USB_DIR_IN &&
                    inEndpoint == null
                ) {
                    inEndpoint = endpoint
                } else if (endpoint.type == UsbConstants.USB_ENDPOINT_XFER_BULK &&
                    endpoint.direction == UsbConstants.USB_DIR_OUT &&
                    outEndpoint == null
                ) {
                    outEndpoint = endpoint
                }
            }
        }

        if (inEndpoint == null || outEndpoint == null) {
            close()
            throw IOException("USB serial requires bulk IN and OUT endpoints")
        }
        controlInterface?.let {
            controlInterfaceId = it.id
            configureCdcAcm(conn, it.id, baudRate)
        }
    }

    fun read(buffer: ByteArray, timeoutMs: Int): Int {
        val conn = connection ?: throw IOException("USB serial is not open")
        val endpoint = inEndpoint ?: throw IOException("USB serial has no input endpoint")
        return conn.bulkTransfer(endpoint, buffer, buffer.size, timeoutMs).coerceAtLeast(0)
    }

    @Synchronized
    fun writeAll(buffer: ByteArray, timeoutMs: Int) {
        val conn = connection ?: throw IOException("USB serial is not open")
        val endpoint = outEndpoint ?: throw IOException("USB serial has no output endpoint")
        var offset = 0
        while (offset < buffer.size) {
            val length = minOf(buffer.size - offset, endpoint.maxPacketSize.coerceAtLeast(1) * 16)
            val written = conn.bulkTransfer(endpoint, buffer, offset, length, timeoutMs)
            if (written <= 0) throw IOException("USB serial write timed out at $offset/${buffer.size}")
            offset += written
        }
    }

    @Synchronized
    fun setControlLines(dtr: Boolean, rts: Boolean) {
        val conn = connection ?: throw IOException("USB serial is not open")
        val interfaceId = controlInterfaceId ?: throw IOException("USB serial has no control interface")
        val value = (if (dtr) 1 else 0) or (if (rts) 2 else 0)
        val result = conn.controlTransfer(0x21, 0x22, value, interfaceId, null, 0, 1_000)
        if (result < 0) throw IOException("Unable to set USB serial control lines")
    }

    private fun configureCdcAcm(conn: UsbDeviceConnection, interfaceId: Int, baudRate: Int) {
        val lineCoding = byteArrayOf(
            (baudRate and 0xff).toByte(),
            ((baudRate ushr 8) and 0xff).toByte(),
            ((baudRate ushr 16) and 0xff).toByte(),
            ((baudRate ushr 24) and 0xff).toByte(),
            0,
            0,
            8,
        )
        conn.controlTransfer(0x21, 0x20, 0, interfaceId, lineCoding, lineCoding.size, 1000)
        conn.controlTransfer(0x21, 0x22, 3, interfaceId, null, 0, 1000)
    }

    override fun close() {
        val conn = connection
        if (conn != null) {
            claimedInterfaces.forEach { runCatching { conn.releaseInterface(it) } }
            runCatching { conn.close() }
        }
        claimedInterfaces.clear()
        connection = null
        inEndpoint = null
        outEndpoint = null
        controlInterfaceId = null
    }
}
