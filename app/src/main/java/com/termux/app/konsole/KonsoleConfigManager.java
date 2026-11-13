package com.termux.app.konsole;

import android.content.Context;
import android.os.Environment;
import android.util.Log;

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
    public static final String BIN_PATH = KONSOLE_ROOT_PATH + "/bin";

    public static final String FILE_UI_XML = CUSTOMIZE_PATH + "/ui.xml";
    public static final String FILE_BEHAVIOR_XML = CUSTOMIZE_PATH + "/behavior.xml";
    public static final String FILE_ALIASES_XML = CUSTOMIZE_PATH + "/aliases.xml";
    public static final String FILE_VARIABLES_XML = CUSTOMIZE_PATH + "/variables.xml";
    public static final String FILE_INIT_KONS = CUSTOMIZE_PATH + "/init.kons";
    public static final String FILE_VARIABLE_TXT = KONSOLE_ROOT_PATH + "/variable.txt";

    private static KonsoleConfigManager sInstance;

    private final Map<String, String> mUserAliases = new ConcurrentHashMap<>();
    private final Map<String, String> mUserVariables = new ConcurrentHashMap<>();
    private final Map<String, String> mUiProperties = new ConcurrentHashMap<>();
    private final Map<String, String> mBehaviorProperties = new ConcurrentHashMap<>();

    private KonsoleConfigManager() {
    }

    public static synchronized KonsoleConfigManager getInstance() {
        if (sInstance == null) {
            sInstance = new KonsoleConfigManager();
        }
        return sInstance;
    }

    public void init(Context context) {
        try {
            if (context != null) {
                com.termux.shared.android.PermissionUtils.checkAndRequestLegacyOrManageExternalStoragePermission(context, -1, false);
            }
            ensureDirectories();
            ensureDefaultXmlFiles();
            loadAllConfigurations();
            generateVariableDocumentation();
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
            BIN_PATH,
            PACKAGES_PATH,
            INSTALLED_PACKAGES_PATH,
            DOWNLOADS_PATH,
            DEB_PATH,
            PLUGINS_PATH
        };
        for (String path : dirs) {
            try {
                File d = new File(path);
                if (!d.exists()) {
                    d.mkdirs();
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
                "    <textColor>#00FF66</textColor>\n" +
                "    <promptColor>#00E5FF</promptColor>\n" +
                "    <fontSize>14</fontSize>\n" +
                "    <cursorStyle>BLOCK</cursorStyle>\n" +
                "    <transparency>0.95</transparency>\n" +
                "    <gridColumns>4</gridColumns>\n" +
                "    <statusBarVisible>true</statusBarVisible>\n" +
                "</ui>\n");

        createFileIfNotExist(FILE_BEHAVIOR_XML,
                "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" +
                "<behavior>\n" +
                "    <mode>konsole</mode>\n" +
                "    <autoCompleteKey>RIGHT_ARROW</autoCompleteKey>\n" +
                "    <hapticFeedback>true</hapticFeedback>\n" +
                "    <greeting>Welcome to Konsole OS v1.0. Type 'kons help' for commands.</greeting>\n" +
                "</behavior>\n");

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
    }

    private void createFileIfNotExist(String filePath, String content) {
        File file = new File(filePath);
        if (!file.exists()) {
            writeTextToFileSafely(filePath, content);
        }
    }

    public synchronized void loadAllConfigurations() {
        loadAliases();
        loadVariables();
        loadPropertiesFromXml(FILE_UI_XML, mUiProperties);
        loadPropertiesFromXml(FILE_BEHAVIOR_XML, mBehaviorProperties);
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
        return !"false".equalsIgnoreCase(mUiProperties.getOrDefault("statusBarVisible", "true"));
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

    public static final String FILE_SUDO_XML = CUSTOMIZE_PATH + "/sudo.xml";
    private boolean mIsSudoLocked = false;

    public synchronized boolean isSudoLocked() {
        return mIsSudoLocked;
    }

    public synchronized void setSudoLocked(boolean locked) {
        this.mIsSudoLocked = locked;
    }

    public synchronized boolean hasSudoPassword() {
        File file = new File(FILE_SUDO_XML);
        return file.exists() && file.length() > 0;
    }

    public synchronized boolean verifySudoPassword(String rawPassword) {
        if (!hasSudoPassword()) return false;
        String passHash = computeSha256(rawPassword);
        String savedHash = getSudoProperty("passwordHash");
        return passHash.equalsIgnoreCase(savedHash);
    }

    public synchronized boolean verifyRescueHash(String rescueHash) {
        if (!hasSudoPassword()) return false;
        String savedRescueHash = getSudoProperty("rescueHash");
        return rescueHash != null && rescueHash.trim().equalsIgnoreCase(savedRescueHash);
    }

    public synchronized String setSudoPassword(String newPassword) {
        String passHash = computeSha256(newPassword);
        String rescueHash = computeSha256("RESCUE_SALT_" + passHash + "_" + newPassword);

        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<sudo>\n");
        sb.append("    <passwordHash>").append(escapeXml(passHash)).append("</passwordHash>\n");
        sb.append("    <rescueHash>").append(escapeXml(rescueHash)).append("</rescueHash>\n");
        sb.append("</sudo>\n");

        writeTextToFileSafely(FILE_SUDO_XML, sb.toString());
        return rescueHash;
    }

    public synchronized boolean resetSudoPasswordWithHash(String rescueHash) {
        if (verifyRescueHash(rescueHash)) {
            File sudoFile = new File(FILE_SUDO_XML);
            if (sudoFile.exists()) sudoFile.delete();
            mIsSudoLocked = false;
            return true;
        }
        return false;
    }

    public synchronized void resetSudoRedoFactoryReset() {
        File root = new File(KONSOLE_ROOT_PATH);
        deleteDirRecursively(root);
        mIsSudoLocked = false;
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

    private String getSudoProperty(String key) {
        File file = new File(FILE_SUDO_XML);
        if (!file.exists()) return null;
        try {
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            DocumentBuilder db = dbf.newDocumentBuilder();
            Document doc = db.parse(file);
            Element root = doc.getDocumentElement();
            NodeList list = root.getElementsByTagName(key);
            if (list.getLength() > 0) {
                return list.item(0).getTextContent().trim();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error reading sudo property " + key, e);
        }
        return null;
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
