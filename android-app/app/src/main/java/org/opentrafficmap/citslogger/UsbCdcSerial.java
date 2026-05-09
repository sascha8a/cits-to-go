package org.opentrafficmap.citslogger;

import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;

import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

final class UsbCdcSerial implements Closeable {
    private final UsbManager usbManager;
    private final UsbDevice device;
    private UsbDeviceConnection connection;
    private UsbEndpoint inEndpoint;
    private final List<UsbInterface> claimed = new ArrayList<>();

    UsbCdcSerial(UsbManager usbManager, UsbDevice device) {
        this.usbManager = usbManager;
        this.device = device;
    }

    String describe() {
        return String.format("%s vid=%04x pid=%04x", device.getDeviceName(), device.getVendorId(), device.getProductId());
    }

    void open(int baudRate) throws IOException {
        connection = usbManager.openDevice(device);
        if (connection == null) throw new IOException("Unable to open USB device");

        UsbInterface controlInterface = null;
        for (int i = 0; i < device.getInterfaceCount(); i++) {
            UsbInterface intf = device.getInterface(i);
            if (!connection.claimInterface(intf, true)) continue;
            claimed.add(intf);
            if (controlInterface == null &&
                    (intf.getInterfaceClass() == UsbConstants.USB_CLASS_COMM ||
                            intf.getInterfaceClass() == UsbConstants.USB_CLASS_CDC_DATA ||
                            intf.getInterfaceClass() == UsbConstants.USB_CLASS_VENDOR_SPEC)) {
                controlInterface = intf;
            }
            for (int e = 0; e < intf.getEndpointCount(); e++) {
                UsbEndpoint ep = intf.getEndpoint(e);
                if (ep.getType() == UsbConstants.USB_ENDPOINT_XFER_BULK &&
                        ep.getDirection() == UsbConstants.USB_DIR_IN && inEndpoint == null) {
                    inEndpoint = ep;
                }
            }
        }

        if (inEndpoint == null) {
            close();
            throw new IOException("No USB bulk IN endpoint found. Is the ESP32-C5 console using USB Serial/JTAG or CDC?");
        }

        if (controlInterface != null) configureCdcAcm(controlInterface.getId(), baudRate);
    }

    private void configureCdcAcm(int interfaceId, int baudRate) {
        byte[] lineCoding = new byte[] {
                (byte) (baudRate & 0xff),
                (byte) ((baudRate >>> 8) & 0xff),
                (byte) ((baudRate >>> 16) & 0xff),
                (byte) ((baudRate >>> 24) & 0xff),
                0,
                0,
                8
        };
        connection.controlTransfer(0x21, 0x20, 0, interfaceId, lineCoding, lineCoding.length, 1000);
        connection.controlTransfer(0x21, 0x22, 3, interfaceId, null, 0, 1000);
    }

    int read(byte[] buffer, int timeoutMs) throws IOException {
        if (connection == null || inEndpoint == null) throw new IOException("USB serial is not open");
        int n = connection.bulkTransfer(inEndpoint, buffer, buffer.length, timeoutMs);
        return Math.max(n, 0);
    }

    @Override
    public void close() {
        if (connection != null) {
            for (UsbInterface intf : claimed) {
                try { connection.releaseInterface(intf); } catch (Exception ignored) {}
            }
            claimed.clear();
            try { connection.close(); } catch (Exception ignored) {}
        }
        connection = null;
        inEndpoint = null;
    }
}
