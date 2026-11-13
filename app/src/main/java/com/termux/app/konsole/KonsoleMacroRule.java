package com.termux.app.konsole;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * JSON-serializable Macro Rule data model for Konsole OS Automation.
 * Supports triggers (system broadcasts, cron, intervals, notifications, app events, manual),
 * runtime constraints, and ordered Shell + Native Android actions.
 */
public class KonsoleMacroRule {

    public enum TriggerType {
        MANUAL("Manual / CLI Only"),
        CRON("Cron Expression"),
        INTERVAL("Periodic Interval (min)"),
        BATTERY_LOW("Battery Low"),
        BATTERY_OKAY("Battery Okay"),
        POWER_CONNECTED("Charger Connected"),
        POWER_DISCONNECTED("Charger Disconnected"),
        WIFI_CONNECTED("Wi-Fi Connected"),
        WIFI_DISCONNECTED("Wi-Fi Disconnected"),
        SCREEN_ON("Screen Turned On"),
        SCREEN_OFF("Screen Turned Off"),
        BLUETOOTH_ON("Bluetooth Enabled"),
        HEADSET_PLUGGED("Headset Plugged"),
        NOTIFICATION("Notification Received"),
        APP_OPENED("App Opened (Accessibility)");

        public final String label;

        TriggerType(String label) {
            this.label = label;
        }

        public static TriggerType fromString(String raw) {
            if (raw == null) return MANUAL;
            try {
                return valueOf(raw.trim().toUpperCase());
            } catch (Exception ignored) {
                return MANUAL;
            }
        }
    }

    public enum ActionType {
        SHELL("Background Shell / Fish Command"),
        SHELL_SESSION("Write to Active Terminal Session"),
        FLASHLIGHT("Toggle Flashlight (on/off)"),
        NOTIFICATION("Post System Notification"),
        TOAST("Show Screen Toast"),
        VIBRATE("Vibrate Pattern (ms)"),
        LAUNCH_APP("Launch Android App"),
        INTENT_URL("Open URL / Deep Link"),
        TAP("Accessibility Screen Tap (x,y)"),
        SWIPE("Accessibility Swipe (x1,y1,x2,y2)");

        public final String label;

        ActionType(String label) {
            this.label = label;
        }

        public static ActionType fromString(String raw) {
            if (raw == null) return SHELL;
            try {
                return valueOf(raw.trim().toUpperCase());
            } catch (Exception ignored) {
                return SHELL;
            }
        }
    }

    public static class Constraints {
        public boolean requireCharging = false;
        public boolean requireDischarging = false;
        public int minBattery = 0;      // 0..100
        public int maxBattery = 100;    // 0..100
        public boolean requireWifi = false;
        public boolean requireScreenOn = false;
        public String ssidFilter = "";

        public JSONObject toJson() throws JSONException {
            JSONObject obj = new JSONObject();
            obj.put("requireCharging", requireCharging);
            obj.put("requireDischarging", requireDischarging);
            obj.put("minBattery", minBattery);
            obj.put("maxBattery", maxBattery);
            obj.put("requireWifi", requireWifi);
            obj.put("requireScreenOn", requireScreenOn);
            obj.put("ssidFilter", ssidFilter != null ? ssidFilter : "");
            return obj;
        }

        public static Constraints fromJson(JSONObject obj) {
            Constraints c = new Constraints();
            if (obj == null) return c;
            c.requireCharging = obj.optBoolean("requireCharging", false);
            c.requireDischarging = obj.optBoolean("requireDischarging", false);
            c.minBattery = obj.optInt("minBattery", 0);
            c.maxBattery = obj.optInt("maxBattery", 100);
            c.requireWifi = obj.optBoolean("requireWifi", false);
            c.requireScreenOn = obj.optBoolean("requireScreenOn", false);
            c.ssidFilter = obj.optString("ssidFilter", "");
            return c;
        }

        public String summary() {
            List<String> parts = new ArrayList<>();
            if (requireCharging) parts.add("Charging");
            if (requireDischarging) parts.add("Discharging");
            if (minBattery > 0) parts.add("Bat≥" + minBattery + "%");
            if (maxBattery < 100) parts.add("Bat≤" + maxBattery + "%");
            if (requireWifi) parts.add("Wi-Fi");
            if (requireScreenOn) parts.add("Screen On");
            if (ssidFilter != null && !ssidFilter.trim().isEmpty()) parts.add("SSID=" + ssidFilter.trim());
            return parts.isEmpty() ? "No constraints" : String.join(" · ", parts);
        }
    }

    public static class MacroAction {
        public ActionType type = ActionType.SHELL;
        public String param1 = "";
        public String param2 = "";
        public String param3 = "";

        public MacroAction() {
        }

        public MacroAction(ActionType type, String param1, String param2, String param3) {
            this.type = type != null ? type : ActionType.SHELL;
            this.param1 = param1 != null ? param1 : "";
            this.param2 = param2 != null ? param2 : "";
            this.param3 = param3 != null ? param3 : "";
        }

        public JSONObject toJson() throws JSONException {
            JSONObject obj = new JSONObject();
            obj.put("type", type.name());
            obj.put("param1", param1 != null ? param1 : "");
            obj.put("param2", param2 != null ? param2 : "");
            obj.put("param3", param3 != null ? param3 : "");
            return obj;
        }

        public static MacroAction fromJson(JSONObject obj) {
            MacroAction a = new MacroAction();
            if (obj == null) return a;
            a.type = ActionType.fromString(obj.optString("type", "SHELL"));
            a.param1 = obj.optString("param1", "");
            a.param2 = obj.optString("param2", "");
            a.param3 = obj.optString("param3", "");
            return a;
        }

        public String describe() {
            switch (type) {
                case SHELL:
                    return "Shell: " + param1;
                case SHELL_SESSION:
                    return "Session PTY: " + param1;
                case FLASHLIGHT:
                    return "Flashlight " + (param1.isEmpty() ? "toggle" : param1.toUpperCase());
                case NOTIFICATION:
                    return "Notify \"" + param1 + "\": " + param2;
                case TOAST:
                    return "Toast \"" + param1 + "\"";
                case VIBRATE:
                    return "Vibrate " + (param1.isEmpty() ? "250" : param1) + "ms";
                case LAUNCH_APP:
                    return "Launch " + param1;
                case INTENT_URL:
                    return "Open URL " + param1;
                case TAP:
                    return "Tap (" + param1 + ", " + param2 + ")";
                case SWIPE:
                    return "Swipe (" + param1 + ") -> (" + param2 + ")";
                default:
                    return type.name();
            }
        }
    }

    public String id;
    public String name;
    public String description = "";
    public boolean enabled = true;
    public TriggerType triggerType = TriggerType.MANUAL;
    public String triggerValue = "";
    public Constraints constraints = new Constraints();
    public List<MacroAction> actions = new ArrayList<>();
    public int runCount = 0;
    public long lastRunTimeMs = 0L;
    public String lastRunSummary = "Never run";

    public KonsoleMacroRule() {
        this.id = "macro_" + UUID.randomUUID().toString().substring(0, 8);
    }

    public JSONObject toJson() throws JSONException {
        JSONObject obj = new JSONObject();
        obj.put("id", id);
        obj.put("name", name != null ? name : "Untitled Macro");
        obj.put("description", description != null ? description : "");
        obj.put("enabled", enabled);
        obj.put("triggerType", triggerType.name());
        obj.put("triggerValue", triggerValue != null ? triggerValue : "");
        obj.put("constraints", constraints != null ? constraints.toJson() : new Constraints().toJson());

        JSONArray arr = new JSONArray();
        if (actions != null) {
            for (MacroAction action : actions) {
                arr.put(action.toJson());
            }
        }
        obj.put("actions", arr);
        obj.put("runCount", runCount);
        obj.put("lastRunTimeMs", lastRunTimeMs);
        obj.put("lastRunSummary", lastRunSummary != null ? lastRunSummary : "");
        return obj;
    }

    public static KonsoleMacroRule fromJson(JSONObject obj) {
        KonsoleMacroRule rule = new KonsoleMacroRule();
        if (obj == null) return rule;
        String rawId = obj.optString("id", "").trim();
        if (!rawId.isEmpty()) rule.id = rawId;
        rule.name = obj.optString("name", "Untitled Macro");
        rule.description = obj.optString("description", "");
        rule.enabled = obj.optBoolean("enabled", true);
        rule.triggerType = TriggerType.fromString(obj.optString("triggerType", "MANUAL"));
        rule.triggerValue = obj.optString("triggerValue", "");
        rule.constraints = Constraints.fromJson(obj.optJSONObject("constraints"));

        rule.actions = new ArrayList<>();
        JSONArray arr = obj.optJSONArray("actions");
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject item = arr.optJSONObject(i);
                if (item != null) {
                    rule.actions.add(MacroAction.fromJson(item));
                }
            }
        }
        rule.runCount = obj.optInt("runCount", 0);
        rule.lastRunTimeMs = obj.optLong("lastRunTimeMs", 0L);
        rule.lastRunSummary = obj.optString("lastRunSummary", "Never run");
        return rule;
    }

    public static List<KonsoleMacroRule> createStarterTemplates() {
        List<KonsoleMacroRule> list = new ArrayList<>();

        KonsoleMacroRule batSaver = new KonsoleMacroRule();
        batSaver.id = "tpl_battery_saver";
        batSaver.name = "Low Battery Log & Alert";
        batSaver.description = "Logs battery level & sends an OS alert when battery runs low";
        batSaver.enabled = false;
        batSaver.triggerType = TriggerType.BATTERY_LOW;
        batSaver.constraints.requireDischarging = true;
        batSaver.actions.add(new MacroAction(ActionType.NOTIFICATION,
            "Battery Low (%battery_level%%)",
            "CPU %cpu_usage% · RAM %ram_used% on %wifi_ssid%", ""));
        batSaver.actions.add(new MacroAction(ActionType.SHELL,
            "echo \"[%time%] Battery low: %battery_level%%\" >> $HOME/.konsole_auto.log", "", ""));
        list.add(batSaver);

        KonsoleMacroRule wifiWorkspace = new KonsoleMacroRule();
        wifiWorkspace.id = "tpl_wifi_ping";
        wifiWorkspace.name = "Wi-Fi Network Snapshot";
        wifiWorkspace.description = "Logs IP & SSID whenever Wi-Fi connects, with battery toast";
        wifiWorkspace.enabled = false;
        wifiWorkspace.triggerType = TriggerType.WIFI_CONNECTED;
        wifiWorkspace.constraints.requireWifi = true;
        wifiWorkspace.actions.add(new MacroAction(ActionType.TOAST,
            "Wi-Fi Connected: %wifi_ssid% (%ip_address%)", "", ""));
        wifiWorkspace.actions.add(new MacroAction(ActionType.SHELL,
            "echo \"[%date% %time%] Connected SSID=$wifi_ssid IP=$ip_address\" >> $HOME/.konsole_net.log", "", ""));
        list.add(wifiWorkspace);

        KonsoleMacroRule cronHealth = new KonsoleMacroRule();
        cronHealth.id = "tpl_cron_health";
        cronHealth.name = "15-Min Health Heartbeat";
        cronHealth.description = "Runs a Fish/shell health check every 15 minutes via Cron";
        cronHealth.enabled = false;
        cronHealth.triggerType = TriggerType.CRON;
        cronHealth.triggerValue = "*/15 * * * *";
        cronHealth.constraints.minBattery = 15;
        cronHealth.actions.add(new MacroAction(ActionType.SHELL,
            "echo \"[%time%] CPU=%cpu_usage% RAM=%ram_used% BAT=%battery_level%%\" >> $HOME/.konsole_health.log", "", ""));
        list.add(cronHealth);

        KonsoleMacroRule chargeBrief = new KonsoleMacroRule();
        chargeBrief.id = "tpl_charger_brief";
        chargeBrief.name = "Charger Connected Notify";
        chargeBrief.description = "Vibra-chime & toast with device diagnostics when plugged in";
        chargeBrief.enabled = false;
        chargeBrief.triggerType = TriggerType.POWER_CONNECTED;
        chargeBrief.constraints.requireCharging = true;
        chargeBrief.actions.add(new MacroAction(ActionType.VIBRATE, "0,120,80,120", "", ""));
        chargeBrief.actions.add(new MacroAction(ActionType.TOAST,
            "Charging (%battery_level%%) · RAM %ram_used%", "", ""));
        list.add(chargeBrief);

        return list;
    }
}
