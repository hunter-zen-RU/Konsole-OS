package com.termux.app.konsole;

import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.termux.R;
import com.termux.app.TermuxActivity;
import com.termux.app.TermuxInstaller;
import com.termux.app.activities.SettingsActivity;
import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.termux.settings.preferences.TermuxAppSharedPreferences;
import com.termux.shared.termux.shell.command.environment.TermuxShellEnvironment;

import java.io.File;
import java.util.Map;

/**
 * Konsole OS Dashboard Activity:
 * Displays live hardware stats (CPU usage, RAM, network speed, battery, storage),
 * quick-toggle terminal configurations, and verified system paths ($PREFIX, $HOME, apt-key, etc.).
 */
public class KonsoleDashboardActivity extends AppCompatActivity {

    private final Handler mRefreshHandler = new Handler(Looper.getMainLooper());
    private boolean mUpdatingToggles = false;
    private boolean mActivityVisible = false;

    private TermuxAppSharedPreferences mPreferences;

    private TextView mHeaderSubtitle;
    private TextView mCpuValue;
    private TextView mCpuSub;
    private ProgressBar mCpuProgress;
    private TextView mRamValue;
    private TextView mRamSub;
    private ProgressBar mRamProgress;
    private TextView mNetSpeed;
    private TextView mNetSub;
    private TextView mBatteryValue;
    private TextView mStorageSub;

    private SwitchCompat mSwitchKonsoleMode;
    private TextView mModeDesc;
    private SwitchCompat mSwitchHudVisible;
    private SwitchCompat mSwitchExtraKeys;
    private SwitchCompat mSwitchKeepScreenOn;
    private SwitchCompat mSwitchSoftKeyboard;
    private SwitchCompat mSwitchMarginAdjust;
    private TextView mFontValue;

    private LinearLayout mPathsContainer;

    private final Runnable mTicker = new Runnable() {
        @Override
        public void run() {
            if (!mActivityVisible) return;
            KonsoleTelemetryManager.getInstance().refreshNow(KonsoleDashboardActivity.this);
            bindTelemetry();
            mRefreshHandler.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_konsole_dashboard);

        View rootView = findViewById(R.id.dashboard_root);
        ViewCompat.setOnApplyWindowInsetsListener(rootView, (v, insets) -> {
            androidx.core.graphics.Insets sys = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(sys.left, sys.top, sys.right, sys.bottom);
            return insets;
        });

        View contentColumn = findViewById(R.id.dashboard_content_column);
        if (contentColumn != null) {
            int maxPx = Math.round(640 * getResources().getDisplayMetrics().density);
            int availPx = getResources().getDisplayMetrics().widthPixels - Math.round(32 * getResources().getDisplayMetrics().density);
            if (availPx > maxPx && contentColumn.getLayoutParams() != null) {
                contentColumn.getLayoutParams().width = maxPx;
            }
        }

        mPreferences = TermuxAppSharedPreferences.build(this, false);
        KonsoleConfigManager.getInstance().init(this);
        KonsoleTelemetryManager.getInstance().start(this);
        KonsolePathIndexer.getInstance().init(this);

        bindViews();
        setupListeners();
        loadToggleStates();
        bindPaths();
        KonsoleTelemetryManager.getInstance().refreshNow(this);
        bindTelemetry();
    }

    private void bindViews() {
        mHeaderSubtitle = findViewById(R.id.dashboard_header_subtitle);
        mCpuValue = findViewById(R.id.dashboard_cpu_value);
        mCpuSub = findViewById(R.id.dashboard_cpu_sub);
        mCpuProgress = findViewById(R.id.dashboard_cpu_progress);
        mRamValue = findViewById(R.id.dashboard_ram_value);
        mRamSub = findViewById(R.id.dashboard_ram_sub);
        mRamProgress = findViewById(R.id.dashboard_ram_progress);
        mNetSpeed = findViewById(R.id.dashboard_net_speed);
        mNetSub = findViewById(R.id.dashboard_net_sub);
        mBatteryValue = findViewById(R.id.dashboard_battery_value);
        mStorageSub = findViewById(R.id.dashboard_storage_sub);

        mSwitchKonsoleMode = findViewById(R.id.switch_konsole_mode);
        mModeDesc = findViewById(R.id.dashboard_mode_desc);
        mSwitchHudVisible = findViewById(R.id.switch_hud_visible);
        mSwitchExtraKeys = findViewById(R.id.switch_extra_keys);
        mSwitchKeepScreenOn = findViewById(R.id.switch_keep_screen_on);
        mSwitchSoftKeyboard = findViewById(R.id.switch_soft_keyboard);
        mSwitchMarginAdjust = findViewById(R.id.switch_margin_adjust);
        mFontValue = findViewById(R.id.dashboard_font_value);

        mPathsContainer = findViewById(R.id.dashboard_paths_container);
    }

    private void setupListeners() {
        ImageButton backBtn = findViewById(R.id.dashboard_back_button);
        if (backBtn != null) backBtn.setOnClickListener(v -> finish());

        View terminalBtn = findViewById(R.id.dashboard_return_terminal);
        if (terminalBtn != null) terminalBtn.setOnClickListener(v -> finish());

        View openSettingsBtn = findViewById(R.id.dashboard_open_settings);
        if (openSettingsBtn != null) {
            openSettingsBtn.setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));
        }

        View openMacroLabBtn = findViewById(R.id.dashboard_open_macro_lab);
        if (openMacroLabBtn != null) {
            openMacroLabBtn.setOnClickListener(v -> startActivity(new Intent(this, KonsoleMacroLabActivity.class)));
        }

        View openAppsBtn = findViewById(R.id.dashboard_open_apps);
        if (openAppsBtn != null) {
            openAppsBtn.setOnClickListener(v -> new KonsoleAppsDialog(this, null).show());
        }

        View repairAptBtn = findViewById(R.id.dashboard_repair_apt_btn);
        if (repairAptBtn != null) {
            repairAptBtn.setOnClickListener(v -> {
                repairAptBtn.setEnabled(false);
                new Thread(() -> {
                    TermuxInstaller.ensureAptAndPkgConfigured(TermuxConstants.TERMUX_PREFIX_DIR_PATH);
                    TermuxShellEnvironment.writeEnvironmentToFile(getApplicationContext());
                    KonsolePathIndexer.getInstance().refreshAsync();
                    runOnUiThread(() -> {
                        repairAptBtn.setEnabled(true);
                        bindPaths();
                        Toast.makeText(this, "Verified & repaired apt/pkg under " + TermuxConstants.TERMUX_PREFIX_DIR_PATH, Toast.LENGTH_SHORT).show();
                    });
                }).start();
            });
        }

        mSwitchKonsoleMode.setOnCheckedChangeListener((btn, isChecked) -> {
            if (mUpdatingToggles) return;
            String nextMode = isChecked ? "konsole" : "termux";
            KonsoleConfigManager.getInstance().setBehaviorMode(nextMode);
            KonsoleFishBridge.syncStateFiles(this);
            updateModeDescription(isChecked);
            Toast.makeText(this, "Shell mode: " + nextMode.toUpperCase(), Toast.LENGTH_SHORT).show();
        });

        mSwitchHudVisible.setOnCheckedChangeListener((btn, isChecked) -> {
            if (mUpdatingToggles) return;
            KonsoleConfigManager.getInstance().setUiProperty("statusBarVisible", String.valueOf(isChecked));
        });

        mSwitchExtraKeys.setOnCheckedChangeListener((btn, isChecked) -> {
            if (mUpdatingToggles || mPreferences == null) return;
            mPreferences.setShowTerminalToolbar(isChecked);
            TermuxActivity.updateTermuxActivityStyling(this, false);
        });

        mSwitchKeepScreenOn.setOnCheckedChangeListener((btn, isChecked) -> {
            if (mUpdatingToggles || mPreferences == null) return;
            mPreferences.setKeepScreenOn(isChecked);
            TermuxActivity.updateTermuxActivityStyling(this, false);
        });

        mSwitchSoftKeyboard.setOnCheckedChangeListener((btn, isChecked) -> {
            if (mUpdatingToggles || mPreferences == null) return;
            mPreferences.setSoftKeyboardEnabled(isChecked);
            TermuxActivity.updateTermuxActivityStyling(this, false);
        });

        mSwitchMarginAdjust.setOnCheckedChangeListener((btn, isChecked) -> {
            if (mUpdatingToggles || mPreferences == null) return;
            mPreferences.setTerminalMarginAdjustment(isChecked);
            TermuxActivity.updateTermuxActivityStyling(this, false);
        });

        View fontDec = findViewById(R.id.dashboard_font_dec);
        if (fontDec != null) {
            fontDec.setOnClickListener(v -> adjustFontSize(false));
        }

        View fontInc = findViewById(R.id.dashboard_font_inc);
        if (fontInc != null) {
            fontInc.setOnClickListener(v -> adjustFontSize(true));
        }
    }

    private void adjustFontSize(boolean increase) {
        if (mPreferences == null) return;
        mPreferences.changeFontSize(increase);
        updateFontReadout();
        TermuxActivity.updateTermuxActivityStyling(this, false);
    }

    private void loadToggleStates() {
        mUpdatingToggles = true;
        try {
            KonsoleConfigManager cfg = KonsoleConfigManager.getInstance();
            boolean konsoleMode = "konsole".equalsIgnoreCase(cfg.getMode());
            mSwitchKonsoleMode.setChecked(konsoleMode);
            updateModeDescription(konsoleMode);

            mSwitchHudVisible.setChecked(cfg.isStatusBarVisible());

            boolean hasPrefs = mPreferences != null;
            mSwitchExtraKeys.setEnabled(hasPrefs);
            mSwitchKeepScreenOn.setEnabled(hasPrefs);
            mSwitchSoftKeyboard.setEnabled(hasPrefs);
            mSwitchMarginAdjust.setEnabled(hasPrefs);

            if (hasPrefs) {
                mSwitchExtraKeys.setChecked(mPreferences.shouldShowTerminalToolbar());
                mSwitchKeepScreenOn.setChecked(mPreferences.shouldKeepScreenOn());
                mSwitchSoftKeyboard.setChecked(mPreferences.isSoftKeyboardEnabled());
                mSwitchMarginAdjust.setChecked(mPreferences.isTerminalMarginAdjustmentEnabled());
            }
            updateFontReadout();
        } finally {
            mUpdatingToggles = false;
        }
    }

    private void updateModeDescription(boolean konsoleMode) {
        if (mModeDesc == null) return;
        mModeDesc.setText(konsoleMode
            ? "Konsole OS Fish mode: hardware commands, app launch & aliases via Fish"
            : "Termux Fish mode: pure Fish shell pass-through ('kons' remains active)");
    }

    private void updateFontReadout() {
        if (mFontValue == null) return;
        if (mPreferences != null) {
            mFontValue.setText(mPreferences.getFontSize() + " px");
        } else {
            mFontValue.setText("-- px");
        }
    }

    private void bindTelemetry() {
        Map<String, String> vars = KonsoleTelemetryManager.getInstance().getLiveVariables();

        String time = vars.getOrDefault("time", "--:--:--");
        String device = vars.getOrDefault("device_name", android.os.Build.MODEL);
        if (mHeaderSubtitle != null) {
            mHeaderSubtitle.setText(device + " · " + time);
        }

        String cpuStr = vars.getOrDefault("cpu_usage", "0.0%");
        String cpuCores = vars.getOrDefault("cpu_cores", "--");
        mCpuValue.setText(cpuStr);
        mCpuSub.setText(cpuCores + " CPU Cores");
        mCpuProgress.setProgress(parsePercentInt(cpuStr));

        String ramPctStr = vars.getOrDefault("ram_percent", "0%");
        String ramUsed = vars.getOrDefault("ram_used", "--");
        String ramTotal = vars.getOrDefault("ram_total", "--");
        String ramFree = vars.getOrDefault("ram_free", "--");
        mRamValue.setText(ramPctStr);
        mRamSub.setText(ramUsed + " / " + ramTotal + " (" + ramFree + " free)");
        mRamProgress.setProgress(parsePercentInt(ramPctStr));

        String rxSpeed = vars.getOrDefault("net_rx_speed", "0 B/s");
        String txSpeed = vars.getOrDefault("net_tx_speed", "0 B/s");
        String netType = vars.getOrDefault("network_type", "--");
        String ssid = vars.getOrDefault("wifi_ssid", "--");
        String ip = vars.getOrDefault("ip_address", "127.0.0.1");
        mNetSpeed.setText("↓ " + rxSpeed + "\n↑ " + txSpeed);
        mNetSub.setText(netType + ("None".equals(ssid) ? "" : " (" + ssid + ")") + " · " + ip);

        mBatteryValue.setText(vars.getOrDefault("battery", "N/A"));
        mStorageSub.setText("Disk Free: " + vars.getOrDefault("storage_free", "N/A"));
    }

    private int parsePercentInt(String raw) {
        if (raw == null) return 0;
        try {
            String clean = raw.replace("%", "").trim();
            return Math.min(100, Math.max(0, (int) Math.round(Double.parseDouble(clean))));
        } catch (Exception ignored) {
            return 0;
        }
    }

    private void bindPaths() {
        if (mPathsContainer == null) return;
        mPathsContainer.removeAllViews();

        File prefixDir = new File(TermuxConstants.TERMUX_PREFIX_DIR_PATH);
        File homeDir = new File(TermuxConstants.TERMUX_HOME_DIR_PATH);
        File binDir = new File(TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH);
        File aptKeyFile = new File(TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH, "apt-key");
        File aptConfFile = new File(TermuxConstants.TERMUX_ETC_PREFIX_DIR_PATH, "apt/apt.conf");
        File trustedGpgDir = new File(TermuxConstants.TERMUX_ETC_PREFIX_DIR_PATH, "apt/trusted.gpg.d");
        File fishBin = new File(TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH, "fish");
        File konsoleFishConf = new File(TermuxConstants.TERMUX_ETC_PREFIX_DIR_PATH, "fish/conf.d/konsole_os.fish");
        File konsoleDir = new File(KonsoleConfigManager.KONSOLE_ROOT_PATH);

        int indexedCount = KonsolePathIndexer.getInstance().getPathExecutables().size();
        String activePath = TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + ":/system/bin:/system/xbin (" + indexedCount + " cmds)";

        addPathRow("PACKAGE ID", TermuxConstants.TERMUX_PACKAGE_NAME,
            "com.termux".equals(TermuxConstants.TERMUX_PACKAGE_NAME), "ALIGNED", "CUSTOM", true);
        addPathRow("FISH SHELL", fishBin.getAbsolutePath() + "\n" + konsoleFishConf.getAbsolutePath(),
            fishBin.isFile() && fishBin.canExecute() && konsoleFishConf.isFile(),
            "FISH READY", fishBin.isFile() ? "CONF MISSING" : "RUN kons setup-fish", true);
        addPathRow("$PREFIX", TermuxConstants.TERMUX_PREFIX_DIR_PATH,
            prefixDir.isDirectory(), "READY", "PENDING", true);
        addPathRow("$HOME", TermuxConstants.TERMUX_HOME_DIR_PATH,
            homeDir.isDirectory(), "READY", "PENDING", true);
        addPathRow("$PATH", activePath,
            binDir.isDirectory(), "READY", "NO BIN", true);
        addPathRow("APT-KEY", aptKeyFile.getAbsolutePath(),
            aptKeyFile.isFile() && aptKeyFile.canExecute(), "EXEC", aptKeyFile.isFile() ? "NO +X" : "MISSING", true);
        addPathRow("APT CONFIG & KEYS", aptConfFile.getAbsolutePath() + "\n" + trustedGpgDir.getAbsolutePath(),
            aptConfFile.isFile() && trustedGpgDir.isDirectory(), "READY", "MISSING", true);
        addPathRow("$KONSOLE ROOT", KonsoleConfigManager.KONSOLE_ROOT_PATH,
            konsoleDir.isDirectory(), "READY", "PENDING", false);
    }

    private void addPathRow(String label, String pathValue, boolean ok, String okTag, String warnTag, boolean addDivider) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, dp(6), 0, dp(6));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        TextView labelTv = new TextView(this);
        labelTv.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        labelTv.setText(label);
        labelTv.setTextColor(0xFF8B949E);
        labelTv.setTextSize(11f);
        labelTv.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);

        TextView badgeTv = new TextView(this);
        badgeTv.setText(ok ? okTag : warnTag);
        badgeTv.setTextColor(ok ? 0xFF00FF88 : 0xFFFFD166);
        badgeTv.setBackgroundColor(ok ? 0xFF0F291E : 0xFF2D220D);
        badgeTv.setPadding(dp(8), dp(2), dp(8), dp(2));
        badgeTv.setTextSize(10f);
        badgeTv.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);

        header.addView(labelTv);
        header.addView(badgeTv);

        TextView valTv = new TextView(this);
        valTv.setText(pathValue);
        valTv.setTextColor(0xFFE6EDF3);
        valTv.setTextSize(12f);
        valTv.setTypeface(Typeface.MONOSPACE);
        valTv.setPadding(0, dp(4), 0, 0);

        row.addView(header);
        row.addView(valTv);
        mPathsContainer.addView(row);

        if (addDivider) {
            View divider = new View(this);
            LinearLayout.LayoutParams divParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(1));
            divParams.topMargin = dp(4);
            divParams.bottomMargin = dp(4);
            divider.setLayoutParams(divParams);
            divider.setBackgroundColor(0xFF1A2431);
            mPathsContainer.addView(divider);
        }
    }

    private int dp(int val) {
        return Math.round(val * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onStart() {
        super.onStart();
        mActivityVisible = true;
        loadToggleStates();
        bindPaths();
        mRefreshHandler.removeCallbacks(mTicker);
        mRefreshHandler.post(mTicker);
    }

    @Override
    protected void onStop() {
        super.onStop();
        mActivityVisible = false;
        mRefreshHandler.removeCallbacks(mTicker);
    }
}
