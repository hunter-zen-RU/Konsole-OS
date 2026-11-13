package com.termux.app.konsole;

import android.content.Context;

import org.robolectric.RuntimeEnvironment;

import com.termux.app.TermuxInstaller;
import com.termux.shared.shell.command.environment.UnixShellEnvironment;
import com.termux.terminal.TerminalSession;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
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
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@RunWith(RobolectricTestRunner.class)
public class KonsoleFishAndPkgInstallTest {

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    private File prefixDir;
    private File binDir;

    @Before
    public void setUp() throws Exception {
        File appDir = tempFolder.newFolder("com.termux");
        File filesDir = new File(appDir, "files");
        prefixDir = new File(filesDir, "usr");
        binDir = new File(prefixDir, "bin");
        Assert.assertTrue(binDir.mkdirs());
    }

    @After
    public void tearDown() {
        KonsoleFishBridge.getInstance().stop();
    }

    @Test
    public void testFishBinaryPrioritizedInShellEnvironment() {
        Assert.assertEquals("fish", UnixShellEnvironment.LOGIN_SHELL_BINARIES[0]);
    }

    @Test
    public void testTermuxInstallerGeneratesFishIntegrationAndIpcScripts() throws Exception {
        TermuxInstaller.ensureAptAndPkgConfigured(prefixDir.getAbsolutePath());

        File konsoleExec = new File(prefixDir, "bin/konsole-exec");
        File konsBin = new File(prefixDir, "bin/kons");
        File fishConf = new File(prefixDir, "etc/fish/conf.d/konsole_os.fish");
        File fishCompletions = new File(prefixDir, "etc/fish/completions/kons.fish");
        File bashrc = new File(prefixDir, "etc/bash.bashrc");
        File reqDir = new File(prefixDir, "var/run/konsole-ipc/req");
        File respDir = new File(prefixDir, "var/run/konsole-ipc/resp");
        File modeFile = new File(prefixDir, "var/run/konsole-ipc/mode");
        File varsFish = new File(prefixDir, "var/run/konsole-ipc/vars.fish");

        Assert.assertTrue(konsoleExec.isFile() && konsoleExec.canExecute());
        Assert.assertTrue(konsBin.isFile() && konsBin.canExecute());
        Assert.assertTrue(fishConf.isFile());
        Assert.assertTrue(fishCompletions.isFile());
        Assert.assertTrue(bashrc.isFile());
        Assert.assertTrue(reqDir.isDirectory());
        Assert.assertTrue(respDir.isDirectory());
        Assert.assertTrue(modeFile.isFile());
        Assert.assertTrue(varsFish.isFile());

        String fishConfContent = new String(Files.readAllBytes(fishConf.toPath()), StandardCharsets.UTF_8);
        Assert.assertTrue(fishConfContent.contains("function kons "));
        Assert.assertTrue(fishConfContent.contains("function dashboard "));
        Assert.assertTrue(fishConfContent.contains("function bluetooth "));
        Assert.assertTrue(fishConfContent.contains("function __konsole_command_not_found --on-event fish_command_not_found"));
        // Verify Fish builtins/keywords are never shadowed as functions
        Assert.assertFalse(fishConfContent.contains("function set "));
        Assert.assertFalse(fishConfContent.contains("function switch "));
        Assert.assertFalse(fishConfContent.contains("function cd "));
        Assert.assertFalse(fishConfContent.contains("function echo "));

        String completionsContent = new String(Files.readAllBytes(fishCompletions.toPath()), StandardCharsets.UTF_8);
        Assert.assertTrue(completionsContent.contains("complete -c kons"));
        Assert.assertTrue(completionsContent.contains("setup-fish"));

        String bashrcContent = new String(Files.readAllBytes(bashrc.toPath()), StandardCharsets.UTF_8);
        Assert.assertTrue(bashrcContent.contains("# BEGIN KONSOLE_FISH_SHELL"));
        Assert.assertTrue(bashrcContent.contains("bin/fish"));
    }

    @Test
    public void testInstallExecutableIntoPrefixNormalizesScriptsAndExtractsKosZip() throws Exception {
        // 1. Script with Linux env shebang and CRLF line endings
        File rawScript = tempFolder.newFile("hello.sh");
        try (FileOutputStream fos = new FileOutputStream(rawScript)) {
            fos.write("#!/usr/bin/env bash\r\necho \"hello from konsole\"\r\n".getBytes(StandardCharsets.UTF_8));
        }

        File installed = KonsoleCommandDispatcher.installExecutableIntoPrefix(rawScript, binDir, "hello", "script");
        Assert.assertEquals(new File(binDir, "hello").getAbsolutePath(), installed.getAbsolutePath());
        Assert.assertTrue(installed.isFile() && installed.canExecute());
        String normalized = new String(Files.readAllBytes(installed.toPath()), StandardCharsets.UTF_8);
        Assert.assertFalse(normalized.contains("\r"));
        Assert.assertTrue(normalized.startsWith("#!" + prefixDir.getAbsolutePath() + "/bin/env bash\n"));

        // 2. .kos ZIP archive containing bin/mytool
        File kosZip = tempFolder.newFile("mytool.kos");
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(kosZip))) {
            zos.putNextEntry(new ZipEntry("manifest.json"));
            zos.write("{\"name\":\"mytool\"}".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();

            zos.putNextEntry(new ZipEntry("bin/mytool"));
            zos.write("#!/bin/fish\necho \"running kos tool\"\n".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }

        File installedKos = KonsoleCommandDispatcher.installExecutableIntoPrefix(kosZip, binDir, "mytool", "kos");
        Assert.assertTrue(installedKos.isFile() && installedKos.canExecute());
        String kosScript = new String(Files.readAllBytes(installedKos.toPath()), StandardCharsets.UTF_8);
        Assert.assertTrue(kosScript.startsWith("#!" + prefixDir.getAbsolutePath() + "/bin/fish\n"));
        Assert.assertTrue(kosScript.contains("running kos tool"));

        // 3. Raw ELF binary should keep ELF magic untouched
        File rawElf = tempFolder.newFile("nativebin");
        byte[] elfBytes = new byte[]{0x7F, 'E', 'L', 'F', 2, 1, 1, 0};
        try (FileOutputStream fos = new FileOutputStream(rawElf)) {
            fos.write(elfBytes);
        }
        File installedElf = KonsoleCommandDispatcher.installExecutableIntoPrefix(rawElf, binDir, "nativebin", "binary");
        Assert.assertTrue(installedElf.isFile() && installedElf.canExecute());
        byte[] readBack = Files.readAllBytes(installedElf.toPath());
        Assert.assertArrayEquals(elfBytes, readBack);
    }

    @Test
    public void testKonsoleFishBridgeProcessesIpcRequestAndWritesOutAndRc() throws Exception {
        Context ctx = RuntimeEnvironment.getApplication();
        KonsoleConfigManager.getInstance().init(ctx);
        KonsoleConfigManager.getInstance().setBehaviorMode("konsole");
        TermuxInstaller.ensureAptAndPkgConfigured(prefixDir.getAbsolutePath());

        KonsoleFishBridge bridge = KonsoleFishBridge.getInstance();
        bridge.start(ctx, prefixDir.getAbsolutePath());

        File reqDir = new File(prefixDir, "var/run/konsole-ipc/req");
        File respDir = new File(prefixDir, "var/run/konsole-ipc/resp");
        File reqFile = new File(reqDir, "test123.req");
        try (FileOutputStream fos = new FileOutputStream(reqFile)) {
            fos.write("EXEC\nkons version\n".getBytes(StandardCharsets.UTF_8));
        }

        File rcFile = new File(respDir, "test123.rc");
        File outFile = new File(respDir, "test123.out");
        long deadline = System.currentTimeMillis() + 5000L;
        while (!rcFile.exists() && System.currentTimeMillis() < deadline) {
            Thread.sleep(25);
        }

        Assert.assertTrue("Expected IPC .rc response file", rcFile.isFile());
        Assert.assertTrue("Expected IPC .out response file", outFile.isFile());
        String rc = new String(Files.readAllBytes(rcFile.toPath()), StandardCharsets.UTF_8).trim();
        String out = new String(Files.readAllBytes(outFile.toPath()), StandardCharsets.UTF_8);
        Assert.assertEquals("0", rc);
        Assert.assertTrue(out.contains("Konsole OS"));
        Assert.assertTrue(out.contains("Fish"));

        // Verify completion suggestions include setup-fish
        String setupCompletion = KonsoleFourTierResolver.getInstance().resolvePrediction("kons setu", Collections.emptyList());
        Assert.assertEquals("p-fish", setupCompletion);
    }

    @Test
    public void testPayloadUrlCandidateFallbacks() {
        java.util.List<String> candidates = KonsoleCommandDispatcher.buildCandidatePayloadUrls(
                "https://hunterzen-ru.github.io/scripts/konsole-os-test.sh",
                "https://hunterzen-ru.github.io/Konsole-package/packges.json");
        Assert.assertEquals(2, candidates.size());
        Assert.assertEquals("https://hunterzen-ru.github.io/scripts/konsole-os-test.sh", candidates.get(0));
        Assert.assertEquals("https://hunterzen-ru.github.io/Konsole-package/scripts/konsole-os-test.sh", candidates.get(1));
    }

    @Test
    public void testShShebangAdaptsToPrefixAvailability() throws Exception {
        File scriptWithoutPrefixSh = tempFolder.newFile("no_prefix_sh.sh");
        try (FileOutputStream fos = new FileOutputStream(scriptWithoutPrefixSh)) {
            fos.write("#!/bin/sh\necho ok\n".getBytes(StandardCharsets.UTF_8));
        }

        File outNoSh = KonsoleCommandDispatcher.installExecutableIntoPrefix(scriptWithoutPrefixSh, binDir, "noprefix", "script");
        String contentNoSh = new String(Files.readAllBytes(outNoSh.toPath()), StandardCharsets.UTF_8);
        Assert.assertTrue(contentNoSh.startsWith("#!/system/bin/sh\n"));

        // Now simulate bootstrapped $PREFIX/bin/sh
        Assert.assertTrue(new File(binDir, "sh").createNewFile());
        File outWithSh = KonsoleCommandDispatcher.installExecutableIntoPrefix(scriptWithoutPrefixSh, binDir, "withprefix", "script");
        String contentWithSh = new String(Files.readAllBytes(outWithSh.toPath()), StandardCharsets.UTF_8);
        Assert.assertTrue(contentWithSh.startsWith("#!" + prefixDir.getAbsolutePath() + "/bin/sh\n"));
    }
}
