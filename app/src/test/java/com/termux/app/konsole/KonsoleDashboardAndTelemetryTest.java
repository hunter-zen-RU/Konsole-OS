package com.termux.app.konsole;

import android.app.Application;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.widget.SwitchCompat;

import com.termux.R;
import com.termux.shared.termux.settings.preferences.TermuxAppSharedPreferences;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.util.Collections;
import java.util.Map;

@RunWith(RobolectricTestRunner.class)
public class KonsoleDashboardAndTelemetryTest {

    @Test
    public void testFormatBytesPerSec() {
        Assert.assertEquals("0 B/s", KonsoleTelemetryManager.formatBytesPerSec(0.0));
        Assert.assertEquals("512 B/s", KonsoleTelemetryManager.formatBytesPerSec(512.0));
        Assert.assertEquals("1.5 KB/s", KonsoleTelemetryManager.formatBytesPerSec(1536.0));
        Assert.assertEquals("2.00 MB/s", KonsoleTelemetryManager.formatBytesPerSec(2.0 * 1024.0 * 1024.0));
    }

    @Test
    public void testTelemetryRefreshPopulatesSystemStats() {
        Application app = RuntimeEnvironment.getApplication();
        KonsoleTelemetryManager telemetry = KonsoleTelemetryManager.getInstance();
        telemetry.refreshNow(app);

        Map<String, String> vars = telemetry.getLiveVariables();
        Assert.assertNotNull(vars.get("cpu_cores"));
        Assert.assertNotNull(vars.get("cpu_usage"));
        Assert.assertNotNull(vars.get("ram_total"));
        Assert.assertNotNull(vars.get("ram_used"));
        Assert.assertNotNull(vars.get("ram_percent"));
        Assert.assertNotNull(vars.get("net_rx_speed"));
        Assert.assertNotNull(vars.get("net_tx_speed"));
        Assert.assertNotNull(vars.get("net_speed"));
        Assert.assertTrue(vars.get("net_speed").contains("↓"));
        Assert.assertTrue(vars.get("net_speed").contains("↑"));
    }

    @Test
    public void testDashboardActivityRendersPathsStatsAndPersistsToggles() {
        KonsoleConfigManager config = KonsoleConfigManager.getInstance();
        config.setBehaviorMode("konsole");
        config.setUiProperty("statusBarVisible", "true");

        KonsoleDashboardActivity activity = Robolectric.buildActivity(KonsoleDashboardActivity.class)
            .setup()
            .get();

        TextView cpuValue = activity.findViewById(R.id.dashboard_cpu_value);
        TextView ramValue = activity.findViewById(R.id.dashboard_ram_value);
        TextView netSpeed = activity.findViewById(R.id.dashboard_net_speed);
        LinearLayout pathsContainer = activity.findViewById(R.id.dashboard_paths_container);

        Assert.assertNotNull(cpuValue);
        Assert.assertFalse(cpuValue.getText().toString().isEmpty());
        Assert.assertNotNull(ramValue);
        Assert.assertFalse(ramValue.getText().toString().isEmpty());
        Assert.assertNotNull(netSpeed);
        Assert.assertTrue(netSpeed.getText().toString().contains("↓"));
        Assert.assertNotNull(pathsContainer);
        Assert.assertTrue(pathsContainer.getChildCount() >= 7);

        StringBuilder allPathText = new StringBuilder();
        collectTextViews(pathsContainer, allPathText);
        String renderedPaths = allPathText.toString();
        Assert.assertTrue(renderedPaths.contains("com.termux"));
        Assert.assertTrue(renderedPaths.contains("/data/data/com.termux/files/usr"));
        Assert.assertTrue(renderedPaths.contains("/data/data/com.termux/files/usr/bin/apt-key"));

        SwitchCompat modeSwitch = activity.findViewById(R.id.switch_konsole_mode);
        SwitchCompat hudSwitch = activity.findViewById(R.id.switch_hud_visible);
        SwitchCompat extraKeysSwitch = activity.findViewById(R.id.switch_extra_keys);
        SwitchCompat keepScreenSwitch = activity.findViewById(R.id.switch_keep_screen_on);

        Assert.assertTrue(modeSwitch.isChecked());
        modeSwitch.setChecked(false);
        Assert.assertEquals("termux", config.getMode());
        modeSwitch.setChecked(true);
        Assert.assertEquals("konsole", config.getMode());

        Assert.assertTrue(hudSwitch.isChecked());
        hudSwitch.setChecked(false);
        Assert.assertFalse(config.isStatusBarVisible());
        hudSwitch.setChecked(true);
        Assert.assertTrue(config.isStatusBarVisible());

        TermuxAppSharedPreferences prefs = TermuxAppSharedPreferences.build(activity, false);
        Assert.assertNotNull(prefs);
        boolean initialExtra = extraKeysSwitch.isChecked();
        extraKeysSwitch.setChecked(!initialExtra);
        Assert.assertEquals(!initialExtra, prefs.shouldShowTerminalToolbar());

        boolean initialKeep = keepScreenSwitch.isChecked();
        keepScreenSwitch.setChecked(!initialKeep);
        Assert.assertEquals(!initialKeep, prefs.shouldKeepScreenOn());
    }

    @Test
    public void testFourTierResolverIncludesDashboardCommands() {
        KonsoleFourTierResolver resolver = KonsoleFourTierResolver.getInstance();
        Assert.assertEquals("hboard", resolver.resolvePrediction("das", Collections.emptyList()));
        Assert.assertEquals("oard", resolver.resolvePrediction("kons dashb", Collections.emptyList()));
    }

    private void collectTextViews(LinearLayout root, StringBuilder out) {
        for (int i = 0; i < root.getChildCount(); i++) {
            android.view.View child = root.getChildAt(i);
            if (child instanceof TextView) {
                out.append(((TextView) child).getText()).append('\n');
            } else if (child instanceof LinearLayout) {
                collectTextViews((LinearLayout) child, out);
            }
        }
    }
}
