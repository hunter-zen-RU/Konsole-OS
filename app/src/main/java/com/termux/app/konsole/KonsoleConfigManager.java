package com.termux.app.konsole;

import android.content.Context;
import android.os.Build;
import android.os.Environment;
import android.util.Log;

import com.termux.shared.android.PermissionUtils;
import com.termux.shared.termux.TermuxConstants;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

/**
 * Manages Konsole OS storage, XML configurations ($konsole/customize/),
 * persistent aliases, user variables, and documentation generation.
 */
public class KonsoleConfigManager {

    private static final String TAG = "KonsoleConfigManager";

    public static final String KONSOLE_ROOT_PATH = Environment.getExternalStorageDirectory().getAbsolutePath() + "/Konsole";
    public static final String CUSTOMIZE_PATH = KONSOLE_ROOT_PATH + "/customize";
    public static final String PROFILES_PATH = CUSTOMIZE_PATH + "/profiles";
    public static final String BIN_PATH = KONSOLE_ROOT_PATH + "/bin";
    public static final String CAPTURES_PATH = KONSOLE_ROOT_PATH + "/captures";

    public static final String FILE_UI_XML = CUSTOMIZE_PATH + "/ui.xml";
    public static final String FILE_BEHAVIOR_XML = CUSTOMIZE_PATH + "/behavior.xml";
    public static final String FILE_SHADE_XML = CUSTOMIZE_PATH + "/shade.xml";
    public static final String FILE_EXPERIENCE_XML = CUSTOMIZE_PATH + "/experience.xml";
    public static final String FILE_DESKTOP_XML = CUSTOMIZE_PATH + "/desktop.xml";
    public static final String FILE_ALIASES_XML = CUSTOMIZE_PATH + "/aliases.xml";
    public static final String FILE_VARIABLES_XML = CUSTOMIZE_PATH + "/variables.xml";
    public static final String FILE_INIT_KONS = CUSTOMIZE_PATH + "/init.kons";
    public static final String FILE_CUSTOMIZE_README = CUSTOMIZE_PATH + "/README.md";
    public static final String FILE_VARIABLE_TXT = KONSOLE_ROOT_PATH + "/variable.txt";

    private static KonsoleConfigManager sInstance;

    private final Map<String, String> mUserAliases = new ConcurrentHashMap<>();
    private final Map<String, String> mUserVariables = new ConcurrentHashMap<>();
    private final Map<String, String> mUiProperties = new ConcurrentHashMap<>();
    private final Map<String, String> mBehaviorProperties = new ConcurrentHashMap<>();
    private final Map<String, String> mShadeProperties = new ConcurrentHashMap<>();
    private final Map<String, String> mExperienceProperties = new ConcurrentHashMap<>();
    private final Map<String, String> mDesktopProperties = new ConcurrentHashMap<>();
    private final Map<String, String> mActiveProfileProperties = new ConcurrentHashMap<>();
    private volatile String mActiveProfileName = "default";

    private KonsoleConfigManager() {
    }

    public static synchronized KonsoleConfigManager getInstance() {
        if (sInstance == null) {
            sInstance = new KonsoleConfigManager();
        }
        return sInstance;
    }

    /** Returns whether this app process can read and write the shared external storage area. */
    public static boolean hasSharedStorageAccess(Context context) {
        if (context == null) return false;
        if (PermissionUtils.checkStoragePermission(context,
                PermissionUtils.isLegacyExternalStoragePossible(context))) {
            return true;
        }
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager();
    }

    public void init(Context context) {
        try {
            if (context == null || !hasSharedStorageAccess(context)) {
                Log.w(TAG, "Shared storage permission is not granted; deferring Konsole storage initialization");
                return;
            }
            if (!Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState())) {
                Log.w(TAG, "Primary shared storage is not mounted; deferring Konsole storage initialization");
                return;
            }
            KonsoleSecurityVault.migrateRemoveLegacyExternalSudoXml();
            ensureDirectories();
            ensureDefaultXmlFiles();
            loadAllConfigurations();
            generateVariableDocumentation();
            KonsolePluginManager.getInstance().ensureSeedFiles(context);
        } catch (Exception e) {
            Log.e(TAG, "Error initializing KonsoleConfigManager", e);
        }
    }

    public static final String PACKAGES_PATH = KONSOLE_ROOT_PATH + "/packages";
    public static final String INSTALLED_PACKAGES_PATH = PACKAGES_PATH + "/installed";
    public static final String DOWNLOADS_PATH = PACKAGES_PATH + "/downloads";
    public static final String DEB_PATH = PACKAGES_PATH + "/deb";
    public static final String PLUGINS_PATH = KONSOLE_ROOT_PATH + "/plugins";

    private void ensureDirectories() {
        String[] dirs = new String[] {
            KONSOLE_ROOT_PATH,
            CUSTOMIZE_PATH,
            PROFILES_PATH,
            BIN_PATH,
            CAPTURES_PATH,
            PACKAGES_PATH,
            INSTALLED_PACKAGES_PATH,
            DOWNLOADS_PATH,
            DEB_PATH,
            PLUGINS_PATH
        };
        for (String path : dirs) {
            try {
                File d = new File(path);
                if (!d.exists() && !d.mkdirs() && !d.isDirectory()) {
                    Log.w(TAG, "Failed creating directory " + path + ": mkdirs returned false");
                }
            } catch (Exception e) {
                Log.w(TAG, "Failed creating directory " + path + ": " + e.getMessage());
            }
        }
    }

    public void writeTextToFileSafely(String filePath, String content) {
        try {
            File file = new File(filePath);
            File parent = file.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            if (parent != null && !parent.exists()) {
                Log.w(TAG, "Cannot write to " + filePath + ": directory creation failed (check storage permissions)");
                return;
            }
            try (FileOutputStream fos = new FileOutputStream(file)) {
                fos.write(content.getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception e) {
            Log.e(TAG, "Error writing file " + filePath + ": " + e.getMessage());
        }
    }

    private void ensureDefaultXmlFiles() {
        createFileIfNotExist(FILE_UI_XML,
                "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" +
                "<ui>\n" +
                "    <backgroundColor>#0B0F14</backgroundColor>\n" +
                "    <terminalBackgroundColor>#000000</terminalBackgroundColor>\n" +
                "    <surfaceColor>#0D1117</surfaceColor>\n" +
                "    <textColor>#00FF88</textColor>\n" +
                "    <promptColor>#00E5FF</promptColor>\n" +
                "    <accentColor>#00E5FF</accentColor>\n" +
                "    <warnColor>#FFD166</warnColor>\n" +
                "    <fontSize>14</fontSize>\n" +
                "    <fontFamily>monospace</fontFamily>\n" +
                "    <cursorStyle>BLOCK</cursorStyle>\n" +
                "    <transparency>0.95</transparency>\n" +
                "    <gridColumns>4</gridColumns>\n" +
                "    <drawerWidthDp>268</drawerWidthDp>\n" +
                "    <statusBarVisible>true</statusBarVisible>\n" +
                "    <hudPosition>top</hudPosition>\n" +
                "    <hudShowMode>true</hudShowMode>\n" +
                "    <hudShowTelemetry>true</hudShowTelemetry>\n" +
                "    <hudShowDash>true</hudShowDash>\n" +
                "    <hudShowApps>true</hudShowApps>\n" +
                "    <hudShowPlugins>true</hudShowPlugins>\n" +
                "    <hudShowCmd>true</hudShowCmd>\n" +
                "    <extraKeysVisible>true</extraKeysVisible>\n" +
                "    <fullscreen>false</fullscreen>\n" +
                "</ui>\n");

        createFileIfNotExist(FILE_BEHAVIOR_XML,
                "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" +
                "<behavior>\n" +
                "    <mode>konsole</mode>\n" +
                "    <autoCompleteKey>RIGHT_ARROW</autoCompleteKey>\n" +
                "    <hapticFeedback>true</hapticFeedback>\n" +
                "    <greeting>Welcome to Konsole OS v1.0.0-premier. Type 'kons help' for commands.</greeting>\n" +
                "    <promptFormat>konsole@os</promptFormat>\n" +
                "    <defaultShell>fish</defaultShell>\n" +
                "    <startupCommands></startupCommands>\n" +
                "</behavior>\n");

        createFileIfNotExist(FILE_SHADE_XML,
                "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" +
                "<shade>\n" +
                "    <enabled>true</enabled>\n" +
                "    <replaceSystemShade>false</replaceSystemShade>\n" +
                "    <swipeDownToOpen>true</swipeDownToOpen>\n" +
                "    <triggerHeightDp>22</triggerHeightDp>\n" +
                "    <backgroundColor>#F2090D12</backgroundColor>\n" +
                "    <cardColor>#101822</cardColor>\n" +
                "    <tileActiveColor>#00E5FF</tileActiveColor>\n" +
                "    <tileInactiveColor>#131C27</tileInactiveColor>\n" +
                "    <textColor>#E6EDF3</textColor>\n" +
                "    <tileColumns>4</tileColumns>\n" +
                "    <blurDimAmount>0.72</blurDimAmount>\n" +
                "    <showClock>true</showClock>\n" +
                "    <showTelemetryStrip>true</showTelemetryStrip>\n" +
                "    <showBrightnessSlider>true</showBrightnessSlider>\n" +
                "    <showMediaControls>false</showMediaControls>\n" +
                "    <showNotifications>true</showNotifications>\n" +
                "    <maxNotifications>15</maxNotifications>\n" +
                "    <tiles>workstation,wifi,bluetooth,pcap,daemon_sshd,daemon_kex,mode,plugins,dashboard,panic,settings,flashlight</tiles>\n" +
                "</shade>\n");

        createFileIfNotExist(FILE_EXPERIENCE_XML,
                "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" +
                "<experience>\n" +
                "    <animationSpeed>1.0</animationSpeed>\n" +
                "    <doubleTapAction>command_palette</doubleTapAction>\n" +
                "    <swipeDownAction>open_shade</swipeDownAction>\n" +
                "    <swipeUpAction>extra_keys</swipeUpAction>\n" +
                "    <longPressHudAction>open_shade</longPressHudAction>\n" +
                "    <backButtonAction>newline_in_konsole</backButtonAction>\n" +
                "    <autoOpenKeyboard>true</autoOpenKeyboard>\n" +
                "    <keepScreenOn>false</keepScreenOn>\n" +
                "    <confirmSessionKill>true</confirmSessionKill>\n" +
                "    <showBootBanner>true</showBootBanner>\n" +
                "    <pluginAutoLoadExtensions>true</pluginAutoLoadExtensions>\n" +
                "</experience>\n");

        createFileIfNotExist(FILE_DESKTOP_XML,
                "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" +
                "<desktop>\n" +
                "    <desktopAutoSwitch>true</desktopAutoSwitch>\n" +
                "    <fontSize>13</fontSize>\n" +
                "    <hudPosition>top</hudPosition>\n" +
                "    <extraKeysVisible>false</extraKeysVisible>\n" +
                "    <drawerWidthDp>320</drawerWidthDp>\n" +
                "    <hardwareKeymap>workstation</hardwareKeymap>\n" +
                "</desktop>\n");

        createFileIfNotExist(PROFILES_PATH + "/default.xml",
                "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" +
                "<profile>\n" +
                "    <name>default</name>\n" +
                "    <description>Balanced Workstation &amp; Shell Profile</description>\n" +
                "    <promptColor>#00E5FF</promptColor>\n" +
                "    <accentColor>#00E5FF</accentColor>\n" +
                "    <textColor>#00FF88</textColor>\n" +
                "</profile>\n");

        createFileIfNotExist(PROFILES_PATH + "/lab.xml",
                "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" +
                "<profile>\n" +
                "    <name>lab</name>\n" +
                "    <description>NetHunter-Style Security &amp; Diagnostics Lab</description>\n" +
                "    <promptColor>#FF5252</promptColor>\n" +
                "    <accentColor>#FF5252</accentColor>\n" +
                "    <textColor>#E6EDF3</textColor>\n" +
                "    <keepScreenOn>true</keepScreenOn>\n" +
                "</profile>\n");

        createFileIfNotExist(PROFILES_PATH + "/stealth.xml",
                "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" +
                "<profile>\n" +
                "    <name>stealth</name>\n" +
                "    <description>Low-distraction monochrome stealth profile</description>\n" +
                "    <promptColor>#8B949E</promptColor>\n" +
                "    <accentColor>#9FB0C3</accentColor>\n" +
                "    <textColor>#C9D1D9</textColor>\n" +
                "    <statusBarVisible>false</statusBarVisible>\n" +
                "</profile>\n");

        createFileIfNotExist(FILE_ALIASES_XML,
                "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" +
                "<aliases>\n" +
                "    <alias name=\"ll\" value=\"ls -la\"/>\n" +
                "    <alias name=\"sys\" value=\"kons about\"/>\n" +
                "</aliases>\n");

        createFileIfNotExist(FILE_VARIABLES_XML,
                "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" +
                "<variables>\n" +
                "    <variable name=\"my_var\" value=\"Konsole User\"/>\n" +
                "</variables>\n");

        createFileIfNotExist(FILE_INIT_KONS,
                "#!/bin/bash/kons\n" +
                "# Konsole OS Startup Script\n" +
                "echo \"Booting Konsole OS...\"\n");

        createFileIfNotExist(FILE_CUSTOMIZE_README,
                "# Konsole OS XML Customization Guide ($konsole/customize)\n\n" +
                "Edit any XML file below and run `kons reload` (or reopen the app) to apply changes:\n\n" +
                "- `ui.xml`: Colors (`backgroundColor`, `terminalBackgroundColor`, `surfaceColor`, `textColor`, `promptColor`, `accentColor`), `fontSize`, `fontFamily` (`monospace`|`sans-serif`|`serif`|ttf path), `cursorStyle` (`BLOCK`|`UNDERLINE`|`BAR`), `transparency`, `gridColumns`, `hudPosition` (`top`|`bottom`), `hudShow*`, `extraKeysVisible`, `fullscreen`.\n" +
                "- `shade.xml`: Custom replacement notification/control shade (`enabled`, `replaceSystemShade`, `swipeDownToOpen`, `triggerHeightDp`, `tileColumns`, `tiles`, `backgroundColor`, `tileActiveColor`, `showClock`, `showTelemetryStrip`, `showBrightnessSlider`, `showMediaControls`, `showNotifications`).\n" +
                "- `experience.xml`: Gestures and UX (`animationSpeed`, `doubleTapAction`, `swipeDownAction`, `swipeUpAction`, `longPressHudAction`, `backButtonAction`, `autoOpenKeyboard`, `keepScreenOn`, `confirmSessionKill`).\n" +
                "- `desktop.xml`: External display / DeX overrides (`desktopAutoSwitch`, `fontSize`, `hudPosition`, `extraKeysVisible`, `drawerWidthDp`).\n" +
                "- `profiles/*.xml`: Workspace profiles (`default`, `lab`, `stealth`) activated with `kons profile <name>`.\n" +
                "- `behavior.xml`: Shell `mode` (`konsole`|`termux`), `hapticFeedback`, `greeting`, `promptFormat`, `startupCommands`.\n" +
                "- `aliases.xml` & `variables.xml`: Persistent Fish/Konsole aliases and `$var` definitions.\n");
    }

    private void createFileIfNotExist(String filePath, String content) {
        File file = new File(filePath);
        if (!file.exists()) {
            writeTextToFileSafely(filePath, content);
        }
    }

    public synchronized void loadAllConfigurations() {
        ensureDirectories();
        loadAliases();
        loadVariables();
        loadPropertiesFromXml(FILE_UI_XML, mUiProperties);
        loadPropertiesFromXml(FILE_BEHAVIOR_XML, mBehaviorProperties);
        loadPropertiesFromXml(FILE_SHADE_XML, mShadeProperties);
        loadPropertiesFromXml(FILE_EXPERIENCE_XML, mExperienceProperties);
        loadPropertiesFromXml(FILE_DESKTOP_XML, mDesktopProperties);
        loadActiveProfileOverrides();
    }

    public synchronized List<String> listProfiles() {
        java.util.List<String> list = new java.util.ArrayList<>();
        File dir = new File(PROFILES_PATH);
        if (!dir.isDirectory()) return list;
        File[] files = dir.listFiles((d, name) -> name.endsWith(".xml"));
        if (files != null) {
            for (File f : files) {
                list.add(f.getName().substring(0, f.getName().length() - 4));
            }
            java.util.Collections.sort(list);
        }
        return list;
    }

    public String getActiveProfile() {
        return mActiveProfileName;
    }

    public synchronized boolean setActiveProfile(String profileName) {
        if (profileName == null || profileName.trim().isEmpty()) return false;
        String clean = profileName.trim().replaceAll("[^a-zA-Z0-9._-]", "");
        File profileFile = new File(PROFILES_PATH, clean + ".xml");
        if (!profileFile.isFile()) return false;
        mActiveProfileName = clean;
        loadAllConfigurations();
        return true;
    }

    private void loadActiveProfileOverrides() {
        mActiveProfileProperties.clear();
        File profileFile = new File(PROFILES_PATH, mActiveProfileName + ".xml");
        if (!profileFile.isFile()) return;
        loadPropertiesFromXml(profileFile.getAbsolutePath(), mActiveProfileProperties);
        for (Map.Entry<String, String> entry : mActiveProfileProperties.entrySet()) {
            String k = entry.getKey();
            if ("name".equals(k) || "description".equals(k)) continue;
            if ("keepScreenOn".equals(k)) {
                mExperienceProperties.put(k, entry.getValue());
            } else {
                mUiProperties.put(k, entry.getValue());
            }
        }
    }

    public String getDesktopProperty(String key, String def) {
        return mDesktopProperties.getOrDefault(key, def);
    }

    public boolean getDesktopBoolean(String key, boolean def) {
        String v = mDesktopProperties.get(key);
        if (v == null || v.trim().isEmpty()) return def;
        return "true".equalsIgnoreCase(v.trim()) || "1".equals(v.trim());
    }

    public int getDesktopInt(String key, int def, int min, int max) {
        try {
            String v = mDesktopProperties.get(key);
            if (v == null || v.trim().isEmpty()) return def;
            return Math.max(min, Math.min(max, Integer.parseInt(v.trim())));
        } catch (Exception ignored) {
            return def;
        }
    }

    private void loadAliases() {
        mUserAliases.clear();
        File file = new File(FILE_ALIASES_XML);
        if (!file.exists()) return;
        try {
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            DocumentBuilder db = dbf.newDocumentBuilder();
            Document doc = db.parse(file);
            NodeList list = doc.getElementsByTagName("alias");
            for (int i = 0; i < list.getLength(); i++) {
                Node node = list.item(i);
                if (node.getNodeType() == Node.ELEMENT_NODE) {
                    Element elem = (Element) node;
                    String name = elem.getAttribute("name");
                    String val = elem.getAttribute("value");
                    if (!name.isEmpty() && !val.isEmpty()) {
                        mUserAliases.put(name, val);
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed loading aliases.xml", e);
        }
    }

    private void loadVariables() {
        mUserVariables.clear();
        File file = new File(FILE_VARIABLES_XML);
        if (!file.exists()) return;
        try {
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            DocumentBuilder db = dbf.newDocumentBuilder();
            Document doc = db.parse(file);
            NodeList list = doc.getElementsByTagName("variable");
            for (int i = 0; i < list.getLength(); i++) {
                Node node = list.item(i);
                if (node.getNodeType() == Node.ELEMENT_NODE) {
                    Element elem = (Element) node;
                    String name = elem.getAttribute("name");
                    String val = elem.getAttribute("value");
                    if (!name.isEmpty()) {
                        mUserVariables.put(name, val);
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed loading variables.xml", e);
        }
    }

    private void loadPropertiesFromXml(String filePath, Map<String, String> targetMap) {
        targetMap.clear();
        File file = new File(filePath);
        if (!file.exists()) return;
        try {
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            DocumentBuilder db = dbf.newDocumentBuilder();
            Document doc = db.parse(file);
            Element root = doc.getDocumentElement();
            NodeList children = root.getChildNodes();
            for (int i = 0; i < children.getLength(); i++) {
                Node node = children.item(i);
                if (node.getNodeType() == Node.ELEMENT_NODE) {
                    targetMap.put(node.getNodeName(), node.getTextContent().trim());
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed loading " + filePath, e);
        }
    }

    public synchronized void setAlias(String name, String value) {
        mUserAliases.put(name, value);
        saveAliasesToXml();
    }

    private void saveAliasesToXml() {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<aliases>\n");
        for (Map.Entry<String, String> entry : mUserAliases.entrySet()) {
            sb.append("    <alias name=\"").append(escapeXml(entry.getKey()))
                    .append("\" value=\"").append(escapeXml(entry.getValue())).append("\"/>\n");
        }
        sb.append("</aliases>\n");
        writeTextToFileSafely(FILE_ALIASES_XML, sb.toString());
    }

    public synchronized void setUserVariable(String name, String value) {
        mUserVariables.put(name, value);
        saveVariablesToXml();
        KonsoleFishBridge.syncStateFiles(TermuxConstants.TERMUX_PREFIX_DIR_PATH);
    }

    private void saveVariablesToXml() {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<variables>\n");
        for (Map.Entry<String, String> entry : mUserVariables.entrySet()) {
            sb.append("    <variable name=\"").append(escapeXml(entry.getKey()))
                    .append("\" value=\"").append(escapeXml(entry.getValue())).append("\"/>\n");
        }
        sb.append("</variables>\n");
        writeTextToFileSafely(FILE_VARIABLES_XML, sb.toString());
    }

    public synchronized void setUiProperty(String key, String value) {
        if (key == null || key.trim().isEmpty()) return;
        mUiProperties.put(key.trim(), value != null ? value.trim() : "");
        savePropertiesToXml(FILE_UI_XML, "ui", mUiProperties);
    }

    public synchronized void setBehaviorProperty(String key, String value) {
        if (key == null || key.trim().isEmpty()) return;
        mBehaviorProperties.put(key.trim(), value != null ? value.trim() : "");
        savePropertiesToXml(FILE_BEHAVIOR_XML, "behavior", mBehaviorProperties);
    }

    public synchronized void setShadeProperty(String key, String value) {
        if (key == null || key.trim().isEmpty()) return;
        mShadeProperties.put(key.trim(), value != null ? value.trim() : "");
        savePropertiesToXml(FILE_SHADE_XML, "shade", mShadeProperties);
    }

    public synchronized void setExperienceProperty(String key, String value) {
        if (key == null || key.trim().isEmpty()) return;
        mExperienceProperties.put(key.trim(), value != null ? value.trim() : "");
        savePropertiesToXml(FILE_EXPERIENCE_XML, "experience", mExperienceProperties);
    }

    public synchronized boolean setCustomizeProperty(String category, String key, String value) {
        if (category == null || key == null) return false;
        switch (category.trim().toLowerCase()) {
            case "ui":
                setUiProperty(key, value);
                return true;
            case "behavior":
                setBehaviorProperty(key, value);
                return true;
            case "shade":
                setShadeProperty(key, value);
                return true;
            case "experience":
                setExperienceProperty(key, value);
                return true;
            default:
                return false;
        }
    }

    public synchronized void resetDefaultCustomizeXml() {
        String[] xmlFiles = new String[]{
                FILE_UI_XML, FILE_BEHAVIOR_XML, FILE_SHADE_XML, FILE_EXPERIENCE_XML, FILE_DESKTOP_XML, FILE_CUSTOMIZE_README
        };
        for (String path : xmlFiles) {
            File f = new File(path);
            if (f.exists()) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
        }
        mActiveProfileName = "default";
        ensureDirectories();
        ensureDefaultXmlFiles();
        loadAllConfigurations();
    }

    private void savePropertiesToXml(String filePath, String rootTag, Map<String, String> properties) {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<").append(rootTag).append(">\n");
        for (Map.Entry<String, String> entry : properties.entrySet()) {
            sb.append("    <").append(entry.getKey()).append(">")
                    .append(escapeXml(entry.getValue()))
                    .append("</").append(entry.getKey()).append(">\n");
        }
        sb.append("</").append(rootTag).append(">\n");
        writeTextToFileSafely(filePath, sb.toString());
    }

    public boolean isStatusBarVisible() {
        return getUiBoolean("statusBarVisible", true);
    }

    public String getUiProperty(String key, String def) {
        return mUiProperties.getOrDefault(key, def);
    }

    public boolean getUiBoolean(String key, boolean def) {
        String v = mUiProperties.get(key);
        if (v == null || v.trim().isEmpty()) return def;
        return "true".equalsIgnoreCase(v.trim()) || "1".equals(v.trim());
    }

    public int getUiInt(String key, int def, int min, int max) {
        try {
            String v = mUiProperties.get(key);
            if (v == null || v.trim().isEmpty()) return def;
            int parsed = Integer.parseInt(v.trim());
            return Math.max(min, Math.min(max, parsed));
        } catch (Exception ignored) {
            return def;
        }
    }

    public float getUiFloat(String key, float def, float min, float max) {
        try {
            String v = mUiProperties.get(key);
            if (v == null || v.trim().isEmpty()) return def;
            float parsed = Float.parseFloat(v.trim());
            return Math.max(min, Math.min(max, parsed));
        } catch (Exception ignored) {
            return def;
        }
    }

    public int getUiColor(String key, int defColor) {
        return parseHexColor(mUiProperties.get(key), defColor);
    }

    public String getShadeProperty(String key, String def) {
        return mShadeProperties.getOrDefault(key, def);
    }

    public boolean getShadeBoolean(String key, boolean def) {
        String v = mShadeProperties.get(key);
        if (v == null || v.trim().isEmpty()) return def;
        return "true".equalsIgnoreCase(v.trim()) || "1".equals(v.trim());
    }

    public int getShadeInt(String key, int def, int min, int max) {
        try {
            String v = mShadeProperties.get(key);
            if (v == null || v.trim().isEmpty()) return def;
            int parsed = Integer.parseInt(v.trim());
            return Math.max(min, Math.min(max, parsed));
        } catch (Exception ignored) {
            return def;
        }
    }

    public float getShadeFloat(String key, float def, float min, float max) {
        try {
            String v = mShadeProperties.get(key);
            if (v == null || v.trim().isEmpty()) return def;
            float parsed = Float.parseFloat(v.trim());
            return Math.max(min, Math.min(max, parsed));
        } catch (Exception ignored) {
            return def;
        }
    }

    public int getShadeColor(String key, int defColor) {
        return parseHexColor(mShadeProperties.get(key), defColor);
    }

    public String getExperienceProperty(String key, String def) {
        return mExperienceProperties.getOrDefault(key, def);
    }

    public boolean getExperienceBoolean(String key, boolean def) {
        String v = mExperienceProperties.get(key);
        if (v == null || v.trim().isEmpty()) return def;
        return "true".equalsIgnoreCase(v.trim()) || "1".equals(v.trim());
    }

    public float getExperienceFloat(String key, float def, float min, float max) {
        try {
            String v = mExperienceProperties.get(key);
            if (v == null || v.trim().isEmpty()) return def;
            float parsed = Float.parseFloat(v.trim());
            return Math.max(min, Math.min(max, parsed));
        } catch (Exception ignored) {
            return def;
        }
    }

    public String getBehaviorProperty(String key, String def) {
        return mBehaviorProperties.getOrDefault(key, def);
    }

    public boolean getBehaviorBoolean(String key, boolean def) {
        String v = mBehaviorProperties.get(key);
        if (v == null || v.trim().isEmpty()) return def;
        return "true".equalsIgnoreCase(v.trim()) || "1".equals(v.trim());
    }

    public static int parseHexColor(String hex, int fallback) {
        if (hex == null || hex.trim().isEmpty()) return fallback;
        try {
            return android.graphics.Color.parseColor(hex.trim());
        } catch (Exception ignored) {
            return fallback;
        }
    }

    public synchronized void setBehaviorMode(String mode) {
        mBehaviorProperties.put("mode", mode);
        savePropertiesToXml(FILE_BEHAVIOR_XML, "behavior", mBehaviorProperties);
        KonsoleFishBridge.syncStateFiles(TermuxConstants.TERMUX_PREFIX_DIR_PATH);
    }

    public String getMode() {
        return mBehaviorProperties.getOrDefault("mode", "konsole");
    }

    public Map<String, String> getUserAliases() {
        return mUserAliases;
    }

    public Map<String, String> getUserVariables() {
        return mUserVariables;
    }

    public Map<String, String> getUiProperties() {
        return mUiProperties;
    }

    public Map<String, String> getBehaviorProperties() {
        return mBehaviorProperties;
    }

    public Map<String, String> getShadeProperties() {
        return mShadeProperties;
    }

    public Map<String, String> getExperienceProperties() {
        return mExperienceProperties;
    }

    public void generateVariableDocumentation() {
        String doc = "=======================================================\n" +
                "      KONSOLE OS LIVE & CUSTOM SYSTEM VARIABLES        \n" +
                "=======================================================\n\n" +
                "$konsole       - Absolute path to Konsole storage (/storage/emulated/0/Konsole)\n" +
                "$time          - Current local time (HH:mm:ss)\n" +
                "$date          - Current local date (yyyy-MM-dd)\n" +
                "$battery       - Battery level percentage and charging status (e.g. 85% [Charging])\n" +
                "$cpu_cores     - Number of available CPU cores\n" +
                "$cpu_usage     - Approximate system CPU usage percentage\n" +
                "$ram_total     - Total RAM memory installed on device\n" +
                "$ram_used      - Currently used RAM memory\n" +
                "$ram_free      - Available free RAM memory\n" +
                "$storage_free  - Available free storage space on device\n" +
                "$device_name   - Hardware device manufacturer & model\n" +
                "$android_version - Android OS version & SDK release\n" +
                "$ip_address    - Local Wi-Fi or mobile network IP address\n" +
                "$wifi_ssid     - Name of the currently connected Wi-Fi SSID\n" +
                "$network_type  - Active network connectivity type (Wi-Fi / Mobile / Disconnected)\n\n" +
                "Custom user-defined variables saved in $konsole/customize/variables.xml\n" +
                "can be defined using 'kons set <var_name=value>'.\n";
        writeTextToFileSafely(FILE_VARIABLE_TXT, doc);
    }

    public synchronized void resetSudoRedoFactoryReset() {
        File root = new File(KONSOLE_ROOT_PATH);
        deleteDirRecursively(root);
        KonsoleSecurityVault.migrateRemoveLegacyExternalSudoXml();
        ensureDirectories();
        ensureDefaultXmlFiles();
        loadAllConfigurations();
    }

    private void deleteDirRecursively(File fileOrDirectory) {
        if (fileOrDirectory.isDirectory()) {
            File[] children = fileOrDirectory.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteDirRecursively(child);
                }
            }
        }
        fileOrDirectory.delete();
    }

    public String computeSha256(String text) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (Exception e) {
            return String.valueOf(text.hashCode());
        }
    }

    private String escapeXml(String str) {
        if (str == null) return "";
        return str.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }
}
