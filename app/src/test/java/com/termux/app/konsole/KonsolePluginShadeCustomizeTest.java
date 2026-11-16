package com.termux.app.konsole;

import android.app.Application;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.appcompat.widget.SwitchCompat;

import com.termux.R;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@RunWith(RobolectricTestRunner.class)
public class KonsolePluginShadeCustomizeTest {

    private Application app;

    @Before
    public void setUp() {
        app = RuntimeEnvironment.getApplication();
        KonsoleConfigManager.getInstance().resetDefaultCustomizeXml();
        KonsolePluginManager.getInstance().ensureSeedFiles(app);
    }

    @Test
    public void testCustomizeXmlSeedAndSetProperty() {
        KonsoleConfigManager cfg = KonsoleConfigManager.getInstance();

        Assert.assertTrue(new File(KonsoleConfigManager.FILE_UI_XML).isFile());
        Assert.assertTrue(new File(KonsoleConfigManager.FILE_BEHAVIOR_XML).isFile());
        Assert.assertTrue(new File(KonsoleConfigManager.FILE_SHADE_XML).isFile());
        Assert.assertTrue(new File(KonsoleConfigManager.FILE_EXPERIENCE_XML).isFile());
        Assert.assertTrue(new File(KonsoleConfigManager.FILE_CUSTOMIZE_README).isFile());

        Assert.assertTrue(cfg.setCustomizeProperty("ui", "hudPosition", "bottom"));
        Assert.assertTrue(cfg.setCustomizeProperty("shade", "replaceSystemShade", "true"));
        Assert.assertTrue(cfg.setCustomizeProperty("experience", "autoOpenKeyboard", "false"));
        Assert.assertTrue(cfg.setCustomizeProperty("behavior", "greeting", "Custom Boot Greeting"));
        Assert.assertFalse(cfg.setCustomizeProperty("unknown", "k", "v"));

        cfg.loadAllConfigurations();

        Assert.assertEquals("bottom", cfg.getUiProperty("hudPosition", "top"));
        Assert.assertTrue(cfg.getShadeBoolean("replaceSystemShade", false));
        Assert.assertFalse(cfg.getExperienceBoolean("autoOpenKeyboard", true));
        Assert.assertEquals("Custom Boot Greeting", cfg.getBehaviorProperty("greeting", ""));
    }

    @Test
    public void testSeededDocsAndSamplePluginManifest() {
        File pluginsRoot = new File(KonsoleConfigManager.PLUGINS_PATH);
        File promptMd = new File(pluginsRoot, "prompt.md");
        File readmeMd = new File(pluginsRoot, "readme.md");
        Assert.assertTrue("prompt.md should be seeded", promptMd.isFile());
        Assert.assertTrue("readme.md should be seeded", readmeMd.isFile());

        KonsolePlugin sample = KonsolePluginManager.getInstance().findPluginByName("system-pulse");
        Assert.assertNotNull("system-pulse sample plugin should exist", sample);
        Assert.assertEquals("System Pulse", sample.name);
        Assert.assertEquals("1.0.0", sample.version);
        Assert.assertEquals("android", sample.build);
        Assert.assertEquals("1.0.0", sample.konsoleTargetVersion);
        Assert.assertTrue(sample.isLaunchable());
        Assert.assertEquals("base.html", sample.startingFile);
        Assert.assertTrue(sample.iconFile != null && sample.iconFile.isFile());
        Assert.assertTrue(sample.flatFiles.contains("app.png"));
        Assert.assertTrue(sample.flatFiles.contains("manifest.json"));
        Assert.assertTrue(sample.flatFiles.contains("base.html"));
        Assert.assertTrue(sample.flatFiles.contains("assets/pulse.css"));
        Assert.assertTrue(sample.flatFiles.contains("scripts/diagnostics.sh"));
    }

    @Test
    public void testLaunchableFilterExcludesExtensionsAndDefaultsStartingFile() throws Exception {
        File extDir = new File(KonsoleConfigManager.PLUGINS_PATH, "sys-extension-test");
        if (!extDir.exists()) Assert.assertTrue(extDir.mkdirs());

        try {
            File baseSh = new File(extDir, "base.sh");
            try (FileOutputStream fos = new FileOutputStream(baseSh)) {
                fos.write("echo hello\n".getBytes(StandardCharsets.UTF_8));
            }
            File manifest = new File(extDir, "manifest.json");
            String json = "{\n"
                    + "  \"name\": \"Sys Extension\",\n"
                    + "  \"description\": \"Background helper extension\",\n"
                    + "  \"version\": \"2.1\",\n"
                    + "  \"build\": \"linux\",\n"
                    + "  \"konsoleTargetVersion\": \"1.0.0\",\n"
                    + "  \"type\": \"extention\",\n"
                    + "  \"deps\": [\"curl\", \"jq\"],\n"
                    + "  \"permissions\": [\"android.permission.INTERNET\"]\n"
                    + "}";
            try (FileOutputStream fos = new FileOutputStream(manifest)) {
                fos.write(json.getBytes(StandardCharsets.UTF_8));
            }

            KonsolePlugin parsedExt = KonsolePlugin.fromDirectory(extDir);
            Assert.assertNotNull(parsedExt);
            Assert.assertTrue(parsedExt.isExtension());
            Assert.assertFalse(parsedExt.isLaunchable());
            Assert.assertEquals("base.sh", parsedExt.startingFile);
            Assert.assertEquals("linux", parsedExt.build);
            Assert.assertEquals(2, parsedExt.deps.size());
            Assert.assertEquals(1, parsedExt.permissions.size());

            List<KonsolePlugin> launchables = KonsolePluginManager.getInstance().getLaunchablePlugins();
            boolean hasSample = false;
            for (KonsolePlugin p : launchables) {
                Assert.assertTrue("Launcher must only contain launchable plugins", p.isLaunchable());
                Assert.assertNotEquals("sys-extension-test", p.dirName);
                if ("system-pulse".equals(p.dirName)) hasSample = true;
            }
            Assert.assertTrue(hasSample);
        } finally {
            KonsolePluginManager.getInstance().removePlugin("sys-extension-test");
        }
    }

    @Test
    public void testParseRemoteGitHubPluginsJson() {
        String sampleJson = "{\n"
                + "  \"plugins\": [\n"
                + "    {\n"
                + "      \"name\": \"test\",\n"
                + "      \"version\": \"1.0\",\n"
                + "      \"description\": \"My custom system cleanup and backup script\",\n"
                + "      \"type\": \"launchable\",\n"
                + "      \"url\": \"https://hunterzen-ru.github.io/Konsole-plugins/konsole-ocr.zip\"\n"
                + "    },\n"
                + "    {\n"
                + "      \"name\": \"sys\",\n"
                + "      \"version\": \"0.0\",\n"
                + "      \"description\": \"Comprehensive system health and network monitor using TUI variables.\",\n"
                + "      \"type\": \"extention\",\n"
                + "      \"url\": \"https://hunterzen-ru.github.io/TUI/packages/test.zip\"\n"
                + "    },\n"
                + "    {\n"
                + "      \"name\": \"tui-app\",\n"
                + "      \"version\": \"6.1\",\n"
                + "      \"type\": \"extention\",\n"
                + "      \"description\": \"T-UI Console Launcher Official Release\",\n"
                + "      \"url\": \"https://github.com/hunter-zen-RU/TUI/releases/latest/download/tui.zip\"\n"
                + "    }\n"
                + "  ]\n"
                + "}";

        List<KonsolePluginManager.RemotePluginTemplate> templates = KonsolePluginManager.parseRemoteRegistryJson(sampleJson);
        Assert.assertEquals(3, templates.size());
        Assert.assertEquals("test", templates.get(0).name);
        Assert.assertEquals("launchable", templates.get(0).type);
        Assert.assertEquals("sys", templates.get(1).name);
        Assert.assertEquals("extention", templates.get(1).type);
        Assert.assertEquals("tui-app", templates.get(2).name);
        Assert.assertEquals("6.1", templates.get(2).version);
    }

    @Test
    public void testExtractZipSafelyBlocksZipSlip() throws Exception {
        ByteArrayOutputStream safeBaos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(safeBaos)) {
            zos.putNextEntry(new ZipEntry("hello/base.sh"));
            zos.write("echo ok\n".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }
        File safeDest = new File(app.getCacheDir(), "zip_safe_test");
        KonsolePluginManager.extractZipSafely(safeBaos.toByteArray(), safeDest);
        Assert.assertTrue(new File(safeDest, "hello/base.sh").isFile());

        ByteArrayOutputStream evilBaos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(evilBaos)) {
            zos.putNextEntry(new ZipEntry("../../evil.sh"));
            zos.write("bad".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }
        boolean blocked = false;
        try {
            KonsolePluginManager.extractZipSafely(evilBaos.toByteArray(), new File(app.getCacheDir(), "zip_evil_test"));
        } catch (IOException expected) {
            blocked = expected.getMessage() != null && expected.getMessage().contains("Zip Slip");
        }
        Assert.assertTrue("Zip Slip traversal must be rejected", blocked);
    }

    @Test
    public void testShadeControllerAndHudPluginsButtonAndDashboardControls() {
        FrameLayout host = new FrameLayout(app);
        KonsoleShadeController shadeController = new KonsoleShadeController(app, () -> {});
        View shadeView = shadeController.getOrCreateView(host);
        Assert.assertNotNull(shadeView);
        Assert.assertNotNull(shadeView.findViewById(R.id.shade_tiles_grid));
        Assert.assertNotNull(shadeView.findViewById(R.id.shade_set_default_btn));

        android.content.Context themed = new android.view.ContextThemeWrapper(
                app, R.style.Theme_TermuxActivity_DayNight_NoActionBar);
        View activityLayout = LayoutInflater.from(themed).inflate(R.layout.activity_termux, null, false);
        TextView plgBtn = activityLayout.findViewById(R.id.konsole_hud_plugins);
        Assert.assertNotNull("HUD bar must include PLG plugin launcher button", plgBtn);
        Assert.assertEquals("PLG", plgBtn.getText().toString());
        Assert.assertNotNull(activityLayout.findViewById(R.id.konsole_shade_host));

        KonsoleDashboardActivity dashboard = Robolectric.buildActivity(KonsoleDashboardActivity.class)
                .setup()
                .get();
        SwitchCompat replaceShadeSwitch = dashboard.findViewById(R.id.switch_replace_shade);
        Assert.assertNotNull(replaceShadeSwitch);
        Assert.assertNotNull(dashboard.findViewById(R.id.dashboard_open_shade_btn));
        Assert.assertNotNull(dashboard.findViewById(R.id.dashboard_open_plugins));
        Assert.assertNotNull(dashboard.findViewById(R.id.dashboard_open_templates));
        Assert.assertNotNull(dashboard.findViewById(R.id.dashboard_reload_xml));
    }
}
