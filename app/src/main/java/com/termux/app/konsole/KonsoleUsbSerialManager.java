package com.termux.app.konsole;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.os.Build;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;

/**
 * USB-OTG Hardware & UART/Serial Bridge via Android UsbManager.
 * Enumerates attached USB peripherals (FTDI, CH340, CP210x, CDC-ACM, ADB-OTG targets),
 * requests USB host permission, and performs raw CDC/bulk-transfer serial I/O without root.
 */
public final class KonsoleUsbSerialManager {

    public static final String ACTION_USB_PERMISSION = "com.termux.konsole.USB_PERMISSION";

    public static final class UsbDeviceSummary {
        public final UsbDevice device;
        public final int vendorId;
        public final int productId;
        public final String chipsetHint;
        public final boolean hasPermission;

        public UsbDeviceSummary(UsbDevice device, int vendorId, int productId, String chipsetHint, boolean hasPermission) {
            this.device = device;
            this.vendorId = vendorId;
            this.productId = productId;
            this.chipsetHint = chipsetHint;
            this.hasPermission = hasPermission;
        }
    }

    private KonsoleUsbSerialManager() {
    }

    public static List<UsbDeviceSummary> listAttachedDevices(Context context) {
        List<UsbDeviceSummary> out = new ArrayList<>();
        if (context == null) return out;
        UsbManager um = (UsbManager) context.getSystemService(Context.USB_SERVICE);
        if (um == null) return out;
        HashMap<String, UsbDevice> map = um.getDeviceList();
        if (map == null) return out;

        for (UsbDevice dev : map.values()) {
            out.add(new UsbDeviceSummary(
                    dev,
                    dev.getVendorId(),
                    dev.getProductId(),
                    identifyChipset(dev.getVendorId(), dev.getProductId(), dev.getDeviceClass()),
                    um.hasPermission(dev)
            ));
        }
        return out;
    }

    public static String identifyChipset(int vid, int pid, int devClass) {
        if (vid == 0x0403) return "FTDI UART (0x0403:" + hex4(pid) + ")";
        if (vid == 0x1A86) return "CH340/CH341 UART (0x1A86:" + hex4(pid) + ")";
        if (vid == 0x10C4) return "CP210x Silicon Labs UART";
        if (vid == 0x067B) return "Prolific PL2303 UART";
        if (vid == 0x2341 || vid == 0x2A03) return "Arduino / CDC Serial";
        if (devClass == UsbConstants.USB_CLASS_COMM) return "USB CDC-ACM Serial";
        return String.format(Locale.US, "USB 0x%04X:0x%04X (class %d)", vid, pid, devClass);
    }

    public static String formatDeviceReport(Context context) {
        List<UsbDeviceSummary> devices = listAttachedDevices(context);
        StringBuilder sb = new StringBuilder();
        sb.append("[USB OTG Host Devices (").append(devices.size()).append(")]\n");
        if (devices.isEmpty()) {
            sb.append("  (No USB OTG devices attached. Connect a UART adapter or OTG peripheral.)\n");
            return sb.toString();
        }
        for (UsbDeviceSummary s : devices) {
            String prod = "";
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && s.hasPermission) {
                try {
                    prod = s.device.getProductName();
                } catch (Exception ignored) {
                }
            }
            sb.append(String.format(Locale.US, "  %-28s  %s  perm=%s  %s\n",
                    s.device.getDeviceName(), s.chipsetHint, s.hasPermission ? "GRANTED" : "ASK",
                    prod != null ? prod : ""));
        }
        return sb.toString();
    }

    public static boolean requestPermission(Context context, UsbDevice device) {
        if (context == null || device == null) return false;
        UsbManager um = (UsbManager) context.getSystemService(Context.USB_SERVICE);
        if (um == null) return false;
        if (um.hasPermission(device)) return true;
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            flags |= PendingIntent.FLAG_MUTABLE;
        }
        PendingIntent pi = PendingIntent.getBroadcast(context, device.getDeviceId(),
                new Intent(ACTION_USB_PERMISSION), flags);
        um.requestPermission(device, pi);
        return false;
    }

    /** Sends a command/line and reads available UART/CDC bulk response for serial devices. */
    public static String exchangeCdcSerial(Context context, UsbDevice device, String payload, int baudRate, int readTimeoutMs) {
        if (context == null || device == null) return "No USB device specified.";
        UsbManager um = (UsbManager) context.getSystemService(Context.USB_SERVICE);
        if (um == null) return "UsbManager unavailable.";
        if (!um.hasPermission(device)) {
            requestPermission(context, device);
            return "Requested USB host permission for " + device.getDeviceName() + ". Re-run after granting.";
        }

        UsbInterface dataIface = null;
        UsbEndpoint epIn = null;
        UsbEndpoint epOut = null;

        for (int i = 0; i < device.getInterfaceCount(); i++) {
            UsbInterface iface = device.getInterface(i);
            UsbEndpoint foundIn = null;
            UsbEndpoint foundOut = null;
            for (int e = 0; e < iface.getEndpointCount(); e++) {
                UsbEndpoint ep = iface.getEndpoint(e);
                if (ep.getType() == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                    if (ep.getDirection() == UsbConstants.USB_DIR_IN && foundIn == null) foundIn = ep;
                    if (ep.getDirection() == UsbConstants.USB_DIR_OUT && foundOut == null) foundOut = ep;
                }
            }
            if (foundIn != null && foundOut != null) {
                dataIface = iface;
                epIn = foundIn;
                epOut = foundOut;
                break;
            }
        }

        if (dataIface == null || epIn == null || epOut == null) {
            return "Device " + device.getDeviceName() + " exposes no bulk IN/OUT serial endpoints.";
        }

        UsbDeviceConnection conn = um.openDevice(device);
        if (conn == null) return "Failed opening USB connection to " + device.getDeviceName();

        try {
            conn.claimInterface(dataIface, true);
            // CDC SET_LINE_CODING (baud, 8N1) for standard CDC-ACM UART adapters
            byte[] lineCoding = new byte[]{
                    (byte) (baudRate & 0xFF),
                    (byte) ((baudRate >> 8) & 0xFF),
                    (byte) ((baudRate >> 16) & 0xFF),
                    (byte) ((baudRate >> 24) & 0xFF),
                    0x00, // 1 stop bit
                    0x00, // no parity
                    0x08  // 8 data bits
            };
            conn.controlTransfer(0x21, 0x20, 0, dataIface.getId(), lineCoding, lineCoding.length, 500);
            conn.controlTransfer(0x21, 0x22, 0x03, dataIface.getId(), null, 0, 500); // DTR + RTS

            if (payload != null && !payload.isEmpty()) {
                byte[] outBytes = (payload + "\r\n").getBytes(StandardCharsets.UTF_8);
                conn.bulkTransfer(epOut, outBytes, outBytes.length, 1000);
            }

            byte[] rx = new byte[Math.max(64, epIn.getMaxPacketSize() * 4)];
            StringBuilder sb = new StringBuilder();
            long deadline = System.currentTimeMillis() + Math.max(300, readTimeoutMs);
            while (System.currentTimeMillis() < deadline) {
                int r = conn.bulkTransfer(epIn, rx, rx.length, 250);
                if (r > 0) {
                    sb.append(new String(rx, 0, r, StandardCharsets.UTF_8));
                } else {
                    break;
                }
            }
            return sb.length() == 0 ? "(Sent " + (payload != null ? payload.length() : 0) + " bytes; 0 bytes returned)" : sb.toString();
        } finally {
            try {
                conn.releaseInterface(dataIface);
            } catch (Exception ignored) {
            }
            conn.close();
        }
    }

    private static String hex4(int v) {
        return String.format(Locale.US, "%04X", v & 0xFFFF);
    }
}
