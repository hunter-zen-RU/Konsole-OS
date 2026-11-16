package com.termux.app.konsole;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.util.Log;

import com.termux.shared.termux.TermuxConstants;
import com.termux.terminal.TerminalSession;

import java.io.File;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Rootless Kali NetHunter-equivalent manager for Termux proot-distro containers,
 * KeX/TigerVNC graphical desktops, and local workstation daemon probing.
 */
public final class KonsoleDaemonAndDistroManager {

    private static final String TAG = "KonsoleDistroMgr";

    public static final class DaemonStatus {
        public final String id;
        public final String label;
        public final int port;
        public final boolean running;
        public final boolean installed;

        public DaemonStatus(String id, String label, int port, boolean running, boolean installed) {
            this.id = id;
            this.label = label;
            this.port = port;
            this.running = running;
            this.installed = installed;
        }
    }

    private KonsoleDaemonAndDistroManager() {
    }

    public static List<String> getInstalledDistros(String prefixPath) {
        List<String> list = new ArrayList<>();
        String base = (prefixPath == null || prefixPath.isEmpty())
                ? TermuxConstants.TERMUX_PREFIX_DIR_PATH
                : prefixPath;
        File rootfsDir = new File(base, "var/lib/proot-distro/installed-rootfs");
        if (!rootfsDir.isDirectory()) return list;
        File[] entries = rootfsDir.listFiles();
        if (entries == null) return list;
        for (File f : entries) {
            if (f.isDirectory() && !f.getName().startsWith(".")) {
                list.add(f.getName());
            }
        }
        return list;
    }

    public static boolean isProotDistroAvailable(String prefixPath) {
        String base = (prefixPath == null || prefixPath.isEmpty())
                ? TermuxConstants.TERMUX_PREFIX_DIR_PATH
                : prefixPath;
        return new File(base, "bin/proot-distro").isFile()
                && new File(base, "bin/proot").isFile();
    }

    public static String buildInstallDistroCommand(String distro) {
        String target = (distro == null || distro.trim().isEmpty()) ? "debian" : sanitizeDistroName(distro);
        return "pkg update -y && pkg install -y proot-distro && proot-distro install " + target;
    }

    public static String buildLoginDistroCommand(String distro) {
        String target = (distro == null || distro.trim().isEmpty()) ? "debian" : sanitizeDistroName(distro);
        return "proot-distro login " + target
                + " --shared-tmp --bind \"" + KonsoleConfigManager.KONSOLE_ROOT_PATH + ":/mnt/konsole\"";
    }

    public static String buildKexInstallCommand() {
        return "pkg install -y tigervnc xfce4 x11-repo && echo 'Run: vncserver :1 -geometry 1920x1080 -localhost yes'";
    }

    public static String buildKexStartCommand(int displayNum) {
        int disp = Math.max(1, Math.min(9, displayNum));
        return "vncserver -kill :" + disp + " >/dev/null 2>&1; vncserver :" + disp + " -localhost yes -geometry 1600x900";
    }

    public static String buildKexStopCommand(int displayNum) {
        int disp = Math.max(1, Math.min(9, displayNum));
        return "vncserver -kill :" + disp;
    }

    public static List<DaemonStatus> probeCoreDaemons(String prefixPath) {
        String base = (prefixPath == null || prefixPath.isEmpty())
                ? TermuxConstants.TERMUX_PREFIX_DIR_PATH
                : prefixPath;

        return Arrays.asList(
                new DaemonStatus("sshd", "OpenSSH Server", 8022,
                        isLocalPortOpen(8022, 120), new File(base, "bin/sshd").isFile()),
                new DaemonStatus("kex", "KeX VNC (:1)", 5901,
                        isLocalPortOpen(5901, 120),
                        new File(base, "bin/vncserver").isFile() || new File(base, "bin/Xtigervnc").isFile()),
                new DaemonStatus("tor", "Tor SOCKS5 Proxy", 9050,
                        isLocalPortOpen(9050, 120), new File(base, "bin/tor").isFile()),
                new DaemonStatus("http", "Local HTTP Share", 8080,
                        isLocalPortOpen(8080, 120),
                        new File(base, "bin/python3").isFile() || new File(base, "bin/python").isFile()),
                new DaemonStatus("adb", "Local ADB Daemon", 5555,
                        isLocalPortOpen(5555, 120), new File(base, "bin/adb").isFile())
        );
    }

    public static boolean isLocalPortOpen(int port, int timeoutMs) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", port), timeoutMs);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    public static boolean startOrStopDaemonInSession(Context context, String daemonId, boolean start, TerminalSession session) {
        if (daemonId == null) return false;
        String id = daemonId.trim().toLowerCase();
        String cmd;
        switch (id) {
            case "sshd":
                cmd = start ? "sshd && echo '[Daemon] sshd listening on port 8022'" : "pkill -x sshd && echo '[Daemon] sshd stopped'";
                break;
            case "kex":
            case "vnc":
                cmd = start ? buildKexStartCommand(1) : buildKexStopCommand(1);
                break;
            case "tor":
                cmd = start ? "nohup tor >/dev/null 2>&1 & echo '[Daemon] tor started on :9050'" : "pkill -x tor && echo '[Daemon] tor stopped'";
                break;
            case "http":
                cmd = start ? "nohup python3 -m http.server 8080 --bind 127.0.0.1 --directory \""
                        + KonsoleConfigManager.KONSOLE_ROOT_PATH + "\" >/dev/null 2>&1 & echo '[Daemon] HTTP server on 127.0.0.1:8080'"
                        : "pkill -f 'http.server 8080' && echo '[Daemon] HTTP server stopped'";
                break;
            case "adb":
                cmd = start ? "adb start-server && adb devices" : "adb kill-server && echo '[Daemon] adb stopped'";
                break;
            default:
                return false;
        }

        if (session != null) {
            String writeLine = "\u0015" + cmd + "\n";
            byte[] b = writeLine.getBytes(StandardCharsets.UTF_8);
            session.write(b, 0, b.length);
            return true;
        }

        KonsoleMacroExecutor.ExecutionOutcome out = KonsoleMacroExecutor.runShellCommand(context, cmd, null);
        return out.exitCode == 0;
    }

    public static boolean launchVncViewerUri(Context context, int port) {
        if (context == null) return false;
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse("vnc://127.0.0.1:" + port));
            if (!(context instanceof android.app.Activity)) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            }
            context.startActivity(intent);
            return true;
        } catch (Exception e) {
            Log.i(TAG, "No native VNC client registered for vnc:// URI");
            return false;
        }
    }

    private static String sanitizeDistroName(String raw) {
        return raw.trim().replaceAll("[^a-zA-Z0-9._-]", "");
    }
}
