package com.termux.app.konsole;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Environment;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Component 2: 4-Tier Resolver Engine
 * Implements a Fish-shell style 4-tier prefix matching engine in strict priority order:
 *  Tier 1: Command History (Highest Priority)
 *  Tier 2: File & Directory Paths
 *  Tier 3: Command, Built-in, Function & Alias Names (First Word Only)
 *  Tier 4: Argument, Subcommand & Flag Completions (Lowest Priority)
 */
public class KonsoleFourTierResolver {

    private static KonsoleFourTierResolver sInstance;

    private final Set<String> mPathCommands = new HashSet<>(Arrays.asList(
            "cd", "ls", "cat", "nano", "vim", "rm", "cp", "mv", "mkdir", "chmod",
            "python", "python3", "node", "bash", "sh", "tar", "grep", "find",
            "less", "more", "head", "tail", "source", "."
    ));

    private final List<String> mBuiltInCommands = Arrays.asList(
            "cd", "export", "alias", "source", "echo", "exit", "history", "pwd", "set", "unset", "clear",
            "bluetooth", "bt", "wifi", "music", "media", "call", "contact", "contacts", "volume", "vol",
            "calc", "eval", "flash", "flashlight", "vibrate", "beep", "brightness", "dnd", "wallpaper",
            "lock", "battery", "home", "uninstall", "settings", "kons", "launch", "version", "about", "help", "switch"
    );

    private final List<String> mInstallablePackages = Arrays.asList(
            "htop", "neofetch", "fastfetch", "cmatrix", "python", "python3", "node", "nodejs", "npm",
            "tree", "git", "wget", "curl", "ffmpeg", "nano", "vim", "zsh", "bash", "tmux", "screen",
            "nmap", "perl", "ruby", "clang", "gcc", "make", "gdb", "sl", "figlet", "toilet", "cowsay",
            "fortune", "ranger", "micro", "jq", "ripgrep", "fd", "bat", "eza", "exa", "fish", "tshark"
    );

    // CLI Tool Completion Dictionary (Subcommands & Flags)
    private final Map<String, List<String>> mCompletionRegistry = new HashMap<>();
    private final Map<String, List<String>> mDynamicCompletionCache = new ConcurrentHashMap<>();
    private List<String> mAppNames = new ArrayList<>();

    private KonsoleFourTierResolver() {
        initCompletionRegistry();
    }

    public static synchronized KonsoleFourTierResolver getInstance() {
        if (sInstance == null) {
            sInstance = new KonsoleFourTierResolver();
        }
        return sInstance;
    }

    public void init(Context context) {
        new Thread(() -> {
            try {
                PackageManager pm = context.getPackageManager();
                List<ApplicationInfo> apps = pm.getInstalledApplications(PackageManager.GET_META_DATA);
                List<String> names = new ArrayList<>();
                for (ApplicationInfo app : apps) {
                    if (pm.getLaunchIntentForPackage(app.packageName) != null) {
                        String label = pm.getApplicationLabel(app).toString().toLowerCase();
                        names.add(label);
                    }
                }
                mAppNames = names;
            } catch (Exception ignored) {
            }
        }).start();
    }

    private void initCompletionRegistry() {
        mCompletionRegistry.put("git", Arrays.asList(
                "commit", "status", "push", "pull", "checkout", "branch", "clone", "add", "diff",
                "log", "remote", "merge", "rebase", "fetch", "init", "stash", "reset", "--help", "-v", "--version"
        ));
        mCompletionRegistry.put("pkg", Arrays.asList(
                "install", "update", "upgrade", "search", "show", "list-installed", "clean", "autoremove", "reinstall", "--help", "-y"
        ));
        mCompletionRegistry.put("apt", Arrays.asList(
                "install", "update", "upgrade", "search", "show", "list-installed", "clean", "autoremove", "--help", "-y"
        ));
        mCompletionRegistry.put("pip", Arrays.asList(
                "install", "uninstall", "list", "freeze", "show", "search", "cache", "--help", "--version", "--user", "-r"
        ));
        mCompletionRegistry.put("pip3", Arrays.asList(
                "install", "uninstall", "list", "freeze", "show", "search", "cache", "--help", "--version", "--user", "-r"
        ));
        mCompletionRegistry.put("npm", Arrays.asList(
                "install", "uninstall", "run", "start", "test", "build", "init", "publish", "list", "audit", "--help", "-v"
        ));
        mCompletionRegistry.put("docker", Arrays.asList(
                "run", "build", "ps", "images", "stop", "start", "restart", "exec", "logs", "compose", "pull", "push", "--help", "-v"
        ));
        mCompletionRegistry.put("systemctl", Arrays.asList(
                "status", "start", "stop", "restart", "enable", "disable", "reload", "daemon-reload", "is-active"
        ));
        mCompletionRegistry.put("curl", Arrays.asList(
                "--help", "-s", "-v", "-X", "-H", "-d", "-o", "-O", "-L", "--header", "--data"
        ));
        mCompletionRegistry.put("ssh", Arrays.asList("-p", "-i", "-v", "-N", "-L", "-R"));
        mCompletionRegistry.put("grep", Arrays.asList("-rn", "-i", "-E", "-v", "-l", "--exclude-dir", "--color"));
        mCompletionRegistry.put("find", Arrays.asList("-name", "-type", "-exec", "-maxdepth", "-delete"));
        mCompletionRegistry.put("tar", Arrays.asList("-czvf", "-xzvf", "-tjf", "-C", "--extract", "--create"));
        mCompletionRegistry.put("ollama", Arrays.asList("run", "pull", "push", "list", "show", "create", "cp", "rm", "serve", "--help"));
        mCompletionRegistry.put("claude", Arrays.asList("run", "chat", "config", "--help", "-v", "--version"));
        mCompletionRegistry.put("kons", Arrays.asList("apps", "set", "notify", "toast", "alias", "list", "install", "update", "version", "about", "help", "switch"));
        mCompletionRegistry.put("settings", Arrays.asList("wifi", "bluetooth", "apps", "display", "sound", "security", "storage"));
        mCompletionRegistry.put("bluetooth", Arrays.asList("status", "on", "off", "ls", "connect"));
        mCompletionRegistry.put("bt", Arrays.asList("status", "on", "off", "ls", "connect"));
        mCompletionRegistry.put("wifi", Arrays.asList("status", "on", "off", "ls", "connect"));
        mCompletionRegistry.put("music", Arrays.asList("play", "pause", "stop", "ls"));
        mCompletionRegistry.put("media", Arrays.asList("play", "pause", "stop", "ls"));
        mCompletionRegistry.put("contact", Arrays.asList("ls", "search"));
        mCompletionRegistry.put("contacts", Arrays.asList("ls", "search"));
        mCompletionRegistry.put("volume", Arrays.asList("media", "ringer", "silent", "vibrate", "normal"));
        mCompletionRegistry.put("vol", Arrays.asList("media", "ringer", "silent", "vibrate", "normal"));
        mCompletionRegistry.put("flash", Arrays.asList("on", "off"));
        mCompletionRegistry.put("flashlight", Arrays.asList("on", "off"));
        mCompletionRegistry.put("brightness", Arrays.asList("80", "100", "auto"));
        mCompletionRegistry.put("dnd", Arrays.asList("on", "off"));
    }

    /**
     * Resolves un-typed suffix prediction for current input buffer using 4-Tier lookup in strict priority order.
     */
    public String resolvePrediction(String rawInput, List<String> history) {
        if (rawInput == null || rawInput.isEmpty()) return null;

        // Extract active command segment after last command operator (&&, ;, |, ||)
        String activeSegment = extractActiveSegment(rawInput);
        if (activeSegment == null || activeSegment.isEmpty()) return null;

        // Tokenize active segment
        boolean hasTrailingSpace = activeSegment.endsWith(" ");
        String[] tokens = activeSegment.trim().split("\\s+");
        String firstToken = tokens.length > 0 ? tokens[0].toLowerCase() : "";
        String trailingToken = hasTrailingSpace ? "" : (tokens.length > 0 ? tokens[tokens.length - 1] : "");

        // TIER 1: Command History (Highest Priority)
        String tier1 = resolveTier1History(rawInput, history);
        if (tier1 != null) return tier1;

        // TIER 2: File & Directory Paths
        boolean isPathCmd = mPathCommands.contains(firstToken);
        boolean isPathToken = trailingToken.contains("/") || trailingToken.contains("./") ||
                trailingToken.contains("../") || trailingToken.contains("~/");
        if (isPathCmd || isPathToken) {
            String tier2 = resolveTier2Paths(firstToken, trailingToken);
            if (tier2 != null) return tier2;
        }

        // TIER 3: Command, Built-in, Function & Alias Names (First Word Only)
        if (tokens.length <= 1 && !hasTrailingSpace) {
            String tier3 = resolveTier3Commands(firstToken);
            if (tier3 != null) return tier3;
        }

        // TIER 4: Argument, Subcommand & Flag Completions (Lowest Priority)
        if (tokens.length >= 2 || (tokens.length == 1 && hasTrailingSpace)) {
            String tier4 = resolveTier4Arguments(firstToken, trailingToken);
            if (tier4 != null) return tier4;
        }

        return null;
    }

    private String extractActiveSegment(String rawInput) {
        int lastIdx = -1;
        String[] ops = new String[]{"&&", "||", ";", "|"};
        for (String op : ops) {
            int idx = rawInput.lastIndexOf(op);
            if (idx > lastIdx) {
                lastIdx = idx + op.length();
            }
        }
        if (lastIdx != -1 && lastIdx < rawInput.length()) {
            return rawInput.substring(lastIdx).replaceAll("^\\s+", "");
        }
        return rawInput;
    }

    // TIER 1: History Lookup (Reverse Chronological)
    private String resolveTier1History(String inputBuffer, List<String> history) {
        if (history == null || history.isEmpty()) return null;
        String lowerInput = inputBuffer.toLowerCase();
        for (String hist : history) {
            if (hist.toLowerCase().startsWith(lowerInput) && hist.length() > inputBuffer.length()) {
                return hist.substring(inputBuffer.length());
            }
        }
        return null;
    }

    // TIER 2: Path & Directory Scanning
    private String resolveTier2Paths(String command, String pathToken) {
        try {
            String homeDir = "/data/data/com.huntech.konsoleos/files/home";
            String expandedPath = pathToken;
            if (expandedPath.startsWith("~")) {
                expandedPath = homeDir + expandedPath.substring(1);
            }

            File dir;
            String prefix = "";
            if (expandedPath.endsWith("/")) {
                dir = new File(expandedPath.isEmpty() ? "." : expandedPath);
            } else {
                int lastSlash = expandedPath.lastIndexOf('/');
                if (lastSlash != -1) {
                    dir = new File(expandedPath.substring(0, lastSlash + 1));
                    prefix = expandedPath.substring(lastSlash + 1);
                } else {
                    dir = new File(".");
                    prefix = expandedPath;
                }
            }

            if (!dir.exists() || !dir.isDirectory()) return null;
            File[] files = dir.listFiles();
            if (files == null) return null;

            boolean cdOnlyDirs = "cd".equalsIgnoreCase(command);
            String lowerPrefix = prefix.toLowerCase();

            for (File f : files) {
                if (cdOnlyDirs && !f.isDirectory()) continue;
                String name = f.getName();
                if (name.toLowerCase().startsWith(lowerPrefix) && name.length() > prefix.length()) {
                    String suffix = name.substring(prefix.length());
                    if (f.isDirectory() && !suffix.endsWith("/")) {
                        suffix += "/";
                    }
                    return suffix;
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    // TIER 3: First Word Command Lookup
    private String resolveTier3Commands(String firstToken) {
        if (firstToken == null || firstToken.isEmpty()) return null;
        String lowerToken = firstToken.toLowerCase();

        // 1. Built-in commands
        for (String b : mBuiltInCommands) {
            if (b.toLowerCase().startsWith(lowerToken) && b.length() > firstToken.length()) {
                return b.substring(firstToken.length());
            }
        }

        // 2. User Aliases
        Map<String, String> aliases = KonsoleConfigManager.getInstance().getUserAliases();
        for (String alias : aliases.keySet()) {
            if (alias.toLowerCase().startsWith(lowerToken) && alias.length() > firstToken.length()) {
                return alias.substring(firstToken.length());
            }
        }

        // 3. $PATH Executables
        Set<String> pathExecs = KonsolePathIndexer.getInstance().getPathExecutables();
        for (String exec : pathExecs) {
            if (exec.toLowerCase().startsWith(lowerToken) && exec.length() > firstToken.length()) {
                return exec.substring(firstToken.length());
            }
        }

        // 4. Installed App Names
        for (String app : mAppNames) {
            if (app.startsWith(lowerToken) && app.length() > firstToken.length()) {
                return app.substring(firstToken.length());
            }
        }

        // 5. Installable Packages
        for (String pkg : mInstallablePackages) {
            if (pkg.startsWith(lowerToken)) {
                if (pkg.equals(lowerToken)) {
                    return " (install: kons install " + pkg + ")";
                } else if (pkg.length() > firstToken.length()) {
                    return pkg.substring(firstToken.length()) + " (install)";
                }
            }
        }

        return null;
    }

    // TIER 4: Subcommand & Flag Completions
    private String resolveTier4Arguments(String baseCmd, String trailingToken) {
        if (baseCmd == null || baseCmd.isEmpty()) return null;
        String lowerBase = baseCmd.toLowerCase();
        String lowerToken = trailingToken != null ? trailingToken.toLowerCase() : "";

        // Check launch app completions
        if (lowerBase.equals("launch")) {
            for (String app : mAppNames) {
                if (app.startsWith(lowerToken) && app.length() > lowerToken.length()) {
                    return app.substring(lowerToken.length());
                }
            }
        }

        // Check registry completions
        List<String> list = mCompletionRegistry.get(lowerBase);
        if (list != null) {
            for (String item : list) {
                if (item.toLowerCase().startsWith(lowerToken) && item.length() > lowerToken.length()) {
                    return item.substring(lowerToken.length());
                }
            }
        }

        // Check bash completion system files cache
        List<String> cached = mDynamicCompletionCache.get(lowerBase);
        if (cached != null) {
            for (String item : cached) {
                if (item.toLowerCase().startsWith(lowerToken) && item.length() > lowerToken.length()) {
                    return item.substring(lowerToken.length());
                }
            }
        }

        return null;
    }
}
