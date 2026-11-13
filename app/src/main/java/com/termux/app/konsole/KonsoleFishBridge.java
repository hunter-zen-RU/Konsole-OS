package com.termux.app.konsole;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.FileObserver;
import android.util.Log;

import com.termux.shared.termux.TermuxConstants;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Event-driven IPC bridge allowing real Fish shell sessions (and bash/sh subshells)
 * to invoke native Android / Konsole OS commands and receive stdout + exit codes cleanly.
 */
public class KonsoleFishBridge {

    private static final String TAG = "KonsoleFishBridge";

    @SuppressLint("StaticFieldLeak")
    private static KonsoleFishBridge sInstance;

    private final ExecutorService mExecutor = Executors.newCachedThreadPool();
    private final Set<String> mInFlight = java.util.Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());
    private final Object mWakeLockObj = new Object();

    private Context mContext;
    private java.lang.ref.WeakReference<android.app.Activity> mActiveActivityRef;
    private String mPrefixPath;
    private volatile boolean mRunning = false;
    private Thread mWatcherThread;
    private FileObserver mFileObserver;

    private KonsoleFishBridge() {
    }

    public static synchronized KonsoleFishBridge getInstance() {
        if (sInstance == null) {
            sInstance = new KonsoleFishBridge();
        }
        return sInstance;
    }

    public static String getIpcDirPath(String prefixPath) {
        String base = (prefixPath == null || prefixPath.isEmpty())
                ? TermuxConstants.TERMUX_PREFIX_DIR_PATH
                : prefixPath;
        return base + "/var/run/konsole-ipc";
    }

    public synchronized void start(Context context) {
        start(context, TermuxConstants.TERMUX_PREFIX_DIR_PATH);
    }

    public synchronized void startIfBootstrapReady(Context context) {
        startIfBootstrapReady(context, TermuxConstants.TERMUX_PREFIX_DIR_PATH);
    }

    public synchronized void startIfBootstrapReady(Context context, String prefixPath) {
        if (context == null) return;
        String resolvedPrefix = (prefixPath == null || prefixPath.isEmpty())
                ? TermuxConstants.TERMUX_PREFIX_DIR_PATH
                : prefixPath;
        if (!com.termux.app.TermuxInstaller.isBootstrapInstalled(resolvedPrefix)) {
            Log.i(TAG, "Deferring KonsoleFishBridge start until Termux bootstrap is installed at " + resolvedPrefix);
            return;
        }
        start(context, resolvedPrefix);
    }

    public synchronized void start(Context context, String prefixPath) {
        if (context == null) return;
        this.mContext = context.getApplicationContext();
        this.mPrefixPath = (prefixPath == null || prefixPath.isEmpty())
                ? TermuxConstants.TERMUX_PREFIX_DIR_PATH
                : prefixPath;

        ensureIpcDirectories(mPrefixPath);
        syncStateFiles(mPrefixPath);

        if (mRunning) return;
        mRunning = true;

        final File reqDir = new File(getIpcDirPath(mPrefixPath), "req");
        cleanupStaleFiles(reqDir);
        cleanupStaleFiles(new File(getIpcDirPath(mPrefixPath), "resp"));

        startFileObserver(reqDir);

        mWatcherThread = new Thread(() -> {
            while (mRunning) {
                try {
                    scanRequests(reqDir);
                    synchronized (mWakeLockObj) {
                        mWakeLockObj.wait(60L);
                    }
                } catch (InterruptedException ignored) {
                    if (!mRunning) break;
                } catch (Exception e) {
                    Log.w(TAG, "Watcher loop error: " + e.getMessage());
                }
            }
        }, "KonsoleFishBridgeWatcher");
        mWatcherThread.setDaemon(true);
        mWatcherThread.start();
    }

    public synchronized void stop() {
        mRunning = false;
        if (mFileObserver != null) {
            try {
                mFileObserver.stopWatching();
            } catch (Exception ignored) {
            }
            mFileObserver = null;
        }
        synchronized (mWakeLockObj) {
            mWakeLockObj.notifyAll();
        }
        if (mWatcherThread != null) {
            mWatcherThread.interrupt();
            mWatcherThread = null;
        }
    }

    public void setActiveActivity(android.app.Activity activity) {
        this.mActiveActivityRef = activity == null ? null : new java.lang.ref.WeakReference<>(activity);
    }

    public Context getUiContext(Context fallback) {
        java.lang.ref.WeakReference<android.app.Activity> ref = mActiveActivityRef;
        if (ref != null) {
            android.app.Activity act = ref.get();
            if (act != null && !act.isFinishing() && !act.isDestroyed()) {
                return act;
            }
        }
        return fallback != null ? fallback : mContext;
    }

    public static void ensureIpcDirectories(String prefixPath) {
        String ipcDir = getIpcDirPath(prefixPath);
        for (String sub : new String[]{"", "/req", "/resp"}) {
            File d = new File(ipcDir + sub);
            if (!d.exists()) {
                d.mkdirs();
            }
        }
    }

    public static void syncStateFiles(Context context) {
        syncStateFiles(TermuxConstants.TERMUX_PREFIX_DIR_PATH);
    }

    public static void syncStateFiles(String prefixPath) {
        if (prefixPath == null || prefixPath.trim().isEmpty()) return;
        File prefixDir = new File(prefixPath);
        if (!prefixDir.isDirectory()) return;
        if (!com.termux.app.TermuxInstaller.isBootstrapInstalled(prefixPath)
                && !new File(prefixPath, "bin/konsole-exec").isFile()) {
            return;
        }
        try {
            ensureIpcDirectories(prefixPath);
            String ipcDir = getIpcDirPath(prefixPath);

            KonsoleConfigManager cfg = KonsoleConfigManager.getInstance();
            String mode = "termux".equalsIgnoreCase(cfg.getMode()) ? "termux" : "konsole";
            writeAtomicUtf8(new File(ipcDir, "mode"), mode);

            StringBuilder varsFish = new StringBuilder();
            varsFish.append("# Auto-generated Konsole OS variables for Fish shell\n");
            varsFish.append("set -gx konsole ").append(quoteFish(KonsoleConfigManager.KONSOLE_ROOT_PATH)).append('\n');
            varsFish.append("set -gx KONSOLE_MODE ").append(quoteFish(mode)).append('\n');

            Map<String, String> liveVars = KonsoleTelemetryManager.getInstance().getLiveVariables();
            for (Map.Entry<String, String> e : liveVars.entrySet()) {
                if (isValidFishVarName(e.getKey())) {
                    varsFish.append("set -g ").append(e.getKey()).append(' ')
                            .append(quoteFish(e.getValue())).append('\n');
                }
            }
            Map<String, String> userVars = cfg.getUserVariables();
            for (Map.Entry<String, String> e : userVars.entrySet()) {
                if (isValidFishVarName(e.getKey())) {
                    varsFish.append("set -gx ").append(e.getKey()).append(' ')
                            .append(quoteFish(e.getValue())).append('\n');
                }
            }
            Map<String, String> aliases = cfg.getUserAliases();
            for (Map.Entry<String, String> e : aliases.entrySet()) {
                String aName = e.getKey();
                if (isValidFishVarName(aName) && !isReservedFishKeywordOrBuiltin(aName)) {
                    varsFish.append("alias ").append(aName).append(' ')
                            .append(quoteFish(e.getValue())).append('\n');
                }
            }

            writeAtomicUtf8(new File(ipcDir, "vars.fish"), varsFish.toString());
        } catch (Exception e) {
            Log.w(TAG, "Failed syncing Fish state files: " + e.getMessage());
        }
    }

    private static boolean isReservedFishKeywordOrBuiltin(String name) {
        String lower = name.toLowerCase();
        return lower.equals("set") || lower.equals("switch") || lower.equals("function")
                || lower.equals("if") || lower.equals("for") || lower.equals("while")
                || lower.equals("end") || lower.equals("cd") || lower.equals("source")
                || lower.equals("alias") || lower.equals("eval") || lower.equals("exit")
                || lower.equals("kons");
    }

    private static boolean isValidFishVarName(String name) {
        return name != null && name.matches("^[A-Za-z_][A-Za-z0-9_]*$");
    }

    private static String quoteFish(String val) {
        if (val == null) return "''";
        return "'" + val.replace("\\", "\\\\").replace("'", "\\'") + "'";
    }

    @SuppressWarnings("deprecation")
    private void startFileObserver(File reqDir) {
        try {
            if (mFileObserver != null) {
                mFileObserver.stopWatching();
            }
            mFileObserver = new FileObserver(reqDir.getAbsolutePath(),
                    FileObserver.CREATE | FileObserver.MOVED_TO | FileObserver.CLOSE_WRITE) {
                @Override
                public void onEvent(int event, String path) {
                    if (path != null && path.endsWith(".req")) {
                        synchronized (mWakeLockObj) {
                            mWakeLockObj.notifyAll();
                        }
                    }
                }
            };
            mFileObserver.startWatching();
        } catch (Exception e) {
            Log.w(TAG, "FileObserver unavailable, using polling fallback: " + e.getMessage());
        }
    }

    private void cleanupStaleFiles(File dir) {
        if (dir == null || !dir.isDirectory()) return;
        File[] files = dir.listFiles();
        if (files == null) return;
        long cutoff = System.currentTimeMillis() - 60_000L;
        for (File f : files) {
            if (f.isFile() && f.lastModified() < cutoff) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
        }
    }

    private void scanRequests(File reqDir) {
        if (!reqDir.isDirectory()) return;
        File[] files = reqDir.listFiles();
        if (files == null || files.length == 0) return;

        for (File reqFile : files) {
            String name = reqFile.getName();
            if (!name.endsWith(".req") || !reqFile.isFile()) continue;
            String reqId = name.substring(0, name.length() - 4);
            if (!mInFlight.add(reqId)) continue;

            mExecutor.execute(() -> processSingleRequest(reqId, reqFile));
        }
    }

    private void processSingleRequest(String reqId, File reqFile) {
        String prefix = mPrefixPath != null ? mPrefixPath : TermuxConstants.TERMUX_PREFIX_DIR_PATH;
        File respDir = new File(getIpcDirPath(prefix), "resp");
        if (!respDir.exists()) respDir.mkdirs();

        try {
            String action = "EXEC";
            StringBuilder cmdBuilder = new StringBuilder();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(reqFile), StandardCharsets.UTF_8))) {
                String firstLine = br.readLine();
                if (firstLine != null && !firstLine.trim().isEmpty()) {
                    action = firstLine.trim();
                }
                String line;
                while ((line = br.readLine()) != null) {
                    if (cmdBuilder.length() > 0) cmdBuilder.append(' ');
                    cmdBuilder.append(line);
                }
            }
            //noinspection ResultOfMethodCallIgnored
            reqFile.delete();

            String command = cmdBuilder.toString().trim();
            Context ctx = getUiContext(mContext);
            if (ctx == null) {
                writeResponse(respDir, reqId, "Konsole OS service context unavailable.\n", 1);
                return;
            }

            final StringBuilder outBuf = new StringBuilder();
            final java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
            final java.util.concurrent.atomic.AtomicInteger exitCode = new java.util.concurrent.atomic.AtomicInteger(0);

            KonsoleCommandDispatcher.CommandSink sink = new KonsoleCommandDispatcher.CommandSink() {
                @Override
                public synchronized void write(String text) {
                    if (text != null) {
                        outBuf.append(text.replace("\r\n", "\n"));
                    }
                }

                @Override
                public void finish(int code) {
                    exitCode.set(code);
                    done.countDown();
                }
            };

            if ("TRY_LAUNCH".equalsIgnoreCase(action)) {
                KonsoleCommandDispatcher.getInstance().tryLaunchOrSuggest(ctx, command, sink);
            } else if ("MACRO".equalsIgnoreCase(action)) {
                KonsoleAutomationEngine.getInstance().handleMacroCli(ctx, sink, command);
            } else {
                KonsoleCommandDispatcher.getInstance().executeBridgeCommand(ctx, command, sink);
            }

            try {
                done.await(180, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
            }

            syncStateFiles(prefix);
            writeResponse(respDir, reqId, outBuf.toString(), exitCode.get());
        } catch (Exception e) {
            Log.e(TAG, "Error handling Fish IPC request " + reqId, e);
            writeResponse(respDir, reqId, "Konsole IPC error: " + e.getMessage() + "\n", 1);
        } finally {
            mInFlight.remove(reqId);
        }
    }

    private static void writeResponse(File respDir, String reqId, String output, int exitCode) {
        writeAtomicUtf8(new File(respDir, reqId + ".out"), output != null ? output : "");
        writeAtomicUtf8(new File(respDir, reqId + ".rc"), String.valueOf(exitCode));
    }

    public static void writeAtomicUtf8(File dest, String content) {
        try {
            File parent = dest.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            File tmp = new File(parent, dest.getName() + ".tmp." + Thread.currentThread().getId());
            try (FileOutputStream fos = new FileOutputStream(tmp)) {
                fos.write((content != null ? content : "").getBytes(StandardCharsets.UTF_8));
            }
            if (!tmp.renameTo(dest)) {
                try (FileOutputStream fos = new FileOutputStream(dest)) {
                    fos.write((content != null ? content : "").getBytes(StandardCharsets.UTF_8));
                }
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
            }
        } catch (Exception e) {
            Log.w(TAG, "Atomic write failed for " + dest.getAbsolutePath() + ": " + e.getMessage());
        }
    }
}
