package com.termux.app.konsole;

import com.termux.app.TermuxInstaller;

import org.json.JSONObject;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
public class KonsoleInstallerAndPkgTest {

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    @Test
    public void testTermuxPackageAndAptKeyDestinations() {
        Assert.assertEquals("com.termux", com.termux.shared.termux.TermuxConstants.TERMUX_PACKAGE_NAME);
        Assert.assertEquals("/data/data/com.termux", com.termux.shared.termux.TermuxConstants.TERMUX_INTERNAL_PRIVATE_APP_DATA_DIR_PATH);
        Assert.assertEquals("/data/data/com.termux/files/usr", com.termux.shared.termux.TermuxConstants.TERMUX_PREFIX_DIR_PATH);
        Assert.assertEquals("/data/data/com.termux/files/usr/bin/apt-key", com.termux.shared.termux.TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/apt-key");
    }

    @Test
    public void testEnsureAptAndPkgConfiguredCreatesMissingDirsAndPatchesPaths() throws Exception {
        File appRoot = tempFolder.newFolder("com.termux");
        File filesDir = new File(appRoot, "files");
        File prefixDir = new File(filesDir, "usr");
        File binDir = new File(prefixDir, "bin");
        File sourcesListD = new File(prefixDir, "etc/apt/sources.list.d");
        Assert.assertTrue(binDir.mkdirs());
        Assert.assertTrue(sourcesListD.mkdirs());

        // Pre-populate scripts/configs with legacy com.huntech.konsoleos paths
        File pkgScript = new File(binDir, "pkg");
        try (FileOutputStream fos = new FileOutputStream(pkgScript)) {
            fos.write("#!/bin/sh\nPREFIX=/data/data/com.huntech.konsoleos/files/usr\n".getBytes(StandardCharsets.UTF_8));
        }
        File aptKeyScript = new File(binDir, "apt-key");
        try (FileOutputStream fos = new FileOutputStream(aptKeyScript)) {
            fos.write("#!/bin/sh\nTRUSTEDPARTS=/data/data/com.huntech.konsoleos/files/usr/etc/apt/trusted.gpg.d\n".getBytes(StandardCharsets.UTF_8));
        }
        File extraSource = new File(sourcesListD, "game.list");
        try (FileOutputStream fos = new FileOutputStream(extraSource)) {
            fos.write("deb [signed-by=/data/data/com.huntech.konsoleos/files/usr/etc/apt/trusted.gpg.d/termux.gpg] https://packages.termux.dev/apt/termux-main stable main\n"
                .getBytes(StandardCharsets.UTF_8));
        }

        // Note: etc/apt/trusted.gpg.d does NOT exist yet before calling ensureAptAndPkgConfigured
        File trustedDir = new File(prefixDir, "etc/apt/trusted.gpg.d");
        Assert.assertFalse(trustedDir.exists());

        TermuxInstaller.ensureAptAndPkgConfigured(prefixDir.getAbsolutePath());

        // Verify all required directories were created to avoid ENOENT
        String[] expectedDirs = {
            "etc/apt",
            "etc/apt/trusted.gpg.d",
            "etc/apt/sources.list.d",
            "etc/apt/apt.conf.d",
            "etc/apt/preferences.d",
            "etc/tls",
            "var/lib/apt/lists/partial",
            "var/cache/apt/archives/partial",
            "var/log/apt",
            "var/lib/dpkg/updates",
            "var/lib/dpkg/info",
            "var/lib/dpkg/alternatives",
            "var/lib/dpkg/triggers",
            "tmp"
        };
        for (String rel : expectedDirs) {
            File dir = new File(prefixDir, rel);
            Assert.assertTrue("Missing required directory: " + rel, dir.isDirectory());
        }

        File dpkgStatus = new File(prefixDir, "var/lib/dpkg/status");
        Assert.assertTrue(dpkgStatus.isFile());

        File sourcesList = new File(prefixDir, "etc/apt/sources.list");
        Assert.assertTrue(sourcesList.isFile());

        File aptConf = new File(prefixDir, "etc/apt/apt.conf");
        Assert.assertTrue(aptConf.isFile());
        String aptConfContent = new String(Files.readAllBytes(aptConf.toPath()), StandardCharsets.UTF_8);
        Assert.assertTrue(aptConfContent.contains("Dir::Etc::trustedparts \"" + trustedDir.getAbsolutePath() + "\";"));
        Assert.assertTrue(aptConfContent.contains("Dir::State::status \"" + dpkgStatus.getAbsolutePath() + "\";"));
        Assert.assertTrue(aptConfContent.contains("Dir::Bin::apt-key \"" + aptKeyScript.getAbsolutePath() + "\";"));

        String pkgContent = new String(Files.readAllBytes(pkgScript.toPath()), StandardCharsets.UTF_8);
        Assert.assertFalse(pkgContent.contains("/data/data/com.huntech.konsoleos/files"));
        Assert.assertTrue(pkgContent.contains(filesDir.getAbsolutePath()));
        Assert.assertTrue(pkgScript.canExecute());
        Assert.assertTrue(aptKeyScript.canExecute());

        String extraContent = new String(Files.readAllBytes(extraSource.toPath()), StandardCharsets.UTF_8);
        Assert.assertFalse(extraContent.contains("/data/data/com.huntech.konsoleos/files"));
        Assert.assertTrue(extraContent.contains(trustedDir.getAbsolutePath()));
    }

    @Test
    public void testKonsolePackageEndpointsIncludeRequestedUrls() {
        List<String> endpoints = Arrays.asList(KonsoleCommandDispatcher.PACKAGE_ENDPOINTS);
        Assert.assertTrue(endpoints.contains("https://hunterzen-ru.github.io/Konsole-package/packges.json"));
        Assert.assertTrue(endpoints.contains("https://hunter-zen-ru.github.io/Konsole-package/packges.json"));
    }

    @Test
    public void testParsePackagesFromAllSupportedJsonSchemas() {
        String objectWrapped = "{ \"packages\": [ {\"name\":\"neofetch\",\"version\":\"7.1\",\"type\":\"script\"}, {\"name\":\"htop\",\"version\":\"3.2\"} ] }";
        List<JSONObject> parsedWrapped = KonsoleCommandDispatcher.parsePackagesFromJson(objectWrapped);
        Assert.assertEquals(2, parsedWrapped.size());
        Assert.assertEquals("neofetch", parsedWrapped.get(0).optString("name"));

        String rawArray = "[ {\"name\":\"fastfetch\",\"version\":\"2.0\",\"description\":\"Fast sysinfo\"} ]";
        List<JSONObject> parsedArray = KonsoleCommandDispatcher.parsePackagesFromJson(rawArray);
        Assert.assertEquals(1, parsedArray.size());
        Assert.assertEquals("fastfetch", parsedArray.get(0).optString("name"));

        String keyedMap = "{ \"bat\": {\"version\":\"0.24\",\"type\":\"deb\"} }";
        List<JSONObject> parsedMap = KonsoleCommandDispatcher.parsePackagesFromJson(keyedMap);
        Assert.assertEquals(1, parsedMap.size());
        Assert.assertEquals("bat", parsedMap.get(0).optString("name"));
        Assert.assertEquals("deb", parsedMap.get(0).optString("type"));

        Assert.assertTrue(KonsoleCommandDispatcher.parsePackagesFromJson("").isEmpty());
        Assert.assertTrue(KonsoleCommandDispatcher.parsePackagesFromJson(null).isEmpty());
    }

    @Test
    public void testFourTierResolverFuzzyTypoAndKonsSubcommands() {
        KonsoleFourTierResolver resolver = KonsoleFourTierResolver.getInstance();

        String fuzzyHint = resolver.resolvePrediction("bluetoth", Collections.emptyList());
        Assert.assertEquals(" (did you mean: bluetooth?)", fuzzyHint);

        String paletteSub = resolver.resolvePrediction("kons pal", Collections.emptyList());
        Assert.assertEquals("ette", paletteSub);

        String searchSub = resolver.resolvePrediction("kons sea", Collections.emptyList());
        Assert.assertEquals("rch", searchSub);

        String hudSub = resolver.resolvePrediction("kons hu", Collections.emptyList());
        Assert.assertEquals("d", hudSub);

        String runSub = resolver.resolvePrediction("kons ru", Collections.emptyList());
        Assert.assertEquals("n", runSub);
    }

    @Test
    public void testIsBootstrapInstalledAndBridgeDeferral() throws Exception {
        File appRoot = tempFolder.newFolder("bootstrap_check");
        File prefixDir = new File(appRoot, "files/usr");
        File ipcReqDir = new File(prefixDir, "var/run/konsole-ipc/req");
        Assert.assertTrue(ipcReqDir.mkdirs());
        File binDir = new File(prefixDir, "bin");
        Assert.assertTrue(binDir.mkdirs());

        // Only IPC & custom bins -> NOT bootstrapped yet
        Assert.assertFalse(TermuxInstaller.isBootstrapInstalled(prefixDir.getAbsolutePath()));

        // Deferral helper must not create IPC folders in a fresh empty prefix
        File cleanPrefix = new File(appRoot, "clean/usr");
        Assert.assertTrue(cleanPrefix.mkdirs());
        KonsoleFishBridge.getInstance().startIfBootstrapReady(
            org.robolectric.RuntimeEnvironment.getApplication(),
            cleanPrefix.getAbsolutePath()
        );
        Assert.assertFalse(new File(cleanPrefix, "var/run/konsole-ipc").exists());

        // Add core shell & apt binaries -> now bootstrapped
        Assert.assertTrue(new File(binDir, "sh").createNewFile());
        Assert.assertTrue(new File(binDir, "apt").createNewFile());
        Assert.assertTrue(TermuxInstaller.isBootstrapInstalled(prefixDir.getAbsolutePath()));
    }

    @Test
    public void testExtractPayloadUrlsSupportsMultiUrlAndBackups() throws Exception {
        String pkgJson = "{"
            + "\"name\":\"test\","
            + "\"url\":[\"https://primary.example.com/test.sh\",\"scripts/konsole-os-test.sh\"],"
            + "\"mirrors\":[\"https://mirror.example.org/test.sh\"],"
            + "\"backup_url\":\"https://backup.example.net/test.sh\""
            + "}";
        JSONObject obj = new JSONObject(pkgJson);
        List<String> urls = KonsoleCommandDispatcher.extractPayloadUrls(
            obj, "https://hunterzen-ru.github.io/Konsole-package/packges.json");

        Assert.assertEquals(4, urls.size());
        Assert.assertEquals("https://primary.example.com/test.sh", urls.get(0));
        Assert.assertEquals("https://hunterzen-ru.github.io/Konsole-package/scripts/konsole-os-test.sh", urls.get(1));
        Assert.assertEquals("https://mirror.example.org/test.sh", urls.get(2));
        Assert.assertEquals("https://backup.example.net/test.sh", urls.get(3));

        Assert.assertTrue(KonsoleCommandDispatcher.isShellBuiltinName("test"));
        Assert.assertTrue(KonsoleCommandDispatcher.isShellBuiltinName("echo"));
        Assert.assertFalse(KonsoleCommandDispatcher.isShellBuiltinName("neofetch"));
    }
}
