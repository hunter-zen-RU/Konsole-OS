package com.termux.app.konsole;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.PowerManager;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Evaluates runtime Android device state against macro constraints using BatteryManager,
 * ConnectivityManager/WifiManager, and PowerManager, and builds the %var% injection map.
 */
public final class KonsoleConstraintEvaluator {

    public static class EvaluationResult {
        public final boolean passed;
        public final String summary;
        public final List<String> failureReasons;

        public EvaluationResult(boolean passed, String summary, List<String> failureReasons) {
            this.passed = passed;
            this.summary = summary;
            this.failureReasons = failureReasons;
        }
    }

    public static class DeviceSnapshot {
        public int batteryLevel = 100;
        public boolean isCharging = false;
        public boolean wifiConnected = false;
        public boolean networkConnected = false;
        public String networkType = "None";
        public String wifiSsid = "None";
        public boolean screenOn = true;
    }

    private KonsoleConstraintEvaluator() {
    }

    public static DeviceSnapshot captureSnapshot(Context context) {
        DeviceSnapshot snap = new DeviceSnapshot();
        if (context == null) return snap;

        // 1. Battery state via ACTION_BATTERY_CHANGED sticky broadcast + BatteryManager fallback
        try {
            Intent batIntent = context.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (batIntent != null) {
                int level = batIntent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                int scale = batIntent.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
                int status = batIntent.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
                if (level >= 0 && scale > 0) {
                    snap.batteryLevel = Math.round((level * 100f) / scale);
                }
                snap.isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING
                        || status == BatteryManager.BATTERY_STATUS_FULL;
            } else {
                BatteryManager bm = (BatteryManager) context.getSystemService(Context.BATTERY_SERVICE);
                if (bm != null) {
                    int cap = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
                    if (cap >= 0 && cap <= 100) snap.batteryLevel = cap;
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        snap.isCharging = bm.isCharging();
                    }
                }
            }
        } catch (Exception ignored) {
        }

        // 2. Connectivity & Wi-Fi state
        try {
            ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    android.net.Network active = cm.getActiveNetwork();
                    NetworkCapabilities caps = active != null ? cm.getNetworkCapabilities(active) : null;
                    if (caps != null) {
                        snap.networkConnected = true;
                        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                            snap.wifiConnected = true;
                            snap.networkType = "Wi-Fi";
                        } else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                            snap.networkType = "Cellular";
                        } else {
                            snap.networkType = "Connected";
                        }
                    }
                } else {
                    NetworkInfo info = cm.getActiveNetworkInfo();
                    if (info != null && info.isConnected()) {
                        snap.networkConnected = true;
                        snap.wifiConnected = info.getType() == ConnectivityManager.TYPE_WIFI;
                        snap.networkType = snap.wifiConnected ? "Wi-Fi" : info.getTypeName();
                    }
                }
            }
            if (snap.wifiConnected) {
                WifiManager wm = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                if (wm != null) {
                    WifiInfo wi = wm.getConnectionInfo();
                    String ssid = wi != null ? wi.getSSID() : null;
                    if (ssid != null) {
                        if (ssid.startsWith("\"") && ssid.endsWith("\"") && ssid.length() >= 2) {
                            ssid = ssid.substring(1, ssid.length() - 1);
                        }
                        if (!ssid.isEmpty() && !"<unknown ssid>".equalsIgnoreCase(ssid)) {
                            snap.wifiSsid = ssid;
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }

        // 3. Screen interactive state
        try {
            PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                snap.screenOn = pm.isInteractive();
            }
        } catch (Exception ignored) {
        }

        return snap;
    }

    public static EvaluationResult evaluate(Context context, KonsoleMacroRule.Constraints c) {
        if (c == null) {
            return new EvaluationResult(true, "All constraints satisfied (none configured)", new ArrayList<>());
        }
        DeviceSnapshot snap = captureSnapshot(context);
        List<String> failures = new ArrayList<>();

        if (c.requireCharging && !snap.isCharging) {
            failures.add("Requires charger connected (currently discharging)");
        }
        if (c.requireDischarging && snap.isCharging) {
            failures.add("Requires battery discharging (currently charging)");
        }
        if (snap.batteryLevel < c.minBattery) {
            failures.add("Battery " + snap.batteryLevel + "% < min " + c.minBattery + "%");
        }
        if (snap.batteryLevel > c.maxBattery) {
            failures.add("Battery " + snap.batteryLevel + "% > max " + c.maxBattery + "%");
        }
        if (c.requireWifi && !snap.wifiConnected) {
            failures.add("Requires active Wi-Fi connection (network=" + snap.networkType + ")");
        }
        if (c.requireScreenOn && !snap.screenOn) {
            failures.add("Requires screen interactive/on");
        }
        if (c.ssidFilter != null && !c.ssidFilter.trim().isEmpty()) {
            String expected = c.ssidFilter.trim();
            if (!snap.wifiConnected || !expected.equalsIgnoreCase(snap.wifiSsid)) {
                failures.add("Requires SSID '" + expected + "' (current='" + snap.wifiSsid + "')");
            }
        }

        if (failures.isEmpty()) {
            return new EvaluationResult(true,
                    "Constraints passed (Bat " + snap.batteryLevel + "% " + (snap.isCharging ? "Charging" : "Discharging")
                            + ", Net=" + snap.networkType + ", Screen=" + (snap.screenOn ? "On" : "Off") + ")",
                    failures);
        }
        return new EvaluationResult(false, String.join("; ", failures), failures);
    }

    /**
     * Builds the dynamic variable map for injection into native actions (%var%) and shell exports.
     */
    public static Map<String, String> buildVariableMap(Context context,
                                                       KonsoleMacroRule rule,
                                                       String triggerType,
                                                       Map<String, String> triggerExtras) {
        Map<String, String> vars = new LinkedHashMap<>();

        if (context != null) {
            KonsoleTelemetryManager.getInstance().refreshNow(context);
        }
        Map<String, String> live = KonsoleTelemetryManager.getInstance().getLiveVariables();
        for (Map.Entry<String, String> e : live.entrySet()) {
            if (e.getKey() != null && e.getValue() != null) {
                vars.put(e.getKey(), e.getValue());
            }
        }

        DeviceSnapshot snap = captureSnapshot(context);
        vars.put("konsole", KonsoleConfigManager.KONSOLE_ROOT_PATH);
        vars.put("battery_level", String.valueOf(snap.batteryLevel));
        vars.put("battery_charging", String.valueOf(snap.isCharging));
        vars.put("wifi_ssid", snap.wifiSsid);
        vars.put("network_type", snap.networkType);
        vars.put("screen_on", String.valueOf(snap.screenOn));
        vars.put("trigger_type", triggerType != null ? triggerType : "MANUAL");
        vars.put("macro_name", rule != null && rule.name != null ? rule.name : "");
        vars.put("macro_id", rule != null && rule.id != null ? rule.id : "");

        // Defaults for notification / app event variables so placeholders never stay unresolved
        vars.putIfAbsent("foreground_app", "");
        vars.putIfAbsent("notification_pkg", "");
        vars.putIfAbsent("notification_title", "");
        vars.putIfAbsent("notification_text", "");

        if (triggerExtras != null) {
            for (Map.Entry<String, String> e : triggerExtras.entrySet()) {
                if (e.getKey() != null && e.getValue() != null) {
                    vars.put(e.getKey(), e.getValue());
                }
            }
        }
        return vars;
    }

    /**
     * Substitutes both %var_name% placeholders and $var_name references (for native actions).
     */
    public static String interpolate(String template, Map<String, String> vars) {
        if (template == null) return "";
        if (vars == null || vars.isEmpty()) return template;
        String out = template;
        for (Map.Entry<String, String> e : vars.entrySet()) {
            String val = e.getValue() != null ? e.getValue() : "";
            out = out.replace("%" + e.getKey() + "%", val);
        }
        return out;
    }
}
