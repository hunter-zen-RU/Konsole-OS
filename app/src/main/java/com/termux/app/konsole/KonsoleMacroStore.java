package com.termux.app.konsole;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Persists user-created macros as human- and script-readable JSON in:
 * 1) $konsole/macros/macros.json (/storage/emulated/0/Konsole/macros/macros.json) when writable
 * 2) <filesDir>/konsole/macros.json as guaranteed internal fallback
 * Also maintains execution history (history.json).
 */
public class KonsoleMacroStore {

    private static final String TAG = "KonsoleMacroStore";
    private static final int MAX_HISTORY_ENTRIES = 60;

    public static class ExecutionRecord {
        public long timestampMs;
        public String macroId;
        public String macroName;
        public String triggerType;
        public boolean constraintsPassed;
        public boolean forced;
        public int exitCode;
        public String summary;
        public String output;

        public JSONObject toJson() {
            JSONObject obj = new JSONObject();
            try {
                obj.put("timestampMs", timestampMs);
                obj.put("macroId", macroId != null ? macroId : "");
                obj.put("macroName", macroName != null ? macroName : "");
                obj.put("triggerType", triggerType != null ? triggerType : "");
                obj.put("constraintsPassed", constraintsPassed);
                obj.put("forced", forced);
                obj.put("exitCode", exitCode);
                obj.put("summary", summary != null ? summary : "");
                obj.put("output", output != null ? output : "");
            } catch (Exception ignored) {
            }
            return obj;
        }

        public static ExecutionRecord fromJson(JSONObject obj) {
            ExecutionRecord r = new ExecutionRecord();
            if (obj == null) return r;
            r.timestampMs = obj.optLong("timestampMs", 0L);
            r.macroId = obj.optString("macroId", "");
            r.macroName = obj.optString("macroName", "");
            r.triggerType = obj.optString("triggerType", "");
            r.constraintsPassed = obj.optBoolean("constraintsPassed", true);
            r.forced = obj.optBoolean("forced", false);
            r.exitCode = obj.optInt("exitCode", 0);
            r.summary = obj.optString("summary", "");
            r.output = obj.optString("output", "");
            return r;
        }
    }

    private static KonsoleMacroStore sInstance;
    private final Object mLock = new Object();

    private KonsoleMacroStore() {
    }

    public static synchronized KonsoleMacroStore getInstance() {
        if (sInstance == null) {
            sInstance = new KonsoleMacroStore();
        }
        return sInstance;
    }

    public File getPrimaryMacrosFile(Context context) {
        return new File(KonsoleConfigManager.KONSOLE_ROOT_PATH + "/macros/macros.json");
    }

    public File getFallbackMacrosFile(Context context) {
        File base = context != null ? context.getFilesDir() : new File("/data/data/com.termux/files");
        return new File(base, "konsole/macros.json");
    }

    public File getHistoryFile(Context context) {
        File primaryDir = new File(KonsoleConfigManager.KONSOLE_ROOT_PATH + "/macros");
        if (canWriteDir(primaryDir)) {
            return new File(primaryDir, "history.json");
        }
        File base = context != null ? context.getFilesDir() : new File("/data/data/com.termux/files");
        return new File(base, "konsole/macro_history.json");
    }

    public String getActiveStoragePath(Context context) {
        File primary = getPrimaryMacrosFile(context);
        if (primary.isFile() || canWriteDir(primary.getParentFile())) {
            return primary.getAbsolutePath();
        }
        return getFallbackMacrosFile(context).getAbsolutePath();
    }

    public List<KonsoleMacroRule> loadMacros(Context context) {
        synchronized (mLock) {
            File primary = getPrimaryMacrosFile(context);
            File fallback = getFallbackMacrosFile(context);
            File source = selectNewestExisting(primary, fallback);

            if (source == null || !source.isFile()) {
                List<KonsoleMacroRule> defaults = new ArrayList<>();
                defaults.add(KonsoleMacroRule.createStarterTemplates().get(2)); // 15-Min Health Heartbeat (disabled initially)
                saveMacrosLocked(context, defaults);
                return defaults;
            }

            try {
                String raw = readUtf8(source);
                if (raw.trim().isEmpty()) return new ArrayList<>();
                JSONObject root = new JSONObject(raw);
                JSONArray arr = root.optJSONArray("macros");
                List<KonsoleMacroRule> result = new ArrayList<>();
                if (arr != null) {
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject item = arr.optJSONObject(i);
                        if (item != null) {
                            result.add(KonsoleMacroRule.fromJson(item));
                        }
                    }
                }
                return result;
            } catch (Exception e) {
                Log.e(TAG, "Failed parsing macros JSON: " + e.getMessage());
                return new ArrayList<>();
            }
        }
    }

    public void saveMacros(Context context, List<KonsoleMacroRule> macros) {
        synchronized (mLock) {
            saveMacrosLocked(context, macros);
        }
    }

    private void saveMacrosLocked(Context context, List<KonsoleMacroRule> macros) {
        try {
            JSONObject root = new JSONObject();
            root.put("version", 1);
            root.put("updatedAtMs", System.currentTimeMillis());
            JSONArray arr = new JSONArray();
            if (macros != null) {
                for (KonsoleMacroRule r : macros) {
                    arr.put(r.toJson());
                }
            }
            root.put("macros", arr);
            String json = root.toString(2);

            writeUtf8(getFallbackMacrosFile(context), json);
            File primary = getPrimaryMacrosFile(context);
            if (canWriteDir(primary.getParentFile())) {
                writeUtf8(primary, json);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed saving macros JSON: " + e.getMessage());
        }
    }

    public KonsoleMacroRule findByIdOrName(Context context, String idOrName) {
        if (idOrName == null || idOrName.trim().isEmpty()) return null;
        String needle = idOrName.trim();
        List<KonsoleMacroRule> all = loadMacros(context);
        for (KonsoleMacroRule r : all) {
            if (needle.equalsIgnoreCase(r.id) || (r.name != null && needle.equalsIgnoreCase(r.name.trim()))) {
                return r;
            }
        }
        for (KonsoleMacroRule r : all) {
            if (r.name != null && r.name.toLowerCase().contains(needle.toLowerCase())) {
                return r;
            }
        }
        return null;
    }

    public void upsertMacro(Context context, KonsoleMacroRule rule) {
        if (rule == null) return;
        synchronized (mLock) {
            List<KonsoleMacroRule> all = loadMacros(context);
            boolean replaced = false;
            for (int i = 0; i < all.size(); i++) {
                if (all.get(i).id.equals(rule.id)) {
                    all.set(i, rule);
                    replaced = true;
                    break;
                }
            }
            if (!replaced) {
                all.add(rule);
            }
            saveMacrosLocked(context, all);
        }
    }

    public boolean deleteMacro(Context context, String macroId) {
        if (macroId == null) return false;
        synchronized (mLock) {
            List<KonsoleMacroRule> all = loadMacros(context);
            boolean removed = false;
            for (int i = all.size() - 1; i >= 0; i--) {
                if (macroId.equals(all.get(i).id)) {
                    all.remove(i);
                    removed = true;
                }
            }
            if (removed) {
                saveMacrosLocked(context, all);
            }
            return removed;
        }
    }

    public void appendHistory(Context context, ExecutionRecord record) {
        if (record == null) return;
        synchronized (mLock) {
            List<ExecutionRecord> list = loadHistory(context);
            list.add(0, record);
            while (list.size() > MAX_HISTORY_ENTRIES) {
                list.remove(list.size() - 1);
            }
            saveHistoryLocked(context, list);
        }
    }

    public List<ExecutionRecord> loadHistory(Context context) {
        synchronized (mLock) {
            File file = getHistoryFile(context);
            if (!file.isFile()) return new ArrayList<>();
            try {
                String raw = readUtf8(file);
                if (raw.trim().isEmpty()) return new ArrayList<>();
                JSONArray arr = new JSONArray(raw);
                List<ExecutionRecord> out = new ArrayList<>();
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.optJSONObject(i);
                    if (o != null) out.add(ExecutionRecord.fromJson(o));
                }
                return out;
            } catch (Exception e) {
                return new ArrayList<>();
            }
        }
    }

    public void clearHistory(Context context) {
        synchronized (mLock) {
            saveHistoryLocked(context, new ArrayList<>());
        }
    }

    private void saveHistoryLocked(Context context, List<ExecutionRecord> list) {
        try {
            JSONArray arr = new JSONArray();
            for (ExecutionRecord r : list) {
                arr.put(r.toJson());
            }
            writeUtf8(getHistoryFile(context), arr.toString(2));
        } catch (Exception e) {
            Log.w(TAG, "Failed saving macro history: " + e.getMessage());
        }
    }

    private static File selectNewestExisting(File primary, File fallback) {
        boolean pOk = primary != null && primary.isFile();
        boolean fOk = fallback != null && fallback.isFile();
        if (pOk && fOk) {
            return primary.lastModified() >= fallback.lastModified() ? primary : fallback;
        }
        if (pOk) return primary;
        if (fOk) return fallback;
        return null;
    }

    private static boolean canWriteDir(File dir) {
        if (dir == null) return false;
        try {
            if (!dir.exists() && !dir.mkdirs()) return false;
            return dir.isDirectory() && dir.canWrite();
        } catch (Exception e) {
            return false;
        }
    }

    private static String readUtf8(File file) throws Exception {
        byte[] buf = new byte[(int) Math.min(file.length(), 2 * 1024 * 1024)];
        int total = 0;
        try (FileInputStream fis = new FileInputStream(file)) {
            int r;
            while (total < buf.length && (r = fis.read(buf, total, buf.length - total)) != -1) {
                total += r;
            }
        }
        return new String(buf, 0, total, StandardCharsets.UTF_8);
    }

    private static void writeUtf8(File file, String content) throws Exception {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        try (FileOutputStream fos = new FileOutputStream(file)) {
            fos.write((content != null ? content : "").getBytes(StandardCharsets.UTF_8));
        }
    }
}
