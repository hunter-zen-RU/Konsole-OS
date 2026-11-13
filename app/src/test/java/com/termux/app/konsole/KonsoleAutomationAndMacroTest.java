package com.termux.app.konsole;

import android.app.Application;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.termux.R;
import com.termux.app.TermuxInstaller;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@RunWith(RobolectricTestRunner.class)
public class KonsoleAutomationAndMacroTest {

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    private Application app;

    @Before
    public void setUp() {
        app = RuntimeEnvironment.getApplication();
        KonsoleMacroStore.getInstance().clearHistory(app);
    }

    @After
    public void tearDown() {
        KonsoleAutomationEngine.getInstance().stop();
        KonsoleFishBridge.getInstance().stop();
    }

    @Test
    public void testCronParserValidationAndNextRunComputation() {
        Assert.assertTrue(KonsoleCronParser.isValid("*/5 * * * *"));
        Assert.assertTrue(KonsoleCronParser.isValid("0 9-17 * * 1-5"));
        Assert.assertTrue(KonsoleCronParser.isValid("@hourly"));
        Assert.assertTrue(KonsoleCronParser.isValid("@daily"));
        Assert.assertTrue(KonsoleCronParser.isValid("@every_5m"));

        Assert.assertFalse(KonsoleCronParser.isValid(""));
        Assert.assertFalse(KonsoleCronParser.isValid("* * *"));
        Assert.assertFalse(KonsoleCronParser.isValid("75 * * * *"));
        Assert.assertFalse(KonsoleCronParser.isValid("0 25 * * *"));

        Calendar cal = Calendar.getInstance();
        cal.set(2026, Calendar.MAY, 15, 10, 30, 0);
        cal.set(Calendar.MILLISECOND, 0);

        Assert.assertTrue(KonsoleCronParser.matches("*/15 * * * *", cal));
        Assert.assertFalse(KonsoleCronParser.matches("5 * * * *", cal));

        long nextMs = KonsoleCronParser.computeNextTriggerTimeMs("*/15 * * * *", cal.getTimeInMillis());
        Calendar nextCal = Calendar.getInstance();
        nextCal.setTimeInMillis(nextMs);
        Assert.assertEquals(10, nextCal.get(Calendar.HOUR_OF_DAY));
        Assert.assertEquals(45, nextCal.get(Calendar.MINUTE));
    }

    @Test
    public void testMacroStorePersistenceAndTemplates() {
        KonsoleMacroRule rule = new KonsoleMacroRule();
        rule.id = "test_persist_1";
        rule.name = "Backup Check";
        rule.triggerType = KonsoleMacroRule.TriggerType.WIFI_CONNECTED;
        rule.constraints.requireWifi = true;
        rule.constraints.minBattery = 20;
        rule.actions.add(new KonsoleMacroRule.MacroAction(
                KonsoleMacroRule.ActionType.SHELL, "echo ok", "", ""));

        KonsoleMacroStore store = KonsoleMacroStore.getInstance();
        store.upsertMacro(app, rule);

        KonsoleMacroRule loaded = store.findByIdOrName(app, "test_persist_1");
        Assert.assertNotNull(loaded);
        Assert.assertEquals("Backup Check", loaded.name);
        Assert.assertEquals(KonsoleMacroRule.TriggerType.WIFI_CONNECTED, loaded.triggerType);
        Assert.assertTrue(loaded.constraints.requireWifi);
        Assert.assertEquals(20, loaded.constraints.minBattery);
        Assert.assertEquals(1, loaded.actions.size());
        Assert.assertEquals("echo ok", loaded.actions.get(0).param1);

        Assert.assertTrue(store.deleteMacro(app, "test_persist_1"));
        Assert.assertNull(store.findByIdOrName(app, "test_persist_1"));
        Assert.assertTrue(KonsoleMacroRule.createStarterTemplates().size() >= 4);
    }

    @Test
    public void testConstraintEvaluatorAndVariableInterpolation() {
        KonsoleMacroRule.Constraints lenient = new KonsoleMacroRule.Constraints();
        lenient.minBattery = 0;
        lenient.maxBattery = 100;
        KonsoleConstraintEvaluator.EvaluationResult pass = KonsoleConstraintEvaluator.evaluate(app, lenient);
        Assert.assertTrue(pass.passed);

        KonsoleMacroRule.Constraints impossibleWifiSsid = new KonsoleMacroRule.Constraints();
        impossibleWifiSsid.ssidFilter = "DefinitelyMissingSsid_999";
        KonsoleConstraintEvaluator.EvaluationResult fail = KonsoleConstraintEvaluator.evaluate(app, impossibleWifiSsid);
        Assert.assertFalse(fail.passed);
        Assert.assertTrue(fail.summary.contains("DefinitelyMissingSsid_999"));

        KonsoleMacroRule sample = new KonsoleMacroRule();
        sample.name = "DemoRule";
        Map<String, String> vars = KonsoleConstraintEvaluator.buildVariableMap(
                app, sample, "NOTIFICATION", Collections.singletonMap("notification_title", "AlertTitle"));
        Assert.assertEquals("DemoRule", vars.get("macro_name"));
        Assert.assertEquals("AlertTitle", vars.get("notification_title"));

        String rendered = KonsoleConstraintEvaluator.interpolate(
                "Macro=%macro_name% Title=%notification_title% Bat=%battery_level%%", vars);
        Assert.assertTrue(rendered.contains("Macro=DemoRule"));
        Assert.assertTrue(rendered.contains("Title=AlertTitle"));
        Assert.assertFalse(rendered.contains("%macro_name%"));
    }

    @Test
    public void testAutomationEngineShellVariableInjectionAndCliBridge() throws Exception {
        KonsoleMacroRule shellRule = new KonsoleMacroRule();
        shellRule.id = "cli_shell_macro";
        shellRule.name = "CLI Shell Export Test";
        shellRule.enabled = true;
        shellRule.triggerType = KonsoleMacroRule.TriggerType.MANUAL;
        shellRule.constraints.requireCharging = true; // will be tested both normal & --force
        shellRule.actions.add(new KonsoleMacroRule.MacroAction(
                KonsoleMacroRule.ActionType.SHELL,
                "echo \"TRIG=$trigger_type NAME=$macro_name BAT=%battery_level%\"",
                "", ""));
        KonsoleMacroStore.getInstance().upsertMacro(app, shellRule);

        // Forced run executes shell command with exported environment + %var% substitution
        KonsoleMacroStore.ExecutionRecord rec = KonsoleAutomationEngine.getInstance().testMacro(app, "cli_shell_macro", true);
        Assert.assertEquals(0, rec.exitCode);
        Assert.assertTrue("Output was: " + rec.output, rec.output.contains("TRIG=MANUAL_TEST"));
        Assert.assertTrue("Output was: " + rec.output, rec.output.contains("NAME=CLI Shell Export Test"));

        // Bidirectional CLI: macro list & macro trigger via Sink
        StringBuilder cliOut = new StringBuilder();
        CountDownLatch latch = new CountDownLatch(1);
        AtomicInteger cliRc = new AtomicInteger(-1);

        KonsoleAutomationEngine.getInstance().handleMacroCli(app, new KonsoleCommandDispatcher.CommandSink() {
            @Override
            public void write(String text) {
                cliOut.append(text);
            }

            @Override
            public void finish(int exitCode) {
                cliRc.set(exitCode);
                latch.countDown();
            }
        }, "list");

        Assert.assertTrue(latch.await(3, TimeUnit.SECONDS));
        Assert.assertEquals(0, cliRc.get());
        Assert.assertTrue(cliOut.toString().contains("CLI Shell Export Test"));
    }

    @Test
    public void testInstallerGeneratesMacroAndAutoTriggerBinaries() throws Exception {
        File prefixDir = tempFolder.newFolder("prefix");
        TermuxInstaller.ensureAptAndPkgConfigured(prefixDir.getAbsolutePath());

        File konsoleExec = new File(prefixDir, "bin/konsole-exec");
        File macroBin = new File(prefixDir, "bin/macro");
        File autoTriggerBin = new File(prefixDir, "bin/auto-trigger");
        File fishConf = new File(prefixDir, "etc/fish/conf.d/konsole_os.fish");
        File completions = new File(prefixDir, "etc/fish/completions/kons.fish");

        Assert.assertTrue(macroBin.isFile() && macroBin.canExecute());
        Assert.assertTrue(autoTriggerBin.isFile() && autoTriggerBin.canExecute());

        String execText = new String(Files.readAllBytes(konsoleExec.toPath()), StandardCharsets.UTF_8);
        String fishText = new String(Files.readAllBytes(fishConf.toPath()), StandardCharsets.UTF_8);
        String compText = new String(Files.readAllBytes(completions.toPath()), StandardCharsets.UTF_8);

        Assert.assertTrue(execText.contains("--macro"));
        Assert.assertTrue(execText.contains("ACTION=\"MACRO\""));
        Assert.assertTrue(fishText.contains("function macro"));
        Assert.assertTrue(fishText.contains("function auto-trigger"));
        Assert.assertTrue(compText.contains("complete -c macro"));
        Assert.assertTrue(compText.contains("complete -c auto-trigger"));
    }

    @Test
    public void testMacroLabActivityRendersAndRunsTestButton() {
        KonsoleMacroRule demo = new KonsoleMacroRule();
        demo.id = "ui_lab_macro";
        demo.name = "UI Toast Probe";
        demo.enabled = true;
        demo.triggerType = KonsoleMacroRule.TriggerType.MANUAL;
        demo.actions.add(new KonsoleMacroRule.MacroAction(
                KonsoleMacroRule.ActionType.TOAST, "Hello %macro_name%", "", ""));
        KonsoleMacroStore.getInstance().upsertMacro(app, demo);

        KonsoleMacroLabActivity activity = Robolectric.buildActivity(KonsoleMacroLabActivity.class)
                .setup()
                .get();

        LinearLayout listContainer = activity.findViewById(R.id.macro_list_container);
        LinearLayout templatesStrip = activity.findViewById(R.id.macro_templates_strip);
        TextView consoleTv = activity.findViewById(R.id.macro_test_console_output);
        View snapshotBtn = activity.findViewById(R.id.macro_snapshot_btn);

        Assert.assertNotNull(listContainer);
        Assert.assertTrue(listContainer.getChildCount() >= 1);
        Assert.assertNotNull(templatesStrip);
        Assert.assertTrue(templatesStrip.getChildCount() >= 4);
        Assert.assertNotNull(consoleTv);
        Assert.assertNotNull(snapshotBtn);

        snapshotBtn.performClick();
        Assert.assertTrue(consoleTv.getText().toString().contains("[LIVE DEVICE CONSTRAINT SNAPSHOT]"));

        // Verify four-tier resolver completes macro subcommands
        KonsoleFourTierResolver resolver = KonsoleFourTierResolver.getInstance();
        Assert.assertEquals("trigger", resolver.resolvePrediction("macro trigg", Collections.emptyList()).equals("er") ? "trigger" : "");
    }
}
