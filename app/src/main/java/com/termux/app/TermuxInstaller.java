package com.termux.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.Context;
import android.os.Build;
import android.os.Environment;
import android.system.Os;
import android.util.Pair;
import android.view.WindowManager;

import com.termux.R;
import com.termux.shared.file.FileUtils;
import com.termux.shared.termux.crash.TermuxCrashUtils;
import com.termux.shared.termux.file.TermuxFileUtils;
import com.termux.shared.interact.MessageDialogUtils;
import com.termux.shared.logger.Logger;
import com.termux.shared.markdown.MarkdownUtils;
import com.termux.shared.errors.Error;
import com.termux.shared.android.PackageUtils;
import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.termux.TermuxUtils;
import com.termux.shared.termux.shell.command.environment.TermuxShellEnvironment;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static com.termux.shared.termux.TermuxConstants.TERMUX_PREFIX_DIR;
import static com.termux.shared.termux.TermuxConstants.TERMUX_PREFIX_DIR_PATH;
import static com.termux.shared.termux.TermuxConstants.TERMUX_STAGING_PREFIX_DIR;
import static com.termux.shared.termux.TermuxConstants.TERMUX_STAGING_PREFIX_DIR_PATH;

/**
 * Install the Termux bootstrap packages if necessary by following the below steps:
 * <p/>
 * (1) If $PREFIX already exist, verify required apt/dpkg directories & configs and be done.
 * <p/>
 * (2) A progress dialog is shown with "Installing..." message and a spinner.
 * <p/>
 * (3) A staging directory, $STAGING_PREFIX, is cleared if left over from broken installation below.
 * <p/>
 * (4) The zip file is loaded from a shared library.
 * <p/>
 * (5) The zip, containing entries relative to the $PREFIX, is extracted by a zip input stream
 * continuously encountering zip file entries:
 * <p/>
 * (5.1) If the zip entry encountered is SYMLINKS.txt, go through it and remember all symlinks to setup.
 * <p/>
 * (5.2) For every other zip entry, extract it into $STAGING_PREFIX and set execute permissions if necessary.
 */
public final class TermuxInstaller {

    private static final String LOG_TAG = "TermuxInstaller";

    /** Performs bootstrap setup if necessary. */
    static void setupBootstrapIfNeeded(final Activity activity, final Runnable whenDone) {
        String bootstrapErrorMessage;
        Error filesDirectoryAccessibleError;

        // This will also call Context.getFilesDir(), which should ensure that termux files directory
        // is created if it does not already exist
        filesDirectoryAccessibleError = TermuxFileUtils.isTermuxFilesDirectoryAccessible(activity, true, true);
        boolean isFilesDirectoryAccessible = filesDirectoryAccessibleError == null;

        // Termux can only be run as the primary user (device owner) since only that
        // account has the expected file system paths. Verify that:
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && !PackageUtils.isCurrentUserThePrimaryUser(activity)) {
            bootstrapErrorMessage = activity.getString(R.string.bootstrap_error_not_primary_user_message,
                MarkdownUtils.getMarkdownCodeForString(TERMUX_PREFIX_DIR_PATH, false));
            Logger.logError(LOG_TAG, "isFilesDirectoryAccessible: " + isFilesDirectoryAccessible);
            Logger.logError(LOG_TAG, bootstrapErrorMessage);
            sendBootstrapCrashReportNotification(activity, bootstrapErrorMessage);
            MessageDialogUtils.exitAppWithErrorMessage(activity,
                activity.getString(R.string.bootstrap_error_title),
                bootstrapErrorMessage);
            return;
        }

        if (!isFilesDirectoryAccessible) {
            bootstrapErrorMessage = Error.getMinimalErrorString(filesDirectoryAccessibleError);
            //noinspection SdCardPath
            if (PackageUtils.isAppInstalledOnExternalStorage(activity) &&
                !TermuxConstants.TERMUX_FILES_DIR_PATH.equals(activity.getFilesDir().getAbsolutePath().replaceAll("^/data/user/0/", "/data/data/"))) {
                bootstrapErrorMessage += "\n\n" + activity.getString(R.string.bootstrap_error_installed_on_portable_sd,
                    MarkdownUtils.getMarkdownCodeForString(TERMUX_PREFIX_DIR_PATH, false));
            }

            Logger.logError(LOG_TAG, bootstrapErrorMessage);
            sendBootstrapCrashReportNotification(activity, bootstrapErrorMessage);
            MessageDialogUtils.showMessage(activity,
                activity.getString(R.string.bootstrap_error_title),
                bootstrapErrorMessage, null);
            return;
        }

        // If prefix directory exists, verify it is non-empty AND contains core bootstrap binaries
        // (avoids skipping bootstrap if IPC or partial config files were created beforehand)
        if (FileUtils.directoryFileExists(TERMUX_PREFIX_DIR_PATH, true)) {
            if (TermuxFileUtils.isTermuxPrefixDirectoryEmpty() || !isBootstrapInstalled(TERMUX_PREFIX_DIR_PATH)) {
                Logger.logInfo(LOG_TAG, "The termux prefix directory \"" + TERMUX_PREFIX_DIR_PATH + "\" is empty or missing core bootstrap binaries.");
            } else {
                ensureAptAndPkgConfigured(TERMUX_PREFIX_DIR_PATH);
                restoreKonsoleUserBinsIfMissing(TERMUX_PREFIX_DIR_PATH);
                TermuxShellEnvironment.writeEnvironmentToFile(activity);
                whenDone.run();
                return;
            }
        } else if (FileUtils.fileExists(TERMUX_PREFIX_DIR_PATH, false)) {
            Logger.logInfo(LOG_TAG, "The termux prefix directory \"" + TERMUX_PREFIX_DIR_PATH + "\" does not exist but another file exists at its destination.");
        }

        final ProgressDialog progress = ProgressDialog.show(activity, null, activity.getString(R.string.bootstrap_installer_body), true, false);
        new Thread() {
            @Override
            public void run() {
                try {
                    Logger.logInfo(LOG_TAG, "Installing " + TermuxConstants.TERMUX_APP_NAME + " bootstrap packages.");

                    Error error;

                    // Delete prefix staging directory or any file at its destination
                    error = FileUtils.deleteFile("termux prefix staging directory", TERMUX_STAGING_PREFIX_DIR_PATH, true);
                    if (error != null) {
                        showBootstrapErrorDialog(activity, whenDone, Error.getErrorMarkdownString(error));
                        return;
                    }

                    // Delete prefix directory or any file at its destination
                    error = FileUtils.deleteFile("termux prefix directory", TERMUX_PREFIX_DIR_PATH, true);
                    if (error != null) {
                        showBootstrapErrorDialog(activity, whenDone, Error.getErrorMarkdownString(error));
                        return;
                    }

                    // Create prefix staging directory if it does not already exist and set required permissions
                    error = TermuxFileUtils.isTermuxPrefixStagingDirectoryAccessible(true, true);
                    if (error != null) {
                        showBootstrapErrorDialog(activity, whenDone, Error.getErrorMarkdownString(error));
                        return;
                    }

                    // Create prefix directory if it does not already exist and set required permissions
                    error = TermuxFileUtils.isTermuxPrefixDirectoryAccessible(true, true);
                    if (error != null) {
                        showBootstrapErrorDialog(activity, whenDone, Error.getErrorMarkdownString(error));
                        return;
                    }

                    Logger.logInfo(LOG_TAG, "Extracting bootstrap zip to prefix staging directory \"" + TERMUX_STAGING_PREFIX_DIR_PATH + "\".");

                    final byte[] buffer = new byte[8096];
                    final List<Pair<String, String>> symlinks = new ArrayList<>(50);

                    final byte[] zipBytes = loadZipBytes();
                    try (ZipInputStream zipInput = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
                        ZipEntry zipEntry;
                        while ((zipEntry = zipInput.getNextEntry()) != null) {
                            if (zipEntry.getName().equals("SYMLINKS.txt")) {
                                BufferedReader symlinksReader = new BufferedReader(new InputStreamReader(zipInput));
                                String line;
                                while ((line = symlinksReader.readLine()) != null) {
                                    String[] parts = line.split("←");
                                    if (parts.length != 2)
                                        throw new RuntimeException("Malformed symlink line: " + line);
                                    String oldPath = parts[0];
                                    String newPath = TERMUX_STAGING_PREFIX_DIR_PATH + "/" + parts[1];
                                    symlinks.add(Pair.create(oldPath, newPath));

                                    error = ensureDirectoryExists(new File(newPath).getParentFile());
                                    if (error != null) {
                                        showBootstrapErrorDialog(activity, whenDone, Error.getErrorMarkdownString(error));
                                        return;
                                    }
                                }
                            } else {
                                String zipEntryName = zipEntry.getName();
                                File targetFile = new File(TERMUX_STAGING_PREFIX_DIR_PATH, zipEntryName);
                                boolean isDirectory = zipEntry.isDirectory();

                                error = ensureDirectoryExists(isDirectory ? targetFile : targetFile.getParentFile());
                                if (error != null) {
                                    showBootstrapErrorDialog(activity, whenDone, Error.getErrorMarkdownString(error));
                                    return;
                                }

                                if (!isDirectory) {
                                    try (FileOutputStream outStream = new FileOutputStream(targetFile)) {
                                        int readBytes;
                                        while ((readBytes = zipInput.read(buffer)) != -1)
                                            outStream.write(buffer, 0, readBytes);
                                    }
                                    if (zipEntryName.startsWith("bin/") || zipEntryName.startsWith("libexec") ||
                                        zipEntryName.startsWith("lib/apt/apt-helper") || zipEntryName.startsWith("lib/apt/methods")) {
                                        //noinspection OctalInteger
                                        Os.chmod(targetFile.getAbsolutePath(), 0700);
                                    }
                                }
                            }
                        }
                    }

                    if (symlinks.isEmpty())
                        throw new RuntimeException("No SYMLINKS.txt encountered");
                    for (Pair<String, String> symlink : symlinks) {
                        Os.symlink(symlink.first, symlink.second);
                    }

                    Logger.logInfo(LOG_TAG, "Moving termux prefix staging to prefix directory.");

                    if (!TERMUX_STAGING_PREFIX_DIR.renameTo(TERMUX_PREFIX_DIR)) {
                        throw new RuntimeException("Moving termux prefix staging to prefix directory failed");
                    }

                    ensureAptAndPkgConfigured(TERMUX_PREFIX_DIR_PATH);
                    restoreKonsoleUserBinsIfMissing(TERMUX_PREFIX_DIR_PATH);

                    Logger.logInfo(LOG_TAG, "Bootstrap packages installed successfully.");

                    // Recreate env file since termux prefix was wiped earlier
                    TermuxShellEnvironment.writeEnvironmentToFile(activity);

                    activity.runOnUiThread(whenDone);

                } catch (final Exception e) {
                    showBootstrapErrorDialog(activity, whenDone, Logger.getStackTracesMarkdownString(null, Logger.getStackTracesStringArray(e)));

                } finally {
                    activity.runOnUiThread(() -> {
                        try {
                            progress.dismiss();
                        } catch (RuntimeException e) {
                            // Activity already dismissed - ignore.
                        }
                    });
                }
            }
        }.start();
    }

    public static void showBootstrapErrorDialog(Activity activity, Runnable whenDone, String message) {
        Logger.logErrorExtended(LOG_TAG, "Bootstrap Error:\n" + message);

        // Send a notification with the exception so that the user knows why bootstrap setup failed
        sendBootstrapCrashReportNotification(activity, message);

        activity.runOnUiThread(() -> {
            try {
                new AlertDialog.Builder(activity).setTitle(R.string.bootstrap_error_title).setMessage(R.string.bootstrap_error_body)
                    .setNegativeButton(R.string.bootstrap_error_abort, (dialog, which) -> {
                        dialog.dismiss();
                        activity.finish();
                    })
                    .setPositiveButton(R.string.bootstrap_error_try_again, (dialog, which) -> {
                        dialog.dismiss();
                        FileUtils.deleteFile("termux prefix directory", TERMUX_PREFIX_DIR_PATH, true);
                        TermuxInstaller.setupBootstrapIfNeeded(activity, whenDone);
                    }).show();
            } catch (WindowManager.BadTokenException e1) {
                // Activity already dismissed - ignore.
            }
        });
    }

    private static void sendBootstrapCrashReportNotification(Activity activity, String message) {
        final String title = TermuxConstants.TERMUX_APP_NAME + " Bootstrap Error";

        // Add info of all install Termux plugin apps as well since their target sdk or installation
        // on external/portable sd card can affect Termux app files directory access or exec.
        TermuxCrashUtils.sendCrashReportNotification(activity, LOG_TAG,
            title, null, "## " + title + "\n\n" + message + "\n\n" +
                TermuxUtils.getTermuxDebugMarkdownString(activity),
            true, false, TermuxUtils.AppInfoMode.TERMUX_AND_PLUGIN_PACKAGES, true);
    }

    static void setupStorageSymlinks(final Context context) {
        final String LOG_TAG = "termux-storage";
        final String title = TermuxConstants.TERMUX_APP_NAME + " Setup Storage Error";

        Logger.logInfo(LOG_TAG, "Setting up storage symlinks.");

        new Thread() {
            public void run() {
                try {
                    Error error;
                    File storageDir = TermuxConstants.TERMUX_STORAGE_HOME_DIR;

                    error = FileUtils.clearDirectory("~/storage", storageDir.getAbsolutePath());
                    if (error != null) {
                        Logger.logErrorAndShowToast(context, LOG_TAG, error.getMessage());
                        Logger.logErrorExtended(LOG_TAG, "Setup Storage Error\n" + error.toString());
                        TermuxCrashUtils.sendCrashReportNotification(context, LOG_TAG, title, null,
                            "## " + title + "\n\n" + Error.getErrorMarkdownString(error),
                            true, false, TermuxUtils.AppInfoMode.TERMUX_PACKAGE, true);
                        return;
                    }

                    Logger.logInfo(LOG_TAG, "Setting up storage symlinks at ~/storage/shared, ~/storage/downloads, ~/storage/dcim, ~/storage/pictures, ~/storage/music and ~/storage/movies for directories in \"" + Environment.getExternalStorageDirectory().getAbsolutePath() + "\".");

                    // Get primary storage root "/storage/emulated/0" symlink
                    File sharedDir = Environment.getExternalStorageDirectory();
                    Os.symlink(sharedDir.getAbsolutePath(), new File(storageDir, "shared").getAbsolutePath());

                    File documentsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS);
                    Os.symlink(documentsDir.getAbsolutePath(), new File(storageDir, "documents").getAbsolutePath());

                    File downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                    Os.symlink(downloadsDir.getAbsolutePath(), new File(storageDir, "downloads").getAbsolutePath());

                    File dcimDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM);
                    Os.symlink(dcimDir.getAbsolutePath(), new File(storageDir, "dcim").getAbsolutePath());

                    File picturesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES);
                    Os.symlink(picturesDir.getAbsolutePath(), new File(storageDir, "pictures").getAbsolutePath());

                    File musicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC);
                    Os.symlink(musicDir.getAbsolutePath(), new File(storageDir, "music").getAbsolutePath());

                    File moviesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES);
                    Os.symlink(moviesDir.getAbsolutePath(), new File(storageDir, "movies").getAbsolutePath());

                    File podcastsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PODCASTS);
                    Os.symlink(podcastsDir.getAbsolutePath(), new File(storageDir, "podcasts").getAbsolutePath());

                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                        File audiobooksDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_AUDIOBOOKS);
                        Os.symlink(audiobooksDir.getAbsolutePath(), new File(storageDir, "audiobooks").getAbsolutePath());
                    }

                    // Dir 0 should ideally be for primary storage
                    // https://cs.android.com/android/platform/superproject/+/android-12.0.0_r32:frameworks/base/core/java/android/app/ContextImpl.java;l=818
                    // https://cs.android.com/android/platform/superproject/+/android-12.0.0_r32:frameworks/base/core/java/android/os/Environment.java;l=219
                    // https://cs.android.com/android/platform/superproject/+/android-12.0.0_r32:frameworks/base/core/java/android/os/Environment.java;l=181
                    // https://cs.android.com/android/platform/superproject/+/android-12.0.0_r32:frameworks/base/services/core/java/com/android/server/StorageManagerService.java;l=3796
                    // https://cs.android.com/android/platform/superproject/+/android-7.0.0_r36:frameworks/base/services/core/java/com/android/server/MountService.java;l=3053

                    // Create "Android/data/com.termux" symlinks
                    File[] dirs = context.getExternalFilesDirs(null);
                    if (dirs != null && dirs.length > 0) {
                        for (int i = 0; i < dirs.length; i++) {
                            File dir = dirs[i];
                            if (dir == null) continue;
                            String symlinkName = "external-" + i;
                            Logger.logInfo(LOG_TAG, "Setting up storage symlinks at ~/storage/" + symlinkName + " for \"" + dir.getAbsolutePath() + "\".");
                            Os.symlink(dir.getAbsolutePath(), new File(storageDir, symlinkName).getAbsolutePath());
                        }
                    }

                    // Create "Android/media/com.termux" symlinks
                    dirs = context.getExternalMediaDirs();
                    if (dirs != null && dirs.length > 0) {
                        for (int i = 0; i < dirs.length; i++) {
                            File dir = dirs[i];
                            if (dir == null) continue;
                            String symlinkName = "media-" + i;
                            Logger.logInfo(LOG_TAG, "Setting up storage symlinks at ~/storage/" + symlinkName + " for \"" + dir.getAbsolutePath() + "\".");
                            Os.symlink(dir.getAbsolutePath(), new File(storageDir, symlinkName).getAbsolutePath());
                        }
                    }

                    Logger.logInfo(LOG_TAG, "Storage symlinks created successfully.");
                } catch (Exception e) {
                    Logger.logErrorAndShowToast(context, LOG_TAG, e.getMessage());
                    Logger.logStackTraceWithMessage(LOG_TAG, "Setup Storage Error: Error setting up link", e);
                    TermuxCrashUtils.sendCrashReportNotification(context, LOG_TAG, title, null,
                        "## " + title + "\n\n" + Logger.getStackTracesMarkdownString(null, Logger.getStackTracesStringArray(e)),
                        true, false, TermuxUtils.AppInfoMode.TERMUX_PACKAGE, true);
                }
            }
        }.start();
    }

    /**
     * Checks whether the core Termux bootstrap binaries (shell + dpkg/apt) are present under {@code prefixPath}.
     */
    public static boolean isBootstrapInstalled(String prefixPath) {
        if (prefixPath == null || prefixPath.trim().isEmpty()) return false;
        File prefixDir = new File(prefixPath);
        if (!prefixDir.isDirectory()) return false;
        File binDir = new File(prefixDir, "bin");
        if (!binDir.isDirectory()) return false;
        boolean hasShell = new File(binDir, "sh").exists() || new File(binDir, "bash").exists();
        boolean hasPkgMgr = new File(binDir, "dpkg").exists()
            || new File(binDir, "apt").exists()
            || new File(binDir, "apt-get").exists();
        return hasShell && hasPkgMgr;
    }

    /**
     * Re-installs user packages previously tracked in {@code $konsole/packages/installed} into
     * {@code $PREFIX/bin} if missing (e.g. after a prefix self-heal/re-extraction).
     */
    public static void restoreKonsoleUserBinsIfMissing(String prefixPath) {
        if (prefixPath == null || prefixPath.trim().isEmpty()) return;
        try {
            File installedDir = new File(com.termux.app.konsole.KonsoleConfigManager.INSTALLED_PACKAGES_PATH);
            if (!installedDir.isDirectory()) return;
            File[] manifests = installedDir.listFiles();
            if (manifests == null || manifests.length == 0) return;
            File prefixBinDir = new File(prefixPath, "bin");
            for (File manifestFile : manifests) {
                if (!manifestFile.isFile() || !manifestFile.getName().endsWith(".json")) continue;
                try {
                    String jsonStr = readUtf8FileSafely(manifestFile);
                    if (jsonStr.isEmpty()) continue;
                    org.json.JSONObject json = new org.json.JSONObject(jsonStr);
                    String name = json.optString("name", "").trim();
                    String type = json.optString("type", "script").trim().toLowerCase();
                    if (name.isEmpty() || "deb".equals(type) || "apk".equals(type)) continue;
                    File targetBin = new File(prefixBinDir, name);
                    if (targetBin.exists()) continue;

                    String localPath = json.optString("localPath", "");
                    File payload = !localPath.isEmpty() ? new File(localPath) : null;
                    if (payload == null || !payload.isFile()) {
                        File extBin = new File(com.termux.app.konsole.KonsoleConfigManager.BIN_PATH, name);
                        if (extBin.isFile()) payload = extBin;
                    }
                    if (payload != null && payload.isFile()) {
                        com.termux.app.konsole.KonsoleCommandDispatcher.installExecutableIntoPrefix(payload, prefixBinDir, name, type);
                    }
                } catch (Exception ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * Verifies and creates required apt/dpkg/tls/tmp directories inside {@code prefixPath}
     * before writing configuration files, eliminating ENOENT errors (e.g. trusted.gpg.d/)
     * and adapting com.termux bootstrap paths to {@link TermuxConstants#TERMUX_PACKAGE_NAME}.
     */
    public static void ensureAptAndPkgConfigured(String prefixPath) {
        if (prefixPath == null || prefixPath.trim().isEmpty()) return;

        File prefixDir = new File(prefixPath);
        File filesDir = prefixDir.getParentFile();
        File appDataDir = filesDir != null ? filesDir.getParentFile() : null;
        String filesPath = filesDir != null ? filesDir.getAbsolutePath() : TermuxConstants.TERMUX_FILES_DIR_PATH;
        String appDataPath = appDataDir != null ? appDataDir.getAbsolutePath() : TermuxConstants.TERMUX_INTERNAL_PRIVATE_APP_DATA_DIR_PATH;

        String[] requiredDirs = new String[] {
            "bin",
            "etc",
            "etc/apt",
            "etc/apt/trusted.gpg.d",
            "etc/apt/sources.list.d",
            "etc/apt/apt.conf.d",
            "etc/apt/preferences.d",
            "etc/fish",
            "etc/fish/conf.d",
            "etc/fish/completions",
            "etc/profile.d",
            "etc/tls",
            "var",
            "var/lib",
            "var/lib/apt",
            "var/lib/apt/lists",
            "var/lib/apt/lists/partial",
            "var/cache",
            "var/cache/apt",
            "var/cache/apt/archives",
            "var/cache/apt/archives/partial",
            "var/log",
            "var/log/apt",
            "var/lib/dpkg",
            "var/lib/dpkg/updates",
            "var/lib/dpkg/info",
            "var/lib/dpkg/alternatives",
            "var/lib/dpkg/triggers",
            "var/run",
            "var/run/konsole-ipc",
            "var/run/konsole-ipc/req",
            "var/run/konsole-ipc/resp",
            "tmp"
        };

        for (String relDir : requiredDirs) {
            File dir = new File(prefixDir, relDir);
            if (!dir.exists()) {
                if (!dir.mkdirs() && !dir.exists()) {
                    Logger.logWarn(LOG_TAG, "Failed to create required prefix directory: " + dir.getAbsolutePath());
                }
            }
        }

        // Ensure dpkg status file exists
        File dpkgStatus = new File(prefixDir, "var/lib/dpkg/status");
        if (!dpkgStatus.exists()) {
            writeUtf8FileSafely(dpkgStatus, "");
        }

        // Ensure default sources.list exists
        File sourcesList = new File(prefixDir, "etc/apt/sources.list");
        if (!sourcesList.exists()) {
            writeUtf8FileSafely(sourcesList,
                "# The main termux repository, with cloudflare cache\n" +
                "deb https://packages.termux.dev/apt/termux-main stable main\n");
        }

        // Patch hardcoded paths in common config/script files if present
        patchTermuxPathsInFile(sourcesList, filesPath, appDataPath, false);
        patchTermuxPathsInDirectory(new File(prefixDir, "etc/apt/sources.list.d"), filesPath, appDataPath, false);
        patchTermuxPathsInDirectory(new File(prefixDir, "etc/apt/apt.conf.d"), filesPath, appDataPath, false);
        patchTermuxPathsInFile(new File(prefixDir, "bin/pkg"), filesPath, appDataPath, true);
        patchTermuxPathsInFile(new File(prefixDir, "bin/apt-key"), filesPath, appDataPath, true);
        patchTermuxPathsInFile(new File(prefixDir, "etc/profile"), filesPath, appDataPath, false);
        patchTermuxPathsInFile(new File(prefixDir, "etc/bash.bashrc"), filesPath, appDataPath, false);

        // Ensure core apt/pkg binaries and apt methods retain executable permissions
        for (String binRel : new String[]{"bin/apt", "bin/apt-get", "bin/apt-cache", "bin/apt-config", "bin/apt-key", "bin/pkg", "bin/dpkg", "bin/dpkg-deb", "lib/apt/apt-helper"}) {
            File binFile = new File(prefixDir, binRel);
            if (binFile.exists()) {
                ensureExecutable(binFile);
            }
        }
        File aptMethodsDir = new File(prefixDir, "lib/apt/methods");
        if (aptMethodsDir.isDirectory()) {
            File[] methods = aptMethodsDir.listFiles();
            if (methods != null) {
                for (File m : methods) {
                    if (m.exists()) ensureExecutable(m);
                }
            }
        }

        // Verify apt config & trusted.gpg.d directory exist before writing apt.conf
        File aptEtcDir = new File(prefixDir, "etc/apt");
        File trustedPartsDir = new File(prefixDir, "etc/apt/trusted.gpg.d");
        if (!aptEtcDir.exists()) aptEtcDir.mkdirs();
        if (!trustedPartsDir.exists()) trustedPartsDir.mkdirs();

        if (aptEtcDir.isDirectory() && trustedPartsDir.isDirectory()) {
            StringBuilder aptConf = new StringBuilder();
            aptConf.append("// Auto-configured by Konsole OS for ").append(prefixPath).append("\n");
            aptConf.append("Dir \"").append(prefixPath).append("/\";\n");
            aptConf.append("Dir::State \"").append(prefixPath).append("/var/lib/apt\";\n");
            aptConf.append("Dir::State::status \"").append(prefixPath).append("/var/lib/dpkg/status\";\n");
            aptConf.append("Dir::Cache \"").append(prefixPath).append("/var/cache/apt\";\n");
            aptConf.append("Dir::Etc \"").append(prefixPath).append("/etc/apt\";\n");
            aptConf.append("Dir::Etc::sourcelist \"sources.list\";\n");
            aptConf.append("Dir::Etc::sourceparts \"sources.list.d\";\n");
            aptConf.append("Dir::Etc::parts \"apt.conf.d\";\n");
            aptConf.append("Dir::Etc::preferences \"preferences\";\n");
            aptConf.append("Dir::Etc::preferencesparts \"preferences.d\";\n");
            aptConf.append("Dir::Etc::trusted \"trusted.gpg\";\n");
            aptConf.append("Dir::Etc::trustedparts \"").append(trustedPartsDir.getAbsolutePath()).append("\";\n");
            aptConf.append("Dir::Bin::methods \"").append(prefixPath).append("/lib/apt/methods\";\n");
            aptConf.append("Dir::Bin::dpkg \"").append(prefixPath).append("/bin/dpkg\";\n");
            aptConf.append("Dir::Bin::apt-key \"").append(prefixPath).append("/bin/apt-key\";\n");
            aptConf.append("Dir::Log \"").append(prefixPath).append("/var/log/apt\";\n");
            aptConf.append("DPkg::Options { \"--admindir=").append(prefixPath).append("/var/lib/dpkg\"; };\n");
            File caCert = new File(prefixDir, "etc/tls/cert.pem");
            if (caCert.exists()) {
                aptConf.append("Acquire::https::CaInfo \"").append(caCert.getAbsolutePath()).append("\";\n");
            }

            File aptConfFile = new File(aptEtcDir, "apt.conf");
            writeUtf8FileSafely(aptConfFile, aptConf.toString());
        } else {
            Logger.logWarn(LOG_TAG, "Skipping apt.conf write because required apt directories could not be created under " + prefixPath);
        }

        ensureFishAndKonsoleScripts(prefixPath);
        com.termux.app.konsole.KonsoleFishBridge.syncStateFiles(prefixPath);
    }

    /**
     * Generates Fish shell integration scripts, IPC command wrappers ($PREFIX/bin/konsole-exec &
     * $PREFIX/bin/kons), native Fish functions & completions, and bash.bashrc Fish handover.
     */
    public static void ensureFishAndKonsoleScripts(String prefixPath) {
        if (prefixPath == null || prefixPath.trim().isEmpty()) return;
        File prefixDir = new File(prefixPath);
        if (!prefixDir.exists() && !prefixDir.mkdirs()) return;

        String execPath = prefixPath + "/bin/konsole-exec";
        String ipcDirPath = prefixPath + "/var/run/konsole-ipc";
        String varsFishPath = ipcDirPath + "/vars.fish";

        // 1. $PREFIX/bin/konsole-exec (0755)
        String konsoleExecScript =
            "#!/system/bin/sh\n" +
            "PREFIX_DIR=\"" + prefixPath + "\"\n" +
            "IPC_DIR=\"$PREFIX_DIR/var/run/konsole-ipc\"\n" +
            "REQ_DIR=\"$IPC_DIR/req\"\n" +
            "RESP_DIR=\"$IPC_DIR/resp\"\n" +
            "mkdir -p \"$REQ_DIR\" \"$RESP_DIR\" 2>/dev/null\n\n" +
            "if [ \"$1\" = \"--mode\" ]; then\n" +
            "    if [ -r \"$IPC_DIR/mode\" ]; then\n" +
            "        cat \"$IPC_DIR/mode\"\n" +
            "    else\n" +
            "        printf \"konsole\"\n" +
            "    fi\n" +
            "    exit 0\n" +
            "fi\n\n" +
            "ACTION=\"EXEC\"\n" +
            "if [ \"$1\" = \"--try-launch\" ]; then\n" +
            "    ACTION=\"TRY_LAUNCH\"\n" +
            "    shift\n" +
            "elif [ \"$1\" = \"--macro\" ]; then\n" +
            "    ACTION=\"MACRO\"\n" +
            "    shift\n" +
            "fi\n\n" +
            "if [ $# -eq 0 ] && [ \"$ACTION\" != \"MACRO\" ]; then\n" +
            "    exit 0\n" +
            "fi\n\n" +
            "RAND=\"${RANDOM:-0}\"\n" +
            "REQ_ID=\"$$.$(date +%s 2>/dev/null || echo 0).$RAND\"\n" +
            "TMP_FILE=\"$REQ_DIR/$REQ_ID.tmp\"\n" +
            "REQ_FILE=\"$REQ_DIR/$REQ_ID.req\"\n" +
            "OUT_FILE=\"$RESP_DIR/$REQ_ID.out\"\n" +
            "RC_FILE=\"$RESP_DIR/$REQ_ID.rc\"\n\n" +
            "{\n" +
            "    printf \"%s\\n\" \"$ACTION\"\n" +
            "    printf \"%s\\n\" \"$*\"\n" +
            "} > \"$TMP_FILE\"\n" +
            "mv \"$TMP_FILE\" \"$REQ_FILE\"\n\n" +
            "I=0\n" +
            "while [ ! -f \"$RC_FILE\" ] && [ \"$I\" -lt 18000 ]; do\n" +
            "    usleep 20000 2>/dev/null || sleep 0.02 2>/dev/null || sleep 1\n" +
            "    I=$((I + 1))\n" +
            "done\n\n" +
            "if [ -f \"$OUT_FILE\" ]; then\n" +
            "    cat \"$OUT_FILE\"\n" +
            "fi\n" +
            "RC=1\n" +
            "if [ -f \"$RC_FILE\" ]; then\n" +
            "    RC=\"$(cat \"$RC_FILE\" 2>/dev/null)\"\n" +
            "    [ -z \"$RC\" ] && RC=0\n" +
            "fi\n" +
            "rm -f \"$OUT_FILE\" \"$RC_FILE\" 2>/dev/null\n" +
            "exit \"$RC\"\n";
        File konsoleExecFile = new File(prefixDir, "bin/konsole-exec");
        writeUtf8FileSafely(konsoleExecFile, konsoleExecScript);
        ensureExecutable(konsoleExecFile);

        // 2. $PREFIX/bin/kons (0755)
        String konsBinScript =
            "#!/system/bin/sh\n" +
            "exec \"" + execPath + "\" kons \"$@\"\n";
        File konsBinFile = new File(prefixDir, "bin/kons");
        writeUtf8FileSafely(konsBinFile, konsBinScript);
        ensureExecutable(konsBinFile);

        // 2b. $PREFIX/bin/macro and $PREFIX/bin/auto-trigger bidirectional bridge binaries (0755)
        String macroBinScript =
            "#!/system/bin/sh\n" +
            "exec \"" + execPath + "\" --macro \"$@\"\n";
        File macroBinFile = new File(prefixDir, "bin/macro");
        writeUtf8FileSafely(macroBinFile, macroBinScript);
        ensureExecutable(macroBinFile);

        File autoTriggerBinFile = new File(prefixDir, "bin/auto-trigger");
        writeUtf8FileSafely(autoTriggerBinFile, macroBinScript);
        ensureExecutable(autoTriggerBinFile);

        // 3. $PREFIX/etc/fish/conf.d/konsole_os.fish
        StringBuilder fishConf = new StringBuilder();
        fishConf.append("# Konsole OS native Fish shell integration (auto-generated)\n");
        fishConf.append("set -gx KONSOLE_IPC_DIR \"").append(ipcDirPath).append("\"\n");
        fishConf.append("if not set -q konsole\n");
        fishConf.append("    set -gx konsole \"").append(com.termux.app.konsole.KonsoleConfigManager.KONSOLE_ROOT_PATH).append("\"\n");
        fishConf.append("end\n");
        fishConf.append("if test -f \"").append(varsFishPath).append("\"\n");
        fishConf.append("    source \"").append(varsFishPath).append("\"\n");
        fishConf.append("end\n\n");

        fishConf.append("function __konsole_mode\n");
        fishConf.append("    if test -r \"$KONSOLE_IPC_DIR/mode\"\n");
        fishConf.append("        string trim -- (cat \"$KONSOLE_IPC_DIR/mode\")\n");
        fishConf.append("    else\n");
        fishConf.append("        echo \"konsole\"\n");
        fishConf.append("    end\n");
        fishConf.append("end\n\n");

        fishConf.append("function __konsole_sync_vars\n");
        fishConf.append("    if test -f \"").append(varsFishPath).append("\"\n");
        fishConf.append("        source \"").append(varsFishPath).append("\"\n");
        fishConf.append("    end\n");
        fishConf.append("end\n\n");

        fishConf.append("function fish_greeting\n");
        fishConf.append("    __konsole_sync_vars\n");
        fishConf.append("    set -l mode (__konsole_mode)\n");
        fishConf.append("    set_color --bold brcyan\n");
        fishConf.append("    printf \"▸ Konsole OS\"\n");
        fishConf.append("    set_color brblack\n");
        fishConf.append("    printf \" · \"\n");
        fishConf.append("    set_color white\n");
        fishConf.append("    printf \"Fish Shell  \"\n");
        fishConf.append("    if test \"$mode\" = \"konsole\"\n");
        fishConf.append("        set_color --bold brgreen\n");
        fishConf.append("        printf \"[KONSOLE]\\n\"\n");
        fishConf.append("    else\n");
        fishConf.append("        set_color --bold yellow\n");
        fishConf.append("        printf \"[TERMUX]\\n\"\n");
        fishConf.append("    end\n");
        fishConf.append("    set_color brblack\n");
        fishConf.append("    printf \"  Run 'kons help' · 'kons dashboard' · or any Linux command\\n\"\n");
        fishConf.append("    set_color normal\n");
        fishConf.append("end\n\n");

        fishConf.append("function fish_prompt\n");
        fishConf.append("    set -l last_status $status\n");
        fishConf.append("    set -l mode (__konsole_mode)\n");
        fishConf.append("    if test \"$mode\" = \"konsole\"\n");
        fishConf.append("        set_color --bold brcyan\n");
        fishConf.append("        printf \"konsole\"\n");
        fishConf.append("        set_color brblack\n");
        fishConf.append("        printf \"@\"\n");
        fishConf.append("        set_color --bold brgreen\n");
        fishConf.append("        printf \"os\"\n");
        fishConf.append("    else\n");
        fishConf.append("        set_color --bold yellow\n");
        fishConf.append("        printf \"termux\"\n");
        fishConf.append("    end\n");
        fishConf.append("    set_color brblack\n");
        fishConf.append("    printf \" \"\n");
        fishConf.append("    set_color --bold brblue\n");
        fishConf.append("    printf \"%s\" (prompt_pwd)\n");
        fishConf.append("    if test $last_status -ne 0\n");
        fishConf.append("        set_color --bold brred\n");
        fishConf.append("        printf \" [%d]\" $last_status\n");
        fishConf.append("    end\n");
        fishConf.append("    if test \"$mode\" = \"konsole\"\n");
        fishConf.append("        set_color --bold brcyan\n");
        fishConf.append("    else\n");
        fishConf.append("        set_color --bold yellow\n");
        fishConf.append("    end\n");
        fishConf.append("    printf \" ❯ \"\n");
        fishConf.append("    set_color normal\n");
        fishConf.append("end\n\n");

        fishConf.append("function kons --description \"Konsole OS command suite\"\n");
        fishConf.append("    \"").append(execPath).append("\" kons $argv\n");
        fishConf.append("    set -l rc $status\n");
        fishConf.append("    __konsole_sync_vars\n");
        fishConf.append("    return $rc\n");
        fishConf.append("end\n\n");

        fishConf.append("function dashboard --description \"Open Konsole OS Dashboard\"\n");
        fishConf.append("    \"").append(execPath).append("\" kons dashboard $argv\n");
        fishConf.append("end\n\n");

        fishConf.append("function dash --description \"Open Konsole OS Dashboard or POSIX dash\"\n");
        fishConf.append("    if test (__konsole_mode) = \"termux\"; and command -sq dash\n");
        fishConf.append("        command dash $argv\n");
        fishConf.append("    else\n");
        fishConf.append("        \"").append(execPath).append("\" kons dashboard $argv\n");
        fishConf.append("    end\n");
        fishConf.append("end\n\n");

        String[] hardwareAndOsCmds = new String[]{
            "bluetooth", "bt", "wifi", "music", "media", "call",
            "contact", "contacts", "volume", "vol", "calc",
            "flash", "flashlight", "vibrate", "beep", "brightness",
            "dnd", "wallpaper", "lock", "battery", "launch",
            "uninstall", "settings"
        };
        for (String cmd : hardwareAndOsCmds) {
            fishConf.append("function ").append(cmd).append(" --description \"Konsole OS ").append(cmd).append("\"\n");
            fishConf.append("    if test (__konsole_mode) = \"konsole\"\n");
            fishConf.append("        \"").append(execPath).append("\" ").append(cmd).append(" $argv\n");
            fishConf.append("        set -l rc $status\n");
            fishConf.append("        __konsole_sync_vars\n");
            fishConf.append("        return $rc\n");
            fishConf.append("    else if command -sq ").append(cmd).append("\n");
            fishConf.append("        command ").append(cmd).append(" $argv\n");
            fishConf.append("    else\n");
            fishConf.append("        \"").append(execPath).append("\" ").append(cmd).append(" $argv\n");
            fishConf.append("    end\n");
            fishConf.append("end\n\n");
        }

        fishConf.append("function macro --description \"Konsole OS Automation & Macro Bridge\"\n");
        fishConf.append("    \"").append(execPath).append("\" --macro $argv\n");
        fishConf.append("end\n\n");

        fishConf.append("function auto-trigger --description \"Konsole OS Automation Trigger Bridge\"\n");
        fishConf.append("    \"").append(execPath).append("\" --macro $argv\n");
        fishConf.append("end\n\n");

        fishConf.append("function __konsole_command_not_found --on-event fish_command_not_found\n");
        fishConf.append("    if test (__konsole_mode) = \"konsole\"\n");
        fishConf.append("        \"").append(execPath).append("\" --try-launch $argv\n");
        fishConf.append("        if test $status -eq 0\n");
        fishConf.append("            return 0\n");
        fishConf.append("        end\n");
        fishConf.append("    end\n");
        fishConf.append("    echo \"fish: Unknown command: $argv[1]\" >&2\n");
        fishConf.append("    return 127\n");
        fishConf.append("end\n");

        writeUtf8FileSafely(new File(prefixDir, "etc/fish/conf.d/konsole_os.fish"), fishConf.toString());

        // 4. $PREFIX/etc/fish/completions/kons.fish
        String konsCompletions =
            "# Konsole OS Fish completions\n" +
            "complete -c kons -f\n" +
            "complete -c kons -n '__fish_use_subcommand' -a 'dashboard dash macro macrolab setup-fish fish apps palette search install run remove list sys hud set notify toast alias update version about help switch'\n" +
            "complete -c kons -n '__fish_seen_subcommand_from switch' -a 'konsole termux'\n" +
            "complete -c kons -n '__fish_seen_subcommand_from hud' -a 'on off'\n" +
            "complete -c macro -f -n '__fish_use_subcommand' -a 'lab list status trigger enable disable notify toast flash vibrate launch tap swipe help'\n" +
            "complete -c auto-trigger -f -n '__fish_use_subcommand' -a 'lab list status trigger enable disable notify toast flash vibrate launch tap swipe help'\n" +
            "complete -c bluetooth -f -a 'status on off ls connect'\n" +
            "complete -c bt -f -a 'status on off ls connect'\n" +
            "complete -c wifi -f -a 'status on off ls connect'\n" +
            "complete -c music -f -a 'play pause stop ls'\n" +
            "complete -c media -f -a 'play pause stop ls'\n" +
            "complete -c contact -f -a 'ls search'\n" +
            "complete -c contacts -f -a 'ls search'\n" +
            "complete -c volume -f -a 'media ringer alarm notification silent vibrate normal'\n" +
            "complete -c vol -f -a 'media ringer alarm notification silent vibrate normal'\n" +
            "complete -c flash -f -a 'on off'\n" +
            "complete -c flashlight -f -a 'on off'\n" +
            "complete -c brightness -f -a '25 50 80 100 auto'\n" +
            "complete -c dnd -f -a 'on off'\n" +
            "complete -c settings -f -a 'wifi bluetooth apps display sound security storage'\n" +
            "complete -c lock -f -a '--sudo --sudo-set --sudo-reset --sudo-redo'\n";
        writeUtf8FileSafely(new File(prefixDir, "etc/fish/completions/kons.fish"), konsCompletions);

        // 5. Optional user ~/.config/fish/config.fish (only create if missing; never overwrite user customization)
        File filesDir = prefixDir.getParentFile();
        if (filesDir != null) {
            File userFishConf = new File(filesDir, "home/.config/fish/config.fish");
            if (!userFishConf.exists()) {
                writeUtf8FileSafely(userFishConf,
                    "# User Fish shell customizations\n" +
                    "# System Konsole OS functions are loaded from $PREFIX/etc/fish/conf.d/konsole_os.fish\n");
            }
        }

        // 6. Idempotent hook in $PREFIX/etc/bash.bashrc to launch Fish cleanly
        File bashrc = new File(prefixDir, "etc/bash.bashrc");
        String existingBashrc = readUtf8FileSafely(bashrc);
        String startMarker = "# BEGIN KONSOLE_FISH_SHELL";
        String endMarker = "# END KONSOLE_FISH_SHELL";
        String hookBlock =
            startMarker + "\n" +
            "export konsole=\"" + com.termux.app.konsole.KonsoleConfigManager.KONSOLE_ROOT_PATH + "\"\n" +
            "case \"$-\" in\n" +
            "  *i*)\n" +
            "    if [ -z \"$KONSOLE_NO_AUTO_FISH\" ] && [ -x \"" + prefixPath + "/bin/fish\" ]; then\n" +
            "      export SHELL=\"" + prefixPath + "/bin/fish\"\n" +
            "      exec \"" + prefixPath + "/bin/fish\" -l\n" +
            "    elif [ ! -x \"" + prefixPath + "/bin/fish\" ]; then\n" +
            "      echo \"[Konsole OS] Fish shell not installed yet. Run 'kons setup-fish' to install & activate Fish.\"\n" +
            "    fi\n" +
            "    ;;\n" +
            "esac\n" +
            endMarker + "\n";

        String updatedBashrc;
        int startIdx = existingBashrc.indexOf(startMarker);
        int endIdx = existingBashrc.indexOf(endMarker);
        if (startIdx >= 0 && endIdx > startIdx) {
            int afterEnd = endIdx + endMarker.length();
            if (afterEnd < existingBashrc.length() && existingBashrc.charAt(afterEnd) == '\n') {
                afterEnd++;
            }
            updatedBashrc = existingBashrc.substring(0, startIdx) + hookBlock + existingBashrc.substring(afterEnd);
        } else {
            updatedBashrc = existingBashrc + (existingBashrc.isEmpty() || existingBashrc.endsWith("\n") ? "" : "\n") + hookBlock;
        }
        if (!updatedBashrc.equals(existingBashrc)) {
            writeUtf8FileSafely(bashrc, updatedBashrc);
        }
    }

    private static String readUtf8FileSafely(File file) {
        if (file == null || !file.isFile() || !file.canRead()) return "";
        try {
            byte[] bytes = new byte[(int) Math.min(file.length(), 1024 * 1024)];
            int readTotal = 0;
            try (FileInputStream fis = new FileInputStream(file)) {
                int r;
                while (readTotal < bytes.length && (r = fis.read(bytes, readTotal, bytes.length - readTotal)) != -1) {
                    readTotal += r;
                }
            }
            return new String(bytes, 0, readTotal, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    private static void patchTermuxPathsInDirectory(File dir, String filesPath, String appDataPath, boolean executable) {
        if (dir == null || !dir.isDirectory()) return;
        File[] children = dir.listFiles();
        if (children == null) return;
        for (File child : children) {
            if (child.isFile()) {
                patchTermuxPathsInFile(child, filesPath, appDataPath, executable);
            }
        }
    }

    private static void patchTermuxPathsInFile(File file, String filesPath, String appDataPath, boolean executable) {
        if (file == null || !file.isFile() || !file.canRead()) return;
        try {
            byte[] bytes = new byte[(int) Math.min(file.length(), 1024 * 1024)];
            int readTotal = 0;
            try (FileInputStream fis = new FileInputStream(file)) {
                int r;
                while (readTotal < bytes.length && (r = fis.read(bytes, readTotal, bytes.length - readTotal)) != -1) {
                    readTotal += r;
                }
            }
            String content = new String(bytes, 0, readTotal, StandardCharsets.UTF_8);
            if (!content.contains("/data/data/com.termux") && !content.contains("/data/data/com.huntech.konsoleos")) return;

            String patched = content.replace("/data/data/com.termux/files", filesPath)
                .replace("/data/data/com.termux", appDataPath)
                .replace("/data/data/com.huntech.konsoleos/files", filesPath)
                .replace("/data/data/com.huntech.konsoleos", appDataPath);
            if (!patched.equals(content)) {
                writeUtf8FileSafely(file, patched);
                if (executable) {
                    ensureExecutable(file);
                }
            }
        } catch (Exception e) {
            Logger.logWarn(LOG_TAG, "Failed patching paths in " + file.getAbsolutePath() + ": " + e.getMessage());
        }
    }

    private static void ensureExecutable(File file) {
        if (file == null || !file.isFile()) return;
        file.setExecutable(true, true);
        //noinspection OctalInteger
        try {
            Os.chmod(file.getAbsolutePath(), 0700);
        } catch (Exception ignored) {
        }
    }

    private static void writeUtf8FileSafely(File targetFile, String content) {
        if (targetFile == null) return;
        File parent = targetFile.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs() && !parent.exists()) {
            Logger.logWarn(LOG_TAG, "Cannot write " + targetFile.getAbsolutePath() + ": parent directory missing");
            return;
        }
        try (FileOutputStream fos = new FileOutputStream(targetFile)) {
            fos.write(content.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            Logger.logWarn(LOG_TAG, "Failed writing file " + targetFile.getAbsolutePath() + ": " + e.getMessage());
        }
    }

    private static Error ensureDirectoryExists(File directory) {
        return FileUtils.createDirectoryFile(directory.getAbsolutePath());
    }

    public static byte[] loadZipBytes() {
        // Only load the shared library when necessary to save memory usage.
        System.loadLibrary("termux-bootstrap");
        return getZip();
    }

    public static native byte[] getZip();

}
