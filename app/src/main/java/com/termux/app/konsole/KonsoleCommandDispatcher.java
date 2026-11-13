package com.termux.app.konsole;

import android.annotation.SuppressLint;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.WallpaperManager;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.hardware.camera2.CameraManager;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.net.Uri;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.ContactsContract;
import android.provider.Settings;
import android.util.Log;
import android.view.KeyEvent;
import android.widget.Toast;

import androidx.core.app.NotificationCompat;
import androidx.core.content.FileProvider;

import com.termux.app.TermuxActivity;
import com.termux.shared.termux.TermuxConstants;
import com.termux.terminal.TerminalSession;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Modular Command Dispatcher for Konsole OS.
 * Intercepts terminal commands, expands variables ($konsole, system telemetry),
 * resolves aliases, routes built-in OS commands, launches apps, and provides native Android controls.
 */
public class KonsoleCommandDispatcher {

    private static final String TAG = "KonsoleCommandDispatcher";
    private static final String NOTIF_CHANNEL_ID = "konsole_os_notifications";

    @SuppressLint("StaticFieldLeak")
    private static KonsoleCommandDispatcher sInstance;

    public interface CommandSink {
        void write(String text);
        void finish(int exitCode);
    }

    private static final class BridgeState {
        final CommandSink sink;
        boolean deferred = false;
        BridgeState(CommandSink sink) {
            this.sink = sink;
        }
    }

    private final ThreadLocal<BridgeState> mBridgeState = new ThreadLocal<>();

    private KonsoleCommandDispatcher() {
    }

    public static synchronized KonsoleCommandDispatcher getInstance() {
        if (sInstance == null) {
            sInstance = new KonsoleCommandDispatcher();
        }
        return sInstance;
    }

    private BridgeState markAsyncDeferred() {
        BridgeState state = mBridgeState.get();
        if (state != null) {
            state.deferred = true;
        }
        return state;
    }

    private void completeAsync(BridgeState state, int exitCode) {
        if (state != null && state.sink != null) {
            state.sink.finish(exitCode);
        }
    }

    /**
     * Executes a command dispatched from Fish shell (via KonsoleFishBridge / konsole-exec).
     */
    public void executeBridgeCommand(Context context, String rawInput, CommandSink sink) {
        BridgeState state = new BridgeState(sink);
        mBridgeState.set(state);
        try {
            boolean handled = handleCommand(context, null, rawInput);
            if (!state.deferred) {
                sink.finish(handled ? 0 : 127);
            }
        } catch (Exception e) {
            sink.write("\033[1;31m[Konsole OS Error]\033[0m " + e.getMessage() + "\n");
            if (!state.deferred) {
                sink.finish(1);
            }
        } finally {
            mBridgeState.remove();
        }
    }

    /**
     * Called by Fish's fish_command_not_found event handler when in konsole mode.
     * Attempts to launch matching installed Android apps; otherwise provides typo/package hints.
     */
    public void tryLaunchOrSuggest(Context context, String rawInput, CommandSink sink) {
        BridgeState state = new BridgeState(sink);
        mBridgeState.set(state);
        try {
            if (rawInput == null || rawInput.trim().isEmpty()) {
                sink.finish(127);
                return;
            }
            String trimmed = rawInput.trim();
            if ("konsole".equalsIgnoreCase(KonsoleConfigManager.getInstance().getMode())) {
                if (tryLaunchAppByLabel(context, null, trimmed)) {
                    sink.finish(0);
                    return;
                }
            }
            String firstWord = trimmed.split("\\s+")[0];
            String suggestion = KonsoleFourTierResolver.getInstance().suggestCommand(firstWord);
            if (suggestion != null) {
                sink.write("\033[1;33m[Konsole OS]\033[0m Unknown command '\033[1;31m" + firstWord
                        + "\033[0m'. Did you mean '\033[1;36m" + suggestion + "\033[0m'?\n");
            }
            sink.finish(127);
        } catch (Exception e) {
            sink.finish(127);
        } finally {
            mBridgeState.remove();
        }
    }

    /**
     * Attempts to handle input line.
     * @return true if command was intercepted and handled natively; false to pass to native PTY.
     */
    public boolean handleCommand(Context context, TerminalSession session, String rawInput) {
        if (rawInput == null) return false;
        String line = rawInput.trim();
        if (line.isEmpty()) return false;

        KonsoleConfigManager configMgr = KonsoleConfigManager.getInstance();
        String mode = configMgr.getMode();

        // 1. Mode Check: If in termux mode, only allow 'kons' commands
        if ("termux".equalsIgnoreCase(mode)) {
            if (line.equals("kons switch konsole")) {
                configMgr.setBehaviorMode("konsole");
                KonsoleFishBridge.syncStateFiles(context);
                writeToTerminal(session, "\r\n\033[1;32m[Konsole OS]\033[0m Switched to \033[1;36mKonsole OS\033[0m launcher mode.\r\n");
                return true;
            }
            if (!line.toLowerCase().startsWith("kons ") && !line.equalsIgnoreCase("kons")) {
                return false;
            }
        }

        // Sudo Lockdown Interceptor
        if (configMgr.isSudoLocked()) {
            if (line.startsWith("lock --sudo-reset ")) {
                String hashInput = line.substring("lock --sudo-reset ".length()).trim();
                if (configMgr.resetSudoPasswordWithHash(hashInput)) {
                    stopKioskLockdown(context);
                    writeToTerminal(session, "\r\n\033[1;32m[Konsole OS Sudo]\033[0m Sudo password reset successfully! Screen unlocked.\r\n");
                } else {
                    writeToTerminal(session, "\r\n\033[1;31m[LOCKED]\033[0m Invalid rescue hash.\r\n");
                }
                return true;
            } else if (configMgr.verifySudoPassword(line)) {
                configMgr.setSudoLocked(false);
                stopKioskLockdown(context);
                writeToTerminal(session, "\r\n\033[1;32m[Konsole OS Sudo]\033[0m Password verified! Konsole OS unlocked.\r\n");
                return true;
            } else {
                writeToTerminal(session, "\r\n\033[1;31m[LOCKED - KONSOLE OS SUDO LOCK]\033[0m Enter password or run 'lock --sudo-reset <rescue-hash>':\r\n");
                return true;
            }
        }

        // Add input to Fish-shell auto-suggestion history
        KonsoleGhostTextRenderer.getInstance().addHistory(line);

        // 2. Variable & Shortcut Expansion
        line = expandVariables(line);

        // 3. Alias Resolution
        line = resolveAlias(line);

        // 4. Command Routing
        String[] parts = line.split("\\s+");
        String cmd = parts[0].toLowerCase();

        // Check 'kons' management suite
        if (cmd.equals("kons")) {
            return handleKonsSuite(context, session, line, parts);
        }

        // Check hardware & OS system commands
        switch (cmd) {
            case "bluetooth":
            case "bt":
                return handleBluetooth(context, session, parts);

            case "wifi":
                return handleWifi(context, session, parts);

            case "music":
            case "media":
                return handleMusic(context, session, parts);

            case "call":
                return handleCall(context, session, parts);

            case "contact":
            case "contacts":
                return handleContacts(context, session, parts);

            case "volume":
            case "vol":
                return handleVolume(context, session, parts);

            case "calc":
            case "eval":
                return handleCalc(session, line);

            case "flash":
            case "flashlight":
                return handleFlash(context, session, parts);

            case "vibrate":
                return handleVibrate(context, session, parts);

            case "beep":
                return handleBeep(session, parts);

            case "brightness":
                return handleBrightness(context, session, parts);

            case "dnd":
                return handleDnd(context, session, parts);

            case "wallpaper":
                return handleWallpaper(context, session, parts);

            case "lock":
                return handleLock(context, session, line, parts);

            case "battery":
                return handleBattery(context, session);

            case "clear":
            case "home":
                return handleClearHome(session);

            case "launch":
                return handleLaunch(context, session, line, parts);

            case "uninstall":
                return handleUninstall(context, session, parts);

            case "settings":
                return handleSettings(context, session, parts);

            case "dashboard":
            case "dash":
                return openDashboardActivity(context, session);

            case "macro":
            case "auto-trigger":
            case "macrolab":
                return dispatchMacroCommand(context, session, line, parts);

            default:
                // Check if line matches an installed Android launchable application label!
                if (tryLaunchAppByLabel(context, session, line)) {
                    return true;
                }
                break;
        }

        // 5. Fallback to native PTY shell
        return false;
    }

    public String expandVariables(String line) {
        if (line == null) return "";

        // Expand $konsole
        line = line.replace("$konsole", KonsoleConfigManager.KONSOLE_ROOT_PATH);

        // Expand live system variables
        Map<String, String> liveVars = KonsoleTelemetryManager.getInstance().getLiveVariables();
        for (Map.Entry<String, String> entry : liveVars.entrySet()) {
            line = line.replace("$" + entry.getKey(), entry.getValue());
        }

        // Expand custom user variables
        Map<String, String> userVars = KonsoleConfigManager.getInstance().getUserVariables();
        for (Map.Entry<String, String> entry : userVars.entrySet()) {
            line = line.replace("$" + entry.getKey(), entry.getValue());
        }

        return line;
    }

    private String resolveAlias(String line) {
        Map<String, String> aliases = KonsoleConfigManager.getInstance().getUserAliases();
        String[] parts = line.split("\\s+", 2);
        String firstWord = parts[0];
        if (aliases.containsKey(firstWord)) {
            String replacement = aliases.get(firstWord);
            if (parts.length > 1) {
                return replacement + " " + parts[1];
            } else {
                return replacement;
            }
        }
        return line;
    }

    private boolean handleKonsSuite(Context context, TerminalSession session, String line, String[] parts) {
        if (parts.length < 2) {
            writeToTerminal(session, "\r\nUsage: kons <dashboard|macro|apps|palette|search|install|run|remove|list|sys|hud|set|notify|toast|alias|setup-fish|update|version|about|help|switch>\r\n");
            return true;
        }

        String sub = parts[1].toLowerCase();
        switch (sub) {
            case "apps":
                Context uiCtx = KonsoleFishBridge.getInstance().getUiContext(context);
                if (uiCtx instanceof TermuxActivity) {
                    ((TermuxActivity) uiCtx).runOnUiThread(() -> {
                        KonsoleAppsDialog dialog = new KonsoleAppsDialog(uiCtx, (appName, pkg) -> {
                            if (session != null) {
                                writeToTerminal(session, "\r\n\033[1;32m[Konsole OS]\033[0m Launched \033[1;36m" + appName + "\033[0m (" + pkg + ")\r\n");
                            }
                        });
                        dialog.show();
                    });
                } else {
                    writeToTerminal(session, "\r\n\033[1;33m[Konsole OS]\033[0m Open Termux window to display Apps Drawer.\r\n");
                }
                return true;

            case "set":
                if (parts.length < 3 || !parts[2].contains("=")) {
                    writeToTerminal(session, "\r\nUsage: kons set <key=value>\r\n");
                } else {
                    String[] kv = parts[2].split("=", 2);
                    KonsoleConfigManager.getInstance().setUserVariable(kv[0].trim(), kv[1].trim());
                    KonsoleFishBridge.syncStateFiles(context);
                    writeToTerminal(session, "\r\n\033[1;32m[Konsole OS]\033[0m Variable \033[1;33m$" + kv[0].trim() + "\033[0m set to: " + kv[1].trim() + "\r\n");
                }
                return true;

            case "notify":
                if (parts.length < 4) {
                    writeToTerminal(session, "\r\nUsage: kons notify <title> <message>\r\n");
                } else {
                    String title = parts[2];
                    StringBuilder msg = new StringBuilder();
                    for (int i = 3; i < parts.length; i++) {
                        msg.append(parts[i]).append(" ");
                    }
                    sendNotification(context, title, msg.toString().trim());
                    writeToTerminal(session, "\r\n\033[1;32m[Konsole OS]\033[0m Notification dispatched.\r\n");
                }
                return true;

            case "toast":
                if (parts.length < 3) {
                    writeToTerminal(session, "\r\nUsage: kons toast <message>\r\n");
                } else {
                    StringBuilder msg = new StringBuilder();
                    for (int i = 2; i < parts.length; i++) {
                        msg.append(parts[i]).append(" ");
                    }
                    showToast(context, msg.toString().trim());
                    writeToTerminal(session, "\r\n\033[1;32m[Konsole OS]\033[0m Toast shown.\r\n");
                }
                return true;

            case "alias":
                if (parts.length < 3 || !parts[2].contains("=")) {
                    writeToTerminal(session, "\r\nUsage: kons alias <name=command-value>\r\n");
                } else {
                    String[] kv = parts[2].split("=", 2);
                    KonsoleConfigManager.getInstance().setAlias(kv[0].trim(), kv[1].trim());
                    writeToTerminal(session, "\r\n\033[1;32m[Konsole OS]\033[0m Alias \033[1;33m" + kv[0].trim() + "\033[0m created for: " + kv[1].trim() + "\r\n");
                }
                return true;

            case "list":
                writeToTerminal(session, "\r\n\033[1;36m====================================================\033[0m\r\n" +
                        "\033[1;32m       INSTALLED KONSOLE OS PACKAGES & PLUGINS      \033[0m\r\n" +
                        "\033[1;36m====================================================\033[0m\r\n");

                File installedDir = new File(KonsoleConfigManager.INSTALLED_PACKAGES_PATH);
                File[] manifestFiles = installedDir.listFiles();
                boolean foundAny = false;
                if (manifestFiles != null && manifestFiles.length > 0) {
                    for (File f : manifestFiles) {
                        if (f.getName().endsWith(".json")) {
                            try (BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
                                StringBuilder sb = new StringBuilder();
                                String lineStr;
                                while ((lineStr = br.readLine()) != null) sb.append(lineStr);

                                JSONObject json = new JSONObject(sb.toString());
                                String name = json.optString("name", f.getName().replace(".json", ""));
                                String ver = json.optString("version", "1.0");
                                String type = json.optString("type", "pkg").toUpperCase();
                                String desc = json.optString("description", "");

                                writeToTerminal(session, " \033[1;33m" + name + "\033[0m (v" + ver + ") [\033[1;36m" + type + "\033[0m] - " + desc + "\r\n");
                                foundAny = true;
                            } catch (Exception ignored) {}
                        }
                    }
                }

                File binDir = new File(KonsoleConfigManager.BIN_PATH);
                File[] binFiles = binDir.listFiles();
                if (binFiles != null && binFiles.length > 0) {
                    for (File f : binFiles) {
                        File manifest = new File(installedDir, f.getName().toLowerCase() + ".json");
                        if (!manifest.exists()) {
                            writeToTerminal(session, " \033[1;33m" + f.getName() + "\033[0m [\033[1;32mEXECUTABLE\033[0m] - $konsole/bin/" + f.getName() + "\r\n");
                            foundAny = true;
                        }
                    }
                }

                if (!foundAny) {
                    writeToTerminal(session, " (No custom packages or plugins installed)\r\n");
                }
                writeToTerminal(session, "\033[1;36m====================================================\033[0m\r\n");
                return true;

            case "install":
            case "update":
                if (parts.length == 2 && sub.equals("update")) {
                    // Check OTA update for Konsole OS
                    performOtaCheck(context, session);
                    return true;
                }
                if (parts.length < 3) {
                    writeToTerminal(session, "\r\nUsage: kons install|update <package-name>\r\n");
                } else {
                    installKonsPackage(context, session, parts[2]);
                }
                return true;

            case "run":
                if (parts.length < 3) {
                    writeToTerminal(session, "\r\nUsage: kons run <package-or-command> [args...]\r\n");
                } else {
                    runInstalledPackage(context, session, parts);
                }
                return true;

            case "search":
                String searchQuery = parts.length >= 3 ? line.substring(line.indexOf(parts[1]) + parts[1].length()).trim() : "";
                searchKonsPackages(session, searchQuery);
                return true;

            case "remove":
            case "rm":
                if (parts.length < 3) {
                    writeToTerminal(session, "\r\nUsage: kons remove <package-name>\r\n");
                } else {
                    removeKonsPackage(context, session, parts[2]);
                }
                return true;

            case "sys":
            case "status":
                printSystemTelemetryCard(session);
                return true;

            case "hud":
                if (parts.length >= 3 && (parts[2].equalsIgnoreCase("on") || parts[2].equalsIgnoreCase("off"))) {
                    boolean showHud = parts[2].equalsIgnoreCase("on");
                    KonsoleConfigManager.getInstance().setUiProperty("statusBarVisible", String.valueOf(showHud));
                    Context hudCtx = KonsoleFishBridge.getInstance().getUiContext(context);
                    if (hudCtx instanceof TermuxActivity) {
                        ((TermuxActivity) hudCtx).runOnUiThread(((TermuxActivity) hudCtx)::refreshKonsoleHudVisibility);
                    }
                    writeToTerminal(session, "\r\n\033[1;32m[Konsole OS]\033[0m Telemetry HUD bar turned " + (showHud ? "\033[1;32mON\033[0m" : "\033[1;33mOFF\033[0m") + ".\r\n");
                } else {
                    writeToTerminal(session, "\r\nUsage: kons hud <on|off>\r\n");
                }
                return true;

            case "palette":
                Context palCtx = KonsoleFishBridge.getInstance().getUiContext(context);
                if (palCtx instanceof TermuxActivity) {
                    ((TermuxActivity) palCtx).runOnUiThread(((TermuxActivity) palCtx)::showCommandPaletteDialog);
                }
                return true;

            case "dash":
            case "dashboard":
                return openDashboardActivity(context, session);

            case "macro":
            case "macros":
            case "macrolab": {
                if (parts.length < 3 || "lab".equalsIgnoreCase(parts[2])) {
                    return openMacroLabActivity(context, session);
                }
                StringBuilder args = new StringBuilder();
                for (int i = 2; i < parts.length; i++) {
                    if (args.length() > 0) args.append(' ');
                    args.append(parts[i]);
                }
                return runMacroCliToSession(context, session, args.toString());
            }

            case "setup-fish":
            case "fish":
                installAndConfigureFishShell(context, session);
                return true;

            case "version":
            case "about":
                writeToTerminal(session, "\r\n\033[1;36m====================================================\033[0m\r\n" +
                        "\033[1;32m  Konsole OS - Fish-Powered Terminal OS Environment\033[0m\r\n" +
                        "\033[1;33m  Version:\033[0m v1.0.0-35mos (Build 118)\r\n" +
                        "\033[1;33m  Package:\033[0m " + TermuxConstants.TERMUX_PACKAGE_NAME + "\r\n" +
                        "\033[1;33m  Shell  :\033[0m Fish + Termux Prefix (" + TermuxConstants.TERMUX_PREFIX_DIR_PATH + ")\r\n" +
                        "\033[1;33m  Author :\033[0m hunter-zen-RU\r\n" +
                        "\033[1;36m====================================================\033[0m\r\n");
                return true;

            case "help":
                printHelpManual(session);
                return true;

            case "switch":
                if (parts.length >= 3) {
                    String target = parts[2].toLowerCase();
                    if (target.equals("termux") || target.equals("konsole")) {
                        KonsoleConfigManager.getInstance().setBehaviorMode(target);
                        KonsoleFishBridge.syncStateFiles(context);
                        Context swCtx = KonsoleFishBridge.getInstance().getUiContext(context);
                        if (swCtx instanceof TermuxActivity) {
                            ((TermuxActivity) swCtx).runOnUiThread(() -> ((TermuxActivity) swCtx).refreshKonsoleHudVisibility());
                        }
                        writeToTerminal(session, "\r\n\033[1;32m[Konsole OS]\033[0m Switched mode to: \033[1;36m" + target + "\033[0m\r\n");
                    } else {
                        writeToTerminal(session, "\r\nUsage: kons switch <termux|konsole>\r\n");
                    }
                } else {
                    writeToTerminal(session, "\r\nUsage: kons switch <termux|konsole>\r\n");
                }
                return true;

            default:
                writeToTerminal(session, "\r\nUnknown kons command: " + sub + ". Type 'kons help' for assistance.\r\n");
                return true;
        }
    }

    private void performOtaCheck(Context context, TerminalSession session) {
        final BridgeState bridgeState = markAsyncDeferred();
        writeToTerminal(session, "\r\n\033[1;36m[OTA Update]\033[0m Checking latest Konsole OS releases from GitHub...\r\n");
        new Thread(() -> {
            HttpURLConnection conn = null;
            int code = 0;
            try {
                URL url = new URL("https://api.github.com/repos/hunter-zen-RU/Konsole-OS/releases/latest");
                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestProperty("User-Agent", "KonsoleOS-Android");
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(5000);
                if (conn.getResponseCode() == 200) {
                    String body;
                    try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                        StringBuilder jsonSb = new StringBuilder();
                        String l;
                        while ((l = br.readLine()) != null) jsonSb.append(l);
                        body = jsonSb.toString();
                    }

                    JSONObject json = new JSONObject(body);
                    String tag = json.optString("tag_name", "v1.0.0");
                    writeToSinkOrSession(bridgeState, session, "\r\n\033[1;32m[OTA Update]\033[0m Latest Release Tag: \033[1;33m" + tag + "\033[0m\r\n");

                    JSONArray assets = json.optJSONArray("assets");
                    String downloadUrl = null;
                    String targetAbi = Build.SUPPORTED_ABIS[0]; // e.g. arm64-v8a
                    if (assets != null) {
                        for (int i = 0; i < assets.length(); i++) {
                            JSONObject asset = assets.getJSONObject(i);
                            String name = asset.optString("name", "");
                            if (name.contains(targetAbi) || name.contains("universal") || name.endsWith(".apk")) {
                                downloadUrl = asset.optString("browser_download_url");
                                break;
                            }
                        }
                    }

                    if (downloadUrl != null && !downloadUrl.isEmpty()) {
                        writeToSinkOrSession(bridgeState, session, "\033[1;32m[OTA Update]\033[0m Downloading update APK for " + targetAbi + "...\r\n");
                        File updateApk = new File(context.getCacheDir(), "konsole_update.apk");
                        URL apkUrl = new URL(downloadUrl);
                        try (InputStream is = apkUrl.openStream();
                             FileOutputStream fos = new FileOutputStream(updateApk)) {
                            byte[] buf = new byte[8192];
                            int read;
                            while ((read = is.read(buf)) != -1) fos.write(buf, 0, read);
                        }

                        writeToSinkOrSession(bridgeState, session, "\033[1;32m[OTA Update]\033[0m Launching Package Installer...\r\n");
                        launchApkInstaller(context, updateApk);
                    } else {
                        writeToSinkOrSession(bridgeState, session, "\033[1;33m[OTA Update]\033[0m You are currently running the latest build!\r\n");
                    }
                } else {
                    code = 1;
                    writeToSinkOrSession(bridgeState, session, "\033[1;31m[OTA Update]\033[0m Release endpoint returned code: " + conn.getResponseCode() + "\r\n");
                }
            } catch (Exception e) {
                code = 1;
                writeToSinkOrSession(bridgeState, session, "\033[1;31m[OTA Update]\033[0m Failed checking update: " + e.getMessage() + "\r\n");
            } finally {
                if (conn != null) conn.disconnect();
                completeAsync(bridgeState, code);
            }
        }).start();
    }

    private void launchApkInstaller(Context context, File apkFile) {
        Intent installIntent = new Intent(Intent.ACTION_VIEW);
        Uri apkUri = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N
                ? FileProvider.getUriForFile(context, context.getPackageName() + ".fileprovider", apkFile)
                : Uri.fromFile(apkFile);
        installIntent.setDataAndType(apkUri, "application/vnd.android.package-archive");
        installIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
        context.startActivity(installIntent);
    }

    public static final String[] PACKAGE_ENDPOINTS = new String[]{
            "https://hunterzen-ru.github.io/Konsole-package/packges.json",
            "https://hunter-zen-ru.github.io/Konsole-package/packges.json",
            "https://raw.githubusercontent.com/hunter-zen-RU/Konsole-packages/main/packages.json",
            "https://hunterzen-ru.github.io/TUI/packages/packages.json"
    };

    private String httpGetString(String endpoint) throws java.io.IOException {
        HttpURLConnection conn = null;
        try {
            conn = openConnectionWithRedirects(endpoint);
            if (conn.getResponseCode() == HttpURLConnection.HTTP_OK) {
                try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                    StringBuilder sb = new StringBuilder();
                    String l;
                    while ((l = br.readLine()) != null) sb.append(l);
                    return sb.toString();
                }
            }
        } finally {
            if (conn != null) conn.disconnect();
        }
        return null;
    }

    public static List<JSONObject> parsePackagesFromJson(String jsonBody) {
        List<JSONObject> results = new ArrayList<>();
        if (jsonBody == null) return results;
        String trimmed = jsonBody.trim();
        if (trimmed.isEmpty()) return results;
        try {
            if (trimmed.startsWith("[")) {
                JSONArray arr = new JSONArray(trimmed);
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject obj = arr.optJSONObject(i);
                    if (obj != null) results.add(obj);
                }
            } else {
                JSONObject root = new JSONObject(trimmed);
                JSONArray packagesArray = root.optJSONArray("packages");
                if (packagesArray != null) {
                    for (int i = 0; i < packagesArray.length(); i++) {
                        JSONObject obj = packagesArray.optJSONObject(i);
                        if (obj != null) results.add(obj);
                    }
                } else {
                    java.util.Iterator<String> keys = root.keys();
                    while (keys.hasNext()) {
                        String key = keys.next();
                        JSONObject obj = root.optJSONObject(key);
                        if (obj != null) {
                            if (!obj.has("name")) obj.put("name", key);
                            results.add(obj);
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return results;
    }

    private Map<String, JSONObject> fetchPackageCatalog() {
        Map<String, JSONObject> catalog = new LinkedHashMap<>();
        for (String endpoint : PACKAGE_ENDPOINTS) {
            try {
                String body = httpGetString(endpoint);
                if (body != null) {
                    List<JSONObject> parsed = parsePackagesFromJson(body);
                    for (JSONObject pkgObj : parsed) {
                        String name = pkgObj.optString("name", "").trim();
                        if (!name.isEmpty()) {
                            String key = name.toLowerCase();
                            if (!catalog.containsKey(key)) {
                                catalog.put(key, pkgObj);
                            }
                        }
                    }
                }
            } catch (Exception ignored) {
            }
        }
        return catalog;
    }

    private void searchKonsPackages(TerminalSession session, String query) {
        final BridgeState bridgeState = markAsyncDeferred();
        writeToTerminal(session, "\r\n\033[1;36m[Kons Package Manager]\033[0m Querying remote repositories"
                + (query.isEmpty() ? "" : " for '" + query + "'") + "...\r\n");
        new Thread(() -> {
            int code = 0;
            try {
                Map<String, JSONObject> catalog = fetchPackageCatalog();
                if (catalog.isEmpty()) {
                    writeToSinkOrSession(bridgeState, session, "\r\n\033[1;31m[Kons Package Manager]\033[0m No reachable repositories or empty package catalog.\r\n");
                    code = 1;
                    return;
                }
                String q = query.toLowerCase();
                int matches = 0;
                StringBuilder out = new StringBuilder();
                out.append("\r\n\033[1;36m====================================================\033[0m\r\n");
                for (JSONObject p : catalog.values()) {
                    String name = p.optString("name", "");
                    String desc = p.optString("description", "");
                    String type = p.optString("type", "script").toUpperCase();
                    String ver = p.optString("version", "1.0");
                    if (q.isEmpty() || name.toLowerCase().contains(q) || desc.toLowerCase().contains(q)) {
                        boolean installed = new File(KonsoleConfigManager.INSTALLED_PACKAGES_PATH, name.toLowerCase() + ".json").exists()
                                || new File(TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH, name).exists()
                                || new File(KonsoleConfigManager.BIN_PATH, name).exists();
                        out.append(" \033[1;33m").append(name).append("\033[0m (v").append(ver).append(") [\033[1;36m").append(type).append("\033[0m]");
                        if (installed) out.append(" \033[1;32m[INSTALLED]\033[0m");
                        if (!desc.isEmpty()) out.append("\r\n   ").append(desc);
                        out.append("\r\n");
                        matches++;
                    }
                }
                if (matches == 0) {
                    out.append(" (No packages matched '").append(query).append("')\r\n");
                }
                out.append("\033[1;36m====================================================\033[0m\r\n");
                writeToSinkOrSession(bridgeState, session, out.toString());
            } finally {
                completeAsync(bridgeState, code);
            }
        }).start();
    }

    private void removeKonsPackage(Context context, TerminalSession session, String pkgName) {
        String key = pkgName.trim().toLowerCase();
        boolean removed = false;
        String installedType = "";
        File manifest = new File(KonsoleConfigManager.INSTALLED_PACKAGES_PATH, key + ".json");
        if (manifest.isFile()) {
            try (BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(manifest), StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                String l;
                while ((l = br.readLine()) != null) sb.append(l);
                installedType = new JSONObject(sb.toString()).optString("type", "").toLowerCase();
            } catch (Exception ignored) {}
        }

        if ("deb".equals(installedType)) {
            File dpkgBin = new File(TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH, "dpkg");
            if (dpkgBin.canExecute()) {
                int rc = runPrefixProcess(context, null, session, new String[]{dpkgBin.getAbsolutePath(), "-r", pkgName});
                if (rc == 0) removed = true;
            }
        }

        if (manifest.exists() && manifest.delete()) removed = true;
        File prefixBin = new File(TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH, pkgName);
        if (prefixBin.exists() && prefixBin.delete()) removed = true;
        File prefixBinLower = new File(TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH, key);
        if (prefixBinLower.exists() && prefixBinLower.delete()) removed = true;
        File bin = new File(KonsoleConfigManager.BIN_PATH, pkgName);
        if (bin.exists() && bin.delete()) removed = true;
        File binLower = new File(KonsoleConfigManager.BIN_PATH, key);
        if (binLower.exists() && binLower.delete()) removed = true;
        File kos = new File(KonsoleConfigManager.PLUGINS_PATH, pkgName + ".kos");
        if (kos.exists() && kos.delete()) removed = true;
        File deb = new File(KonsoleConfigManager.DEB_PATH, pkgName + ".deb");
        if (deb.exists() && deb.delete()) removed = true;

        if (removed) {
            KonsolePathIndexer.getInstance().refreshAsync();
            writeToTerminal(session, "\r\n\033[1;32m[Kons Package Manager]\033[0m Removed package '\033[1;36m" + pkgName + "\033[0m'.\r\n");
        } else {
            writeToTerminal(session, "\r\n\033[1;33m[Kons Package Manager]\033[0m Package '" + pkgName + "' is not installed.\r\n");
        }
    }

    private void printSystemTelemetryCard(TerminalSession session) {
        Map<String, String> v = KonsoleTelemetryManager.getInstance().getLiveVariables();
        String card = "\r\n\033[1;36m====================================================\033[0m\r\n" +
                "\033[1;32m            KONSOLE OS LIVE SYSTEM TELEMETRY         \033[0m\r\n" +
                "\033[1;36m====================================================\033[0m\r\n" +
                " \033[1;33mDevice :\033[0m " + v.getOrDefault("device_name", Build.MODEL) + "\r\n" +
                " \033[1;33mOS     :\033[0m " + v.getOrDefault("android_version", "Android") + "\r\n" +
                " \033[1;33mCPU    :\033[0m " + v.getOrDefault("cpu_cores", "-") + " cores (" + v.getOrDefault("cpu_usage", "-") + ")\r\n" +
                " \033[1;33mRAM    :\033[0m " + v.getOrDefault("ram_used", "-") + " / " + v.getOrDefault("ram_total", "-") + " (" + v.getOrDefault("ram_free", "-") + " free)\r\n" +
                " \033[1;33mDisk   :\033[0m " + v.getOrDefault("storage_free", "-") + " free\r\n" +
                " \033[1;33mBattery:\033[0m " + v.getOrDefault("battery", "-") + "\r\n" +
                " \033[1;33mNetwork:\033[0m " + v.getOrDefault("network_type", "-") + " (" + v.getOrDefault("wifi_ssid", "-") + ") | " + v.getOrDefault("net_speed", "-") + " | IP: " + v.getOrDefault("ip_address", "-") + "\r\n" +
                "\033[1;36m====================================================\033[0m\r\n";
        writeToTerminal(session, card);
    }

    private void installKonsPackage(Context context, TerminalSession session, String pkgName) {
        final BridgeState bridgeState = markAsyncDeferred();
        writeToTerminal(session, "\r\n\033[1;36m[Kons Package Manager]\033[0m Fetching index for '" + pkgName + "'...\r\n");
        new Thread(() -> {
            int code = 0;
            try {
                java.util.Set<String> visited = new java.util.HashSet<>();
                code = installPackageInternal(context, bridgeState, session, pkgName, visited);
            } finally {
                completeAsync(bridgeState, code);
            }
        }).start();
    }

    private int installPackageInternal(Context context, BridgeState bridgeState, TerminalSession session, String pkgName, java.util.Set<String> visited) {
        String key = pkgName.trim().toLowerCase();
        if (!visited.add(key)) return 0;

        HttpURLConnection dlConn = null;
        try {
            JSONObject targetPkgObj = null;
            String sourceEndpoint = null;
            for (String endpoint : PACKAGE_ENDPOINTS) {
                try {
                    String body = httpGetString(endpoint);
                    if (body != null) {
                        for (JSONObject pObj : parsePackagesFromJson(body)) {
                            if (pkgName.equalsIgnoreCase(pObj.optString("name", ""))) {
                                targetPkgObj = pObj;
                                sourceEndpoint = endpoint;
                                break;
                            }
                        }
                    }
                } catch (Exception ignored) {}
                if (targetPkgObj != null) break;
            }

            if (targetPkgObj == null) {
                writeToSinkOrSession(bridgeState, session, "\033[1;31m[Kons Package Manager]\033[0m Package '" + pkgName + "' not found in remote repositories. Try '\033[1;36mpkg install " + pkgName + "\033[0m' or '\033[1;36mkons search " + pkgName + "\033[0m'.\r\n");
                return 1;
            }

            String name = targetPkgObj.optString("name", pkgName).trim();
            String version = targetPkgObj.optString("version", "1.0");
            String type = targetPkgObj.optString("type", "script").toLowerCase();
            String description = targetPkgObj.optString("description", "Custom Konsole package");
            List<String> candidateUrls = extractPayloadUrls(targetPkgObj, sourceEndpoint);
            String targetName = targetPkgObj.optString("targetName", targetPkgObj.optString("target", name + "." + type));

            writeToSinkOrSession(bridgeState, session, "\033[1;32m[Kons Package Manager]\033[0m Found '\033[1;36m" + name + "\033[0m' v" + version + " [" + type.toUpperCase() + "]\r\n" +
                    " Description: " + description + "\r\n");

            // Install transitive dependencies if declared
            JSONArray deps = targetPkgObj.optJSONArray("deps");
            if (deps != null) {
                for (int i = 0; i < deps.length(); i++) {
                    String depName = deps.optString(i, "").trim();
                    if (!depName.isEmpty() && !visited.contains(depName.toLowerCase())) {
                        installPackageInternal(context, bridgeState, session, depName, visited);
                    }
                }
            }

            if (candidateUrls.isEmpty()) {
                writeToSinkOrSession(bridgeState, session, "\033[1;31m[Kons Package Manager]\033[0m Package '" + name + "' has no download URL.\r\n");
                return 1;
            }

            File downloadsDir = new File(KonsoleConfigManager.DOWNLOADS_PATH);
            if (!downloadsDir.exists()) downloadsDir.mkdirs();
            File localFile = downloadsDir.isDirectory() && downloadsDir.canWrite()
                    ? new File(downloadsDir, targetName)
                    : new File(context.getCacheDir(), targetName);

            int lastHttpCode = -1;
            String lastErrorMsg = null;
            String successfulUrl = null;
            byte[] downloadedBytes = null;

            for (int idx = 0; idx < candidateUrls.size(); idx++) {
                String candidateUrl = candidateUrls.get(idx);
                writeToSinkOrSession(bridgeState, session, "\033[1;36m[Kons Package Manager]\033[0m Checking URL [" + (idx + 1) + "/" + candidateUrls.size() + "]: " + candidateUrl + "...\r\n");
                HttpURLConnection attemptConn = null;
                try {
                    attemptConn = openConnectionWithRedirects(candidateUrl);
                    int httpCode = attemptConn.getResponseCode();
                    if (httpCode == HttpURLConnection.HTTP_OK) {
                        try (InputStream is = attemptConn.getInputStream()) {
                            downloadedBytes = readStreamBytes(is);
                        }
                        if (downloadedBytes != null && downloadedBytes.length > 0) {
                            successfulUrl = candidateUrl;
                            writeToSinkOrSession(bridgeState, session, "\033[1;32m[Kons Package Manager]\033[0m Verified HTTP 200 OK (" + downloadedBytes.length + " bytes) -> installing from " + candidateUrl + "\r\n");
                            break;
                        } else {
                            lastHttpCode = httpCode;
                            lastErrorMsg = "HTTP 200 returned empty payload from " + candidateUrl;
                            downloadedBytes = null;
                        }
                    } else {
                        lastHttpCode = httpCode;
                        lastErrorMsg = "HTTP " + httpCode + " (" + attemptConn.getResponseMessage() + ") from " + candidateUrl;
                        writeToSinkOrSession(bridgeState, session, "\033[1;33m[Kons Package Manager]\033[0m " + lastErrorMsg + (idx + 1 < candidateUrls.size() ? " (trying backup URL...)" : "") + "\r\n");
                    }
                } catch (Exception ex) {
                    lastErrorMsg = ex.getClass().getSimpleName() + ": " + ex.getMessage() + " (" + candidateUrl + ")";
                    writeToSinkOrSession(bridgeState, session, "\033[1;33m[Kons Package Manager]\033[0m Error: " + lastErrorMsg + (idx + 1 < candidateUrls.size() ? " (trying backup URL...)" : "") + "\r\n");
                } finally {
                    if (attemptConn != null) attemptConn.disconnect();
                }
            }

            if (downloadedBytes == null || successfulUrl == null) {
                String reason = lastHttpCode > 0 ? lastErrorMsg : (lastErrorMsg != null ? lastErrorMsg : "No URL returned HTTP 200");
                writeToSinkOrSession(bridgeState, session, "\033[1;31m[Kons Package Manager]\033[0m Download failed across " + candidateUrls.size() + " URL(s): " + reason + "\r\n");
                return 1;
            }

            try {
                try (FileOutputStream fos = new FileOutputStream(localFile)) {
                    fos.write(downloadedBytes);
                }
            } catch (Exception writeErr) {
                localFile = new File(context.getCacheDir(), targetName);
                try (FileOutputStream fos = new FileOutputStream(localFile)) {
                    fos.write(downloadedBytes);
                }
            }

            String pkgUrl = successfulUrl;

            File prefixBinDir = new File(TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH);
            String installedDest = localFile.getAbsolutePath();
            int exitCode = 0;

            switch (type) {
                case "apk":
                    writeToSinkOrSession(bridgeState, session, "\033[1;32m[Kons Package Manager]\033[0m Launching Android Package Installer for " + targetName + "...\r\n");
                    launchApkInstaller(context, localFile);
                    writeToSinkOrSession(bridgeState, session, "\033[1;32m[Kons Package Manager]\033[0m Package '\033[1;36m" + name + "\033[0m' (APK) saved to $konsole/packages/downloads/ and dispatched to installer!\r\n");
                    break;

                case "deb":
                    File debDir = new File(KonsoleConfigManager.DEB_PATH);
                    if (!debDir.exists()) debDir.mkdirs();
                    if (debDir.isDirectory()) {
                        try {
                            copyFile(localFile, new File(debDir, name + ".deb"));
                        } catch (Exception ignored) {}
                    }

                    File tmpDir = new File(TermuxConstants.TERMUX_TMP_PREFIX_DIR_PATH);
                    if (!tmpDir.exists()) tmpDir.mkdirs();
                    File stagedDeb = tmpDir.isDirectory() ? new File(tmpDir, name + ".deb") : localFile;
                    if (!stagedDeb.equals(localFile)) {
                        copyFile(localFile, stagedDeb);
                    }
                    installedDest = stagedDeb.getAbsolutePath();

                    exitCode = installDebViaDpkg(context, bridgeState, session, name, stagedDeb);
                    KonsolePathIndexer.getInstance().refreshAsync();
                    break;

                case "kos":
                    File pluginsDir = new File(KonsoleConfigManager.PLUGINS_PATH);
                    if (!pluginsDir.exists()) pluginsDir.mkdirs();
                    if (pluginsDir.isDirectory()) {
                        try {
                            File kosFile = new File(pluginsDir, targetName.endsWith(".kos") ? targetName : name + ".kos");
                            copyFile(localFile, kosFile);
                        } catch (Exception ignored) {}
                    }

                    File installedKosBin = installExecutableIntoPrefix(localFile, prefixBinDir, name, "kos");
                    installedDest = installedKosBin.getAbsolutePath();
                    mirrorToExternalBinBestEffort(installedKosBin, name);
                    KonsolePathIndexer.getInstance().refreshAsync();
                    writeToSinkOrSession(bridgeState, session, "\033[1;32m[Kons Package Manager]\033[0m Plugin '\033[1;36m" + name + "\033[0m' installed to \033[1;33m" + installedDest + "\033[0m (on $PATH & executable).\r\n");
                    if (isShellBuiltinName(name)) {
                        writeToSinkOrSession(bridgeState, session, "\033[1;33m[Note]\033[0m '" + name + "' is also a shell builtin. Run it using '\033[1;36mkons run " + name + "\033[0m', '\033[1;36mcommand " + name + "\033[0m', or '\033[1;36m" + installedDest + "\033[0m'.\r\n");
                    }
                    break;

                default: // script / sh / fish / binary
                    File installedScriptBin = installExecutableIntoPrefix(localFile, prefixBinDir, name, type);
                    installedDest = installedScriptBin.getAbsolutePath();
                    mirrorToExternalBinBestEffort(installedScriptBin, name);
                    KonsolePathIndexer.getInstance().refreshAsync();
                    writeToSinkOrSession(bridgeState, session, "\033[1;32m[Kons Package Manager]\033[0m Executable '\033[1;36m" + name + "\033[0m' installed to \033[1;33m" + installedDest + "\033[0m (on $PATH & executable).\r\n");
                    if (isShellBuiltinName(name)) {
                        writeToSinkOrSession(bridgeState, session, "\033[1;33m[Note]\033[0m '" + name + "' is also a shell builtin. Run it using '\033[1;36mkons run " + name + "\033[0m', '\033[1;36mcommand " + name + "\033[0m', or '\033[1;36m" + installedDest + "\033[0m'.\r\n");
                    }
                    break;
            }

            // Save local metadata manifest copy
            File installedDir = new File(KonsoleConfigManager.INSTALLED_PACKAGES_PATH);
            if (!installedDir.exists()) installedDir.mkdirs();
            if (installedDir.isDirectory()) {
                File manifestFile = new File(installedDir, name.toLowerCase() + ".json");
                JSONObject manifest = new JSONObject();
                manifest.put("name", name);
                manifest.put("version", version);
                manifest.put("type", type);
                manifest.put("description", description);
                manifest.put("url", pkgUrl);
                if (candidateUrls.size() > 1) {
                    manifest.put("urls", new JSONArray(candidateUrls));
                }
                manifest.put("targetName", targetName);
                manifest.put("installedAt", System.currentTimeMillis());
                manifest.put("localPath", localFile.getAbsolutePath());
                manifest.put("prefixInstalledPath", installedDest);
                if (deps != null) {
                    manifest.put("deps", deps);
                }
                try (FileOutputStream mFos = new FileOutputStream(manifestFile)) {
                    mFos.write(manifest.toString(2).getBytes(StandardCharsets.UTF_8));
                }
            }
            return exitCode;
        } catch (Exception e) {
            writeToSinkOrSession(bridgeState, session, "\033[1;31m[Kons Package Manager]\033[0m Installation failed: " + e.getMessage() + "\r\n");
            return 1;
        } finally {
            if (dlConn != null) dlConn.disconnect();
        }
    }

    /**
     * Extracts all candidate download URLs from a package JSON entry in priority order.
     * Supports "url" as either a JSONArray or a String, plus "urls", "mirrors", "backup_urls", and "backup_url".
     */
    public static List<String> extractPayloadUrls(JSONObject pkgObj, String sourceEndpoint) {
        java.util.LinkedHashSet<String> urls = new java.util.LinkedHashSet<>();
        if (pkgObj == null) return new ArrayList<>();

        String[] fields = new String[]{"url", "urls", "mirrors", "backup_urls", "backup_url"};
        for (String field : fields) {
            Object val = pkgObj.opt(field);
            if (val instanceof JSONArray) {
                JSONArray arr = (JSONArray) val;
                for (int i = 0; i < arr.length(); i++) {
                    String raw = arr.optString(i, "").trim();
                    addResolvedCandidates(urls, raw, sourceEndpoint);
                }
            } else if (val instanceof String) {
                String raw = ((String) val).trim();
                if (!raw.isEmpty()) {
                    // Also support comma-separated URLs if someone puts multiple http(s) URLs in one string
                    if (raw.matches("(?i)^https?://.*,\\s*https?://.*")) {
                        for (String part : raw.split("\\s*,\\s*")) {
                            addResolvedCandidates(urls, part.trim(), sourceEndpoint);
                        }
                    } else {
                        addResolvedCandidates(urls, raw, sourceEndpoint);
                    }
                }
            }
        }
        return new ArrayList<>(urls);
    }

    private static void addResolvedCandidates(java.util.LinkedHashSet<String> out, String rawUrl, String sourceEndpoint) {
        if (rawUrl == null || rawUrl.isEmpty()) return;
        String resolved = rawUrl;
        if (!resolved.startsWith("http://") && !resolved.startsWith("https://") && sourceEndpoint != null) {
            try {
                resolved = new URL(new URL(sourceEndpoint), resolved).toString();
            } catch (Exception ignored) {}
        }
        out.addAll(buildCandidatePayloadUrls(resolved, sourceEndpoint));
    }

    public static boolean isShellBuiltinName(String name) {
        if (name == null) return false;
        switch (name.trim().toLowerCase()) {
            case "test":
            case "[":
            case "cd":
            case "echo":
            case "pwd":
            case "set":
            case "unset":
            case "true":
            case "false":
            case "kill":
            case "time":
            case "printf":
            case "read":
            case "exec":
            case "eval":
            case "command":
            case "source":
            case "type":
            case "alias":
            case "export":
            case "exit":
            case "shift":
            case "return":
            case "break":
            case "continue":
            case "switch":
            case "status":
            case "string":
            case "math":
            case "random":
            case "functions":
            case "builtin":
                return true;
            default:
                return false;
        }
    }

    private void runInstalledPackage(Context context, TerminalSession session, String[] parts) {
        final BridgeState bridgeState = markAsyncDeferred();
        new Thread(() -> {
            int code = 1;
            try {
                String targetName = parts[2].trim();
                File target = new File(TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH, targetName);
                if (!target.exists()) {
                    File lowerTarget = new File(TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH, targetName.toLowerCase());
                    if (lowerTarget.exists()) target = lowerTarget;
                }
                if (!target.exists()) {
                    File extTarget = new File(KonsoleConfigManager.BIN_PATH, targetName);
                    if (extTarget.exists()) target = extTarget;
                }
                if (!target.exists()) {
                    writeToSinkOrSession(bridgeState, session, "\033[1;31m[Konsole OS]\033[0m Installed executable '" + targetName
                            + "' not found under " + TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + ". Try 'kons install " + targetName + "' or 'kons list'.\r\n");
                    return;
                }

                target.setExecutable(true, false);
                try {
                    //noinspection OctalInteger
                    android.system.Os.chmod(target.getAbsolutePath(), 0755);
                } catch (Throwable ignored) {}

                String[] cmd = new String[parts.length - 2];
                cmd[0] = target.getAbsolutePath();
                if (parts.length > 3) {
                    System.arraycopy(parts, 3, cmd, 1, parts.length - 3);
                }
                code = runPrefixProcess(context, bridgeState, session, cmd, "");
            } finally {
                completeAsync(bridgeState, code);
            }
        }).start();
    }

    /**
     * Installs a downloaded script, binary, or .kos bundle directly into {@code prefixBinDir}
     * (e.g. /data/data/com.termux/files/usr/bin) with 0755 permissions and Termux-compatible shebangs
     * so commands execute directly on $PATH without Android external-storage noexec restrictions.
     */
    public static File installExecutableIntoPrefix(File payloadFile, File prefixBinDir, String commandName, String packageType) throws java.io.IOException {
        if (!prefixBinDir.exists() && !prefixBinDir.mkdirs()) {
            throw new java.io.IOException("Failed creating bin directory: " + prefixBinDir.getAbsolutePath());
        }

        byte[] payloadBytes = readFileBytes(payloadFile);
        if (payloadBytes.length >= 2 && payloadBytes[0] == 'P' && payloadBytes[1] == 'K') {
            byte[] extracted = extractEntryFromKosZip(payloadFile, commandName);
            if (extracted != null && extracted.length > 0) {
                payloadBytes = extracted;
            }
        }

        boolean isElf = payloadBytes.length >= 4
                && payloadBytes[0] == 0x7F && payloadBytes[1] == 'E'
                && payloadBytes[2] == 'L' && payloadBytes[3] == 'F';

        if (!isElf) {
            String prefixPath = prefixBinDir.getParentFile() != null
                    ? prefixBinDir.getParentFile().getAbsolutePath()
                    : TermuxConstants.TERMUX_PREFIX_DIR_PATH;
            String text = new String(payloadBytes, StandardCharsets.UTF_8)
                    .replace("\r\n", "\n")
                    .replace("\r", "\n");

            if (text.startsWith("#!")) {
                int nl = text.indexOf('\n');
                String firstLine = nl >= 0 ? text.substring(0, nl) : text;
                String rest = nl >= 0 ? text.substring(nl) : "";
                String rewritten = rewriteShebangLine(firstLine, prefixPath);
                text = rewritten + rest;
            } else {
                String shPath = new File(prefixPath, "bin/sh").exists() ? (prefixPath + "/bin/sh") : "/system/bin/sh";
                String defaultInterp = "fish".equalsIgnoreCase(packageType)
                        ? prefixPath + "/bin/fish"
                        : shPath;
                text = "#!" + defaultInterp + "\n" + text;
            }
            payloadBytes = text.getBytes(StandardCharsets.UTF_8);
        }

        File targetBin = new File(prefixBinDir, commandName);
        try (FileOutputStream fos = new FileOutputStream(targetBin)) {
            fos.write(payloadBytes);
        }
        targetBin.setExecutable(true, false);
        try {
            //noinspection OctalInteger
            android.system.Os.chmod(targetBin.getAbsolutePath(), 0755);
        } catch (Throwable ignored) {
        }
        return targetBin;
    }

    private static String rewriteShebangLine(String firstLine, String prefixPath) {
        String trimmed = firstLine.substring(2).trim();
        if (trimmed.startsWith("/usr/bin/env ")) {
            String arg = trimmed.substring("/usr/bin/env ".length()).trim();
            return "#!" + prefixPath + "/bin/env " + arg;
        }
        if (trimmed.equals("/bin/bash") || trimmed.equals("/usr/bin/bash")) {
            return "#!" + prefixPath + "/bin/bash";
        }
        if (trimmed.equals("/bin/sh") || trimmed.equals("/usr/bin/sh")) {
            return new File(prefixPath, "bin/sh").exists()
                    ? "#!" + prefixPath + "/bin/sh"
                    : "#!/system/bin/sh";
        }
        if (trimmed.equals("/bin/fish") || trimmed.equals("/usr/bin/fish")) {
            return "#!" + prefixPath + "/bin/fish";
        }
        if (trimmed.equals("/bin/zsh") || trimmed.equals("/usr/bin/zsh")) {
            return "#!" + prefixPath + "/bin/zsh";
        }
        return firstLine;
    }

    private static byte[] extractEntryFromKosZip(File zipFile, String commandName) {
        try (java.util.zip.ZipInputStream zis = new java.util.zip.ZipInputStream(new FileInputStream(zipFile))) {
            java.util.zip.ZipEntry entry;
            byte[] fallback = null;
            String[] preferredNames = new String[]{
                "bin/" + commandName, commandName, "run.fish", "main.fish", "run.sh", "main.sh"
            };
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;
                String ename = entry.getName();
                byte[] entryBytes = readStreamBytes(zis);
                for (String pref : preferredNames) {
                    if (ename.equalsIgnoreCase(pref)) {
                        return entryBytes;
                    }
                }
                if (fallback == null && !ename.endsWith(".json") && !ename.endsWith(".md")) {
                    fallback = entryBytes;
                }
            }
            return fallback;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static byte[] readFileBytes(File file) throws java.io.IOException {
        try (FileInputStream fis = new FileInputStream(file)) {
            return readStreamBytes(fis);
        }
    }

    private static byte[] readStreamBytes(InputStream in) throws java.io.IOException {
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int r;
        while ((r = in.read(buf)) != -1) {
            baos.write(buf, 0, r);
        }
        return baos.toByteArray();
    }

    static List<String> buildCandidatePayloadUrls(String pkgUrl, String sourceEndpoint) {
        java.util.LinkedHashSet<String> urls = new java.util.LinkedHashSet<>();
        if (pkgUrl != null && !pkgUrl.trim().isEmpty()) {
            urls.add(pkgUrl.trim());
        }
        try {
            if (pkgUrl != null && sourceEndpoint != null) {
                URL payload = new URL(pkgUrl.trim());
                URL source = new URL(sourceEndpoint);
                String srcPath = source.getPath();
                int lastSlash = srcPath != null ? srcPath.lastIndexOf('/') : -1;
                if (lastSlash > 0 && payload.getHost().equalsIgnoreCase(source.getHost())) {
                    String repoPrefix = srcPath.substring(0, lastSlash);
                    String payloadPath = payload.getPath();
                    if (payloadPath != null && !payloadPath.startsWith(repoPrefix + "/")) {
                        String combinedPath = repoPrefix + (payloadPath.startsWith("/") ? payloadPath : "/" + payloadPath);
                        urls.add(new URL(source.getProtocol(), source.getHost(), source.getPort(), combinedPath).toString());
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return new ArrayList<>(urls);
    }

    private static HttpURLConnection openConnectionWithRedirects(String urlStr) throws java.io.IOException {
        String current = urlStr;
        for (int i = 0; i < 5; i++) {
            URL url = new URL(current);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setInstanceFollowRedirects(false);
            conn.setConnectTimeout(6000);
            conn.setReadTimeout(15000);
            conn.setRequestProperty("User-Agent", "KonsoleOS-PkgManager");
            int code = conn.getResponseCode();
            if (code == HttpURLConnection.HTTP_MOVED_PERM
                    || code == HttpURLConnection.HTTP_MOVED_TEMP
                    || code == 307 || code == 308) {
                String location = conn.getHeaderField("Location");
                conn.disconnect();
                if (location == null || location.trim().isEmpty()) {
                    throw new java.io.IOException("HTTP " + code + " redirect missing Location header from " + current);
                }
                current = new URL(url, location.trim()).toString();
                continue;
            }
            return conn;
        }
        throw new java.io.IOException("Too many redirects downloading " + urlStr);
    }

    private void mirrorToExternalBinBestEffort(File installedBin, String name) {
        try {
            File extBin = new File(KonsoleConfigManager.BIN_PATH, name);
            copyFile(installedBin, extBin);
            extBin.setExecutable(true, false);
        } catch (Exception ignored) {
        }
    }

    private int installDebViaDpkg(Context context, BridgeState bridgeState, TerminalSession session, String pkgName, File debFile) {
        com.termux.app.TermuxInstaller.ensureAptAndPkgConfigured(TermuxConstants.TERMUX_PREFIX_DIR_PATH);
        File dpkgBin = new File(TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH, "dpkg");
        if (!dpkgBin.isFile()) {
            writeToSinkOrSession(bridgeState, session, "\033[1;33m[Kons Package Manager]\033[0m .deb saved to " + debFile.getAbsolutePath()
                    + ", but dpkg binary was not found at " + dpkgBin.getAbsolutePath() + ".\r\n");
            return 1;
        }

        writeToSinkOrSession(bridgeState, session, "\033[1;36m[Kons Package Manager]\033[0m Installing " + pkgName + " via dpkg -i...\r\n");
        int rc = runPrefixProcess(context, bridgeState, session, new String[]{dpkgBin.getAbsolutePath(), "-i", debFile.getAbsolutePath()});
        if (rc != 0) {
            File aptGetBin = new File(TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH, "apt-get");
            if (aptGetBin.isFile()) {
                writeToSinkOrSession(bridgeState, session, "\033[1;33m[Kons Package Manager]\033[0m Resolving dependencies with apt-get install -f -y...\r\n");
                rc = runPrefixProcess(context, bridgeState, session, new String[]{aptGetBin.getAbsolutePath(), "install", "-f", "-y"});
            }
        }
        if (rc == 0) {
            writeToSinkOrSession(bridgeState, session, "\033[1;32m[Kons Package Manager]\033[0m Installed .deb package '\033[1;36m" + pkgName + "\033[0m' successfully!\r\n");
        } else {
            writeToSinkOrSession(bridgeState, session, "\033[1;31m[Kons Package Manager]\033[0m dpkg exited with code " + rc + ".\r\n");
        }
        return rc;
    }

    private int runPrefixProcess(Context context, BridgeState bridgeState, TerminalSession session, String[] cmd) {
        return runPrefixProcess(context, bridgeState, session, cmd, "  ");
    }

    private int runPrefixProcess(Context context, BridgeState bridgeState, TerminalSession session, String[] cmd, String linePrefix) {
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            File homeDir = new File(TermuxConstants.TERMUX_HOME_DIR_PATH);
            if (homeDir.isDirectory()) {
                pb.directory(homeDir);
            }
            java.util.HashMap<String, String> env = new com.termux.shared.termux.shell.command.environment.TermuxShellEnvironment()
                    .getEnvironment(context, false);
            pb.environment().putAll(env);
            pb.environment().put("DEBIAN_FRONTEND", "noninteractive");
            pb.environment().put("PATH", TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + ":/system/bin:/system/xbin");

            Process p = pb.start();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                String prefix = linePrefix != null ? linePrefix : "";
                while ((line = br.readLine()) != null) {
                    writeToSinkOrSession(bridgeState, session, prefix + line + "\r\n");
                }
            }
            return p.waitFor();
        } catch (Exception e) {
            writeToSinkOrSession(bridgeState, session, "\033[1;31m[Exec Error]\033[0m " + e.getMessage() + "\r\n");
            return 1;
        }
    }

    private void installAndConfigureFishShell(Context context, TerminalSession session) {
        final BridgeState bridgeState = markAsyncDeferred();
        new Thread(() -> {
            int code = 0;
            try {
                com.termux.app.TermuxInstaller.ensureAptAndPkgConfigured(TermuxConstants.TERMUX_PREFIX_DIR_PATH);
                File fishBin = new File(TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH, "fish");
                if (fishBin.isFile() && fishBin.canExecute()) {
                    writeToSinkOrSession(bridgeState, session, "\033[1;32m[Fish Shell]\033[0m Fish is already installed at " + fishBin.getAbsolutePath()
                            + ". Konsole OS Fish scripts updated! Run '\033[1;36mexec fish -l\033[0m' to reload.\r\n");
                    return;
                }

                File pkgBin = new File(TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH, "pkg");
                File aptGetBin = new File(TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH, "apt-get");
                if (pkgBin.isFile()) {
                    writeToSinkOrSession(bridgeState, session, "\033[1;36m[Fish Shell]\033[0m Installing Fish shell via pkg...\r\n");
                    runPrefixProcess(context, bridgeState, session, new String[]{pkgBin.getAbsolutePath(), "update", "-y"});
                    code = runPrefixProcess(context, bridgeState, session, new String[]{pkgBin.getAbsolutePath(), "install", "-y", "fish"});
                } else if (aptGetBin.isFile()) {
                    writeToSinkOrSession(bridgeState, session, "\033[1;36m[Fish Shell]\033[0m Installing Fish shell via apt-get...\r\n");
                    runPrefixProcess(context, bridgeState, session, new String[]{aptGetBin.getAbsolutePath(), "update", "-y"});
                    code = runPrefixProcess(context, bridgeState, session, new String[]{aptGetBin.getAbsolutePath(), "install", "-y", "fish"});
                } else {
                    writeToSinkOrSession(bridgeState, session, "\033[1;31m[Fish Shell]\033[0m Neither pkg nor apt-get found under " + TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + ".\r\n");
                    code = 1;
                    return;
                }

                com.termux.app.TermuxInstaller.ensureAptAndPkgConfigured(TermuxConstants.TERMUX_PREFIX_DIR_PATH);
                KonsolePathIndexer.getInstance().refreshAsync();
                if (code == 0) {
                    writeToSinkOrSession(bridgeState, session, "\033[1;32m[Fish Shell]\033[0m Fish installed & configured! Start immediately with '\033[1;36mexec fish -l\033[0m' or open a new session.\r\n");
                }
            } finally {
                completeAsync(bridgeState, code);
            }
        }).start();
    }

    private void copyFile(File src, File dst) throws java.io.IOException {
        File parent = dst.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
        if (parent == null || !parent.exists()) {
            throw new java.io.IOException("Target directory cannot be created: " + dst.getAbsolutePath());
        }
        try (InputStream in = new FileInputStream(src);
             FileOutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[8192];
            int len;
            while ((len = in.read(buf)) > 0) {
                out.write(buf, 0, len);
            }
        }
    }

    private void printHelpManual(TerminalSession session) {
        String help = "\r\n\033[1;36m=====================================================================\033[0m\r\n" +
                "\033[1;32m               KONSOLE OS COMMAND MANUAL (FISH SHELL)                \033[0m\r\n" +
                "\033[1;36m=====================================================================\033[0m\r\n\n" +
                "\033[1;33m[ System & Apps ]\033[0m\r\n" +
                "  <app-name>              - Fuzzy-launch installed Android apps (via Fish)\r\n" +
                "  uninstall <app-name>    - Uninstall specified Android app\r\n" +
                "  settings [wifi|bt|apps] - Open Android system settings\r\n\n" +
                "\033[1;33m[ Hardware & Connectivity ]\033[0m\r\n" +
                "  bluetooth status|on|off|ls|connect <mac>\r\n" +
                "  wifi status|on|off|ls|connect <ssid>\r\n" +
                "  flash on|off            - Toggle camera flashlight\r\n" +
                "  battery                 - Detailed battery state & health\r\n" +
                "  brightness <0-100|auto> - Adjust screen brightness\r\n" +
                "  dnd on|off              - Toggle Do Not Disturb mode\r\n" +
                "  wallpaper <path>        - Set home screen wallpaper\r\n" +
                "  lock                    - Lock device screen\r\n\n" +
                "\033[1;33m[ Communications & Media ]\033[0m\r\n" +
                "  call <number|name>      - Resolve contact name and place call\r\n" +
                "  contact ls|search <q>   - Search device contacts\r\n" +
                "  music play|pause|stop|ls- Media player control & track listing\r\n" +
                "  volume <stream> <level> - Adjust stream volume (media, ringer, etc.)\r\n" +
                "  vibrate <pattern>       - Vibrate pattern (e.g. 0,300,500)\r\n" +
                "  beep <pattern>          - Play audio tone sequence\r\n" +
                "  calc <expression>       - Evaluate math expression\r\n\n" +
                "\033[1;33m[ Kons OS Management & Automation ]\033[0m\r\n" +
                "  kons dashboard          - Open live system stats, path & toggle Dashboard\r\n" +
                "  kons macro              - Open visual UI Macro Lab (triggers, cron & tests)\r\n" +
                "  macro <lab|list|trigger|notify|toast|flash|tap|swipe>\r\n" +
                "                          - Bidirectional script-to-Android automation CLI\r\n" +
                "  kons setup-fish         - Install/update native Fish shell environment\r\n" +
                "  kons apps               - Terminal-styled GUI apps grid\r\n" +
                "  kons palette            - Open fuzzy Command Palette (also HUD CMD)\r\n" +
                "  kons sys                - Live hardware, CPU, RAM & network telemetry\r\n" +
                "  kons hud <on|off>       - Show or hide top telemetry HUD bar\r\n" +
                "  kons search [query]     - Search remote Konsole OS package catalog\r\n" +
                "  kons install <pkg>      - Install script/kos ($PREFIX/bin) or deb (dpkg -i)\r\n" +
                "  kons run <pkg> [args]   - Run installed package directly (bypasses shell builtins)\r\n" +
                "  kons remove <pkg>       - Remove installed kons package\r\n" +
                "  kons list               - List installed kons packages\r\n" +
                "  kons set <key=val>      - Save persistent user variable\r\n" +
                "  kons alias <name=val>   - Create command shortcut alias\r\n" +
                "  kons notify <t> <m>     - Send native system notification\r\n" +
                "  kons toast <msg>        - Display screen toast message\r\n" +
                "  kons update             - Check & install Konsole OS OTA updates\r\n" +
                "  kons switch <termux|konsole> - Toggle Fish shell OS integration mode\r\n" +
                "\033[1;36m=====================================================================\033[0m\r\n";
        writeToTerminal(session, help);
    }

    private boolean tryLaunchAppByLabel(Context context, TerminalSession session, String labelInput) {
        PackageManager pm = context.getPackageManager();
        List<ApplicationInfo> apps = pm.getInstalledApplications(PackageManager.GET_META_DATA);
        String target = labelInput.toLowerCase();

        for (ApplicationInfo app : apps) {
            Intent launchIntent = pm.getLaunchIntentForPackage(app.packageName);
            if (launchIntent != null) {
                String appLabel = pm.getApplicationLabel(app).toString().toLowerCase();
                if (appLabel.equals(target) || appLabel.replaceAll("\\s+", "").equals(target.replaceAll("\\s+", ""))) {
                    try {
                        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        context.startActivity(launchIntent);
                        writeToTerminal(session, "\r\n\033[1;32m[Konsole OS]\033[0m Launching \033[1;36m" + pm.getApplicationLabel(app) + "\033[0m...\r\n");
                        return true;
                    } catch (Exception e) {
                        writeToTerminal(session, "\r\n\033[1;31m[Konsole OS]\033[0m Error launching app: " + e.getMessage() + "\r\n");
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private boolean handleBluetooth(Context context, TerminalSession session, String[] parts) {
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) {
            writeToTerminal(session, "\r\n\033[1;31m[Bluetooth]\033[0m Device does not support Bluetooth.\r\n");
            return true;
        }

        if (parts.length < 2) {
            writeToTerminal(session, "\r\nUsage: bluetooth status|on|off|ls|connect <mac>\r\n");
            return true;
        }

        String sub = parts[1].toLowerCase();
        try {
            switch (sub) {
                case "status":
                    writeToTerminal(session, "\r\n\033[1;36m[Bluetooth Status]\033[0m State: " +
                            (adapter.isEnabled() ? "\033[1;32mENABLED\033[0m" : "\033[1;31mDISABLED\033[0m") +
                            " | Name: " + adapter.getName() + "\r\n");
                    return true;

                case "on":
                    if (!adapter.isEnabled()) adapter.enable();
                    writeToTerminal(session, "\r\n\033[1;32m[Bluetooth]\033[0m Enabling Bluetooth adapter...\r\n");
                    return true;

                case "off":
                    if (adapter.isEnabled()) adapter.disable();
                    writeToTerminal(session, "\r\n\033[1;33m[Bluetooth]\033[0m Disabling Bluetooth adapter...\r\n");
                    return true;

                case "ls":
                    writeToTerminal(session, "\r\n\033[1;36m[Paired Bluetooth Devices]\033[0m\r\n");
                    Set<BluetoothDevice> paired = adapter.getBondedDevices();
                    if (paired != null && !paired.isEmpty()) {
                        for (BluetoothDevice dev : paired) {
                            writeToTerminal(session, " - " + dev.getName() + " (" + dev.getAddress() + ")\r\n");
                        }
                    } else {
                        writeToTerminal(session, " (No paired devices found)\r\n");
                    }
                    return true;

                case "connect":
                    if (parts.length < 3) {
                        writeToTerminal(session, "\r\nUsage: bluetooth connect <mac-address>\r\n");
                    } else {
                        writeToTerminal(session, "\r\n\033[1;32m[Bluetooth]\033[0m Initiating connection to " + parts[2] + "...\r\n");
                    }
                    return true;
            }
        } catch (SecurityException e) {
            writeToTerminal(session, "\r\n\033[1;31m[Bluetooth]\033[0m Permission missing: BLUETOOTH_CONNECT\r\n");
        }
        return true;
    }

    private boolean handleWifi(Context context, TerminalSession session, String[] parts) {
        WifiManager wm = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wm == null) {
            writeToTerminal(session, "\r\n\033[1;31m[Wi-Fi]\033[0m Wi-Fi Manager unavailable.\r\n");
            return true;
        }

        if (parts.length < 2) {
            writeToTerminal(session, "\r\nUsage: wifi status|on|off|ls|connect <ssid> [password]\r\n");
            return true;
        }

        String sub = parts[1].toLowerCase();
        switch (sub) {
            case "status":
                boolean enabled = wm.isWifiEnabled();
                String ssid = wm.getConnectionInfo() != null ? wm.getConnectionInfo().getSSID() : "N/A";
                writeToTerminal(session, "\r\n\033[1;36m[Wi-Fi Status]\033[0m State: " +
                        (enabled ? "\033[1;32mENABLED\033[0m" : "\033[1;31mDISABLED\033[0m") +
                        " | SSID: " + ssid + "\r\n");
                return true;

            case "on":
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    Intent panelIntent = new Intent(Settings.Panel.ACTION_WIFI);
                    panelIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    context.startActivity(panelIntent);
                    writeToTerminal(session, "\r\n\033[1;33m[Wi-Fi]\033[0m Android 10+ restricts background Wi-Fi toggles. Opening Wi-Fi Quick Panel...\r\n");
                } else {
                    wm.setWifiEnabled(true);
                    writeToTerminal(session, "\r\n\033[1;32m[Wi-Fi]\033[0m Enabling Wi-Fi...\r\n");
                }
                return true;

            case "off":
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    Intent panelIntent = new Intent(Settings.Panel.ACTION_WIFI);
                    panelIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    context.startActivity(panelIntent);
                    writeToTerminal(session, "\r\n\033[1;33m[Wi-Fi]\033[0m Android 10+ restricts background Wi-Fi toggles. Opening Wi-Fi Quick Panel...\r\n");
                } else {
                    wm.setWifiEnabled(false);
                    writeToTerminal(session, "\r\n\033[1;33m[Wi-Fi]\033[0m Disabling Wi-Fi...\r\n");
                }
                return true;

            case "ls":
                writeToTerminal(session, "\r\n\033[1;36m[Available Wi-Fi Networks]\033[0m\r\n");
                wm.startScan();
                List<ScanResult> results = wm.getScanResults();
                if (results != null && !results.isEmpty()) {
                    for (ScanResult sr : results) {
                        if (!sr.SSID.isEmpty()) {
                            writeToTerminal(session, " - " + sr.SSID + " (Signal: " + sr.level + " dBm)\r\n");
                        }
                    }
                } else {
                    writeToTerminal(session, " (Scanning... or no networks found)\r\n");
                }
                return true;

            case "connect":
                if (parts.length < 3) {
                    writeToTerminal(session, "\r\nUsage: wifi connect <ssid> [password]\r\n");
                } else {
                    String targetSsid = parts[2];
                    String pass = parts.length >= 4 ? parts[3] : "";
                    WifiConfiguration conf = new WifiConfiguration();
                    conf.SSID = "\"" + targetSsid + "\"";
                    if (!pass.isEmpty()) {
                        conf.preSharedKey = "\"" + pass + "\"";
                    } else {
                        conf.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE);
                    }
                    int netId = wm.addNetwork(conf);
                    wm.disconnect();
                    wm.enableNetwork(netId, true);
                    wm.reconnect();
                    writeToTerminal(session, "\r\n\033[1;32m[Wi-Fi]\033[0m Connecting to network '" + targetSsid + "'...\r\n");
                }
                return true;
        }
        return true;
    }

    private boolean handleMusic(Context context, TerminalSession session, String[] parts) {
        AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (parts.length < 2) {
            writeToTerminal(session, "\r\nUsage: music play|pause|stop|ls\r\n");
            return true;
        }

        String sub = parts[1].toLowerCase();
        switch (sub) {
            case "play":
            case "pause":
                if (am != null) {
                    KeyEvent event = new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE);
                    am.dispatchMediaKeyEvent(event);
                    am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE));
                }
                writeToTerminal(session, "\r\n\033[1;32m[Music]\033[0m Sent Play/Pause media key.\r\n");
                return true;

            case "stop":
                if (am != null) {
                    am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_STOP));
                    am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_STOP));
                }
                writeToTerminal(session, "\r\n\033[1;33m[Music]\033[0m Sent Stop media key.\r\n");
                return true;

            case "ls":
                File musicDir = new File("/storage/emulated/0/Music");
                writeToTerminal(session, "\r\n\033[1;36m[Local Music Files (/storage/emulated/0/Music)]\033[0m\r\n");
                if (musicDir.exists() && musicDir.isDirectory()) {
                    File[] tracks = musicDir.listFiles();
                    if (tracks != null && tracks.length > 0) {
                        for (File f : tracks) {
                            writeToTerminal(session, " - " + f.getName() + "\r\n");
                        }
                    } else {
                        writeToTerminal(session, " (No music files found)\r\n");
                    }
                } else {
                    writeToTerminal(session, " (Music directory empty or not accessible)\r\n");
                }
                return true;
        }
        return true;
    }

    private boolean handleCall(Context context, TerminalSession session, String[] parts) {
        if (parts.length < 2) {
            writeToTerminal(session, "\r\nUsage: call <number or contact name>\r\n");
            return true;
        }

        StringBuilder target = new StringBuilder();
        for (int i = 1; i < parts.length; i++) target.append(parts[i]).append(" ");
        String query = target.toString().trim();

        String number = query;
        if (!query.matches("^[0-9+#*\\s-]+$")) {
            // Resolve contact name via ContactsContract
            number = resolveContactNumber(context, query);
        }

        if (number != null && !number.isEmpty()) {
            writeToTerminal(session, "\r\n\033[1;32m[Call]\033[0m Initiating call to: \033[1;36m" + number + "\033[0m...\r\n");
            try {
                Intent callIntent = new Intent(Intent.ACTION_CALL, Uri.parse("tel:" + Uri.encode(number)));
                callIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(callIntent);
            } catch (SecurityException e) {
                writeToTerminal(session, "\r\n\033[1;31m[Call]\033[0m Permission CALL_PHONE required.\r\n");
            } catch (Exception e) {
                writeToTerminal(session, "\r\n\033[1;31m[Call]\033[0m Error dialing: " + e.getMessage() + "\r\n");
            }
        } else {
            writeToTerminal(session, "\r\n\033[1;31m[Call]\033[0m Contact '" + query + "' not found.\r\n");
        }
        return true;
    }

    private String resolveContactNumber(Context context, String nameQuery) {
        ContentResolver cr = context.getContentResolver();
        Cursor cursor = cr.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                new String[]{ContactsContract.CommonDataKinds.Phone.NUMBER, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME},
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " LIKE ?",
                new String[]{"%" + nameQuery + "%"}, null);
        if (cursor != null) {
            if (cursor.moveToFirst()) {
                int numIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER);
                String num = cursor.getString(numIdx);
                cursor.close();
                return num;
            }
            cursor.close();
        }
        return null;
    }

    private boolean handleContacts(Context context, TerminalSession session, String[] parts) {
        if (parts.length < 2) {
            writeToTerminal(session, "\r\nUsage: contact ls|search <query>\r\n");
            return true;
        }

        String sub = parts[1].toLowerCase();
        String q = parts.length >= 3 ? parts[2] : "";

        ContentResolver cr = context.getContentResolver();
        try {
            Cursor cursor = cr.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    new String[]{ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER},
                    q.isEmpty() ? null : ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " LIKE ?",
                    q.isEmpty() ? null : new String[]{"%" + q + "%"},
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC");

            writeToTerminal(session, "\r\n\033[1;36m[Device Contacts]\033[0m\r\n");
            if (cursor != null) {
                int count = 0;
                int nameIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME);
                int numIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER);
                while (cursor.moveToNext() && count < 50) {
                    String name = cursor.getString(nameIdx);
                    String num = cursor.getString(numIdx);
                    writeToTerminal(session, " - " + name + ": " + num + "\r\n");
                    count++;
                }
                cursor.close();
                if (count == 0) writeToTerminal(session, " (No matching contacts found)\r\n");
            } else {
                writeToTerminal(session, " (No contacts available)\r\n");
            }
        } catch (SecurityException e) {
            writeToTerminal(session, "\r\n\033[1;31m[Contacts]\033[0m READ_CONTACTS permission required.\r\n");
        }
        return true;
    }

    private boolean handleVolume(Context context, TerminalSession session, String[] parts) {
        AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (am == null) return true;

        if (parts.length < 2) {
            writeToTerminal(session, "\r\nUsage: volume <stream|silent|vibrate|normal> [level]\r\nStreams: voicecall(1), system(2), media(3), alarm(4), notification(5), ringer(6)\r\n");
            return true;
        }

        String arg = parts[1].toLowerCase();
        if (arg.equals("silent") || arg.equals("00")) {
            am.setRingerMode(AudioManager.RINGER_MODE_SILENT);
            writeToTerminal(session, "\r\n\033[1;32m[Volume]\033[0m Ringer mode set to SILENT.\r\n");
            return true;
        } else if (arg.equals("vibrate") || arg.equals("01")) {
            am.setRingerMode(AudioManager.RINGER_MODE_VIBRATE);
            writeToTerminal(session, "\r\n\033[1;32m[Volume]\033[0m Ringer mode set to VIBRATE.\r\n");
            return true;
        } else if (arg.equals("normal") || arg.equals("11")) {
            am.setRingerMode(AudioManager.RINGER_MODE_NORMAL);
            writeToTerminal(session, "\r\n\033[1;32m[Volume]\033[0m Ringer mode set to NORMAL.\r\n");
            return true;
        }

        if (parts.length >= 3) {
            int stream = AudioManager.STREAM_MUSIC;
            try {
                if (arg.contains("voice")) stream = AudioManager.STREAM_VOICE_CALL;
                else if (arg.contains("sys")) stream = AudioManager.STREAM_SYSTEM;
                else if (arg.contains("media")) stream = AudioManager.STREAM_MUSIC;
                else if (arg.contains("alarm")) stream = AudioManager.STREAM_ALARM;
                else if (arg.contains("notif")) stream = AudioManager.STREAM_NOTIFICATION;
                else if (arg.contains("ring")) stream = AudioManager.STREAM_RING;
                else stream = Integer.parseInt(arg);

                int level = Integer.parseInt(parts[2]);
                int max = am.getStreamMaxVolume(stream);
                int target = Math.min(max, Math.max(0, level));
                am.setStreamVolume(stream, target, 0);
                writeToTerminal(session, "\r\n\033[1;32m[Volume]\033[0m Stream " + stream + " volume set to: " + target + "/" + max + "\r\n");
            } catch (Exception e) {
                writeToTerminal(session, "\r\n\033[1;31m[Volume]\033[0m Invalid arguments.\r\n");
            }
        }
        return true;
    }

    private boolean handleCalc(TerminalSession session, String line) {
        int spaceIdx = line.indexOf(' ');
        String expr = spaceIdx >= 0 ? line.substring(spaceIdx + 1).trim() : "";
        if (expr.isEmpty()) {
            writeToTerminal(session, "\r\nUsage: calc <expression> (e.g. calc (2+3)*4, calc sqrt(144))\r\n");
            return true;
        }
        try {
            double res = evaluateExpression(expr);
            writeToTerminal(session, "\r\n\033[1;32m[Calc]\033[0m " + expr + " = \033[1;36m" + res + "\033[0m\r\n");
        } catch (Exception e) {
            writeToTerminal(session, "\r\n\033[1;31m[Calc]\033[0m Invalid math expression: " + e.getMessage() + "\r\n");
        }
        return true;
    }

    private double evaluateExpression(String rawExpr) {
        final String expr = rawExpr.replaceAll("\\s+", "");
        return new Object() {
            int pos = -1, ch;
            void nextChar() { ch = (++pos < expr.length()) ? expr.charAt(pos) : -1; }
            boolean eat(int charToEat) {
                while (ch == ' ') nextChar();
                if (ch == charToEat) { nextChar(); return true; }
                return false;
            }
            double parse() {
                nextChar();
                double x = parseExpression();
                if (pos < expr.length()) throw new RuntimeException("Unexpected: " + (char)ch);
                return x;
            }
            double parseExpression() {
                double x = parseTerm();
                for (;;) {
                    if (eat('+')) x += parseTerm();
                    else if (eat('-')) x -= parseTerm();
                    else return x;
                }
            }
            double parseTerm() {
                double x = parseFactor();
                for (;;) {
                    if (eat('*')) x *= parseFactor();
                    else if (eat('/')) x /= parseFactor();
                    else if (eat('%')) x %= parseFactor();
                    else return x;
                }
            }
            double parseFactor() {
                if (eat('+')) return parseFactor();
                if (eat('-')) return -parseFactor();
                double x;
                int startPos = this.pos;
                if (eat('(')) {
                    x = parseExpression();
                    eat(')');
                } else if ((ch >= '0' && ch <= '9') || ch == '.') {
                    while ((ch >= '0' && ch <= '9') || ch == '.') nextChar();
                    x = Double.parseDouble(expr.substring(startPos, this.pos));
                } else if (ch >= 'a' && ch <= 'z') {
                    while (ch >= 'a' && ch <= 'z') nextChar();
                    String func = expr.substring(startPos, this.pos);
                    x = parseFactor();
                    if (func.equals("sqrt")) x = Math.sqrt(x);
                    else if (func.equals("sin")) x = Math.sin(Math.toRadians(x));
                    else if (func.equals("cos")) x = Math.cos(Math.toRadians(x));
                    else if (func.equals("tan")) x = Math.tan(Math.toRadians(x));
                    else throw new RuntimeException("Unknown function: " + func);
                } else {
                    throw new RuntimeException("Unexpected: " + (char)ch);
                }
                if (eat('^')) x = Math.pow(x, parseFactor());
                return x;
            }
        }.parse();
    }

    private boolean handleFlash(Context context, TerminalSession session, String[] parts) {
        if (parts.length < 2) {
            writeToTerminal(session, "\r\nUsage: flash on|off\r\n");
            return true;
        }

        String sub = parts[1].toLowerCase();
        CameraManager cm = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        if (cm != null) {
            try {
                String cameraId = cm.getCameraIdList()[0];
                boolean turnOn = sub.equals("on");
                cm.setTorchMode(cameraId, turnOn);
                writeToTerminal(session, "\r\n\033[1;32m[Flashlight]\033[0m Turned flashlight " + (turnOn ? "\033[1;32mON\033[0m" : "\033[1;31mOFF\033[0m") + "\r\n");
            } catch (Exception e) {
                writeToTerminal(session, "\r\n\033[1;31m[Flashlight]\033[0m Error: " + e.getMessage() + "\r\n");
            }
        }
        return true;
    }

    private boolean handleVibrate(Context context, TerminalSession session, String[] parts) {
        if (parts.length < 2) {
            writeToTerminal(session, "\r\nUsage: vibrate <millis-pattern> (e.g. vibrate 0,300,500)\r\n");
            return true;
        }

        String[] p = parts[1].split(",");
        long[] pattern = new long[p.length];
        try {
            for (int i = 0; i < p.length; i++) pattern[i] = Long.parseLong(p[i].trim());
            Vibrator v = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
            if (v != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    v.vibrate(VibrationEffect.createWaveform(pattern, -1));
                } else {
                    v.vibrate(pattern, -1);
                }
                writeToTerminal(session, "\r\n\033[1;32m[Vibration]\033[0m Vibration triggered.\r\n");
            }
        } catch (Exception e) {
            writeToTerminal(session, "\r\n\033[1;31m[Vibration]\033[0m Invalid pattern format.\r\n");
        }
        return true;
    }

    private boolean handleBeep(TerminalSession session, String[] parts) {
        if (parts.length < 2) {
            writeToTerminal(session, "\r\nUsage: beep <pattern> (0=beep, 1=double-beep, -=1s silence, -N=N s silence)\r\n");
            return true;
        }

        new Thread(() -> {
            try {
                ToneGenerator toneGen = new ToneGenerator(AudioManager.STREAM_MUSIC, 100);
                String[] tokens = parts[1].split(",");
                for (String t : tokens) {
                    t = t.trim();
                    if (t.equals("0")) {
                        toneGen.startTone(ToneGenerator.TONE_PROP_BEEP, 150);
                        Thread.sleep(200);
                    } else if (t.equals("1")) {
                        toneGen.startTone(ToneGenerator.TONE_PROP_BEEP, 100);
                        Thread.sleep(150);
                        toneGen.startTone(ToneGenerator.TONE_PROP_BEEP, 100);
                        Thread.sleep(200);
                    } else if (t.startsWith("-")) {
                        int seconds = 1;
                        if (t.length() > 1) {
                            try { seconds = Math.abs(Integer.parseInt(t.substring(1))); } catch (Exception ignored) {}
                        }
                        Thread.sleep(seconds * 1000L);
                    }
                }
                toneGen.release();
            } catch (Exception e) {
                Log.e(TAG, "Beep execution error", e);
            }
        }).start();

        writeToTerminal(session, "\r\n\033[1;32m[Beep]\033[0m Tone pattern playing...\r\n");
        return true;
    }

    private boolean handleBrightness(Context context, TerminalSession session, String[] parts) {
        if (parts.length < 2) {
            writeToTerminal(session, "\r\nUsage: brightness <0-100|auto>\r\n");
            return true;
        }

        String sub = parts[1].toLowerCase();
        try {
            if (sub.equals("auto")) {
                Settings.System.putInt(context.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC);
                writeToTerminal(session, "\r\n\033[1;32m[Brightness]\033[0m Set to AUTO mode.\r\n");
            } else {
                int level = Integer.parseInt(sub);
                int val = Math.min(255, Math.max(0, (level * 255) / 100));
                Settings.System.putInt(context.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);
                Settings.System.putInt(context.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, val);
                writeToTerminal(session, "\r\n\033[1;32m[Brightness]\033[0m Set to " + level + "%\r\n");
            }
        } catch (Exception e) {
            writeToTerminal(session, "\r\n\033[1;31m[Brightness]\033[0m Permission WRITE_SETTINGS required or invalid value.\r\n");
        }
        return true;
    }

    private boolean handleDnd(Context context, TerminalSession session, String[] parts) {
        if (parts.length < 2) {
            writeToTerminal(session, "\r\nUsage: dnd on|off\r\n");
            return true;
        }

        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!nm.isNotificationPolicyAccessGranted()) {
                writeToTerminal(session, "\r\n\033[1;31m[DND]\033[0m Notification Policy Access required in Android Settings.\r\n");
                return true;
            }
            boolean enable = parts[1].equalsIgnoreCase("on");
            nm.setInterruptionFilter(enable ? NotificationManager.INTERRUPTION_FILTER_NONE : NotificationManager.INTERRUPTION_FILTER_ALL);
            writeToTerminal(session, "\r\n\033[1;32m[DND]\033[0m Do Not Disturb turned " + (enable ? "\033[1;32mON\033[0m" : "\033[1;31mOFF\033[0m") + "\r\n");
        }
        return true;
    }

    private boolean handleWallpaper(Context context, TerminalSession session, String[] parts) {
        if (parts.length < 2) {
            writeToTerminal(session, "\r\nUsage: wallpaper <image-path>\r\n");
            return true;
        }

        File imgFile = new File(parts[1]);
        if (imgFile.exists()) {
            try {
                Bitmap bitmap = BitmapFactory.decodeFile(imgFile.getAbsolutePath());
                WallpaperManager wm = WallpaperManager.getInstance(context);
                wm.setBitmap(bitmap);
                writeToTerminal(session, "\r\n\033[1;32m[Wallpaper]\033[0m Wallpaper updated successfully!\r\n");
            } catch (Exception e) {
                writeToTerminal(session, "\r\n\033[1;31m[Wallpaper]\033[0m Failed setting wallpaper: " + e.getMessage() + "\r\n");
            }
        } else {
            writeToTerminal(session, "\r\n\033[1;31m[Wallpaper]\033[0m File not found: " + parts[1] + "\r\n");
        }
        return true;
    }

    private boolean handleLock(Context context, TerminalSession session, String rawLine, String[] parts) {
        KonsoleConfigManager configMgr = KonsoleConfigManager.getInstance();

        if (parts.length >= 2) {
            String arg = parts[1].toLowerCase();
            if (arg.equals("--sudo")) {
                if (!configMgr.hasSudoPassword()) {
                    writeToTerminal(session, "\r\n\033[1;31m[Lock Error]\033[0m No sudo password set. Set a password first using:\r\n  \033[1;36mlock --sudo-set <your-password>\033[0m\r\n");
                    return true;
                }
                configMgr.setSudoLocked(true);
                startKioskLockdown(context, session);
                writeToTerminal(session, "\r\n\033[1;31m[KONSOLE OS SUDO LOCKED]\033[0m All commands locked. Enter password to unlock:\r\n");
                return true;
            } else if (arg.equals("--sudo-set")) {
                if (parts.length < 3) {
                    writeToTerminal(session, "\r\nUsage: lock --sudo-set <custom-password>\r\n");
                    return true;
                }
                String customPass = parts[2];
                String rescueHash = configMgr.setSudoPassword(customPass);

                // Copy rescue hash to Clipboard
                try {
                    android.content.ClipboardManager clipboard = (android.content.ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
                    if (clipboard != null) {
                        android.content.ClipData clip = android.content.ClipData.newPlainText("Konsole Rescue Hash", rescueHash);
                        clipboard.setPrimaryClip(clip);
                    }
                } catch (Exception ignored) {}

                writeToTerminal(session, "\r\n\033[1;32m[Konsole OS Sudo]\033[0m Sudo password set successfully!\r\n" +
                        " Rescue Hash: \033[1;36m" + rescueHash + "\033[0m\r\n" +
                        " \033[1;33m(Rescue hash copied to clipboard! Save it in a safe place!)\033[0m\r\n");
                return true;
            } else if (arg.equals("--sudo-reset")) {
                if (parts.length < 3) {
                    writeToTerminal(session, "\r\nUsage: lock --sudo-reset <rescue-hash>\r\n");
                    return true;
                }
                String hashInput = parts[2];
                if (configMgr.resetSudoPasswordWithHash(hashInput)) {
                    stopKioskLockdown(context);
                    writeToTerminal(session, "\r\n\033[1;32m[Konsole OS Sudo]\033[0m Sudo password reset successfully! Lock screen removed.\r\n");
                } else {
                    writeToTerminal(session, "\r\n\033[1;31m[Lock Error]\033[0m Invalid rescue hash.\r\n");
                }
                return true;
            } else if (arg.equals("--sudo-redo")) {
                writeToTerminal(session, "\r\n\033[1;31m[Sudo Redo / Factory Reset]\033[0m Resetting all Konsole OS files and configurations...\r\n");
                configMgr.resetSudoRedoFactoryReset();
                stopKioskLockdown(context);
                writeToTerminal(session, "\033[1;32m[Konsole OS]\033[0m Factory reset complete!\r\n");
                return true;
            }
        }

        startKioskLockdown(context, session);
        return true;
    }

    private void startKioskLockdown(Context context, TerminalSession session) {
        if (!(context instanceof android.app.Activity)) return;
        android.app.Activity activity = (android.app.Activity) context;
        android.app.admin.DevicePolicyManager dpm = (android.app.admin.DevicePolicyManager) context.getSystemService(Context.DEVICE_POLICY_SERVICE);
        String pkgName = context.getPackageName();

        boolean isDeviceOwner = false;
        if (dpm != null) {
            try {
                isDeviceOwner = dpm.isDeviceOwnerApp(pkgName);
            } catch (Exception ignored) {}
        }

        if (isDeviceOwner && dpm != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            try {
                ComponentName adminComp = new ComponentName(context, KonsoleDeviceAdminReceiver.class);
                dpm.setLockTaskPackages(adminComp, new String[]{pkgName});
                activity.startLockTask();
                writeToTerminal(session, "\r\n\033[1;32m[True Kiosk Lock Active]\033[0m Status bar, Notification shade, Home & Recents buttons completely disabled.\r\n");
                return;
            } catch (Exception e) {
                Log.e(TAG, "Device Owner Kiosk Lock failed", e);
            }
        }

        try {
            activity.startLockTask();
            writeToTerminal(session, "\r\n\033[1;33m[Konsole OS Screen Lock Active]\033[0m\r\n" +
                    " \033[1;36mTo enable True Kiosk Lock (blocking Notification Shade & Home button completely), grant Device Owner via ADB:\033[0m\r\n" +
                    " adb shell dpm set-device-owner " + TermuxConstants.TERMUX_PACKAGE_NAME + "/.app.konsole.KonsoleDeviceAdminReceiver\r\n");
        } catch (Exception e) {
            writeToTerminal(session, "\r\n\033[1;31m[Lock Error]\033[0m Could not start screen lock: " + e.getMessage() + "\r\n");
        }
    }

    private void stopKioskLockdown(Context context) {
        if (context instanceof android.app.Activity) {
            try {
                ((android.app.Activity) context).stopLockTask();
            } catch (Exception ignored) {}
        }
    }

    private boolean handleBattery(Context context, TerminalSession session) {
        IntentFilter ifilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        Intent batteryStatus = context.registerReceiver(null, ifilter);
        if (batteryStatus != null) {
            int level = batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = batteryStatus.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
            int status = batteryStatus.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
            int health = batteryStatus.getIntExtra(BatteryManager.EXTRA_HEALTH, -1);
            int temp = batteryStatus.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1);
            String tech = batteryStatus.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY);

            float pct = (level / (float) scale) * 100;
            writeToTerminal(session, "\r\n\033[1;36m[Battery Status]\033[0m\r\n" +
                    " Level: " + String.format("%.0f%%", pct) + "\r\n" +
                    " Status: " + (status == BatteryManager.BATTERY_STATUS_CHARGING ? "Charging" : "Discharging") + "\r\n" +
                    " Health: " + health + "\r\n" +
                    " Temperature: " + (temp / 10.0) + " °C\r\n" +
                    " Tech: " + (tech != null ? tech : "Li-ion") + "\r\n");
        }
        return true;
    }

    private boolean handleClearHome(TerminalSession session) {
        if (session != null) {
            session.appendToEmulator("\033[H\033[2J");
            writeToTerminal(session, "\033[1;32mKonsole OS v1.0.0-35mos\033[0m (" + TermuxConstants.TERMUX_PACKAGE_NAME + ")\r\nType '\033[1;36mkons help\033[0m' for available commands.\r\n");
        }
        return true;
    }

    private boolean handleLaunch(Context context, TerminalSession session, String rawLine, String[] parts) {
        if (parts.length < 2) {
            writeToTerminal(session, "\r\nUsage: launch <app-name or package-name>\r\nExample: launch chrome, launch YouTube, launch settings\r\n");
            return true;
        }

        String target = rawLine.substring(rawLine.indexOf(' ') + 1).trim();
        if (tryLaunchAppByLabel(context, session, target)) {
            return true;
        }

        PackageManager pm = context.getPackageManager();
        try {
            Intent intent = pm.getLaunchIntentForPackage(target);
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(intent);
                writeToTerminal(session, "\r\n\033[1;32m[Konsole OS]\033[0m Launching \033[1;36m" + target + "\033[0m...\r\n");
                return true;
            }
        } catch (Exception ignored) {}

        writeToTerminal(session, "\r\n\033[1;31m[Launch Error]\033[0m Application '\033[1;33m" + target + "\033[0m' not found. Type '\033[1;36mkons apps\033[0m' to view installed applications.\r\n");
        return true;
    }

    private boolean handleUninstall(Context context, TerminalSession session, String[] parts) {
        if (parts.length < 2) {
            writeToTerminal(session, "\r\nUsage: uninstall <app-name or package-name>\r\n");
            return true;
        }

        String target = parts[1].toLowerCase();
        PackageManager pm = context.getPackageManager();
        List<ApplicationInfo> apps = pm.getInstalledApplications(PackageManager.GET_META_DATA);
        String pkgToUninstall = target;

        for (ApplicationInfo app : apps) {
            String label = pm.getApplicationLabel(app).toString().toLowerCase();
            if (label.equals(target)) {
                pkgToUninstall = app.packageName;
                break;
            }
        }

        writeToTerminal(session, "\r\n\033[1;33m[Uninstall]\033[0m Prompting uninstall for package: " + pkgToUninstall + "\r\n");
        Intent uIntent = new Intent(Intent.ACTION_DELETE);
        uIntent.setData(Uri.parse("package:" + pkgToUninstall));
        uIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(uIntent);
        return true;
    }

    private boolean openDashboardActivity(Context context, TerminalSession session) {
        if (context == null) return false;
        Intent intent = new Intent(context, KonsoleDashboardActivity.class);
        if (!(context instanceof android.app.Activity)) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }
        context.startActivity(intent);
        writeToTerminal(session, "\r\n\033[1;32m[Konsole OS]\033[0m Opening Konsole Dashboard...\r\n");
        return true;
    }

    private boolean openMacroLabActivity(Context context, TerminalSession session) {
        if (context == null) return false;
        Intent intent = new Intent(context, KonsoleMacroLabActivity.class);
        if (!(context instanceof android.app.Activity)) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }
        context.startActivity(intent);
        writeToTerminal(session, "\r\n\033[1;32m[Konsole OS]\033[0m Opening Konsole Macro Lab...\r\n");
        return true;
    }

    private boolean dispatchMacroCommand(Context context, TerminalSession session, String rawLine, String[] parts) {
        if ("macrolab".equalsIgnoreCase(parts[0])) {
            return openMacroLabActivity(context, session);
        }
        String args = rawLine.length() > parts[0].length()
                ? rawLine.substring(parts[0].length()).trim()
                : "";
        return runMacroCliToSession(context, session, args);
    }

    private boolean runMacroCliToSession(Context context, TerminalSession session, String args) {
        final BridgeState bridgeState = mBridgeState.get();
        final StringBuilder out = new StringBuilder();
        KonsoleAutomationEngine.getInstance().handleMacroCli(context, new CommandSink() {
            @Override
            public void write(String text) {
                if (bridgeState != null && bridgeState.sink != null) {
                    bridgeState.sink.write(text);
                } else if (text != null) {
                    out.append(text);
                }
            }

            @Override
            public void finish(int exitCode) {
                if (bridgeState == null || bridgeState.sink == null) {
                    writeToTerminal(session, "\r\n" + out.toString());
                }
            }
        }, args);
        return true;
    }

    private boolean handleSettings(Context context, TerminalSession session, String[] parts) {
        String sub = parts.length >= 2 ? parts[1].toLowerCase() : "";
        Intent intent = new Intent(Settings.ACTION_SETTINGS);
        if (sub.equals("wifi")) intent = new Intent(Settings.ACTION_WIFI_SETTINGS);
        else if (sub.equals("bt") || sub.equals("bluetooth")) intent = new Intent(Settings.ACTION_BLUETOOTH_SETTINGS);
        else if (sub.equals("apps")) intent = new Intent(Settings.ACTION_APPLICATION_SETTINGS);
        else if (sub.equals("display")) intent = new Intent(Settings.ACTION_DISPLAY_SETTINGS);

        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
        writeToTerminal(session, "\r\n\033[1;32m[Settings]\033[0m Opening Android System Settings...\r\n");
        return true;
    }

    private void sendNotification(Context context, String title, String message) {
        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(NOTIF_CHANNEL_ID, "Konsole OS Notifications", NotificationManager.IMPORTANCE_DEFAULT);
            nm.createNotificationChannel(channel);
        }

        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, NOTIF_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setContentText(message)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true);

        nm.notify((int) System.currentTimeMillis(), builder.build());
    }

    private void showToast(Context context, String message) {
        Context uiCtx = KonsoleFishBridge.getInstance().getUiContext(context);
        if (uiCtx instanceof android.app.Activity) {
            ((android.app.Activity) uiCtx).runOnUiThread(() -> Toast.makeText(uiCtx, message, Toast.LENGTH_SHORT).show());
        }
    }

    public String getPrompt(TerminalSession session) {
        String cwd = session != null ? session.getCwd() : null;
        if (cwd == null || cwd.isEmpty()) cwd = "~";
        String home = TermuxConstants.TERMUX_HOME_DIR_PATH;
        if (cwd.equals(home)) cwd = "~";
        else if (cwd.startsWith(home)) cwd = "~" + cwd.substring(home.length());

        return "\033[1;36mkonsole\033[1;32m@\033[1;35mos\033[0m:\033[1;34m" + cwd + "\033[0m\033[1;32m$\033[0m ";
    }

    private void writeToSinkOrSession(BridgeState bridgeState, TerminalSession session, String output) {
        if (bridgeState != null && bridgeState.sink != null) {
            bridgeState.sink.write(output);
        } else {
            writeToTerminal(session, output);
        }
    }

    private void writeToTerminal(TerminalSession session, String output) {
        BridgeState bridgeState = mBridgeState.get();
        if (bridgeState != null && bridgeState.sink != null) {
            if (output != null && !output.isEmpty()) {
                bridgeState.sink.write(output);
            }
            return;
        }
        if (session != null) {
            String prompt = getPrompt(session);
            if (output != null && !output.isEmpty()) {
                session.appendToEmulator(output + (output.endsWith("\r\n") || output.endsWith("\n") ? "" : "\r\n") + prompt);
            } else {
                session.appendToEmulator(prompt);
            }
        }
    }
}
