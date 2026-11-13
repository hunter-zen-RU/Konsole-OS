package com.termux.app.konsole;

import android.app.Dialog;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import com.termux.R;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Keyboard & touch-friendly fuzzy Command Palette inspired by modern terminal workflows (Warp/Ghostty).
 * Surfaces built-in Konsole OS commands, package operations, user aliases, and installed Android apps.
 */
public class KonsoleCommandPaletteDialog extends Dialog {

    public interface OnCommandSelectedListener {
        void onCommandSelected(String commandLine);
    }

    private static class PaletteEntry {
        final String command;
        final String category;
        final String description;

        PaletteEntry(String command, String category, String description) {
            this.command = command;
            this.category = category;
            this.description = description;
        }
    }

    private final Context mContext;
    private final OnCommandSelectedListener mListener;
    private final List<PaletteEntry> mAllEntries = new ArrayList<>();
    private final List<PaletteEntry> mFilteredEntries = new ArrayList<>();
    private PaletteAdapter mAdapter;

    public KonsoleCommandPaletteDialog(Context context, OnCommandSelectedListener listener) {
        super(context, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        this.mContext = context;
        this.mListener = listener;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);

        if (getWindow() != null) {
            getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            getWindow().setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT);
            getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        }

        setContentView(R.layout.dialog_konsole_palette);

        View card = findViewById(R.id.konsole_palette_card);
        if (card != null) {
            int maxPx = Math.round(640 * mContext.getResources().getDisplayMetrics().density);
            int availPx = mContext.getResources().getDisplayMetrics().widthPixels - Math.round(32 * mContext.getResources().getDisplayMetrics().density);
            ViewGroup.LayoutParams lp = card.getLayoutParams();
            if (lp != null && availPx > maxPx) {
                lp.width = maxPx;
                card.setLayoutParams(lp);
            }
        }

        EditText searchInput = findViewById(R.id.konsole_palette_search);
        ListView listView = findViewById(R.id.konsole_palette_list);
        TextView closeBtn = findViewById(R.id.konsole_palette_close);

        populateEntries();

        mAdapter = new PaletteAdapter();
        listView.setAdapter(mAdapter);

        closeBtn.setOnClickListener(v -> dismiss());

        listView.setOnItemClickListener((parent, view, position, id) -> {
            if (position >= 0 && position < mFilteredEntries.size()) {
                dispatchSelection(mFilteredEntries.get(position).command);
            }
        });

        searchInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                filterEntries(s.toString());
            }
            @Override public void afterTextChanged(Editable s) {}
        });

        searchInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_GO ||
                (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER && event.getAction() == KeyEvent.ACTION_DOWN)) {
                if (!mFilteredEntries.isEmpty()) {
                    dispatchSelection(mFilteredEntries.get(0).command);
                } else {
                    String raw = searchInput.getText().toString().trim();
                    if (!raw.isEmpty()) {
                        dispatchSelection(raw);
                    }
                }
                return true;
            }
            return false;
        });

        searchInput.requestFocus();
    }

    private void dispatchSelection(String command) {
        dismiss();
        if (mListener != null) {
            mListener.onCommandSelected(command);
        }
    }

    private void populateEntries() {
        mAllEntries.clear();

        // Core Konsole OS workflows
        mAllEntries.add(new PaletteEntry("kons dashboard", "DASHBOARD", "Open interactive system stats, paths & quick-toggle Dashboard"));
        mAllEntries.add(new PaletteEntry("kons macro", "AUTOMATION", "Open visual UI Macro Lab (triggers, cron jobs & live tests)"));
        mAllEntries.add(new PaletteEntry("macro list", "AUTOMATION", "List configured automation macros and cron rules"));
        mAllEntries.add(new PaletteEntry("macro status", "AUTOMATION", "Show automation engine, cron & optional sensor status"));
        mAllEntries.add(new PaletteEntry("kons sys", "SYSTEM", "Live hardware, CPU, RAM & network telemetry dashboard"));
        mAllEntries.add(new PaletteEntry("kons apps", "LAUNCHER", "Open interactive Android app grid drawer"));
        mAllEntries.add(new PaletteEntry("kons search ", "PACKAGE", "Search remote Konsole OS package repositories"));
        mAllEntries.add(new PaletteEntry("kons list", "PACKAGE", "List installed Konsole packages and $konsole/bin scripts"));
        mAllEntries.add(new PaletteEntry("kons update", "OTA", "Check GitHub Releases for Konsole OS APK updates"));
        mAllEntries.add(new PaletteEntry("kons hud on", "UI", "Show top live telemetry HUD bar"));
        mAllEntries.add(new PaletteEntry("kons hud off", "UI", "Hide top live telemetry HUD bar"));
        mAllEntries.add(new PaletteEntry("kons setup-fish", "SHELL", "Install and configure native Fish shell integration"));
        mAllEntries.add(new PaletteEntry("kons switch termux", "MODE", "Switch to pure Termux Fish pass-through mode"));
        mAllEntries.add(new PaletteEntry("kons switch konsole", "MODE", "Switch to Konsole OS Fish command mode"));
        mAllEntries.add(new PaletteEntry("kons help", "DOCS", "Show complete Konsole OS command manual"));
        mAllEntries.add(new PaletteEntry("pkg update && pkg upgrade", "APT/PKG", "Update Termux APT repository cache and upgrade packages"));

        // Hardware & Connectivity controls
        mAllEntries.add(new PaletteEntry("wifi status", "NET", "Show current Wi-Fi state and SSID"));
        mAllEntries.add(new PaletteEntry("bluetooth status", "BT", "Show Bluetooth adapter status"));
        mAllEntries.add(new PaletteEntry("battery", "POWER", "Detailed battery charge, temperature & health"));
        mAllEntries.add(new PaletteEntry("flash on", "HARDWARE", "Turn on device camera flashlight"));
        mAllEntries.add(new PaletteEntry("flash off", "HARDWARE", "Turn off device camera flashlight"));
        mAllEntries.add(new PaletteEntry("settings wifi", "SETTINGS", "Open Android Wi-Fi system settings"));
        mAllEntries.add(new PaletteEntry("settings bt", "SETTINGS", "Open Android Bluetooth settings"));
        mAllEntries.add(new PaletteEntry("settings apps", "SETTINGS", "Open Android Applications settings"));
        mAllEntries.add(new PaletteEntry("clear", "SHELL", "Clear terminal screen"));

        // User aliases
        Map<String, String> aliases = KonsoleConfigManager.getInstance().getUserAliases();
        for (Map.Entry<String, String> entry : aliases.entrySet()) {
            mAllEntries.add(new PaletteEntry(entry.getKey(), "ALIAS", "-> " + entry.getValue()));
        }

        // Launchable Android apps
        try {
            PackageManager pm = mContext.getPackageManager();
            List<ApplicationInfo> apps = pm.getInstalledApplications(PackageManager.GET_META_DATA);
            List<PaletteEntry> appEntries = new ArrayList<>();
            for (ApplicationInfo app : apps) {
                if (pm.getLaunchIntentForPackage(app.packageName) != null) {
                    String label = pm.getApplicationLabel(app).toString();
                    appEntries.add(new PaletteEntry("launch " + label, "APP", app.packageName));
                }
            }
            Collections.sort(appEntries, (a, b) -> a.command.compareToIgnoreCase(b.command));
            mAllEntries.addAll(appEntries);
        } catch (Exception ignored) {
        }

        mFilteredEntries.clear();
        mFilteredEntries.addAll(mAllEntries);
    }

    private void filterEntries(String rawQuery) {
        mFilteredEntries.clear();
        String query = rawQuery == null ? "" : rawQuery.trim().toLowerCase();
        if (query.isEmpty()) {
            mFilteredEntries.addAll(mAllEntries);
        } else {
            String[] terms = query.split("\\s+");
            for (PaletteEntry entry : mAllEntries) {
                String haystack = (entry.command + " " + entry.category + " " + entry.description).toLowerCase();
                boolean matchesAll = true;
                for (String term : terms) {
                    if (!haystack.contains(term)) {
                        matchesAll = false;
                        break;
                    }
                }
                if (matchesAll) {
                    mFilteredEntries.add(entry);
                }
            }
        }
        if (mAdapter != null) {
            mAdapter.notifyDataSetChanged();
        }
    }

    private class PaletteAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return mFilteredEntries.size();
        }

        @Override
        public Object getItem(int position) {
            return mFilteredEntries.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            LinearLayout row;
            LinearLayout topRow;
            TextView titleView;
            TextView badgeView;
            TextView descView;

            if (convertView instanceof LinearLayout && ((LinearLayout) convertView).getChildCount() == 2
                && ((LinearLayout) convertView).getChildAt(0) instanceof LinearLayout) {
                row = (LinearLayout) convertView;
                topRow = (LinearLayout) row.getChildAt(0);
                titleView = (TextView) topRow.getChildAt(0);
                badgeView = (TextView) topRow.getChildAt(1);
                descView = (TextView) row.getChildAt(1);
            } else {
                row = new LinearLayout(mContext);
                row.setOrientation(LinearLayout.VERTICAL);
                row.setBackgroundResource(R.drawable.bg_palette_row);
                int padH = dpToPx(10);
                int padV = dpToPx(10);
                row.setPadding(padH, padV, padH, padV);
                row.setMinimumHeight(dpToPx(52));

                topRow = new LinearLayout(mContext);
                topRow.setOrientation(LinearLayout.HORIZONTAL);
                topRow.setGravity(android.view.Gravity.CENTER_VERTICAL);

                titleView = new TextView(mContext);
                titleView.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
                titleView.setTextSize(13.5f);
                titleView.setSingleLine(true);
                titleView.setEllipsize(android.text.TextUtils.TruncateAt.END);
                LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                titleLp.setMarginEnd(dpToPx(8));
                topRow.addView(titleView, titleLp);

                badgeView = new TextView(mContext);
                badgeView.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
                badgeView.setTextSize(10f);
                badgeView.setPadding(dpToPx(7), dpToPx(2), dpToPx(7), dpToPx(2));
                topRow.addView(badgeView);

                descView = new TextView(mContext);
                descView.setTypeface(Typeface.MONOSPACE);
                descView.setTextSize(11.5f);
                descView.setTextColor(Color.parseColor("#8B949E"));
                LinearLayout.LayoutParams descLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                descLp.topMargin = dpToPx(4);

                row.addView(topRow);
                row.addView(descView, descLp);
            }

            PaletteEntry entry = mFilteredEntries.get(position);
            titleView.setText(entry.command);
            badgeView.setText(entry.category);

            int textColor;
            int badgeBgColor;
            int badgeBorderColor;
            if ("APP".equals(entry.category)) {
                textColor = Color.parseColor("#79C0FF");
                badgeBgColor = Color.parseColor("#112236");
                badgeBorderColor = Color.parseColor("#254A73");
            } else if ("PACKAGE".equals(entry.category) || "APT/PKG".equals(entry.category)) {
                textColor = Color.parseColor("#00FF88");
                badgeBgColor = Color.parseColor("#0E241C");
                badgeBorderColor = Color.parseColor("#1E4F3A");
            } else if ("ALIAS".equals(entry.category)) {
                textColor = Color.parseColor("#D2A8FF");
                badgeBgColor = Color.parseColor("#1E1833");
                badgeBorderColor = Color.parseColor("#3D2E6B");
            } else if ("HARDWARE".equals(entry.category) || "POWER".equals(entry.category) || "SETTINGS".equals(entry.category) || "NET".equals(entry.category) || "BT".equals(entry.category)) {
                textColor = Color.parseColor("#FFD166");
                badgeBgColor = Color.parseColor("#241D0F");
                badgeBorderColor = Color.parseColor("#4F3D1F");
            } else {
                textColor = Color.parseColor("#00E5FF");
                badgeBgColor = Color.parseColor("#0D2530");
                badgeBorderColor = Color.parseColor("#1D4E63");
            }

            titleView.setTextColor(Color.parseColor("#E6EDF3"));
            badgeView.setTextColor(textColor);
            android.graphics.drawable.GradientDrawable pill = new android.graphics.drawable.GradientDrawable();
            pill.setColor(badgeBgColor);
            pill.setCornerRadius(dpToPx(6));
            pill.setStroke(dpToPx(1), badgeBorderColor);
            badgeView.setBackground(pill);

            descView.setText(entry.description);
            return row;
        }

        private int dpToPx(int dp) {
            return Math.round(dp * mContext.getResources().getDisplayMetrics().density);
        }
    }
}
