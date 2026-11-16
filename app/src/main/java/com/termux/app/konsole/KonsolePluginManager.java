package com.termux.app.konsole;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.util.Log;
import android.widget.Toast;

import com.termux.app.TermuxActivity;
import com.termux.shared.termux.TermuxConstants;
import com.termux.terminal.TerminalSession;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Manages Konsole OS plugins under $konsole/plugins/{plugin-name}/:
 * - Seeding sample plugin (system-pulse), prompt.md, and readme.md
 * - Parsing manifest.json (tree files, entry defaults, build, targetVersion, deps, permissions, type)
 * - Filtering launchable vs extention plugins
 * - Fetching and safely extracting GitHub plugin templates from plugins.json
 * - Launching plugins (.html, .js, .sh, .java, .kt) without dynamic DEX/JAR/SO loading
 */
public class KonsolePluginManager {

    private static final String TAG = "KonsolePluginManager";
    public static final String DEFAULT_REGISTRY_URL = "https://hunter-zen-ru.github.io/Konsole-plugins/plugins.json";
    public static final String SAMPLE_PLUGIN_DIR = "system-pulse";

    private static KonsolePluginManager sInstance;

    public static class RemotePluginTemplate {
        public String name;
        public String version;
        public String description;
        public String type; // launchable or extention
        public String url;
        public String sha256;
    }

    public interface InstallCallback {
        void onComplete(boolean success, String message, KonsolePlugin installedPlugin);
    }

    private KonsolePluginManager() {
    }

    public static synchronized KonsolePluginManager getInstance() {
        if (sInstance == null) {
            sInstance = new KonsolePluginManager();
        }
        return sInstance;
    }

    public List<KonsolePlugin> getAllPlugins() {
        List<KonsolePlugin> list = new ArrayList<>();
        File root = new File(KonsoleConfigManager.PLUGINS_PATH);
        if (!root.isDirectory()) return list;
        File[] children = root.listFiles();
        if (children == null) return list;

        for (File child : children) {
            if (child.isDirectory() && !child.getName().startsWith(".")) {
                KonsolePlugin plugin = KonsolePlugin.fromDirectory(child);
                if (plugin != null) {
                    list.add(plugin);
                }
            }
        }
        Collections.sort(list, Comparator.comparing(p -> p.name != null ? p.name.toLowerCase() : ""));
        return list;
    }

    public List<KonsolePlugin> getLaunchablePlugins() {
        List<KonsolePlugin> all = getAllPlugins();
        List<KonsolePlugin> result = new ArrayList<>();
        for (KonsolePlugin p : all) {
            if (p.isLaunchable()) {
                result.add(p);
            }
        }
        return result;
    }

    public List<KonsolePlugin> getExtensionPlugins() {
        List<KonsolePlugin> all = getAllPlugins();
        List<KonsolePlugin> result = new ArrayList<>();
        for (KonsolePlugin p : all) {
            if (!p.isLaunchable()) {
                result.add(p);
            }
        }
        return result;
    }

    public KonsolePlugin findPluginByName(String query) {
        if (query == null || query.trim().isEmpty()) return null;
        String q = query.trim().toLowerCase();
        for (KonsolePlugin p : getAllPlugins()) {
            if ((p.dirName != null && p.dirName.equalsIgnoreCase(q))
                    || (p.name != null && p.name.equalsIgnoreCase(q))) {
                return p;
            }
        }
        return null;
    }

    public List<KonsolePlugin.ExtensionShadeTile> getExtensionShadeTiles() {
        List<KonsolePlugin.ExtensionShadeTile> result = new ArrayList<>();
        for (KonsolePlugin p : getExtensionPlugins()) {
            if (!p.enabled) continue;
            result.addAll(p.shadeTiles);
        }
        return result;
    }

    public List<KonsolePlugin> getExtensionHudChips() {
        List<KonsolePlugin> result = new ArrayList<>();
        for (KonsolePlugin p : getExtensionPlugins()) {
            if (p.enabled && p.hudChipLabel != null && !p.hudChipLabel.isEmpty()
                    && p.hudChipCommand != null && !p.hudChipCommand.isEmpty()) {
                result.add(p);
            }
        }
        return result;
    }

    public void ensureSeedFiles(Context context) {
        File pluginsDir = new File(KonsoleConfigManager.PLUGINS_PATH);
        if (!pluginsDir.exists() && !pluginsDir.mkdirs()) {
            return;
        }

        seedPromptAndReadme(pluginsDir);
        seedSamplePlugin(pluginsDir);
        seedNetProbeExtensionPlugin(pluginsDir);
    }

    private void seedPromptAndReadme(File pluginsDir) {
        File promptFile = new File(pluginsDir, "prompt.md");
        if (!promptFile.exists()) {
            writeUtf8(promptFile, buildPromptMdContent());
        }
        File readmeFile = new File(pluginsDir, "readme.md");
        if (!readmeFile.exists()) {
            writeUtf8(readmeFile, buildReadmeMdContent());
        }
    }

    private void seedSamplePlugin(File pluginsDir) {
        File sampleDir = new File(pluginsDir, SAMPLE_PLUGIN_DIR);
        if (!sampleDir.exists() && !sampleDir.mkdirs()) return;

        File iconFile = new File(sampleDir, "app.png");
        if (!iconFile.exists() || iconFile.length() == 0) {
            generatePluginIconPng(iconFile, "SP", 0xFF00E5FF, 0xFF00FF88);
        }

        File assetsDir = new File(sampleDir, "assets");
        if (!assetsDir.exists()) assetsDir.mkdirs();
        File scriptsDir = new File(sampleDir, "scripts");
        if (!scriptsDir.exists()) scriptsDir.mkdirs();

        File manifestFile = new File(sampleDir, "manifest.json");
        if (!manifestFile.exists()) {
            String manifestJson =
                "{\n" +
                "  \"name\": \"System Pulse\",\n" +
                "  \"description\": \"Sample launchable Konsole OS telemetry & diagnostics dashboard plugin.\",\n" +
                "  \"version\": \"1.0.0\",\n" +
                "  \"build\": \"android\",\n" +
                "  \"konsoleTargetVersion\": \"1.0.0\",\n" +
                "  \"type\": \"launchable\",\n" +
                "  \"startingFile\": \"base.html\",\n" +
                "  \"icon\": \"app.png\",\n" +
                "  \"author\": \"Konsole OS\",\n" +
                "  \"category\": \"diagnostics\",\n" +
                "  \"license\": \"MIT\",\n" +
                "  \"deps\": [],\n" +
                "  \"permissions\": [\n" +
                "    \"android.permission.ACCESS_NETWORK_STATE\",\n" +
                "    \"android.permission.ACCESS_WIFI_STATE\"\n" +
                "  ],\n" +
                "  \"files\": [\n" +
                "    { \"name\": \"app.png\", \"type\": \"file\" },\n" +
                "    { \"name\": \"manifest.json\", \"type\": \"file\" },\n" +
                "    { \"name\": \"base.html\", \"type\": \"file\" },\n" +
                "    {\n" +
                "      \"name\": \"assets\",\n" +
                "      \"type\": \"dir\",\n" +
                "      \"children\": [\n" +
                "        { \"name\": \"pulse.css\", \"type\": \"file\" }\n" +
                "      ]\n" +
                "    },\n" +
                "    {\n" +
                "      \"name\": \"scripts\",\n" +
                "      \"type\": \"dir\",\n" +
                "      \"children\": [\n" +
                "        { \"name\": \"diagnostics.sh\", \"type\": \"file\" }\n" +
                "      ]\n" +
                "    }\n" +
                "  ]\n" +
                "}\n";
            writeUtf8(manifestFile, manifestJson);
        }

        File cssFile = new File(assetsDir, "pulse.css");
        if (!cssFile.exists()) {
            writeUtf8(cssFile,
                ":root { color-scheme: dark; }\n" +
                "body { margin:0; padding:16px; background:#080C10; color:#E6EDF3; font-family:monospace; }\n" +
                ".card { background:#0F1620; border:1px solid #1F2D3D; border-radius:12px; padding:14px; margin-bottom:12px; }\n" +
                ".title { color:#00E5FF; font-size:16px; font-weight:bold; margin-bottom:6px; }\n" +
                ".grid { display:grid; grid-template-columns:1fr 1fr; gap:10px; }\n" +
                ".kv { background:#0A1017; border:1px solid #182330; border-radius:8px; padding:10px; }\n" +
                ".k { color:#8B949E; font-size:11px; }\n" +
                ".v { color:#00FF88; font-size:14px; font-weight:bold; margin-top:4px; }\n" +
                ".btn { background:#112633; color:#00E5FF; border:1px solid #1F4B63; border-radius:8px; padding:10px 14px; font-family:monospace; font-weight:bold; margin-right:8px; margin-top:6px; }\n");
        }

        File shFile = new File(scriptsDir, "diagnostics.sh");
        if (!shFile.exists()) {
            writeUtf8(shFile,
                "#!/system/bin/sh\n" +
                "echo \"[System Pulse] Device: $(uname -a)\"\n" +
                "echo \"[System Pulse] Konsole Root: ${konsole:-/storage/emulated/0/Konsole}\"\n");
        }

        File htmlFile = new File(sampleDir, "base.html");
        if (!htmlFile.exists()) {
            writeUtf8(htmlFile,
                "<!DOCTYPE html>\n" +
                "<html lang=\"en\">\n" +
                "<head>\n" +
                "  <meta charset=\"utf-8\" />\n" +
                "  <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\" />\n" +
                "  <title>System Pulse · Konsole Plugin</title>\n" +
                "  <link rel=\"stylesheet\" href=\"assets/pulse.css\" />\n" +
                "</head>\n" +
                "<body>\n" +
                "  <div class=\"card\">\n" +
                "    <div class=\"title\">● SYSTEM PULSE PLUGIN</div>\n" +
                "    <div style=\"color:#8B949E;font-size:12px;\">Sample launchable plugin ($konsole/plugins/system-pulse/base.html)</div>\n" +
                "  </div>\n" +
                "  <div class=\"card\">\n" +
                "    <div class=\"grid\" id=\"telemetry\">\n" +
                "      <div class=\"kv\"><div class=\"k\">CPU</div><div class=\"v\" id=\"v_cpu\">--</div></div>\n" +
                "      <div class=\"kv\"><div class=\"k\">RAM</div><div class=\"v\" id=\"v_ram\">--</div></div>\n" +
                "      <div class=\"kv\"><div class=\"k\">BATTERY</div><div class=\"v\" id=\"v_bat\">--</div></div>\n" +
                "      <div class=\"kv\"><div class=\"k\">NETWORK</div><div class=\"v\" id=\"v_net\">--</div></div>\n" +
                "    </div>\n" +
                "  </div>\n" +
                "  <div class=\"card\">\n" +
                "    <button class=\"btn\" onclick=\"refreshTelemetry()\">REFRESH</button>\n" +
                "    <button class=\"btn\" onclick=\"runToast()\">PING OS</button>\n" +
                "    <button class=\"btn\" onclick=\"runSys()\">KONS SYS</button>\n" +
                "    <pre id=\"output\" style=\"margin-top:12px;color:#9FB0C3;font-size:12px;white-space:pre-wrap;\">Ready.</pre>\n" +
                "  </div>\n" +
                "  <script>\n" +
                "    function refreshTelemetry() {\n" +
                "      if (typeof Konsole === 'undefined') return;\n" +
                "      var data = JSON.parse(Konsole.getAllVariables());\n" +
                "      document.getElementById('v_cpu').innerText = data.cpu_usage || '--';\n" +
                "      document.getElementById('v_ram').innerText = (data.ram_used || '--') + ' (' + (data.ram_percent || '--') + ')';\n" +
                "      document.getElementById('v_bat').innerText = data.battery || '--';\n" +
                "      document.getElementById('v_net').innerText = (data.network_type || '--') + ' · ' + (data.ip_address || '');\n" +
                "    }\n" +
                "    function runToast() {\n" +
                "      if (typeof Konsole !== 'undefined') Konsole.toast('System Pulse active!');\n" +
                "    }\n" +
                "    function runSys() {\n" +
                "      if (typeof Konsole !== 'undefined') {\n" +
                "        document.getElementById('output').innerText = Konsole.execCommand('kons sys');\n" +
                "      }\n" +
                "    }\n" +
                "    refreshTelemetry();\n" +
                "    setInterval(refreshTelemetry, 1500);\n" +
                "  </script>\n" +
                "</body>\n" +
                "</html>\n");
        }
    }

    private void seedNetProbeExtensionPlugin(File pluginsDir) {
        File extDir = new File(pluginsDir, "net-probe-ext");
        if (!extDir.exists() && !extDir.mkdirs()) return;

        File iconFile = new File(extDir, "app.png");
        if (!iconFile.exists() || iconFile.length() == 0) {
            generatePluginIconPng(iconFile, "NP", 0xFFFFB74D, 0xFFFF7B72);
        }

        File manifestFile = new File(extDir, "manifest.json");
        if (!manifestFile.exists()) {
            String manifestJson =
                "{\n" +
                "  \"name\": \"Net Probe Extension\",\n" +
                "  \"description\": \"Rootless network recon & ARP diagnostic extension hooked into Shade tiles.\",\n" +
                "  \"version\": \"1.0.0\",\n" +
                "  \"build\": \"android\",\n" +
                "  \"konsoleTargetVersion\": \"1.0.0\",\n" +
                "  \"type\": \"extention\",\n" +
                "  \"startingFile\": \"base.sh\",\n" +
                "  \"icon\": \"app.png\",\n" +
                "  \"author\": \"Konsole OS\",\n" +
                "  \"category\": \"recon\",\n" +
                "  \"license\": \"MIT\",\n" +
                "  \"deps\": [],\n" +
                "  \"permissions\": [],\n" +
                "  \"hooks\": {\n" +
                "    \"shadeTiles\": [\n" +
                "      {\n" +
                "        \"id\": \"ext_net_arp\",\n" +
                "        \"title\": \"NET ARP\",\n" +
                "        \"subtitle\": \"IFACE/ARP\",\n" +
                "        \"command\": \"kons net ifaces\"\n" +
                "      }\n" +
                "    ],\n" +
                "    \"hudChip\": {\n" +
                "      \"label\": \"ARP\",\n" +
                "      \"command\": \"kons net arp\"\n" +
                "    },\n" +
                "    \"cliCommands\": [\"kons net ifaces\", \"kons net arp\"]\n" +
                "  },\n" +
                "  \"files\": [\n" +
                "    { \"name\": \"app.png\", \"type\": \"file\" },\n" +
                "    { \"name\": \"manifest.json\", \"type\": \"file\" },\n" +
                "    { \"name\": \"base.sh\", \"type\": \"file\" }\n" +
                "  ]\n" +
                "}\n";
            writeUtf8(manifestFile, manifestJson);
        }

        File baseSh = new File(extDir, "base.sh");
        if (!baseSh.exists()) {
            writeUtf8(baseSh,
                "#!/system/bin/sh\n" +
                "echo \"[Net Probe Extension] Interfaces & Neighbors:\"\n" +
                "konsole-exec kons net ifaces\n");
        }
    }

    public static void generatePluginIconPng(File targetFile, String monogram, int startColor, int endColor) {
        try {
            int size = 192;
            Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bmp);

            Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            bgPaint.setShader(new LinearGradient(0, 0, size, size, startColor, endColor, Shader.TileMode.CLAMP));
            canvas.drawRoundRect(new RectF(8, 8, size - 8, size - 8), 40f, 40f, bgPaint);

            Paint innerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            innerPaint.setColor(0xFF0A0F14);
            canvas.drawRoundRect(new RectF(16, 16, size - 16, size - 16), 34f, 34f, innerPaint);

            Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            textPaint.setColor(startColor);
            textPaint.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
            textPaint.setTextSize(68f);
            textPaint.setTextAlign(Paint.Align.CENTER);
            Paint.FontMetrics fm = textPaint.getFontMetrics();
            float y = (size / 2f) - ((fm.ascent + fm.descent) / 2f);
            String label = (monogram != null && !monogram.isEmpty()) ? monogram.toUpperCase() : "KP";
            if (label.length() > 2) label = label.substring(0, 2);
            canvas.drawText(label, size / 2f, y, textPaint);

            File parent = targetFile.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            try (FileOutputStream fos = new FileOutputStream(targetFile)) {
                bmp.compress(Bitmap.CompressFormat.PNG, 100, fos);
            }
            bmp.recycle();
        } catch (Exception e) {
            Log.w(TAG, "Could not generate plugin icon: " + e.getMessage());
        }
    }

    public List<RemotePluginTemplate> fetchGitHubTemplates(String indexUrl) throws IOException {
        String targetUrl = (indexUrl == null || indexUrl.trim().isEmpty()) ? DEFAULT_REGISTRY_URL : indexUrl.trim();
        HttpURLConnection conn = openWithRedirects(targetUrl);
        try {
            int code = conn.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                throw new IOException("HTTP " + code + " from " + targetUrl);
            }
            String body;
            try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) {
                    sb.append(line).append('\n');
                }
                body = sb.toString();
            }
            return parseRemoteRegistryJson(body);
        } finally {
            conn.disconnect();
        }
    }

    public static List<RemotePluginTemplate> parseRemoteRegistryJson(String jsonBody) {
        List<RemotePluginTemplate> list = new ArrayList<>();
        if (jsonBody == null || jsonBody.trim().isEmpty()) return list;
        try {
            String trimmed = jsonBody.trim();
            JSONArray array;
            if (trimmed.startsWith("[")) {
                array = new JSONArray(trimmed);
            } else {
                JSONObject root = new JSONObject(trimmed);
                array = root.optJSONArray("plugins");
                if (array == null) array = root.optJSONArray("templates");
                if (array == null) array = root.optJSONArray("packages");
            }
            if (array == null) return list;
            for (int i = 0; i < array.length(); i++) {
                JSONObject obj = array.optJSONObject(i);
                if (obj == null) continue;
                RemotePluginTemplate tpl = new RemotePluginTemplate();
                tpl.name = obj.optString("name", "").trim();
                tpl.version = obj.optString("version", "1.0").trim();
                tpl.description = obj.optString("description", "").trim();
                tpl.type = obj.optString("type", KonsolePlugin.TYPE_LAUNCHABLE).trim().toLowerCase();
                tpl.url = obj.optString("url", "").trim();
                tpl.sha256 = obj.optString("sha256", "").trim().toLowerCase();
                if (!tpl.name.isEmpty() && !tpl.url.isEmpty()) {
                    list.add(tpl);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed parsing plugins registry JSON", e);
        }
        return list;
    }

    public void installPluginTemplateAsync(Context context, RemotePluginTemplate template, InstallCallback callback) {
        new Thread(() -> {
            try {
                KonsolePlugin installed = installPluginTemplateBlocking(context, template);
                if (callback != null) {
                    callback.onComplete(true, "Installed plugin '" + installed.name + "'", installed);
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed installing plugin template", e);
                if (callback != null) {
                    callback.onComplete(false, e.getMessage() != null ? e.getMessage() : "Install failed", null);
                }
            }
        }, "KonsolePluginInstaller").start();
    }

    public KonsolePlugin installPluginTemplateBlocking(Context context, RemotePluginTemplate template) throws Exception {
        if (template == null || template.url == null || template.url.trim().isEmpty()) {
            throw new IOException("Plugin template URL is empty");
        }
        String safeDirName = sanitizePluginDirName(template.name);
        File pluginsRoot = new File(KonsoleConfigManager.PLUGINS_PATH);
        if (!pluginsRoot.exists()) pluginsRoot.mkdirs();
        File targetDir = new File(pluginsRoot, safeDirName);

        byte[] zipBytes = downloadBytesWithRedirects(template.url.trim());
        if (template.sha256 != null && !template.sha256.trim().isEmpty()) {
            String actualHash = computeSha256Hex(zipBytes);
            String expectedHash = template.sha256.trim().toLowerCase();
            if (!expectedHash.equalsIgnoreCase(actualHash)) {
                throw new IOException("SHA-256 verification failed for '" + template.name
                        + "' (expected " + expectedHash + ", got " + actualHash + ")");
            }
        }
        File tempExtract = new File(context.getCacheDir(), "plugin_extract_" + safeDirName + "_" + System.currentTimeMillis());
        deleteRecursive(tempExtract);
        if (!tempExtract.mkdirs()) {
            throw new IOException("Unable to create temp extract directory");
        }

        try {
            extractZipSafely(zipBytes, tempExtract);
            File sourceRoot = unwrapSingleRootDir(tempExtract);

            deleteRecursive(targetDir);
            if (!targetDir.mkdirs() && !targetDir.isDirectory()) {
                throw new IOException("Cannot write plugin folder " + targetDir.getAbsolutePath());
            }
            copyRecursive(sourceRoot, targetDir);

            normalizeInstalledPlugin(targetDir, template);
            KonsolePlugin installed = KonsolePlugin.fromDirectory(targetDir);
            if (installed == null) {
                throw new IOException("Plugin directory invalid after extraction");
            }
            return installed;
        } finally {
            deleteRecursive(tempExtract);
        }
    }

    private void normalizeInstalledPlugin(File pluginDir, RemotePluginTemplate template) {
        File iconFile = new File(pluginDir, "app.png");
        if (!iconFile.isFile()) {
            String mono = template.name.length() >= 2 ? template.name.substring(0, 2) : template.name;
            boolean launchable = KonsolePlugin.TYPE_LAUNCHABLE.equalsIgnoreCase(template.type);
            generatePluginIconPng(iconFile, mono,
                    launchable ? 0xFF00E5FF : 0xFFFFB74D,
                    launchable ? 0xFF38BDF8 : 0xFFFF7B72);
        }

        File manifestFile = new File(pluginDir, "manifest.json");
        JSONObject manifest = new JSONObject();
        if (manifestFile.isFile()) {
            try {
                manifest = new JSONObject(readFileUtf8(manifestFile));
            } catch (Exception ignored) {
            }
        }
        try {
            if (!manifest.has("name") || manifest.optString("name").trim().isEmpty()) {
                manifest.put("name", template.name);
            }
            if (!manifest.has("version") || manifest.optString("version").trim().isEmpty()) {
                manifest.put("version", template.version);
            }
            if (!manifest.has("description") || manifest.optString("description").trim().isEmpty()) {
                manifest.put("description", template.description);
            }
            if (!manifest.has("type") || manifest.optString("type").trim().isEmpty()) {
                manifest.put("type", template.type);
            }
            if (!manifest.has("build")) {
                manifest.put("build", "android");
            }
            if (!manifest.has("konsoleTargetVersion")) {
                manifest.put("konsoleTargetVersion", "1.0.0");
            }
            if (!manifest.has("startingFile") && !manifest.has("entry")) {
                manifest.put("startingFile", KonsolePlugin.detectDefaultStartingFile(pluginDir));
            }
            if (!manifest.has("icon")) {
                manifest.put("icon", "app.png");
            }
            if (!manifest.has("deps")) {
                manifest.put("deps", new JSONArray());
            }
            if (!manifest.has("permissions")) {
                manifest.put("permissions", new JSONArray());
            }
            if (!manifest.has("files")) {
                manifest.put("files", buildFilesTreeJson(pluginDir));
            }
            writeUtf8(manifestFile, manifest.toString(2) + "\n");
        } catch (Exception e) {
            Log.w(TAG, "Manifest normalization error: " + e.getMessage());
        }
    }

    public static JSONArray buildFilesTreeJson(File dir) {
        JSONArray arr = new JSONArray();
        File[] children = dir.listFiles();
        if (children == null) return arr;
        Arrays.sort(children, Comparator.comparing(File::getName, String.CASE_INSENSITIVE_ORDER));
        for (File child : children) {
            try {
                JSONObject node = new JSONObject();
                node.put("name", child.getName());
                if (child.isDirectory()) {
                    node.put("type", "dir");
                    node.put("children", buildFilesTreeJson(child));
                } else {
                    node.put("type", "file");
                }
                arr.put(node);
            } catch (Exception ignored) {
            }
        }
        return arr;
    }

    private static final String PREFS_PLUGIN_TRUST = "konsole_plugin_trust_prefs";

    public void launchPlugin(Context context, KonsolePlugin plugin, TerminalSession session) {
        if (context == null || plugin == null) return;
        if (plugin.permissions != null && !plugin.permissions.isEmpty() && context instanceof android.app.Activity) {
            android.content.SharedPreferences prefs = context.getApplicationContext()
                    .getSharedPreferences(PREFS_PLUGIN_TRUST, Context.MODE_PRIVATE);
            String permSig = String.join(",", plugin.permissions);
            String trustKey = "trusted_" + plugin.dirName;
            if (!permSig.equals(prefs.getString(trustKey, ""))) {
                StringBuilder permMsg = new StringBuilder();
                permMsg.append("Plugin '").append(plugin.name).append("' requests access to:\n\n");
                for (String p : plugin.permissions) {
                    permMsg.append("• ").append(p).append("\n");
                }
                if (!plugin.sha256.isEmpty()) {
                    permMsg.append("\nSHA-256: ").append(plugin.sha256);
                }
                permMsg.append("\n\nProceed and trust this plugin?");
                new android.app.AlertDialog.Builder(context, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                        .setTitle("Plugin Permission Review")
                        .setMessage(permMsg.toString())
                        .setPositiveButton("TRUST & LAUNCH", (d, w) -> {
                            prefs.edit().putString(trustKey, permSig).apply();
                            launchPluginInternal(context, plugin, session);
                        })
                        .setNegativeButton("CANCEL", null)
                        .show();
                return;
            }
        }
        launchPluginInternal(context, plugin, session);
    }

    private void launchPluginInternal(Context context, KonsolePlugin plugin, TerminalSession session) {
        String ext = plugin.getEntryExtension();
        File entryFile = plugin.getEntryFile();

        if ("html".equals(ext) || "htm".equals(ext) || "js".equals(ext)
                || "java".equals(ext) || "kt".equals(ext)) {
            Intent intent = new Intent(context, KonsolePluginRunnerActivity.class);
            intent.putExtra(KonsolePluginRunnerActivity.EXTRA_PLUGIN_DIR, plugin.directory.getAbsolutePath());
            if (!(context instanceof android.app.Activity)) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            }
            context.startActivity(intent);
            return;
        }

        // Default shell / script entrypoint (.sh or other CLI executable)
        if (entryFile != null && entryFile.isFile()) {
            entryFile.setExecutable(true, true);
            if (session != null) {
                String cmd = "\u0015sh \"" + entryFile.getAbsolutePath() + "\"\n";
                byte[] bytes = cmd.getBytes(StandardCharsets.UTF_8);
                session.write(bytes, 0, bytes.length);
                if (context instanceof TermuxActivity) {
                    Toast.makeText(context, "Running plugin: " + plugin.name, Toast.LENGTH_SHORT).show();
                }
                return;
            }
        }

        Intent intent = new Intent(context, KonsolePluginRunnerActivity.class);
        intent.putExtra(KonsolePluginRunnerActivity.EXTRA_PLUGIN_DIR, plugin.directory.getAbsolutePath());
        if (!(context instanceof android.app.Activity)) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }
        context.startActivity(intent);
    }

    private static String computeSha256Hex(byte[] data) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(data);
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                String h = Integer.toHexString(0xff & b);
                if (h.length() == 1) sb.append('0');
                sb.append(h);
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    public boolean removePlugin(String dirOrName) {
        KonsolePlugin target = findPluginByName(dirOrName);
        if (target == null || target.directory == null) return false;
        deleteRecursive(target.directory);
        return !target.directory.exists();
    }

    public boolean setPluginEnabled(String dirOrName, boolean enabled) {
        KonsolePlugin target = findPluginByName(dirOrName);
        if (target == null || target.manifestFile == null || !target.manifestFile.isFile()) return false;
        try {
            JSONObject obj = new JSONObject(readFileUtf8(target.manifestFile));
            obj.put("enabled", enabled);
            writeUtf8(target.manifestFile, obj.toString(2) + "\n");
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public static void extractZipSafely(byte[] zipBytes, File targetDir) throws IOException {
        String canonicalDest = targetDir.getCanonicalPath() + File.separator;
        try (ZipInputStream zis = new ZipInputStream(new BufferedInputStream(new java.io.ByteArrayInputStream(zipBytes)))) {
            ZipEntry entry;
            byte[] buffer = new byte[8192];
            while ((entry = zis.getNextEntry()) != null) {
                String name = entry.getName();
                if (name == null || name.isEmpty()) continue;
                File outFile = new File(targetDir, name);
                String canonicalOut = outFile.getCanonicalPath();
                if (!canonicalOut.equals(targetDir.getCanonicalPath()) && !canonicalOut.startsWith(canonicalDest)) {
                    throw new IOException("Blocked unsafe zip entry (Zip Slip): " + name);
                }
                if (entry.isDirectory()) {
                    if (!outFile.exists() && !outFile.mkdirs()) {
                        throw new IOException("Failed creating dir " + outFile);
                    }
                } else {
                    File parent = outFile.getParentFile();
                    if (parent != null && !parent.exists() && !parent.mkdirs()) {
                        throw new IOException("Failed creating parent dir " + parent);
                    }
                    try (FileOutputStream fos = new FileOutputStream(outFile)) {
                        int r;
                        while ((r = zis.read(buffer)) != -1) {
                            fos.write(buffer, 0, r);
                        }
                    }
                }
                zis.closeEntry();
            }
        }
    }

    private static File unwrapSingleRootDir(File extractDir) {
        File[] items = extractDir.listFiles();
        if (items == null) return extractDir;
        List<File> realItems = new ArrayList<>();
        for (File f : items) {
            if (!"__MACOSX".equals(f.getName())) {
                realItems.add(f);
            }
        }
        if (realItems.size() == 1 && realItems.get(0).isDirectory()) {
            return realItems.get(0);
        }
        return extractDir;
    }

    private static void copyRecursive(File src, File dst) throws IOException {
        if (src.isDirectory()) {
            if (!dst.exists() && !dst.mkdirs()) {
                throw new IOException("Cannot create dir " + dst.getAbsolutePath());
            }
            File[] children = src.listFiles();
            if (children != null) {
                for (File child : children) {
                    if ("__MACOSX".equals(child.getName())) continue;
                    copyRecursive(child, new File(dst, child.getName()));
                }
            }
        } else {
            byte[] buf = new byte[8192];
            try (FileInputStream fis = new FileInputStream(src);
                 FileOutputStream fos = new FileOutputStream(dst)) {
                int r;
                while ((r = fis.read(buf)) != -1) {
                    fos.write(buf, 0, r);
                }
            }
        }
    }

    private static void deleteRecursive(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) {
                for (File c : children) deleteRecursive(c);
            }
        }
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    private static String sanitizePluginDirName(String name) {
        String s = (name == null ? "plugin" : name.trim().toLowerCase())
                .replaceAll("[^a-z0-9._-]", "-");
        while (s.contains("..")) s = s.replace("..", "-");
        return s.isEmpty() ? "plugin" : s;
    }

    private HttpURLConnection openWithRedirects(String urlStr) throws IOException {
        String current = urlStr;
        for (int i = 0; i < 5; i++) {
            HttpURLConnection conn = (HttpURLConnection) new URL(current).openConnection();
            conn.setInstanceFollowRedirects(false);
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(15000);
            conn.setRequestProperty("User-Agent", "KonsoleOS-PluginManager/1.0");
            int code = conn.getResponseCode();
            if (code == HttpURLConnection.HTTP_MOVED_PERM || code == HttpURLConnection.HTTP_MOVED_TEMP
                    || code == HttpURLConnection.HTTP_SEE_OTHER || code == 307 || code == 308) {
                String loc = conn.getHeaderField("Location");
                conn.disconnect();
                if (loc == null || loc.trim().isEmpty()) throw new IOException("Redirect without Location");
                current = new URL(new URL(current), loc).toString();
                continue;
            }
            return conn;
        }
        throw new IOException("Too many redirects for " + urlStr);
    }

    private byte[] downloadBytesWithRedirects(String urlStr) throws IOException {
        HttpURLConnection conn = openWithRedirects(urlStr);
        try {
            int code = conn.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                throw new IOException("Download failed with HTTP " + code + " for " + urlStr);
            }
            try (InputStream is = new BufferedInputStream(conn.getInputStream());
                 ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
                byte[] buf = new byte[8192];
                int r;
                while ((r = is.read(buf)) != -1) {
                    baos.write(buf, 0, r);
                }
                return baos.toByteArray();
            }
        } finally {
            conn.disconnect();
        }
    }

    public static String readFileUtf8(File file) {
        if (file == null || !file.isFile()) return "";
        try (FileInputStream fis = new FileInputStream(file);
             ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            byte[] buf = new byte[4096];
            int r;
            while ((r = fis.read(buf)) != -1) baos.write(buf, 0, r);
            return new String(baos.toByteArray(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    private static void writeUtf8(File file, String content) {
        try {
            File parent = file.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            try (FileOutputStream fos = new FileOutputStream(file)) {
                fos.write(content.getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed writing " + file.getAbsolutePath() + ": " + e.getMessage());
        }
    }

    private static String buildPromptMdContent() {
        return "# Konsole OS Plugin Authoring Prompt\n\n" +
                "Copy and paste the prompt below into an AI assistant or use it as your specification when creating a new Konsole OS plugin.\n\n" +
                "```text\n" +
                "Create a complete Konsole OS plugin inside $konsole/plugins/<plugin-name>/.\n\n" +
                "Required directory layout:\n" +
                "$konsole/plugins/<plugin-name>/\n" +
                "  ├── app.png                     (square icon PNG, e.g. 192x192)\n" +
                "  ├── manifest.json               (plugin metadata and file tree)\n" +
                "  ├── base.<html|js|sh|kt|java>   (entrypoint, defaults to base.<ext>)\n" +
                "  └── assets/ or scripts/         (optional supporting files)\n\n" +
                "Requirements for manifest.json:\n" +
                "- name: Human-readable plugin title\n" +
                "- description: Concise description of what the plugin does\n" +
                "- version: Semver string (e.g. \"1.0.0\")\n" +
                "- build: \"android\" or \"linux\"\n" +
                "- konsoleTargetVersion: Target Konsole OS version (e.g. \"1.0.0\")\n" +
                "- type: \"launchable\" (appears in HUD PLG launcher) OR \"extention\" (background/CLI/TUI extension)\n" +
                "- startingFile: Entrypoint filename (defaults to base.<ext>, e.g. \"base.html\" or \"base.sh\")\n" +
                "- icon: \"app.png\"\n" +
                "- deps: Array of required Termux/Konsole dependencies (e.g. [\"python\", \"jq\"])\n" +
                "- permissions: Array of required Android permissions (e.g. [\"android.permission.INTERNET\"])\n" +
                "- files: Tree array describing every file/folder in the plugin:\n" +
                "    [{\"name\":\"app.png\",\"type\":\"file\"}, {\"name\":\"assets\",\"type\":\"dir\",\"children\":[...]}]\n" +
                "- author, category, license, homepage: Additional metadata\n\n" +
                "If startingFile is base.html or base.js, you can call the injected Konsole JS Bridge:\n" +
                "- Konsole.getVariable(key)         -> returns live/custom variable (cpu_usage, battery, ram_used, wifi_ssid, etc.)\n" +
                "- Konsole.getAllVariables()        -> returns JSON string of all live & custom variables\n" +
                "- Konsole.execCommand(cmd)         -> executes a Konsole OS command and returns text output\n" +
                "- Konsole.toast(message)           -> displays an Android toast\n" +
                "- Konsole.getPluginManifest()      -> returns manifest.json string\n" +
                "- Konsole.close()                  -> closes the plugin runner window\n" +
                "```\n";
    }

    private static String buildReadmeMdContent() {
        return "# Konsole OS Plugin System Documentation\n\n" +
                "Plugins live under **`$konsole/plugins/{plugin-name}/`** (`/storage/emulated/0/Konsole/plugins/{plugin-name}/`).\n\n" +
                "## 1. Directory Structure\n\n" +
                "```\n" +
                "$konsole/plugins/{plugin-name}/\n" +
                "├── app.png\n" +
                "├── manifest.json\n" +
                "├── base.<java|kt|js|sh|html>\n" +
                "├── assets/\n" +
                "└── scripts/\n" +
                "```\n\n" +
                "## 2. `manifest.json` Schema\n\n" +
                "| Field | Type | Required | Description |\n" +
                "|---|---|---|---|\n" +
                "| `name` | String | Yes | Display name of the plugin |\n" +
                "| `description` | String | Yes | Short summary of capabilities |\n" +
                "| `version` | String | Yes | Plugin version (e.g. `\"1.0.0\"`) |\n" +
                "| `build` | String | Yes | Target platform: `\"android\"` or `\"linux\"` |\n" +
                "| `konsoleTargetVersion` | String | Yes | Compatible Konsole OS version (e.g. `\"1.0.0\"`) |\n" +
                "| `type` | String | Yes | `\"launchable\"` (shown in HUD PLG launcher) or `\"extention\"` |\n" +
                "| `startingFile` | String | No | Entry file inside plugin dir (defaults to `base.<ext>`) |\n" +
                "| `icon` | String | No | Relative icon path (defaults to `\"app.png\"`) |\n" +
                "| `deps` | String[] | No | Required packages/tools (e.g. `[\"fish\", \"curl\"]`) |\n" +
                "| `permissions` | String[] | No | Required Android permissions |\n" +
                "| `files` | Array/Tree | Yes | Hierarchical tree of plugin files and directories |\n" +
                "| `author` / `category` / `license` | String | No | Additional metadata |\n" +
                "| `sha256` | String | No | Optional SHA-256 digest checked on template install |\n" +
                "| `hooks` | Object | No | Optional `shadeTiles`, `hudChip`, `cliCommands` for extension plugins |\n\n" +
                "### Example `manifest.json`\n\n" +
                "```json\n" +
                "{\n" +
                "  \"name\": \"System Pulse\",\n" +
                "  \"description\": \"Live hardware telemetry widget\",\n" +
                "  \"version\": \"1.0.0\",\n" +
                "  \"build\": \"android\",\n" +
                "  \"konsoleTargetVersion\": \"1.0.0\",\n" +
                "  \"type\": \"launchable\",\n" +
                "  \"startingFile\": \"base.html\",\n" +
                "  \"icon\": \"app.png\",\n" +
                "  \"deps\": [],\n" +
                "  \"permissions\": [\"android.permission.ACCESS_NETWORK_STATE\"],\n" +
                "  \"files\": [\n" +
                "    { \"name\": \"app.png\", \"type\": \"file\" },\n" +
                "    { \"name\": \"manifest.json\", \"type\": \"file\" },\n" +
                "    { \"name\": \"base.html\", \"type\": \"file\" },\n" +
                "    {\n" +
                "      \"name\": \"assets\",\n" +
                "      \"type\": \"dir\",\n" +
                "      \"children\": [\n" +
                "        { \"name\": \"pulse.css\", \"type\": \"file\" }\n" +
                "      ]\n" +
                "    }\n" +
                "  ]\n" +
                "}\n" +
                "```\n\n" +
                "## 3. Plugin Types (`launchable` vs `extention`)\n\n" +
                "- **`launchable`**: Interactive UI apps or scripts (`base.html`, `base.js`, `base.sh`, `base.java`, `base.kt`). Appears in the HUD bar **`PLG`** launcher.\n" +
                "- **`extention`**: CLI tools, Shade tiles (`hooks.shadeTiles`), HUD chips (`hooks.hudChip`), and system extensions (`net-probe-ext` is pre-seeded).\n\n" +
                "## 4. Loading Remote Templates from GitHub\n\n" +
                "Konsole OS reads remote plugin catalogs structured as:\n\n" +
                "```json\n" +
                "{\n" +
                "  \"plugins\": [\n" +
                "    {\n" +
                "      \"name\": \"test\",\n" +
                "      \"version\": \"1.0\",\n" +
                "      \"description\": \"Description here\",\n" +
                "      \"type\": \"launchable\",\n" +
                "      \"url\": \"https://example.com/plugin.zip\"\n" +
                "    }\n" +
                "  ]\n" +
                "}\n" +
                "```\n\n" +
                "Default catalog URL: `https://hunter-zen-ru.github.io/Konsole-plugins/plugins.json`\n\n" +
                "## 5. CLI Commands\n\n" +
                "- `kons plugin list` — List all installed plugins (`launchable` & `extention`)\n" +
                "- `kons plugin launch <name>` — Launch a plugin directly\n" +
                "- `kons plugin templates` — Fetch remote GitHub plugin templates\n" +
                "- `kons plugin install <name> [url]` — Install a plugin from the GitHub catalog\n" +
                "- `kons plugin remove <name>` — Remove an installed plugin\n";
    }
}
