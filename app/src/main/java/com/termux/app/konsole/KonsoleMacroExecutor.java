package com.termux.app.konsole;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.hardware.camera2.CameraManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.Log;
import android.widget.Toast;

import androidx.core.app.NotificationCompat;

import com.termux.app.TermuxActivity;
import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.termux.shell.TermuxShellManager;
import com.termux.shared.termux.shell.command.runner.terminal.TermuxSession;
import com.termux.terminal.TerminalSession;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Executes resolved macro actions:
 * - Background shell/Fish execution with exported trigger variables
 * - Direct writes into the active TerminalSession file descriptor
 * - Fast native Android actions (Flashlight, Notification, Toast, Vibrate, Launch App, Intent URL, Tap/Swipe gestures)
 */
public final class KonsoleMacroExecutor {

    private static final String TAG = "KonsoleMacroExecutor";
    private static final String AUTOMATION_CHANNEL_ID = "konsole_automation_alerts";
    private static volatile boolean sTorchOn = false;

    public static class ExecutionOutcome {
        public int exitCode = 0;
        public String output = "";
        public String summary = "";
    }

    private KonsoleMacroExecutor() {
    }

    public static ExecutionOutcome executeActions(Context context,
                                                  KonsoleMacroRule rule,
                                                  Map<String, String> vars) {
        ExecutionOutcome outcome = new ExecutionOutcome();
        if (rule == null || rule.actions == null || rule.actions.isEmpty()) {
            outcome.summary = "No actions configured";
            return outcome;
        }

        StringBuilder logOut = new StringBuilder();
        int executed = 0;

        for (int i = 0; i < rule.actions.size(); i++) {
            KonsoleMacroRule.MacroAction action = rule.actions.get(i);
            String p1 = KonsoleConstraintEvaluator.interpolate(action.param1, vars);
            String p2 = KonsoleConstraintEvaluator.interpolate(action.param2, vars);
            String p3 = KonsoleConstraintEvaluator.interpolate(action.param3, vars);

            try {
                switch (action.type) {
                    case SHELL: {
                        ExecutionOutcome shRes = runShellCommand(context, p1, vars);
                        logOut.append("[#").append(i + 1).append(" SHELL rc=").append(shRes.exitCode).append("] ")
                              .append(shRes.output).append("\n");
                        if (shRes.exitCode != 0) outcome.exitCode = shRes.exitCode;
                        executed++;
                        break;
                    }
                    case SHELL_SESSION: {
                        boolean ok = writeCommandToActiveSession(p1, vars);
                        logOut.append("[#").append(i + 1).append(" SESSION] ")
                              .append(ok ? "Written to active PTY FD: " + p1 : "No active terminal session")
                              .append("\n");
                        if (!ok) outcome.exitCode = 1;
                        executed++;
                        break;
                    }
                    case FLASHLIGHT: {
                        String msg = executeFlashlight(context, p1);
                        logOut.append("[#").append(i + 1).append(" FLASH] ").append(msg).append("\n");
                        executed++;
                        break;
                    }
                    case NOTIFICATION: {
                        postNotification(context, p1.isEmpty() ? rule.name : p1, p2);
                        logOut.append("[#").append(i + 1).append(" NOTIFY] ")
                              .append(p1).append(": ").append(p2).append("\n");
                        executed++;
                        break;
                    }
                    case TOAST: {
                        showToast(context, p1);
                        logOut.append("[#").append(i + 1).append(" TOAST] ").append(p1).append("\n");
                        executed++;
                        break;
                    }
                    case VIBRATE: {
                        triggerVibrate(context, p1);
                        logOut.append("[#").append(i + 1).append(" VIBRATE] ").append(p1).append("\n");
                        executed++;
                        break;
                    }
                    case LAUNCH_APP: {
                        boolean ok = launchApp(context, p1);
                        logOut.append("[#").append(i + 1).append(" LAUNCH] ")
                              .append(p1).append(" -> ").append(ok ? "Launched" : "Not found").append("\n");
                        if (!ok) outcome.exitCode = 1;
                        executed++;
                        break;
                    }
                    case INTENT_URL: {
                        boolean ok = openIntentUrl(context, p1);
                        logOut.append("[#").append(i + 1).append(" INTENT] ")
                              .append(p1).append(" -> ").append(ok ? "Opened" : "Failed").append("\n");
                        if (!ok) outcome.exitCode = 1;
                        executed++;
                        break;
                    }
                    case TAP: {
                        boolean ok = dispatchTap(p1, p2);
                        logOut.append("[#").append(i + 1).append(" TAP] (")
                              .append(p1).append(",").append(p2).append(") -> ")
                              .append(ok ? "Dispatched" : "Enable Konsole Accessibility Service").append("\n");
                        if (!ok) outcome.exitCode = 1;
                        executed++;
                        break;
                    }
                    case SWIPE: {
                        boolean ok = dispatchSwipe(p1, p2, p3);
                        logOut.append("[#").append(i + 1).append(" SWIPE] ")
                              .append(p1).append(" -> ").append(p2).append(" -> ")
                              .append(ok ? "Dispatched" : "Enable Konsole Accessibility Service").append("\n");
                        if (!ok) outcome.exitCode = 1;
                        executed++;
                        break;
                    }
                }
            } catch (Exception e) {
                outcome.exitCode = 1;
                logOut.append("[#").append(i + 1).append(" ERROR] ").append(e.getMessage()).append("\n");
            }
        }

        outcome.output = logOut.toString().trim();
        outcome.summary = "Executed " + executed + "/" + rule.actions.size() + " action(s)"
                + (outcome.exitCode == 0 ? " OK" : " (rc=" + outcome.exitCode + ")");
        return outcome;
    }

    public static ExecutionOutcome runShellCommand(Context context, String command, Map<String, String> vars) {
        ExecutionOutcome out = new ExecutionOutcome();
        if (command == null || command.trim().isEmpty()) {
            out.output = "(empty command)";
            return out;
        }
        try {
            File fishBin = new File(TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH, "fish");
            File prefixSh = new File(TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH, "sh");
            String shellPath;
            String[] argv;

            StringBuilder envExports = new StringBuilder();
            if (vars != null) {
                for (Map.Entry<String, String> e : vars.entrySet()) {
                    if (isSafeIdentifier(e.getKey())) {
                        envExports.append("export ")
                                  .append(e.getKey())
                                  .append("=")
                                  .append(quotePosix(e.getValue()))
                                  .append("; ");
                    }
                }
            }

            if (fishBin.isFile() && fishBin.canExecute()) {
                shellPath = fishBin.getAbsolutePath();
                StringBuilder fishExports = new StringBuilder();
                if (vars != null) {
                    for (Map.Entry<String, String> e : vars.entrySet()) {
                        if (isSafeIdentifier(e.getKey())) {
                            fishExports.append("set -gx ")
                                       .append(e.getKey())
                                       .append(" ")
                                       .append(quotePosix(e.getValue()))
                                       .append("; ");
                        }
                    }
                }
                argv = new String[]{shellPath, "-c", fishExports + command};
            } else if (prefixSh.isFile() && prefixSh.canExecute()) {
                shellPath = prefixSh.getAbsolutePath();
                argv = new String[]{shellPath, "-c", envExports + command};
            } else {
                shellPath = "/system/bin/sh";
                argv = new String[]{shellPath, "-c", envExports + command};
            }

            ProcessBuilder pb = new ProcessBuilder(argv);
            pb.redirectErrorStream(true);

            File home = new File(TermuxConstants.TERMUX_HOME_DIR_PATH);
            if (home.isDirectory()) {
                pb.directory(home);
            } else if (context != null && context.getFilesDir().isDirectory()) {
                pb.directory(context.getFilesDir());
            }

            Map<String, String> env = pb.environment();
            env.put("HOME", TermuxConstants.TERMUX_HOME_DIR_PATH);
            env.put("PREFIX", TermuxConstants.TERMUX_PREFIX_DIR_PATH);
            env.put("PATH", TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + ":/system/bin:/system/xbin");
            if (vars != null) {
                for (Map.Entry<String, String> e : vars.entrySet()) {
                    if (isSafeIdentifier(e.getKey()) && e.getValue() != null) {
                        env.put(e.getKey(), e.getValue());
                    }
                }
            }

            Process p = pb.start();
            StringBuilder sb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    sb.append(line).append('\n');
                    if (sb.length() > 16384) break;
                }
            }
            boolean finished = p.waitFor(20, TimeUnit.SECONDS);
            if (!finished) {
                p.destroyForcibly();
                out.exitCode = 124;
                sb.append("[Timed out after 20s]");
            } else {
                out.exitCode = p.exitValue();
            }
            out.output = sb.toString().trim();
        } catch (Exception e) {
            out.exitCode = 1;
            out.output = "Shell exec error: " + e.getMessage();
        }
        return out;
    }

    public static boolean writeCommandToActiveSession(String command, Map<String, String> vars) {
        if (command == null || command.trim().isEmpty()) return false;
        TerminalSession targetSession = findActiveTerminalSession();
        if (targetSession == null || !targetSession.isRunning()) return false;

        StringBuilder payload = new StringBuilder();
        if (vars != null) {
            for (String key : new String[]{"battery_level", "wifi_ssid", "trigger_type", "macro_name", "notification_title", "foreground_app"}) {
                String val = vars.get(key);
                if (val != null && !val.isEmpty()) {
                    payload.append("set -gx ").append(key).append(" ").append(quotePosix(val)).append(" 2>/dev/null; ");
                }
            }
        }
        payload.append(command.trim()).append('\n');
        targetSession.write(payload.toString());
        return true;
    }

    public static TerminalSession findActiveTerminalSession() {
        try {
            android.content.Context ctx = KonsoleFishBridge.getInstance().getUiContext(null);
            if (ctx instanceof TermuxActivity) {
                TerminalSession s = ((TermuxActivity) ctx).getCurrentSession();
                if (s != null) return s;
            }
            List<TermuxSession> sessions = TermuxShellManager.getShellManager().mTermuxSessions;
            if (sessions != null && !sessions.isEmpty()) {
                return sessions.get(sessions.size() - 1).getTerminalSession();
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    public static String executeFlashlight(Context context, String mode) {
        if (context == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return "Flashlight requires Android 6+";
        }
        try {
            CameraManager cm = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
            if (cm == null || cm.getCameraIdList().length == 0) {
                return "No camera flash hardware";
            }
            String camId = cm.getCameraIdList()[0];
            String m = mode == null ? "toggle" : mode.trim().toLowerCase();
            boolean next;
            if ("on".equals(m) || "true".equals(m) || "1".equals(m)) next = true;
            else if ("off".equals(m) || "false".equals(m) || "0".equals(m)) next = false;
            else next = !sTorchOn;

            cm.setTorchMode(camId, next);
            sTorchOn = next;
            return "Flashlight " + (next ? "ON" : "OFF");
        } catch (Exception e) {
            return "Flashlight error: " + e.getMessage();
        }
    }

    public static void postNotification(Context context, String title, String message) {
        if (context == null) return;
        try {
            NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationChannel ch = new NotificationChannel(
                        AUTOMATION_CHANNEL_ID,
                        "Konsole OS Automation",
                        NotificationManager.IMPORTANCE_DEFAULT);
                nm.createNotificationChannel(ch);
            }
            NotificationCompat.Builder b = new NotificationCompat.Builder(context, AUTOMATION_CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle(title != null && !title.isEmpty() ? title : "Konsole OS Macro")
                    .setContentText(message != null ? message : "")
                    .setStyle(new NotificationCompat.BigTextStyle().bigText(message != null ? message : ""))
                    .setAutoCancel(true);
            nm.notify((int) (System.currentTimeMillis() & 0x7FFFFFFF), b.build());
        } catch (Exception e) {
            Log.w(TAG, "Notification error: " + e.getMessage());
        }
    }

    public static void showToast(Context context, String message) {
        if (context == null || message == null) return;
        new Handler(Looper.getMainLooper()).post(() ->
                Toast.makeText(context.getApplicationContext(), message, Toast.LENGTH_SHORT).show());
    }

    public static void triggerVibrate(Context context, String patternOrMs) {
        if (context == null) return;
        try {
            Vibrator v = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
            if (v == null || !v.hasVibrator()) return;
            String raw = (patternOrMs == null || patternOrMs.trim().isEmpty()) ? "250" : patternOrMs.trim();
            if (raw.contains(",")) {
                String[] parts = raw.split(",");
                long[] pattern = new long[parts.length];
                for (int i = 0; i < parts.length; i++) {
                    pattern[i] = Math.max(0L, Long.parseLong(parts[i].trim()));
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    v.vibrate(VibrationEffect.createWaveform(pattern, -1));
                } else {
                    v.vibrate(pattern, -1);
                }
            } else {
                long ms = Math.max(30L, Math.min(5000L, Long.parseLong(raw)));
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE));
                } else {
                    v.vibrate(ms);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Vibrate error: " + e.getMessage());
        }
    }

    public static boolean launchApp(Context context, String query) {
        if (context == null || query == null || query.trim().isEmpty()) return false;
        PackageManager pm = context.getPackageManager();
        String target = query.trim();
        try {
            Intent direct = pm.getLaunchIntentForPackage(target);
            if (direct != null) {
                direct.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(direct);
                return true;
            }
            List<ApplicationInfo> apps = pm.getInstalledApplications(PackageManager.GET_META_DATA);
            for (ApplicationInfo app : apps) {
                Intent launch = pm.getLaunchIntentForPackage(app.packageName);
                if (launch != null) {
                    String label = pm.getApplicationLabel(app).toString();
                    if (label.equalsIgnoreCase(target) || label.toLowerCase().contains(target.toLowerCase())) {
                        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        context.startActivity(launch);
                        return true;
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Launch app failed: " + e.getMessage());
        }
        return false;
    }

    public static boolean openIntentUrl(Context context, String url) {
        if (context == null || url == null || url.trim().isEmpty()) return false;
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url.trim()));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean dispatchTap(String xStr, String yStr) {
        try {
            float x = Float.parseFloat(xStr.trim());
            float y = Float.parseFloat(yStr.trim());
            return KonsoleAutomationAccessibilityService.performTap(x, y);
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean dispatchSwipe(String startPair, String endPair, String durationMsStr) {
        try {
            String[] s = startPair.split(",");
            String[] e = endPair.split(",");
            float x1 = Float.parseFloat(s[0].trim());
            float y1 = Float.parseFloat(s[1].trim());
            float x2 = Float.parseFloat(e[0].trim());
            float y2 = Float.parseFloat(e[1].trim());
            long dur = (durationMsStr != null && !durationMsStr.trim().isEmpty())
                    ? Long.parseLong(durationMsStr.trim()) : 300L;
            return KonsoleAutomationAccessibilityService.performSwipe(x1, y1, x2, y2, dur);
        } catch (Exception ex) {
            return false;
        }
    }

    private static boolean isSafeIdentifier(String key) {
        return key != null && key.matches("^[A-Za-z_][A-Za-z0-9_]*$");
    }

    private static String quotePosix(String val) {
        if (val == null) return "''";
        return "'" + val.replace("'", "'\"'\"'") + "'";
    }
}
