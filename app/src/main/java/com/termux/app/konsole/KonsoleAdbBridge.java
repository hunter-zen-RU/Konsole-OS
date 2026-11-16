package com.termux.app.konsole;

import android.content.Context;
import android.content.Intent;
import android.provider.Settings;

import com.termux.shared.termux.TermuxConstants;

import java.io.File;
import java.util.Collections;

/**
 * Rootless Wireless ADB / Privileged Shell Bridge.
 * Leverages Termux's android-tools (`adb`) paired with Android's native Wireless Debugging
 * (`adb pair 127.0.0.1:<port> <code>`) so users can run elevated shell diagnostics without root.
 */
public final class KonsoleAdbBridge {

    private KonsoleAdbBridge() {
    }

    public static boolean isAdbInstalled(String prefixPath) {
        String base = (prefixPath == null || prefixPath.isEmpty())
                ? TermuxConstants.TERMUX_PREFIX_DIR_PATH
                : prefixPath;
        File bin = new File(base, "bin/adb");
        return bin.isFile() && bin.canExecute();
    }

    public static String getStatusReport(Context context) {
        String prefix = TermuxConstants.TERMUX_PREFIX_DIR_PATH;
        boolean adbInstalled = isAdbInstalled(prefix);
        boolean tcp5555Open = KonsoleDaemonAndDistroManager.isLocalPortOpen(5555, 120);

        StringBuilder sb = new StringBuilder();
        sb.append("[Wireless ADB Bridge Status]\n");
        sb.append("  adb binary (android-tools): ").append(adbInstalled ? "INSTALLED" : "NOT INSTALLED (pkg install -y android-tools)").append("\n");
        sb.append("  Local ADB :5555 socket   : ").append(tcp5555Open ? "LISTENING" : "CLOSED").append("\n");
        if (adbInstalled) {
            KonsoleMacroExecutor.ExecutionOutcome out = KonsoleMacroExecutor.runShellCommand(context, "adb devices", Collections.emptyMap());
            sb.append("  adb devices output        :\n").append(out.output).append("\n");
        }
        sb.append("\nQuick Rootless Pairing:\n");
        sb.append("  1. Enable Developer Options -> Wireless debugging.\n");
        sb.append("  2. Tap 'Pair device with pairing code' and run:\n");
        sb.append("     kons adb pair 127.0.0.1:<pairing-port> <code>\n");
        sb.append("  3. Connect: kons adb connect 127.0.0.1:<debug-port>\n");
        return sb.toString();
    }

    public static KonsoleMacroExecutor.ExecutionOutcome pair(Context context, String hostPort, String pairingCode) {
        if (!isAdbInstalled(TermuxConstants.TERMUX_PREFIX_DIR_PATH)) {
            KonsoleMacroExecutor.ExecutionOutcome err = new KonsoleMacroExecutor.ExecutionOutcome();
            err.exitCode = 1;
            err.output = "adb binary missing. Install with: pkg install -y android-tools";
            return err;
        }
        String safeHost = sanitizeArg(hostPort);
        String safeCode = sanitizeArg(pairingCode);
        String cmd = "printf '%s\\n' '" + safeCode + "' | adb pair '" + safeHost + "'";
        return KonsoleMacroExecutor.runShellCommand(context, cmd, Collections.emptyMap());
    }

    public static KonsoleMacroExecutor.ExecutionOutcome connect(Context context, String hostPort) {
        if (!isAdbInstalled(TermuxConstants.TERMUX_PREFIX_DIR_PATH)) {
            KonsoleMacroExecutor.ExecutionOutcome err = new KonsoleMacroExecutor.ExecutionOutcome();
            err.exitCode = 1;
            err.output = "adb binary missing. Install with: pkg install -y android-tools";
            return err;
        }
        String safeHost = sanitizeArg(hostPort);
        return KonsoleMacroExecutor.runShellCommand(context, "adb connect '" + safeHost + "'", Collections.emptyMap());
    }

    public static KonsoleMacroExecutor.ExecutionOutcome runAdbShell(Context context, String shellCmd) {
        if (!isAdbInstalled(TermuxConstants.TERMUX_PREFIX_DIR_PATH)) {
            KonsoleMacroExecutor.ExecutionOutcome err = new KonsoleMacroExecutor.ExecutionOutcome();
            err.exitCode = 1;
            err.output = "adb binary missing. Install with: pkg install -y android-tools";
            return err;
        }
        String escaped = shellCmd == null ? "" : shellCmd.replace("'", "'\\''");
        return KonsoleMacroExecutor.runShellCommand(context, "adb shell '" + escaped + "'", Collections.emptyMap());
    }

    public static void openWirelessDebugSettings(Context context) {
        if (context == null) return;
        try {
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS);
            if (!(context instanceof android.app.Activity)) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            }
            context.startActivity(intent);
        } catch (Exception ignored) {
        }
    }

    private static String sanitizeArg(String raw) {
        return raw == null ? "" : raw.trim().replaceAll("[^a-zA-Z0-9.:_-]", "");
    }
}
