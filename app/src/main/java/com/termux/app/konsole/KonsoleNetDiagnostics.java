package com.termux.app.konsole;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;

import java.io.BufferedReader;
import java.io.FileReader;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.security.MessageDigest;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * Unrooted Network, TLS, DNS, Wi-Fi BSS, mDNS/NSD, and BLE GATT Diagnostics Toolkit.
 */
public final class KonsoleNetDiagnostics {

    private KonsoleNetDiagnostics() {
    }

    public static String getInterfacesAndArpReport() {
        StringBuilder sb = new StringBuilder();
        sb.append("[Network Interfaces]\n");
        try {
            Enumeration<NetworkInterface> ifaces = NetworkInterface.getNetworkInterfaces();
            while (ifaces != null && ifaces.hasMoreElements()) {
                NetworkInterface ni = ifaces.nextElement();
                if (!ni.isUp()) continue;
                Enumeration<InetAddress> addrs = ni.getInetAddresses();
                StringBuilder ipList = new StringBuilder();
                while (addrs.hasMoreElements()) {
                    InetAddress addr = addrs.nextElement();
                    if (addr instanceof Inet4Address || !addr.isLinkLocalAddress()) {
                        if (ipList.length() > 0) ipList.append(", ");
                        ipList.append(addr.getHostAddress());
                    }
                }
                if (ipList.length() > 0) {
                    sb.append(String.format(Locale.US, "  %-10s mtu=%-5d %s\n", ni.getName(), ni.getMTU(), ipList));
                }
            }
        } catch (Exception e) {
            sb.append("  Error reading interfaces: ").append(e.getMessage()).append("\n");
        }

        sb.append("\n[ARP Neighbor Cache (/proc/net/arp)]\n");
        try (BufferedReader br = new BufferedReader(new FileReader("/proc/net/arp"))) {
            String line;
            int count = 0;
            while ((line = br.readLine()) != null) {
                if (count == 0) {
                    count++;
                    continue;
                }
                sb.append("  ").append(line.trim()).append("\n");
                count++;
                if (count > 40) break;
            }
            if (count <= 1) sb.append("  (No neighbor entries visible)\n");
        } catch (Exception e) {
            sb.append("  Unavailable on this Android build: ").append(e.getMessage()).append("\n");
        }
        return sb.toString();
    }

    public static String scanTcpPorts(String host, List<Integer> ports, int timeoutMs) {
        if (host == null || host.trim().isEmpty()) return "Host is required.";
        String target = host.trim();
        StringBuilder sb = new StringBuilder();
        try {
            InetAddress resolved = InetAddress.getByName(target);
            sb.append("[TCP Recon: ").append(target).append(" (").append(resolved.getHostAddress()).append(")]\n");
        } catch (Exception e) {
            return "DNS resolution failed for '" + target + "': " + e.getMessage();
        }

        int openCount = 0;
        for (int port : ports) {
            try (Socket socket = new Socket()) {
                long t0 = System.currentTimeMillis();
                socket.connect(new InetSocketAddress(target, port), timeoutMs);
                long rtt = System.currentTimeMillis() - t0;
                String banner = "";
                try {
                    socket.setSoTimeout(300);
                    byte[] buf = new byte[128];
                    int read = socket.getInputStream().read(buf);
                    if (read > 0) {
                        banner = new String(buf, 0, read).replaceAll("[\\r\\n]+", " ").trim();
                    }
                } catch (Exception ignored) {
                }
                sb.append(String.format(Locale.US, "  OPEN  %-5d/tcp  %3dms  %s\n", port, rtt, banner));
                openCount++;
            } catch (Exception ignored) {
            }
        }
        if (openCount == 0) {
            sb.append("  (0 open ports found in scanned set)\n");
        }
        return sb.toString();
    }

    public static List<Integer> parsePortSpec(String spec) {
        if (spec == null || spec.trim().isEmpty()) {
            return java.util.Arrays.asList(21, 22, 23, 53, 80, 443, 445, 554, 1883, 3306, 3389, 5555, 5900, 5901, 8000, 8022, 8080, 8443);
        }
        List<Integer> list = new ArrayList<>();
        for (String token : spec.split(",")) {
            token = token.trim();
            try {
                if (token.contains("-")) {
                    String[] bounds = token.split("-", 2);
                    int a = Math.max(1, Integer.parseInt(bounds[0].trim()));
                    int b = Math.min(65535, Integer.parseInt(bounds[1].trim()));
                    for (int p = a; p <= b && list.size() < 256; p++) list.add(p);
                } else {
                    int p = Integer.parseInt(token);
                    if (p >= 1 && p <= 65535) list.add(p);
                }
            } catch (Exception ignored) {
            }
        }
        return list;
    }

    public static String inspectTlsCertificate(String host, int port) {
        if (host == null || host.trim().isEmpty()) return "Host is required.";
        String target = host.trim();
        try {
            SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
            try (SSLSocket sock = (SSLSocket) factory.createSocket()) {
                sock.connect(new InetSocketAddress(target, port), 4000);
                sock.startHandshake();
                SSLSession session = sock.getSession();
                StringBuilder sb = new StringBuilder();
                sb.append("[TLS Certificate Inspector: ").append(target).append(":").append(port).append("]\n");
                sb.append("  Protocol : ").append(session.getProtocol()).append("\n");
                sb.append("  Cipher   : ").append(session.getCipherSuite()).append("\n");
                Certificate[] certs = session.getPeerCertificates();
                for (int i = 0; i < certs.length; i++) {
                    if (certs[i] instanceof X509Certificate) {
                        X509Certificate x = (X509Certificate) certs[i];
                        sb.append("  Cert #").append(i + 1).append(":\n");
                        sb.append("    Subject : ").append(x.getSubjectX500Principal().getName()).append("\n");
                        sb.append("    Issuer  : ").append(x.getIssuerX500Principal().getName()).append("\n");
                        sb.append("    Expires : ").append(x.getNotAfter()).append("\n");
                        sb.append("    SHA-256 : ").append(sha256Hex(x.getEncoded())).append("\n");
                    }
                }
                return sb.toString();
            }
        } catch (Exception e) {
            return "TLS inspection failed for " + target + ":" + port + ": " + e.getMessage();
        }
    }

    public static String scanWifiBss(Context context) {
        if (context == null) return "Context unavailable.";
        try {
            WifiManager wm = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm == null) return "WifiManager unavailable.";
            List<android.net.wifi.ScanResult> results = wm.getScanResults();
            if (results == null || results.isEmpty()) {
                return "No Wi-Fi BSS scan results cached (enable Location + Wi-Fi or trigger scan).";
            }
            Collections.sort(results, (a, b) -> Integer.compare(b.level, a.level));
            StringBuilder sb = new StringBuilder();
            sb.append("[Wi-Fi BSS Survey (").append(results.size()).append(" APs)]\n");
            for (android.net.wifi.ScanResult sr : results) {
                String ssid = (sr.SSID == null || sr.SSID.isEmpty()) ? "<hidden>" : sr.SSID;
                sb.append(String.format(Locale.US, "  %-22s  %s  %4d dBm  %d MHz  %s\n",
                        ssid.length() > 22 ? ssid.substring(0, 22) : ssid,
                        sr.BSSID, sr.level, sr.frequency, sr.capabilities));
            }
            return sb.toString();
        } catch (SecurityException se) {
            return "Location permission (ACCESS_FINE_LOCATION) required for Wi-Fi BSSID scan.";
        } catch (Exception e) {
            return "Wi-Fi scan error: " + e.getMessage();
        }
    }

    public interface BleScanCallback {
        void onComplete(String report);
    }

    public static void scanBleAsync(Context context, long durationMs, BleScanCallback callback) {
        if (context == null || callback == null) return;
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null || !adapter.isEnabled()) {
            callback.onComplete("Bluetooth adapter is disabled or unavailable.");
            return;
        }
        BluetoothLeScanner scanner = adapter.getBluetoothLeScanner();
        if (scanner == null) {
            callback.onComplete("BLE scanner unavailable on this device.");
            return;
        }

        final Map<String, String> found = Collections.synchronizedMap(new LinkedHashMap<>());
        final ScanCallback bleCb = new ScanCallback() {
            @Override
            public void onScanResult(int callbackType, ScanResult result) {
                if (result == null || result.getDevice() == null) return;
                String addr = result.getDevice().getAddress();
                String name = null;
                try {
                    name = result.getDevice().getName();
                } catch (SecurityException ignored) {
                }
                if ((name == null || name.isEmpty()) && result.getScanRecord() != null) {
                    name = result.getScanRecord().getDeviceName();
                }
                if (name == null || name.isEmpty()) name = "<unnamed>";
                StringBuilder uuids = new StringBuilder();
                if (result.getScanRecord() != null && result.getScanRecord().getServiceUuids() != null) {
                    for (ParcelUuid u : result.getScanRecord().getServiceUuids()) {
                        if (uuids.length() > 0) uuids.append(",");
                        uuids.append(u.toString(), 0, Math.min(8, u.toString().length()));
                    }
                }
                found.put(addr, String.format(Locale.US, "  %-18s  %-18s  %4d dBm  %s",
                        name.length() > 18 ? name.substring(0, 18) : name, addr, result.getRssi(), uuids));
            }
        };

        try {
            scanner.startScan(bleCb);
        } catch (SecurityException se) {
            callback.onComplete("Bluetooth Scan / Location permission required: " + se.getMessage());
            return;
        } catch (Exception e) {
            callback.onComplete("BLE scan start failed: " + e.getMessage());
            return;
        }

        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            try {
                scanner.stopScan(bleCb);
            } catch (Exception ignored) {
            }
            StringBuilder sb = new StringBuilder();
            sb.append("[BLE GATT Survey (").append(found.size()).append(" devices)]\n");
            if (found.isEmpty()) {
                sb.append("  (No BLE advertisements captured in ").append(durationMs).append("ms)\n");
            } else {
                for (String line : found.values()) {
                    sb.append(line).append("\n");
                }
            }
            callback.onComplete(sb.toString());
        }, Math.max(1000L, Math.min(8000L, durationMs)));
    }

    public static void discoverMdnsServicesAsync(Context context, String serviceType, long durationMs, BleScanCallback callback) {
        if (context == null || callback == null) return;
        NsdManager nsd = (NsdManager) context.getApplicationContext().getSystemService(Context.NSD_SERVICE);
        if (nsd == null) {
            callback.onComplete("NsdManager unavailable.");
            return;
        }
        final String type = (serviceType == null || serviceType.trim().isEmpty()) ? "_http._tcp." : serviceType.trim();
        final List<String> entries = Collections.synchronizedList(new ArrayList<>());
        final NsdManager.DiscoveryListener listener = new NsdManager.DiscoveryListener() {
            @Override public void onStartDiscoveryFailed(String serviceType, int errorCode) {}
            @Override public void onStopDiscoveryFailed(String serviceType, int errorCode) {}
            @Override public void onDiscoveryStarted(String regType) {}
            @Override public void onDiscoveryStopped(String serviceType) {}
            @Override public void onServiceLost(NsdServiceInfo serviceInfo) {}
            @Override
            public void onServiceFound(NsdServiceInfo serviceInfo) {
                entries.add("  " + serviceInfo.getServiceName() + "  [" + serviceInfo.getServiceType() + "]");
            }
        };
        try {
            nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, listener);
        } catch (Exception e) {
            callback.onComplete("mDNS discovery failed: " + e.getMessage());
            return;
        }
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            try {
                nsd.stopServiceDiscovery(listener);
            } catch (Exception ignored) {
            }
            StringBuilder sb = new StringBuilder();
            sb.append("[mDNS / NSD Discovery (" + type + ")]\n");
            if (entries.isEmpty()) {
                sb.append("  (No mDNS services announced for " + type + ")\n");
            } else {
                for (String e : entries) sb.append(e).append("\n");
            }
            callback.onComplete(sb.toString());
        }, Math.max(1500L, Math.min(6000L, durationMs)));
    }

    private static String sha256Hex(byte[] data) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(data);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < d.length; i++) {
                if (i > 0) sb.append(':');
                sb.append(String.format(Locale.US, "%02X", d[i] & 0xFF));
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }
}
