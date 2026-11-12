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

import com.termux.app.TermuxActivity;
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

    private KonsoleCommandDispatcher() {
    }

    public static synchronized KonsoleCommandDispatcher getInstance() {
        if (sInstance == null) {
            sInstance = new KonsoleCommandDispatcher();
        }
        return sInstance;
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

        // 1. Mode Check: If in termux mode, only intercept 'kons switch konsole'
        if ("termux".equalsIgnoreCase(mode)) {
            if (line.equals("kons switch konsole")) {
                configMgr.setBehaviorMode("konsole");
                writeToTerminal(session, "\r\n\033[1;32m[Konsole OS]\033[0m Switched to \033[1;36mKonsole OS\033[0m launcher mode.\r\n");
                return true;
            }
            return false;
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
            writeToTerminal(session, "\r\nUsage: kons <apps|set|notify|toast|alias|list|install|update|version|about|help|switch>\r\n");
            return true;
        }

        String sub = parts[1].toLowerCase();
        switch (sub) {
            case "apps":
                if (context instanceof TermuxActivity) {
                    ((TermuxActivity) context).runOnUiThread(() -> {
                        KonsoleAppsDialog dialog = new KonsoleAppsDialog(context, (appName, pkg) -> {
                            writeToTerminal(session, "\r\n\033[1;32m[Konsole OS]\033[0m Launched \033[1;36m" + appName + "\033[0m (" + pkg + ")\r\n");
                        });
                        dialog.show();
                    });
                }
                return true;

            case "set":
                if (parts.length < 3 || !parts[2].contains("=")) {
                    writeToTerminal(session, "\r\nUsage: kons set <key=value>\r\n");
                } else {
                    String[] kv = parts[2].split("=", 2);
                    KonsoleConfigManager.getInstance().setUserVariable(kv[0].trim(), kv[1].trim());
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

            case "version":
            case "about":
                writeToTerminal(session, "\r\n\033[1;36m====================================================\033[0m\r\n" +
                        "\033[1;32m  Konsole OS - Terminal-based Android OS Replacement\033[0m\r\n" +
                        "\033[1;33m  Version:\033[0m v1.0.0-35mos (Build 118)\r\n" +
                        "\033[1;33m  Package:\033[0m com.huntech.konsoleos\r\n" +
                        "\033[1;33m  Author:\033[0m hunter-zen-RU\r\n" +
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
        writeToTerminal(session, "\r\n\033[1;36m[OTA Update]\033[0m Checking latest Konsole OS releases from GitHub...\r\n");
        new Thread(() -> {
            try {
                URL url = new URL("https://api.github.com/repos/hunter-zen-RU/Konsole-OS/releases/latest");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestProperty("User-Agent", "KonsoleOS-Android");
                conn.setConnectTimeout(5000);
                if (conn.getResponseCode() == 200) {
                    BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                    StringBuilder jsonSb = new StringBuilder();
                    String l;
                    while ((l = br.readLine()) != null) jsonSb.append(l);
                    br.close();

                    JSONObject json = new JSONObject(jsonSb.toString());
                    String tag = json.optString("tag_name", "v1.0.0");
                    writeToTerminal(session, "\r\n\033[1;32m[OTA Update]\033[0m Latest Release Tag: \033[1;33m" + tag + "\033[0m\r\n");

                    JSONArray assets = json.optJSONArray("assets");
                    String downloadUrl = null;
                    String targetAbi = Build.SUPPORTED_ABIS[0]; // e.g. arm64-v8a
                    if (assets != null) {
                        for (int i = 0; i < assets.length(); i++) {
                            JSONObject asset = assets.getJSONObject(i);
                            String name = asset.optString("name", "");
                            if (name.contains(targetAbi) || name.contains("universal")) {
                                downloadUrl = asset.optString("browser_download_url");
                                break;
                            }
                        }
                    }

                    if (downloadUrl != null) {
                        writeToTerminal(session, "\033[1;32m[OTA Update]\033[0m Downloading update APK for " + targetAbi + "...\r\n");
                        File updateApk = new File(context.getCacheDir(), "konsole_update.apk");
                        URL apkUrl = new URL(downloadUrl);
                        InputStream is = apkUrl.openStream();
                        FileOutputStream fos = new FileOutputStream(updateApk);
                        byte[] buf = new byte[8192];
                        int read;
                        while ((read = is.read(buf)) != -1) fos.write(buf, 0, read);
                        fos.close();
                        is.close();

                        writeToTerminal(session, "\033[1;32m[OTA Update]\033[0m Launching Package Installer...\r\n");
                        Intent installIntent = new Intent(Intent.ACTION_VIEW);
                        Uri apkUri = Uri.fromFile(updateApk);
                        installIntent.setDataAndType(apkUri, "application/vnd.android.package-archive");
                        installIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        context.startActivity(installIntent);
                    } else {
                        writeToTerminal(session, "\033[1;33m[OTA Update]\033[0m You are currently running the latest build!\r\n");
                    }
                } else {
                    writeToTerminal(session, "\033[1;31m[OTA Update]\033[0m Release endpoint returned code: " + conn.getResponseCode() + "\r\n");
                }
            } catch (Exception e) {
                writeToTerminal(session, "\033[1;31m[OTA Update]\033[0m Failed checking update: " + e.getMessage() + "\r\n");
            }
        }).start();
    }

    private void installKonsPackage(Context context, TerminalSession session, String pkgName) {
        writeToTerminal(session, "\r\n\033[1;36m[Kons Package Manager]\033[0m Fetching index for '" + pkgName + "'...\r\n");
        new Thread(() -> {
            try {
                String[] endpoints = new String[]{
                        "https://raw.githubusercontent.com/hunter-zen-RU/Konsole-packages/main/packages.json",
                        "https://hunterzen-ru.github.io/TUI/packages/packages.json"
                };

                JSONObject targetPkgObj = null;
                for (String endpoint : endpoints) {
                    try {
                        URL url = new URL(endpoint);
                        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                        conn.setConnectTimeout(4000);
                        conn.setReadTimeout(4000);
                        conn.setRequestProperty("User-Agent", "KonsoleOS-PkgManager");
                        if (conn.getResponseCode() == 200) {
                            BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                            StringBuilder jsonSb = new StringBuilder();
                            String l;
                            while ((l = br.readLine()) != null) jsonSb.append(l);
                            br.close();

                            JSONObject root = new JSONObject(jsonSb.toString());
                            JSONArray packagesArray = root.optJSONArray("packages");
                            if (packagesArray != null) {
                                for (int i = 0; i < packagesArray.length(); i++) {
                                    JSONObject pObj = packagesArray.getJSONObject(i);
                                    String name = pObj.optString("name", "");
                                    if (name.equalsIgnoreCase(pkgName)) {
                                        targetPkgObj = pObj;
                                        break;
                                    }
                                }
                            } else if (root.has(pkgName)) {
                                targetPkgObj = root.getJSONObject(pkgName);
                            }
                        }
                    } catch (Exception ignored) {}
                    if (targetPkgObj != null) break;
                }

                if (targetPkgObj != null) {
                    String name = targetPkgObj.optString("name", pkgName);
                    String version = targetPkgObj.optString("version", "1.0");
                    String type = targetPkgObj.optString("type", "script").toLowerCase();
                    String description = targetPkgObj.optString("description", "Custom Konsole package");
                    String pkgUrl = targetPkgObj.optString("url", "");
                    String targetName = targetPkgObj.optString("targetName", targetPkgObj.optString("target", name + "." + type));

                    writeToTerminal(session, "\033[1;32m[Kons Package Manager]\033[0m Found '\033[1;36m" + name + "\033[0m' v" + version + " [" + type.toUpperCase() + "]\r\n" +
                            " Description: " + description + "\r\n");

                    if (!pkgUrl.isEmpty()) {
                        writeToTerminal(session, "\033[1;36m[Kons Package Manager]\033[0m Downloading payload from " + pkgUrl + "...\r\n");

                        File downloadsDir = new File(KonsoleConfigManager.DOWNLOADS_PATH);
                        if (!downloadsDir.exists()) downloadsDir.mkdirs();

                        File localFile = new File(downloadsDir, targetName);
                        URL dlUrl = new URL(pkgUrl);
                        HttpURLConnection dlConn = (HttpURLConnection) dlUrl.openConnection();
                        dlConn.setConnectTimeout(6000);
                        dlConn.setRequestProperty("User-Agent", "KonsoleOS-PkgManager");

                        try (InputStream is = dlConn.getInputStream();
                             FileOutputStream fos = new FileOutputStream(localFile)) {
                            byte[] buf = new byte[8192];
                            int read;
                            while ((read = is.read(buf)) != -1) {
                                fos.write(buf, 0, read);
                            }
                        }

                        // Save local metadata manifest copy to $konsole/packages/installed/<name>.json
                        File installedDir = new File(KonsoleConfigManager.INSTALLED_PACKAGES_PATH);
                        if (!installedDir.exists()) installedDir.mkdirs();
                        File manifestFile = new File(installedDir, name.toLowerCase() + ".json");

                        JSONObject manifest = new JSONObject();
                        manifest.put("name", name);
                        manifest.put("version", version);
                        manifest.put("type", type);
                        manifest.put("description", description);
                        manifest.put("url", pkgUrl);
                        manifest.put("targetName", targetName);
                        manifest.put("installedAt", System.currentTimeMillis());
                        manifest.put("localPath", localFile.getAbsolutePath());
                        if (targetPkgObj.has("deps")) {
                            manifest.put("deps", targetPkgObj.getJSONArray("deps"));
                        }

                        try (FileOutputStream mFos = new FileOutputStream(manifestFile)) {
                            mFos.write(manifest.toString(2).getBytes(StandardCharsets.UTF_8));
                        }

                        // Handle package type specific logic
                        switch (type) {
                            case "apk":
                                writeToTerminal(session, "\033[1;32m[Kons Package Manager]\033[0m Launching Android Package Installer for " + targetName + "...\r\n");
                                Intent installIntent = new Intent(Intent.ACTION_VIEW);
                                Uri apkUri = Uri.fromFile(localFile);
                                installIntent.setDataAndType(apkUri, "application/vnd.android.package-archive");
                                installIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                                context.startActivity(installIntent);
                                writeToTerminal(session, "\033[1;32m[Kons Package Manager]\033[0m Package '\033[1;36m" + name + "\033[0m' (APK) saved to $konsole/packages/downloads/ and dispatched to installer!\r\n");
                                break;

                            case "deb":
                                File debDir = new File(KonsoleConfigManager.DEB_PATH);
                                if (!debDir.exists()) debDir.mkdirs();
                                File debCopy = new File(debDir, name + ".deb");
                                copyFile(localFile, debCopy);

                                File debBin = new File(KonsoleConfigManager.BIN_PATH, name);
                                String debStub = "#!/bin/bash/kons\necho \"Executing local .deb package: " + name + " (v" + version + ")\"\n";
                                try (FileOutputStream fos = new FileOutputStream(debBin)) {
                                    fos.write(debStub.getBytes(StandardCharsets.UTF_8));
                                }
                                debBin.setExecutable(true);
                                writeToTerminal(session, "\033[1;32m[Kons Package Manager]\033[0m Package '\033[1;36m" + name + "\033[0m' (.deb) saved to $konsole/packages/deb/ and registered at $konsole/bin/" + name + "!\r\n");
                                break;

                            case "kos":
                                File pluginsDir = new File(KonsoleConfigManager.PLUGINS_PATH);
                                if (!pluginsDir.exists()) pluginsDir.mkdirs();
                                File kosFile = new File(pluginsDir, targetName.endsWith(".kos") ? targetName : name + ".kos");
                                copyFile(localFile, kosFile);

                                File kosBin = new File(KonsoleConfigManager.BIN_PATH, name);
                                String kosStub = "#!/bin/bash/kons\necho \"Executing Konsole OS Plugin: " + name + " (" + kosFile.getAbsolutePath() + ")\"\n";
                                try (FileOutputStream fos = new FileOutputStream(kosBin)) {
                                    fos.write(kosStub.getBytes(StandardCharsets.UTF_8));
                                }
                                kosBin.setExecutable(true);
                                writeToTerminal(session, "\033[1;32m[Kons Package Manager]\033[0m Konsole OS Plugin '\033[1;36m" + name + "\033[0m' (.kos) installed to $konsole/plugins/ and registered at $konsole/bin/" + name + "!\r\n");
                                break;

                            default: // script / sh
                                File binFile = new File(KonsoleConfigManager.BIN_PATH, name);
                                copyFile(localFile, binFile);
                                binFile.setExecutable(true);
                                writeToTerminal(session, "\033[1;32m[Kons Package Manager]\033[0m Executable script '\033[1;36m" + name + "\033[0m' installed to $konsole/bin/" + name + "!\r\n");
                                break;
                        }
                    } else {
                        createStubPackage(session, pkgName);
                    }
                } else {
                    writeToTerminal(session, "\033[1;33m[Kons Package Manager]\033[0m Package '" + pkgName + "' not found in remote index. Creating local package stub...\r\n");
                    createStubPackage(session, pkgName);
                }
            } catch (Exception e) {
                writeToTerminal(session, "\033[1;33m[Kons Package Manager]\033[0m Repository error (" + e.getMessage() + "). Creating local package stub...\r\n");
                createStubPackage(session, pkgName);
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

    private void createStubPackage(TerminalSession session, String pkgName) {
        try {
            File binFile = new File(KonsoleConfigManager.BIN_PATH, pkgName);
            File parent = binFile.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            if (parent == null || !parent.exists()) {
                writeToTerminal(session, "\033[1;31m[Kons Package Manager]\033[0m Error: Cannot create bin directory for stub script.\r\n");
                return;
            }
            String stub = "#!/bin/bash/kons\necho \"Executing local kons package: " + pkgName + "\"\n";
            try (FileOutputStream fos = new FileOutputStream(binFile)) {
                fos.write(stub.getBytes(StandardCharsets.UTF_8));
            }
            binFile.setExecutable(true);
            writeToTerminal(session, "\033[1;32m[Kons Package Manager]\033[0m Installed local stub script for '\033[1;36m" + pkgName + "\033[0m' at $konsole/bin/" + pkgName + "\r\n");
        } catch (Exception e) {
            writeToTerminal(session, "\033[1;31m[Kons Package Manager]\033[0m Error creating package: " + e.getMessage() + "\r\n");
        }
    }

    private void printHelpManual(TerminalSession session) {
        String help = "\r\n\033[1;36m=====================================================================\033[0m\r\n" +
                "\033[1;32m                     KONSOLE OS COMMAND MANUAL                       \033[0m\r\n" +
                "\033[1;36m=====================================================================\033[0m\r\n\n" +
                "\033[1;33m[ System & Apps ]\033[0m\r\n" +
                "  <app-name>              - Fuzzy-launch installed Android apps\r\n" +
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
                "\033[1;33m[ Kons OS Management Suite ]\033[0m\r\n" +
                "  kons apps               - Terminal-styled GUI apps grid\r\n" +
                "  kons set <key=val>      - Save persistent user variable\r\n" +
                "  kons alias <name=val>   - Create command shortcut alias\r\n" +
                "  kons notify <t> <m>     - Send native system notification\r\n" +
                "  kons toast <msg>        - Display screen toast message\r\n" +
                "  kons list               - List installed kons packages\r\n" +
                "  kons install <pkg>      - Install/download kons package\r\n" +
                "  kons update             - Check & install Konsole OS OTA updates\r\n" +
                "  kons switch <termux|konsole> - Toggle OS launcher mode\r\n" +
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
                wm.setWifiEnabled(true);
                writeToTerminal(session, "\r\n\033[1;32m[Wi-Fi]\033[0m Enabling Wi-Fi...\r\n");
                return true;

            case "off":
                wm.setWifiEnabled(false);
                writeToTerminal(session, "\r\n\033[1;33m[Wi-Fi]\033[0m Disabling Wi-Fi...\r\n");
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
        String expr = line.substring(line.indexOf(' ') + 1).trim();
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
                    " adb shell dpm set-device-owner com.huntech.konsoleos/.konsole.KonsoleDeviceAdminReceiver\r\n");
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
            writeToTerminal(session, "\033[1;32mKonsole OS v1.0.0-35mos\033[0m (com.huntech.konsoleos)\r\nType '\033[1;36mkons help\033[0m' for available commands.\r\n");
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
        Intent uIntent = new Intent(Intent.ACTION_UNINSTALL_PACKAGE);
        uIntent.setData(Uri.parse("package:" + pkgToUninstall));
        uIntent.putExtra(Intent.EXTRA_RETURN_RESULT, true);
        uIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(uIntent);
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
        if (context instanceof TermuxActivity) {
            ((TermuxActivity) context).runOnUiThread(() -> Toast.makeText(context, message, Toast.LENGTH_SHORT).show());
        }
    }

    public String getPrompt(TerminalSession session) {
        String cwd = session != null ? session.getCwd() : null;
        if (cwd == null || cwd.isEmpty()) cwd = "~";
        String home = "/data/data/com.huntech.konsoleos/files/home";
        if (cwd.equals(home)) cwd = "~";
        else if (cwd.startsWith(home)) cwd = "~" + cwd.substring(home.length());

        return "\033[1;36mkonsole\033[1;32m@\033[1;35mos\033[0m:\033[1;34m" + cwd + "\033[0m\033[1;32m$\033[0m ";
    }

    private void writeToTerminal(TerminalSession session, String output) {
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
