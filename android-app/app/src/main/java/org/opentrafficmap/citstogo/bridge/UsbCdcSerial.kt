package org.opentrafficmap.citstogo.bridge

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import java.io.Closeable
import java.io.IOException
import java.util.logging.Logger

class UsbCdcSerial(
    private val usbManager: UsbManager,
    val device: UsbDevice,
) : CtgByteTransport {
    private val logger = Logger.getLogger("org.opentrafficmap.citstogo.USB.${ConnectionDiagnostics.nextId()}")
    private var receivedFirstBytes = false
    private var reportedEmptyRead = false
    private var wroteFirstBytes = false
    @Volatile private var connection: UsbDeviceConnection? = null
    @Volatile private var inEndpoint: UsbEndpoint? = null
    @Volatile private var outEndpoint: UsbEndpoint? = null
    private var controlInterfaceId: Int? = null
    private val claimedInterfaces = mutableListOf<UsbInterface>()

    override fun description(): String = "${device.deviceName} vid=%04x pid=%04x".format(device.vendorId, device.productId)

    fun open(baudRate: Int = 921_600) {
        logger.info("Opening ${description()}; baud=$baudRate interfaces=${device.interfaceCount} permission=${usbManager.hasPermission(device)}")
        val conn = usbManager.openDevice(device) ?: run {
            logger.severe("UsbManager.openDevice returned null; permission=${usbManager.hasPermission(device)} attached=${usbManager.deviceList.containsKey(device.deviceName)}")
            throw IOException("Unable to open USB device")
        }
        logger.fine("UsbManager.openDevice succeeded")
        connection = conn
        var controlInterface: UsbInterface? = null

        for (i in 0 until device.interfaceCount) {
            val intf = device.getInterface(i)
            val claimed = conn.claimInterface(intf, true)
            logger.fine("Interface id=${intf.id} class=${intf.interfaceClass} subclass=${intf.interfaceSubclass} endpoints=${intf.endpointCount} claimed=$claimed")
            if (!claimed) {
                logger.warning("Could not claim USB interface ${intf.id}")
                continue
            }
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
                logger.fine("Endpoint address=${endpoint.address} type=${endpoint.type} direction=${endpoint.direction} maxPacket=${endpoint.maxPacketSize}")
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
            logger.severe("USB serial requires bulk IN and OUT endpoints")
            close()
            throw IOException("USB serial requires bulk IN and OUT endpoints")
        }
        controlInterface?.let {
            controlInterfaceId = it.id
            configureCdcAcm(conn, it.id, baudRate)
        }
        logger.info("USB ready; IN=${inEndpoint?.address} OUT=${outEndpoint?.address} control=$controlInterfaceId")
    }

    override fun read(buffer: ByteArray, timeoutMs: Int): Int {
        val conn = connection ?: throw IOException("USB serial is not open")
        val endpoint = inEndpoint ?: throw IOException("USB serial has no input endpoint")
        val result = conn.bulkTransfer(endpoint, buffer, buffer.size, timeoutMs)
        if (result > 0 && !receivedFirstBytes) {
            receivedFirstBytes = true
            logger.info("First USB bytes received: count=$result; awaiting valid CTG frame")
        } else if (result <= 0 && !reportedEmptyRead) {
            reportedEmptyRead = true
            logger.fine("First empty USB read: result=$result timeoutMs=$timeoutMs (Android does not distinguish timeout from transfer failure)")
        }
        return result.coerceAtLeast(0)
    }

    @Synchronized
    override fun writeAll(buffer: ByteArray, timeoutMs: Int) {
        val conn = connection ?: throw IOException("USB serial is not open")
        val endpoint = outEndpoint ?: throw IOException("USB serial has no output endpoint")
        var offset = 0
        while (offset < buffer.size) {
            val length = minOf(buffer.size - offset, endpoint.maxPacketSize.coerceAtLeast(1) * 16)
            val written = conn.bulkTransfer(endpoint, buffer, offset, length, timeoutMs)
            if (written <= 0) {
                logger.warning("USB write failed: result=$written offset=$offset bytes=${buffer.size} timeoutMs=$timeoutMs")
                throw IOException("USB serial write timed out at $offset/${buffer.size}")
            }
            offset += written
        }
        if (!wroteFirstBytes && buffer.isNotEmpty()) {
            wroteFirstBytes = true
            logger.info("First USB write completed: bytes=${buffer.size}")
        }
    }

    @Synchronized
    fun setControlLines(dtr: Boolean, rts: Boolean) {
        val conn = connection ?: throw IOException("USB serial is not open")
        val interfaceId = controlInterfaceId ?: throw IOException("USB serial has no control interface")
        val value = (if (dtr) 1 else 0) or (if (rts) 2 else 0)
        val result = conn.controlTransfer(0x21, 0x22, value, interfaceId, null, 0, 1_000)
        logger.fine("USB control lines DTR=$dtr RTS=$rts result=$result")
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
        val codingResult = conn.controlTransfer(0x21, 0x20, 0, interfaceId, lineCoding, lineCoding.size, 1000)
        val linesResult = conn.controlTransfer(0x21, 0x22, 3, interfaceId, null, 0, 1000)
        logger.fine("CDC setup interface=$interfaceId baud=$baudRate lineCodingResult=$codingResult controlLinesResult=$linesResult")
        if (codingResult != lineCoding.size || linesResult < 0) {
            logger.warning("CDC setup incomplete: lineCodingResult=$codingResult controlLinesResult=$linesResult")
        }
    }

    override fun close() {
        val conn = connection
        if (conn != null) {
            logger.info("Closing USB transport; receivedBytes=$receivedFirstBytes wroteBytes=$wroteFirstBytes")
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
