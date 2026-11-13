package com.termux.app.konsole;

import android.content.Context;
import android.util.Log;

import com.termux.shared.termux.TermuxConstants;

import java.io.File;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Component 1: Command/Path Indexer
 * Scans directories listed in $PATH and $konsole/bin in memory.
 * Caches executable binary names on startup and refreshes asynchronously so keystrokes never lag from disk I/O.
 */
public class KonsolePathIndexer {

    private static final String TAG = "KonsolePathIndexer";
    private static KonsolePathIndexer sInstance;

    private final Set<String> mPathExecutables = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());
    private boolean mIsInitialized = false;

    private KonsolePathIndexer() {
    }

    public static synchronized KonsolePathIndexer getInstance() {
        if (sInstance == null) {
            sInstance = new KonsolePathIndexer();
        }
        return sInstance;
    }

    public void init(Context context) {
        if (mIsInitialized) return;
        mIsInitialized = true;
        refreshAsync();
    }

    public void refreshAsync() {
        new Thread(() -> {
            try {
                Set<String> newSet = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());

                // 1. Scan $PATH environment variable
                String pathEnv = System.getenv("PATH");
                if (pathEnv == null || pathEnv.isEmpty()) {
                    pathEnv = TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + ":/system/bin:/system/xbin";
                }

                String[] dirs = pathEnv.split(":");
                for (String d : dirs) {
                    scanDirectory(new File(d), newSet);
                }

                // 2. Scan $konsole/bin
                scanDirectory(new File(KonsoleConfigManager.BIN_PATH), newSet);

                // 3. Scan Termux prefix bin if present
                scanDirectory(new File(TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH), newSet);

                mPathExecutables.clear();
                mPathExecutables.addAll(newSet);
                Log.d(TAG, "Indexed " + mPathExecutables.size() + " $PATH executables.");
            } catch (Exception e) {
                Log.e(TAG, "Error indexing PATH executables", e);
            }
        }).start();
    }

    private void scanDirectory(File dir, Set<String> targetSet) {
        if (dir == null || !dir.exists() || !dir.isDirectory()) return;
        try {
            File[] files = dir.listFiles();
            if (files == null) return;
            for (File f : files) {
                if (f.isFile() && (f.canExecute() || f.canRead())) {
                    targetSet.add(f.getName());
                }
            }
        } catch (Exception ignored) {
        }
    }

    public Set<String> getPathExecutables() {
        return mPathExecutables;
    }

    public boolean isExecutable(String cmd) {
        if (cmd == null || cmd.isEmpty()) return false;
        return mPathExecutables.contains(cmd) || mPathExecutables.contains(cmd.toLowerCase());
    }
}
