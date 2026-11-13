package com.termux.app.konsole;

import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.termux.R;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Visual UI Macro Lab Activity for creating, editing, inspecting, and testing
 * Konsole OS Automation Macros, Schedules, and Cron rules.
 */
public class KonsoleMacroLabActivity extends AppCompatActivity {

    private TextView mSubtitleView;
    private TextView mEngineStatusChip;
    private TextView mStorePathLabel;
    private TextView mNotifAccessBtn;
    private TextView mA11yAccessBtn;
    private LinearLayout mTemplatesStrip;
    private LinearLayout mMacroListContainer;
    private TextView mListHeader;
    private TextView mTestConsoleOutput;

    private final SimpleDateFormat mTimeFmt = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_konsole_macro_lab);

        View root = findViewById(R.id.macro_lab_root);
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            androidx.core.graphics.Insets sys = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(sys.left, sys.top, sys.right, sys.bottom);
            return insets;
        });

        View contentCol = findViewById(R.id.macro_lab_content_column);
        if (contentCol != null) {
            int maxPx = Math.round(680 * getResources().getDisplayMetrics().density);
            int availPx = getResources().getDisplayMetrics().widthPixels - Math.round(32 * getResources().getDisplayMetrics().density);
            if (availPx > maxPx && contentCol.getLayoutParams() != null) {
                contentCol.getLayoutParams().width = maxPx;
            }
        }

        KonsoleAutomationEngine.getInstance().start(this);

        bindViews();
        setupListeners();
        populateTemplatesStrip();
        refreshAll();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshSensorBadges();
    }

    private void bindViews() {
        mSubtitleView = findViewById(R.id.macro_lab_subtitle);
        mEngineStatusChip = findViewById(R.id.macro_engine_status_chip);
        mStorePathLabel = findViewById(R.id.macro_store_path_label);
        mNotifAccessBtn = findViewById(R.id.macro_notif_access_btn);
        mA11yAccessBtn = findViewById(R.id.macro_a11y_access_btn);
        mTemplatesStrip = findViewById(R.id.macro_templates_strip);
        mMacroListContainer = findViewById(R.id.macro_list_container);
        mListHeader = findViewById(R.id.macro_list_header);
        mTestConsoleOutput = findViewById(R.id.macro_test_console_output);
    }

    private void setupListeners() {
        ImageButton backBtn = findViewById(R.id.macro_lab_back);
        if (backBtn != null) backBtn.setOnClickListener(v -> finish());

        View newBtn = findViewById(R.id.macro_lab_new_btn);
        if (newBtn != null) {
            newBtn.setOnClickListener(v -> showMacroEditorDialog(null, true));
        }

        if (mNotifAccessBtn != null) {
            mNotifAccessBtn.setOnClickListener(v -> {
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                        startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
                    } else {
                        startActivity(new Intent(Settings.ACTION_SETTINGS));
                    }
                } catch (Exception e) {
                    Toast.makeText(this, "Open Android Settings -> Notification Access", Toast.LENGTH_SHORT).show();
                }
            });
        }

        if (mA11yAccessBtn != null) {
            mA11yAccessBtn.setOnClickListener(v -> new AlertDialog.Builder(this)
                    .setTitle("Optional Accessibility Sensor")
                    .setMessage("Enabling 'Konsole OS Automation' in Android Accessibility Settings lets macros detect opened apps and perform automated taps/swipes. Leave disabled if you only need Cron, System Broadcasts, and Shell macros.")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Open Accessibility Settings", (d, w) -> {
                        try {
                            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                        } catch (Exception ignored) {
                        }
                    })
                    .show());
        }

        View snapshotBtn = findViewById(R.id.macro_snapshot_btn);
        if (snapshotBtn != null) {
            snapshotBtn.setOnClickListener(v -> showDeviceSnapshotInConsole());
        }

        View clearLogBtn = findViewById(R.id.macro_clear_log_btn);
        if (clearLogBtn != null) {
            clearLogBtn.setOnClickListener(v -> {
                KonsoleMacroStore.getInstance().clearHistory(this);
                renderConsoleHistory(null);
                Toast.makeText(this, "Execution history cleared", Toast.LENGTH_SHORT).show();
            });
        }
    }

    private void populateTemplatesStrip() {
        if (mTemplatesStrip == null) return;
        mTemplatesStrip.removeAllViews();
        List<KonsoleMacroRule> templates = KonsoleMacroRule.createStarterTemplates();
        for (KonsoleMacroRule tpl : templates) {
            TextView chip = new TextView(this);
            chip.setBackgroundResource(R.drawable.bg_dashboard_chip);
            chip.setText("+ " + tpl.name);
            chip.setTextColor(0xFF00E5FF);
            chip.setTextSize(11.5f);
            chip.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
            chip.setGravity(Gravity.CENTER);
            chip.setMinHeight(dp(48));
            chip.setPadding(dp(12), dp(8), dp(12), dp(8));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMarginEnd(dp(8));
            chip.setLayoutParams(lp);
            chip.setOnClickListener(v -> {
                KonsoleMacroRule copy = cloneRuleWithNewId(tpl);
                copy.enabled = true;
                showMacroEditorDialog(copy, true);
            });
            mTemplatesStrip.addView(chip);
        }
    }

    private void refreshAll() {
        refreshSensorBadges();
        renderMacrosList();
        renderConsoleHistory(null);
    }

    private void refreshSensorBadges() {
        boolean running = KonsoleAutomationEngine.getInstance().isRunning();
        if (mEngineStatusChip != null) {
            mEngineStatusChip.setText(running ? "ENGINE: ACTIVE" : "ENGINE: IDLE");
            mEngineStatusChip.setTextColor(running ? 0xFF00FF88 : 0xFFFFD166);
        }
        if (mStorePathLabel != null) {
            mStorePathLabel.setText(KonsoleMacroStore.getInstance().getActiveStoragePath(this));
        }
        if (mNotifAccessBtn != null) {
            boolean ok = KonsoleNotificationListenerService.isConnected();
            mNotifAccessBtn.setText("NOTIF SENSOR: " + (ok ? "ON" : "OFF"));
            mNotifAccessBtn.setTextColor(ok ? 0xFF00FF88 : 0xFFFFD166);
        }
        if (mA11yAccessBtn != null) {
            boolean ok = KonsoleAutomationAccessibilityService.isConnected();
            mA11yAccessBtn.setText("A11Y GESTURES: " + (ok ? "ON" : "OFF"));
            mA11yAccessBtn.setTextColor(ok ? 0xFF00FF88 : 0xFFFFD166);
        }
    }

    private void renderMacrosList() {
        if (mMacroListContainer == null) return;
        mMacroListContainer.removeAllViews();

        List<KonsoleMacroRule> rules = KonsoleMacroStore.getInstance().loadMacros(this);
        if (mListHeader != null) {
            mListHeader.setText("SAVED MACROS & CRON JOBS (" + rules.size() + ")");
        }
        if (mSubtitleView != null) {
            int enabled = 0;
            for (KonsoleMacroRule r : rules) if (r.enabled) enabled++;
            mSubtitleView.setText(rules.size() + " rule(s) · " + enabled + " armed · JSON Sync");
        }

        if (rules.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("No macros configured yet. Tap '+ NEW MACRO' or select a starter template above.");
            empty.setTextColor(0xFF8B949E);
            empty.setTypeface(Typeface.MONOSPACE);
            empty.setTextSize(12f);
            empty.setPadding(dp(14), dp(14), dp(14), dp(14));
            empty.setBackgroundResource(R.drawable.bg_dashboard_card);
            mMacroListContainer.addView(empty);
            return;
        }

        LayoutInflater inflater = LayoutInflater.from(this);
        for (KonsoleMacroRule rule : rules) {
            View card = inflater.inflate(R.layout.item_konsole_macro, mMacroListContainer, false);
            TextView nameTv = card.findViewById(R.id.macro_item_name);
            TextView trigTv = card.findViewById(R.id.macro_item_trigger);
            TextView constrTv = card.findViewById(R.id.macro_item_constraints);
            TextView actionsTv = card.findViewById(R.id.macro_item_actions);
            TextView statsTv = card.findViewById(R.id.macro_item_stats);
            SwitchCompat enableSwitch = card.findViewById(R.id.macro_item_switch);
            View testBtn = card.findViewById(R.id.macro_item_test_btn);
            View forceBtn = card.findViewById(R.id.macro_item_force_btn);
            View editBtn = card.findViewById(R.id.macro_item_edit_btn);
            View deleteBtn = card.findViewById(R.id.macro_item_delete_btn);

            nameTv.setText(rule.name);
            String trigDesc = rule.triggerType.label;
            if (rule.triggerValue != null && !rule.triggerValue.trim().isEmpty()) {
                trigDesc += " [" + rule.triggerValue.trim() + "]";
                if (rule.triggerType == KonsoleMacroRule.TriggerType.CRON) {
                    trigDesc += " · " + KonsoleCronParser.describe(rule.triggerValue);
                }
            }
            trigTv.setText(trigDesc);
            constrTv.setText("Constraints: " + (rule.constraints != null ? rule.constraints.summary() : "None"));

            StringBuilder actSb = new StringBuilder("Actions: ");
            if (rule.actions == null || rule.actions.isEmpty()) {
                actSb.append("(none)");
            } else {
                for (int i = 0; i < rule.actions.size(); i++) {
                    if (i > 0) actSb.append(" -> ");
                    actSb.append(rule.actions.get(i).describe());
                }
            }
            actionsTv.setText(actSb.toString());

            String lastTime = rule.lastRunTimeMs > 0 ? mTimeFmt.format(new Date(rule.lastRunTimeMs)) : "never";
            statsTv.setText("Runs: " + rule.runCount + " · Last (" + lastTime + "): " + rule.lastRunSummary);

            enableSwitch.setOnCheckedChangeListener(null);
            enableSwitch.setChecked(rule.enabled);
            enableSwitch.setOnCheckedChangeListener((b, isChecked) -> {
                rule.enabled = isChecked;
                KonsoleMacroStore.getInstance().upsertMacro(this, rule);
                KonsoleAutomationEngine.getInstance().rescheduleAllCrons(this);
                refreshSensorBadges();
            });

            testBtn.setOnClickListener(v -> runMacroTestAsync(rule, false));
            forceBtn.setOnClickListener(v -> runMacroTestAsync(rule, true));
            editBtn.setOnClickListener(v -> showMacroEditorDialog(rule, false));
            deleteBtn.setOnClickListener(v -> {
                KonsoleMacroStore.getInstance().deleteMacro(this, rule.id);
                KonsoleAutomationEngine.getInstance().rescheduleAllCrons(this);
                renderMacrosList();
                Toast.makeText(this, "Deleted '" + rule.name + "'", Toast.LENGTH_SHORT).show();
            });

            mMacroListContainer.addView(card);
        }
    }

    private void runMacroTestAsync(KonsoleMacroRule rule, boolean force) {
        if (mTestConsoleOutput != null) {
            mTestConsoleOutput.setText("Running " + (force ? "FORCED " : "") + "test for '" + rule.name + "'...");
        }
        new Thread(() -> {
            KonsoleMacroStore.ExecutionRecord rec =
                    KonsoleAutomationEngine.getInstance().testMacro(this, rule.id, force);
            runOnUiThread(() -> {
                renderMacrosList();
                renderConsoleHistory(rec);
            });
        }).start();
    }

    private void showDeviceSnapshotInConsole() {
        KonsoleConstraintEvaluator.DeviceSnapshot snap = KonsoleConstraintEvaluator.captureSnapshot(this);
        java.util.Map<String, String> vars = KonsoleConstraintEvaluator.buildVariableMap(this, null, "SNAPSHOT", null);
        StringBuilder sb = new StringBuilder();
        sb.append("[LIVE DEVICE CONSTRAINT SNAPSHOT]\n");
        sb.append("  %battery_level%    = ").append(snap.batteryLevel).append("% (charging=").append(snap.isCharging).append(")\n");
        sb.append("  %wifi_ssid%        = ").append(snap.wifiSsid).append(" (wifi=").append(snap.wifiConnected).append(")\n");
        sb.append("  %network_type%     = ").append(snap.networkType).append(" | IP=").append(vars.getOrDefault("ip_address", "--")).append("\n");
        sb.append("  %screen_on%        = ").append(snap.screenOn).append("\n");
        sb.append("  %cpu_usage%        = ").append(vars.getOrDefault("cpu_usage", "--")).append("\n");
        sb.append("  %ram_used%         = ").append(vars.getOrDefault("ram_used", "--")).append("\n");
        sb.append("  %konsole%          = ").append(KonsoleConfigManager.KONSOLE_ROOT_PATH).append("\n");
        if (mTestConsoleOutput != null) {
            mTestConsoleOutput.setText(sb.toString());
        }
    }

    private void renderConsoleHistory(@Nullable KonsoleMacroStore.ExecutionRecord latest) {
        if (mTestConsoleOutput == null) return;
        StringBuilder sb = new StringBuilder();
        if (latest != null) {
            sb.append("[LATEST TEST RESULT]\n");
            sb.append("  Macro      : ").append(latest.macroName).append(" (").append(latest.triggerType).append(")\n");
            sb.append("  Constraints: ").append(latest.constraintsPassed ? "PASSED" : (latest.forced ? "BYPASSED (FORCED)" : "FAILED")).append("\n");
            sb.append("  Verdict    : ").append(latest.summary).append(" [rc=").append(latest.exitCode).append("]\n");
            if (latest.output != null && !latest.output.isEmpty()) {
                sb.append("  Output     :\n").append(latest.output).append("\n");
            }
            sb.append("\n");
        }

        List<KonsoleMacroStore.ExecutionRecord> history = KonsoleMacroStore.getInstance().loadHistory(this);
        sb.append("[EXECUTION HISTORY (").append(history.size()).append(")]");
        if (history.isEmpty()) {
            sb.append("\n  (No runs recorded yet)");
        } else {
            int limit = Math.min(8, history.size());
            for (int i = 0; i < limit; i++) {
                KonsoleMacroStore.ExecutionRecord r = history.get(i);
                String t = r.timestampMs > 0 ? mTimeFmt.format(new Date(r.timestampMs)) : "--:--:--";
                sb.append("\n  [").append(t).append("] ").append(r.macroName)
                  .append(" (").append(r.triggerType).append(") -> ").append(r.summary);
            }
        }
        mTestConsoleOutput.setText(sb.toString());
    }

    private void showMacroEditorDialog(@Nullable KonsoleMacroRule existing, boolean isNew) {
        final KonsoleMacroRule working = existing != null
                ? cloneRuleKeepingId(existing)
                : new KonsoleMacroRule();
        if (existing == null) {
            working.name = "My Automation Macro";
            working.triggerType = KonsoleMacroRule.TriggerType.MANUAL;
        }

        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_konsole_macro_editor, null);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setView(dialogView)
                .create();

        EditText nameEt = dialogView.findViewById(R.id.editor_macro_name);
        Spinner trigSpinner = dialogView.findViewById(R.id.editor_trigger_spinner);
        EditText trigValEt = dialogView.findViewById(R.id.editor_trigger_value);
        TextView cronPreview = dialogView.findViewById(R.id.editor_cron_preview);
        View cronPresetsScroll = dialogView.findViewById(R.id.editor_cron_presets_scroll);
        LinearLayout cronPresetsRow = dialogView.findViewById(R.id.editor_cron_presets_row);

        CheckBox chkCharging = dialogView.findViewById(R.id.editor_chk_charging);
        CheckBox chkDischarging = dialogView.findViewById(R.id.editor_chk_discharging);
        CheckBox chkWifi = dialogView.findViewById(R.id.editor_chk_wifi);
        CheckBox chkScreen = dialogView.findViewById(R.id.editor_chk_screen);
        EditText minBatEt = dialogView.findViewById(R.id.editor_min_battery);
        EditText maxBatEt = dialogView.findViewById(R.id.editor_max_battery);
        EditText ssidEt = dialogView.findViewById(R.id.editor_ssid_filter);

        LinearLayout actionsContainer = dialogView.findViewById(R.id.editor_actions_container);
        Spinner actionTypeSpinner = dialogView.findViewById(R.id.editor_action_type_spinner);
        EditText param1Et = dialogView.findViewById(R.id.editor_action_param1);
        EditText param2Et = dialogView.findViewById(R.id.editor_action_param2);
        LinearLayout varChipsRow = dialogView.findViewById(R.id.editor_variable_chips_row);
        View addActionBtn = dialogView.findViewById(R.id.editor_add_action_btn);
        View cancelBtn = dialogView.findViewById(R.id.editor_cancel_btn);
        View saveBtn = dialogView.findViewById(R.id.editor_save_btn);

        nameEt.setText(working.name);
        trigValEt.setText(working.triggerValue);
        chkCharging.setChecked(working.constraints.requireCharging);
        chkDischarging.setChecked(working.constraints.requireDischarging);
        chkWifi.setChecked(working.constraints.requireWifi);
        chkScreen.setChecked(working.constraints.requireScreenOn);
        minBatEt.setText(String.valueOf(working.constraints.minBattery));
        maxBatEt.setText(String.valueOf(working.constraints.maxBattery));
        ssidEt.setText(working.constraints.ssidFilter);

        // Trigger spinner setup
        KonsoleMacroRule.TriggerType[] triggerTypes = KonsoleMacroRule.TriggerType.values();
        List<String> triggerLabels = new ArrayList<>();
        for (KonsoleMacroRule.TriggerType t : triggerTypes) triggerLabels.add(t.label);
        ArrayAdapter<String> trigAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, triggerLabels);
        trigAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        trigSpinner.setAdapter(trigAdapter);
        trigSpinner.setSelection(working.triggerType.ordinal());

        Runnable refreshCronUi = () -> {
            KonsoleMacroRule.TriggerType selected = triggerTypes[trigSpinner.getSelectedItemPosition()];
            boolean isCron = selected == KonsoleMacroRule.TriggerType.CRON;
            boolean isInterval = selected == KonsoleMacroRule.TriggerType.INTERVAL;
            boolean isNotifOrApp = selected == KonsoleMacroRule.TriggerType.NOTIFICATION
                    || selected == KonsoleMacroRule.TriggerType.APP_OPENED;

            trigValEt.setVisibility((isCron || isInterval || isNotifOrApp) ? View.VISIBLE : View.GONE);
            cronPreview.setVisibility(isCron ? View.VISIBLE : View.GONE);
            cronPresetsScroll.setVisibility(isCron ? View.VISIBLE : View.GONE);

            if (isCron) {
                trigValEt.setHint("5-field cron (e.g. */15 * * * * or @hourly)");
                String expr = trigValEt.getText().toString();
                boolean valid = KonsoleCronParser.isValid(expr);
                cronPreview.setText(KonsoleCronParser.describe(expr));
                cronPreview.setTextColor(valid ? 0xFF00FF88 : 0xFFFF7B72);
            } else if (isInterval) {
                trigValEt.setHint("Interval in minutes (e.g. 10)");
            } else if (selected == KonsoleMacroRule.TriggerType.NOTIFICATION) {
                trigValEt.setHint("Optional filter (app pkg or keyword)");
            } else if (selected == KonsoleMacroRule.TriggerType.APP_OPENED) {
                trigValEt.setHint("Package name filter (e.g. com.android.chrome)");
            }
        };

        trigSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
                refreshCronUi.run();
            }
            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        trigValEt.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                refreshCronUi.run();
            }
            @Override public void afterTextChanged(Editable s) {}
        });

        for (String preset : new String[]{"*/5 * * * *", "*/15 * * * *", "@hourly", "@daily", "0 8 * * *"}) {
            TextView pChip = new TextView(this);
            pChip.setBackgroundResource(R.drawable.bg_dashboard_chip);
            pChip.setText(preset);
            pChip.setTextColor(0xFF00E5FF);
            pChip.setTypeface(Typeface.MONOSPACE);
            pChip.setTextSize(11f);
            pChip.setGravity(Gravity.CENTER);
            pChip.setMinHeight(dp(40));
            pChip.setPadding(dp(10), dp(6), dp(10), dp(6));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMarginEnd(dp(6));
            pChip.setLayoutParams(lp);
            pChip.setOnClickListener(v -> trigValEt.setText(preset));
            cronPresetsRow.addView(pChip);
        }
        refreshCronUi.run();

        // Action type spinner setup
        KonsoleMacroRule.ActionType[] actionTypes = KonsoleMacroRule.ActionType.values();
        List<String> actionLabels = new ArrayList<>();
        for (KonsoleMacroRule.ActionType a : actionTypes) actionLabels.add(a.label);
        ArrayAdapter<String> actionAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, actionLabels);
        actionAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        actionTypeSpinner.setAdapter(actionAdapter);

        // Variable injection chips
        String[] quickVars = new String[]{
            "%battery_level%", "%wifi_ssid%", "%ip_address%", "%cpu_usage%",
            "%ram_used%", "%time%", "%notification_title%", "%foreground_app%"
        };
        for (String varToken : quickVars) {
            TextView vChip = new TextView(this);
            vChip.setBackgroundResource(R.drawable.bg_dashboard_chip);
            vChip.setText(varToken);
            vChip.setTextColor(0xFFFFD166);
            vChip.setTypeface(Typeface.MONOSPACE);
            vChip.setTextSize(11f);
            vChip.setPadding(dp(10), dp(8), dp(10), dp(8));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMarginEnd(dp(6));
            vChip.setLayoutParams(lp);
            vChip.setOnClickListener(v -> {
                EditText target = param2Et.hasFocus() ? param2Et : param1Et;
                int start = Math.max(0, target.getSelectionStart());
                target.getText().insert(start, varToken);
            });
            varChipsRow.addView(vChip);
        }

        final Runnable[] renderEditorActions = new Runnable[1];
        renderEditorActions[0] = () -> {
            actionsContainer.removeAllViews();
            if (working.actions.isEmpty()) {
                TextView emptyAct = new TextView(this);
                emptyAct.setText("No actions added yet — build one below.");
                emptyAct.setTextColor(0xFF8B949E);
                emptyAct.setTypeface(Typeface.MONOSPACE);
                emptyAct.setTextSize(11.5f);
                actionsContainer.addView(emptyAct);
                return;
            }
            for (int i = 0; i < working.actions.size(); i++) {
                final int idx = i;
                KonsoleMacroRule.MacroAction act = working.actions.get(i);
                LinearLayout row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setBackgroundResource(R.drawable.bg_dashboard_chip);
                row.setPadding(dp(10), dp(6), dp(6), dp(6));
                LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                rlp.bottomMargin = dp(6);
                row.setLayoutParams(rlp);

                TextView tv = new TextView(this);
                tv.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                tv.setText((i + 1) + ". " + act.describe());
                tv.setTextColor(Color.parseColor("#E6EDF3"));
                tv.setTypeface(Typeface.MONOSPACE);
                tv.setTextSize(11.5f);

                TextView rmBtn = new TextView(this);
                rmBtn.setText("REMOVE");
                rmBtn.setTextColor(0xFFFF7B72);
                rmBtn.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
                rmBtn.setTextSize(10.5f);
                rmBtn.setGravity(Gravity.CENTER);
                rmBtn.setMinWidth(dp(64));
                rmBtn.setMinHeight(dp(44));
                rmBtn.setOnClickListener(v -> {
                    working.actions.remove(idx);
                    renderEditorActions[0].run();
                });

                row.addView(tv);
                row.addView(rmBtn);
                actionsContainer.addView(row);
            }
        };
        renderEditorActions[0].run();

        addActionBtn.setOnClickListener(v -> {
            KonsoleMacroRule.ActionType aType = actionTypes[actionTypeSpinner.getSelectedItemPosition()];
            String p1 = param1Et.getText().toString().trim();
            String p2 = param2Et.getText().toString().trim();
            if (p1.isEmpty() && aType != KonsoleMacroRule.ActionType.FLASHLIGHT && aType != KonsoleMacroRule.ActionType.VIBRATE) {
                Toast.makeText(this, "Enter parameter for " + aType.name(), Toast.LENGTH_SHORT).show();
                return;
            }
            working.actions.add(new KonsoleMacroRule.MacroAction(aType, p1, p2, ""));
            param1Et.setText("");
            param2Et.setText("");
            renderEditorActions[0].run();
        });

        cancelBtn.setOnClickListener(v -> dialog.dismiss());

        saveBtn.setOnClickListener(v -> {
            String title = nameEt.getText().toString().trim();
            if (title.isEmpty()) title = "Untitled Macro";
            working.name = title;
            working.triggerType = triggerTypes[trigSpinner.getSelectedItemPosition()];
            working.triggerValue = trigValEt.getText().toString().trim();

            if (working.triggerType == KonsoleMacroRule.TriggerType.CRON && !KonsoleCronParser.isValid(working.triggerValue)) {
                Toast.makeText(this, "Invalid cron expression!", Toast.LENGTH_SHORT).show();
                return;
            }

            // Auto-capture pending typed action if user didn't click "+ ADD ACTION STEP" yet
            String pendingP1 = param1Et.getText().toString().trim();
            if (!pendingP1.isEmpty()) {
                KonsoleMacroRule.ActionType aType = actionTypes[actionTypeSpinner.getSelectedItemPosition()];
                working.actions.add(new KonsoleMacroRule.MacroAction(aType, pendingP1, param2Et.getText().toString().trim(), ""));
            }

            working.constraints.requireCharging = chkCharging.isChecked();
            working.constraints.requireDischarging = chkDischarging.isChecked();
            working.constraints.requireWifi = chkWifi.isChecked();
            working.constraints.requireScreenOn = chkScreen.isChecked();
            working.constraints.minBattery = clampInt(minBatEt.getText().toString(), 0, 100, 0);
            working.constraints.maxBattery = clampInt(maxBatEt.getText().toString(), 0, 100, 100);
            working.constraints.ssidFilter = ssidEt.getText().toString().trim();

            if (isNew) {
                working.enabled = true;
            }
            KonsoleMacroStore.getInstance().upsertMacro(this, working);
            KonsoleAutomationEngine.getInstance().rescheduleAllCrons(this);
            dialog.dismiss();
            refreshAll();
            Toast.makeText(this, "Saved macro '" + working.name + "'", Toast.LENGTH_SHORT).show();
        });

        dialog.show();
    }

    private KonsoleMacroRule cloneRuleWithNewId(KonsoleMacroRule src) {
        KonsoleMacroRule copy = cloneRuleKeepingId(src);
        copy.id = "macro_" + java.util.UUID.randomUUID().toString().substring(0, 8);
        copy.runCount = 0;
        copy.lastRunTimeMs = 0L;
        copy.lastRunSummary = "Template ready";
        return copy;
    }

    private KonsoleMacroRule cloneRuleKeepingId(KonsoleMacroRule src) {
        try {
            return KonsoleMacroRule.fromJson(src.toJson());
        } catch (Exception e) {
            return new KonsoleMacroRule();
        }
    }

    private static int clampInt(String raw, int min, int max, int fallback) {
        try {
            int val = Integer.parseInt(raw.trim());
            return Math.min(max, Math.max(min, val));
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private int dp(int val) {
        return Math.round(val * getResources().getDisplayMetrics().density);
    }
}
