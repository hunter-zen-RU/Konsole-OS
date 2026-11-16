package com.termux.app.konsole;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

/**
 * Represents an installed Konsole OS plugin located at $konsole/plugins/{dirName}/.
 */
public class KonsolePlugin {

    public static final String TYPE_LAUNCHABLE = "launchable";
    public static final String TYPE_EXTENTION = "extention";
    public static final String TYPE_EXTENSION_ALT = "extension";

    public String dirName;
    public File directory;
    public File manifestFile;
    public File iconFile;

    public String name;
    public String description;
    public String version;
    public String build; // "android" or "linux"
    public String konsoleTargetVersion;
    public String type; // "launchable" or "extention"
    public String startingFile; // defaults to base.<ext>
    public String author;
    public String category;
    public String license;
    public String homepage;
    public boolean enabled = true;

    public final List<String> deps = new ArrayList<>();
    public final List<String> permissions = new ArrayList<>();
    public final List<String> flatFiles = new ArrayList<>();
    public final List<ExtensionShadeTile> shadeTiles = new ArrayList<>();
    public final List<String> cliCommands = new ArrayList<>();
    public String hudChipLabel = "";
    public String hudChipCommand = "";
    public String sha256 = "";
    public String filesTreeRaw = "[]";

    public static class ExtensionShadeTile {
        public String id;
        public String title;
        public String subtitle;
        public String command;
    }

    public boolean isLaunchable() {
        return TYPE_LAUNCHABLE.equalsIgnoreCase(type != null ? type.trim() : "");
    }

    public boolean isExtension() {
        if (type == null) return false;
        String t = type.trim().toLowerCase();
        return TYPE_EXTENTION.equals(t) || TYPE_EXTENSION_ALT.equals(t);
    }

    public String getNormalizedType() {
        if (isLaunchable()) return TYPE_LAUNCHABLE;
        if (isExtension()) return TYPE_EXTENTION;
        return type != null && !type.trim().isEmpty() ? type.trim().toLowerCase() : TYPE_LAUNCHABLE;
    }

    public File getEntryFile() {
        if (directory == null || startingFile == null || startingFile.trim().isEmpty()) {
            return null;
        }
        return new File(directory, startingFile.trim());
    }

    public String getEntryExtension() {
        if (startingFile == null) return "";
        int dot = startingFile.lastIndexOf('.');
        if (dot >= 0 && dot < startingFile.length() - 1) {
            return startingFile.substring(dot + 1).toLowerCase();
        }
        return "";
    }

    public static KonsolePlugin fromDirectory(File pluginDir) {
        if (pluginDir == null || !pluginDir.isDirectory()) return null;
        KonsolePlugin plugin = new KonsolePlugin();
        plugin.dirName = pluginDir.getName();
        plugin.directory = pluginDir;
        plugin.manifestFile = new File(pluginDir, "manifest.json");
        plugin.iconFile = new File(pluginDir, "app.png");

        if (plugin.manifestFile.isFile()) {
            String raw = KonsolePluginManager.readFileUtf8(plugin.manifestFile);
            try {
                JSONObject json = new JSONObject(raw);
                plugin.name = json.optString("name", plugin.dirName).trim();
                if (plugin.name.isEmpty()) plugin.name = plugin.dirName;
                plugin.description = json.optString("description", "Konsole OS Plugin").trim();
                plugin.version = json.optString("version", "1.0.0").trim();
                plugin.build = json.optString("build", "android").trim().toLowerCase();
                plugin.konsoleTargetVersion = json.optString("konsoleTargetVersion",
                        json.optString("targetVersion", "1.0.0")).trim();
                plugin.type = json.optString("type", TYPE_LAUNCHABLE).trim().toLowerCase();
                plugin.startingFile = json.optString("startingFile",
                        json.optString("entry", "")).trim();
                plugin.author = json.optString("author", "Konsole Developer").trim();
                plugin.category = json.optString("category", "utility").trim();
                plugin.license = json.optString("license", "MIT").trim();
                plugin.homepage = json.optString("homepage", "").trim();
                plugin.enabled = json.optBoolean("enabled", true);

                String iconRel = json.optString("icon", "app.png").trim();
                if (!iconRel.isEmpty()) {
                    plugin.iconFile = new File(pluginDir, iconRel);
                }

                JSONArray depsArr = json.optJSONArray("deps");
                if (depsArr != null) {
                    for (int i = 0; i < depsArr.length(); i++) {
                        String d = depsArr.optString(i, "").trim();
                        if (!d.isEmpty()) plugin.deps.add(d);
                    }
                }

                JSONArray permsArr = json.optJSONArray("permissions");
                if (permsArr == null) permsArr = json.optJSONArray("androidPermissions");
                if (permsArr != null) {
                    for (int i = 0; i < permsArr.length(); i++) {
                        String p = permsArr.optString(i, "").trim();
                        if (!p.isEmpty()) plugin.permissions.add(p);
                    }
                }

                Object filesObj = json.opt("files");
                if (filesObj != null) {
                    plugin.filesTreeRaw = filesObj.toString();
                    flattenFilesTree(filesObj, "", plugin.flatFiles);
                }

                plugin.sha256 = json.optString("sha256", "").trim();

                JSONObject hooks = json.optJSONObject("hooks");
                if (hooks != null) {
                    JSONArray tilesArr = hooks.optJSONArray("shadeTiles");
                    if (tilesArr != null) {
                        for (int i = 0; i < tilesArr.length(); i++) {
                            JSONObject tileObj = tilesArr.optJSONObject(i);
                            if (tileObj == null) continue;
                            ExtensionShadeTile t = new ExtensionShadeTile();
                            t.id = tileObj.optString("id", "ext_" + plugin.dirName + "_" + i).trim();
                            t.title = tileObj.optString("title", plugin.name).trim();
                            t.subtitle = tileObj.optString("subtitle", "EXT").trim();
                            t.command = tileObj.optString("command", "").trim();
                            if (!t.id.isEmpty() && !t.command.isEmpty()) {
                                plugin.shadeTiles.add(t);
                            }
                        }
                    }
                    JSONObject hudChip = hooks.optJSONObject("hudChip");
                    if (hudChip != null) {
                        plugin.hudChipLabel = hudChip.optString("label", "").trim();
                        plugin.hudChipCommand = hudChip.optString("command", "").trim();
                    }
                    JSONArray cmdsArr = hooks.optJSONArray("cliCommands");
                    if (cmdsArr != null) {
                        for (int i = 0; i < cmdsArr.length(); i++) {
                            String c = cmdsArr.optString(i, "").trim();
                            if (!c.isEmpty()) plugin.cliCommands.add(c);
                        }
                    }
                }
            } catch (Exception ignored) {
                plugin.name = plugin.dirName;
                plugin.description = "Plugin with malformed manifest.json";
                plugin.version = "1.0.0";
                plugin.build = "android";
                plugin.konsoleTargetVersion = "1.0.0";
                plugin.type = TYPE_LAUNCHABLE;
            }
        } else {
            plugin.name = plugin.dirName;
            plugin.description = "Unmanifested plugin in $konsole/plugins/" + plugin.dirName;
            plugin.version = "1.0.0";
            plugin.build = "android";
            plugin.konsoleTargetVersion = "1.0.0";
            plugin.type = TYPE_LAUNCHABLE;
        }

        if (plugin.startingFile == null || plugin.startingFile.isEmpty()) {
            plugin.startingFile = detectDefaultStartingFile(pluginDir);
        }
        if (plugin.flatFiles.isEmpty()) {
            collectDiskFiles(pluginDir, "", plugin.flatFiles);
        }
        return plugin;
    }

    public static String detectDefaultStartingFile(File pluginDir) {
        if (pluginDir == null || !pluginDir.isDirectory()) return "base.sh";
        String[] candidates = new String[]{
                "base.html", "base.js", "base.sh", "base.kt", "base.java",
                "index.html", "main.sh", "main.js", "run.sh"
        };
        for (String c : candidates) {
            if (new File(pluginDir, c).isFile()) {
                return c;
            }
        }
        File[] files = pluginDir.listFiles();
        if (files != null) {
            List<String> names = new ArrayList<>();
            for (File f : files) {
                if (f.isFile()) {
                    String n = f.getName().toLowerCase();
                    if (n.endsWith(".html") || n.endsWith(".js") || n.endsWith(".sh")
                            || n.endsWith(".kt") || n.endsWith(".java")) {
                        names.add(f.getName());
                    }
                }
            }
            Collections.sort(names);
            if (!names.isEmpty()) return names.get(0);
        }
        return "base.sh";
    }

    private static void flattenFilesTree(Object node, String prefix, List<String> out) {
        if (node instanceof JSONArray) {
            JSONArray arr = (JSONArray) node;
            for (int i = 0; i < arr.length(); i++) {
                Object item = arr.opt(i);
                if (item instanceof String) {
                    String s = ((String) item).trim();
                    if (!s.isEmpty()) out.add(prefix.isEmpty() ? s : prefix + "/" + s);
                } else if (item instanceof JSONObject) {
                    flattenFilesTree(item, prefix, out);
                }
            }
        } else if (node instanceof JSONObject) {
            JSONObject obj = (JSONObject) node;
            if (obj.has("name")) {
                String n = obj.optString("name", "").trim();
                String t = obj.optString("type", "file").trim().toLowerCase();
                String path = prefix.isEmpty() ? n : prefix + "/" + n;
                if ("dir".equals(t) || "directory".equals(t) || obj.has("children")) {
                    JSONArray children = obj.optJSONArray("children");
                    if (children != null) {
                        flattenFilesTree(children, path, out);
                    }
                } else if (!n.isEmpty()) {
                    out.add(path);
                }
            } else {
                Iterator<String> keys = obj.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    Object val = obj.opt(key);
                    String nextPrefix = prefix.isEmpty() ? key : prefix + "/" + key;
                    if (val instanceof JSONArray || val instanceof JSONObject) {
                        flattenFilesTree(val, nextPrefix, out);
                    } else {
                        out.add(nextPrefix);
                    }
                }
            }
        }
    }

    private static void collectDiskFiles(File dir, String prefix, List<String> out) {
        File[] children = dir.listFiles();
        if (children == null) return;
        for (File c : children) {
            String rel = prefix.isEmpty() ? c.getName() : prefix + "/" + c.getName();
            if (c.isDirectory()) {
                collectDiskFiles(c, rel, out);
            } else {
                out.add(rel);
            }
        }
    }
}
