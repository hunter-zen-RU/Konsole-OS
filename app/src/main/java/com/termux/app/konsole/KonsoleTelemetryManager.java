package com.termux.app.konsole;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.TrafficStats;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.StatFs;
import android.os.SystemClock;
import android.util.Log;

import java.io.BufferedReader;
import java.io.FileReader;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 1-Second Ticker updating live telemetry variables:
 * $time, $date, $battery, $cpu_cores, $cpu_usage, $ram_total, $ram_used, $ram_free,
 * $ram_percent, $storage_free, $device_name, $android_version, $ip_address,
 * $wifi_ssid, $network_type, $net_rx_speed, $net_tx_speed, $net_speed.
 */
public class KonsoleTelemetryManager {

    private static final String TAG = "KonsoleTelemetryManager";
    private static KonsoleTelemetryManager sInstance;

    private final Map<String, String> mLiveVariables = new ConcurrentHashMap<>();
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private boolean mRunning = false;

    // Network speed delta state
    private long mLastRxBytes = -1L;
    private long mLastTxBytes = -1L;
    private long mLastNetSampleTimeMs = -1L;

    // CPU utilization delta state
    private long mLastTotalCpu = -1L;
    private long mLastIdleCpu = -1L;
    private long mLastProcCpuMs = -1L;
    private long mLastWallTimeMs = -1L;

    private final Runnable mTickerRunnable = new Runnable() {
        @Override
        public void run() {
            if (!mRunning) return;
            updateTelemetry();
            mHandler.postDelayed(this, 1000);
        }
    };

    private Context mContext;

    private KonsoleTelemetryManager() {
    }

    public static synchronized KonsoleTelemetryManager getInstance() {
        if (sInstance == null) {
            sInstance = new KonsoleTelemetryManager();
        }
        return sInstance;
    }

    public void start(Context context) {
        if (context == null) return;
        this.mContext = context.getApplicationContext();
        if (!mRunning) {
            mRunning = true;
            mHandler.post(mTickerRunnable);
        }
    }

    public void stop() {
        mRunning = false;
        mHandler.removeCallbacks(mTickerRunnable);
    }

    /** Triggers an immediate synchronous telemetry sample when UI needs fresh values. */
    public void refreshNow(Context context) {
        if (context != null && this.mContext == null) {
            this.mContext = context.getApplicationContext();
        }
        updateTelemetry();
    }

    public static String formatBytesPerSec(double bytesPerSec) {
        if (bytesPerSec <= 0) return "0 B/s";
        if (bytesPerSec < 1024.0) {
            return String.format(Locale.US, "%.0f B/s", bytesPerSec);
        } else if (bytesPerSec < 1024.0 * 1024.0) {
            return String.format(Locale.US, "%.1f KB/s", bytesPerSec / 1024.0);
        } else {
            return String.format(Locale.US, "%.2f MB/s", bytesPerSec / (1024.0 * 1024.0));
        }
    }

    private double measureCpuUsagePercent(int cores) {
        // 1. Attempt system-wide /proc/stat sampling
        try (BufferedReader reader = new BufferedReader(new FileReader("/proc/stat"))) {
            String line = reader.readLine();
            if (line != null && line.startsWith("cpu ")) {
                String[] toks = line.trim().split("\\s+");
                if (toks.length >= 5) {
                    long user = Long.parseLong(toks[1]);
                    long nice = Long.parseLong(toks[2]);
                    long sys = Long.parseLong(toks[3]);
                    long idle = Long.parseLong(toks[4]);
                    long iowait = toks.length > 5 ? Long.parseLong(toks[5]) : 0;
                    long irq = toks.length > 6 ? Long.parseLong(toks[6]) : 0;
                    long softirq = toks.length > 7 ? Long.parseLong(toks[7]) : 0;
                    long steal = toks.length > 8 ? Long.parseLong(toks[8]) : 0;

                    long idleAll = idle + iowait;
                    long nonIdle = user + nice + sys + irq + softirq + steal;
                    long total = idleAll + nonIdle;

                    if (mLastTotalCpu > 0 && total > mLastTotalCpu) {
                        long totalDelta = total - mLastTotalCpu;
                        long idleDelta = Math.max(0L, idleAll - mLastIdleCpu);
                        double pct = (100.0 * (totalDelta - idleDelta)) / totalDelta;
                        mLastTotalCpu = total;
                        mLastIdleCpu = idleAll;
                        return Math.min(100.0, Math.max(0.0, pct));
                    }
                    mLastTotalCpu = total;
                    mLastIdleCpu = idleAll;
                }
            }
        } catch (Exception ignored) {
            // SELinux on Android 8+ may block /proc/stat; fall back to process CPU delta below
        }

        // 2. Fallback: process elapsed CPU time over wall time * cores
        long nowWall = SystemClock.elapsedRealtime();
        long nowProcCpu = Process.getElapsedCpuTime();
        double fallbackPct = 0.0;
        if (mLastWallTimeMs > 0 && nowWall > mLastWallTimeMs) {
            long wallDelta = nowWall - mLastWallTimeMs;
            long cpuDelta = Math.max(0L, nowProcCpu - mLastProcCpuMs);
            int safeCores = Math.max(1, cores);
            fallbackPct = (100.0 * cpuDelta) / (wallDelta * safeCores);
            fallbackPct = Math.min(100.0, Math.max(0.1, fallbackPct));
        }
        mLastWallTimeMs = nowWall;
        mLastProcCpuMs = nowProcCpu;
        return fallbackPct;
    }

    private void updateTelemetry() {
        if (mContext == null) return;
        try {
            // $time & $date
            long now = System.currentTimeMillis();
            SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());
            SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault());
            mLiveVariables.put("time", timeFormat.format(new Date(now)));
            mLiveVariables.put("date", dateFormat.format(new Date(now)));

            // $battery
            IntentFilter ifilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
            Intent batteryStatus = mContext.registerReceiver(null, ifilter);
            if (batteryStatus != null) {
                int level = batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                int scale = batteryStatus.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
                int status = batteryStatus.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
                boolean isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL;
                float pct = scale > 0 ? (level / (float) scale) * 100f : 0f;
                mLiveVariables.put("battery", String.format(Locale.US, "%.0f%% %s", pct, isCharging ? "[Charging]" : "[Discharging]"));
            } else {
                mLiveVariables.put("battery", "N/A");
            }

            // $cpu_cores & $cpu_usage
            int cores = Runtime.getRuntime().availableProcessors();
            mLiveVariables.put("cpu_cores", String.valueOf(cores));
            double cpuPct = measureCpuUsagePercent(cores);
            mLiveVariables.put("cpu_usage", String.format(Locale.US, "%.1f%%", cpuPct));

            // $ram_total, $ram_used, $ram_free, $ram_percent
            ActivityManager actManager = (ActivityManager) mContext.getSystemService(Context.ACTIVITY_SERVICE);
            if (actManager != null) {
                ActivityManager.MemoryInfo memInfo = new ActivityManager.MemoryInfo();
                actManager.getMemoryInfo(memInfo);
                long totalMB = memInfo.totalMem / (1024 * 1024);
                long freeMB = memInfo.availMem / (1024 * 1024);
                long usedMB = Math.max(0L, totalMB - freeMB);
                int ramPct = totalMB > 0 ? (int) Math.round((usedMB * 100.0) / totalMB) : 0;
                mLiveVariables.put("ram_total", totalMB + " MB");
                mLiveVariables.put("ram_used", usedMB + " MB");
                mLiveVariables.put("ram_free", freeMB + " MB");
                mLiveVariables.put("ram_percent", ramPct + "%");
            }

            // $storage_free
            try {
                StatFs stat = new StatFs(Environment.getExternalStorageDirectory().getPath());
                long bytesAvailable = stat.getBlockSizeLong() * stat.getAvailableBlocksLong();
                long gbsAvailable = bytesAvailable / (1024 * 1024 * 1024);
                mLiveVariables.put("storage_free", gbsAvailable + " GB");
            } catch (Exception e) {
                mLiveVariables.put("storage_free", "N/A");
            }

            // $device_name & $android_version
            mLiveVariables.put("device_name", Build.MANUFACTURER + " " + Build.MODEL);
            mLiveVariables.put("android_version", "Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")");

            // Network speed via TrafficStats
            long rxBytes = TrafficStats.getTotalRxBytes();
            long txBytes = TrafficStats.getTotalTxBytes();
            long sampleWall = SystemClock.elapsedRealtime();
            double rxRate = 0.0;
            double txRate = 0.0;
            if (rxBytes != TrafficStats.UNSUPPORTED && txBytes != TrafficStats.UNSUPPORTED
                && mLastRxBytes >= 0 && mLastTxBytes >= 0 && mLastNetSampleTimeMs > 0
                && sampleWall > mLastNetSampleTimeMs) {
                double elapsedSec = (sampleWall - mLastNetSampleTimeMs) / 1000.0;
                rxRate = Math.max(0.0, (rxBytes - mLastRxBytes) / elapsedSec);
                txRate = Math.max(0.0, (txBytes - mLastTxBytes) / elapsedSec);
            }
            if (rxBytes != TrafficStats.UNSUPPORTED && txBytes != TrafficStats.UNSUPPORTED) {
                mLastRxBytes = rxBytes;
                mLastTxBytes = txBytes;
                mLastNetSampleTimeMs = sampleWall;
            }
            String rxStr = formatBytesPerSec(rxRate);
            String txStr = formatBytesPerSec(txRate);
            mLiveVariables.put("net_rx_speed", rxStr);
            mLiveVariables.put("net_tx_speed", txStr);
            mLiveVariables.put("net_speed", "↓ " + rxStr + "  ↑ " + txStr);

            // Connectivity
            ConnectivityManager cm = (ConnectivityManager) mContext.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm != null) {
                NetworkInfo activeNetwork = cm.getActiveNetworkInfo();
                if (activeNetwork != null && activeNetwork.isConnected()) {
                    if (activeNetwork.getType() == ConnectivityManager.TYPE_WIFI) {
                        mLiveVariables.put("network_type", "Wi-Fi");
                        WifiManager wifiManager = (WifiManager) mContext.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                        if (wifiManager != null) {
                            WifiInfo wifiInfo = wifiManager.getConnectionInfo();
                            String ssid = wifiInfo != null ? wifiInfo.getSSID() : null;
                            if (ssid != null && ssid.startsWith("\"") && ssid.endsWith("\"")) {
                                ssid = ssid.substring(1, ssid.length() - 1);
                            }
                            mLiveVariables.put("wifi_ssid", ssid != null ? ssid : "Unknown SSID");
                        }
                    } else if (activeNetwork.getType() == ConnectivityManager.TYPE_MOBILE) {
                        mLiveVariables.put("network_type", "Cellular");
                        mLiveVariables.put("wifi_ssid", "Mobile Data");
                    } else {
                        mLiveVariables.put("network_type", activeNetwork.getTypeName());
                        mLiveVariables.put("wifi_ssid", "N/A");
                    }
                } else {
                    mLiveVariables.put("network_type", "Disconnected");
                    mLiveVariables.put("wifi_ssid", "None");
                }
            }

            // $ip_address
            mLiveVariables.put("ip_address", getLocalIpAddress());

        } catch (Exception e) {
            Log.e(TAG, "Error updating telemetry", e);
        }
    }

    private String getLocalIpAddress() {
        try {
            List<NetworkInterface> interfaces = Collections.list(NetworkInterface.getNetworkInterfaces());
            for (NetworkInterface intf : interfaces) {
                List<InetAddress> addrs = Collections.list(intf.getInetAddresses());
                for (InetAddress addr : addrs) {
                    if (!addr.isLoopbackAddress() && addr.getHostAddress() != null) {
                        String sAddr = addr.getHostAddress();
                        if (sAddr.indexOf(':') < 0) { // IPv4
                            return sAddr;
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return "127.0.0.1";
    }

    public Map<String, String> getLiveVariables() {
        return mLiveVariables;
    }

    public String getVariableValue(String name) {
        return mLiveVariables.get(name);
    }
}
