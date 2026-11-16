package com.termux.app.konsole;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import com.termux.R;
import com.termux.terminal.TerminalSession;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Plugin Launcher & GitHub Template Manager Dialog.
 * - Default mode (HUD PLG button): Displays ONLY "launchable" plugins in a grid with app.png icons.
 * - Templates/Extensions mode: Loads templates from GitHub (plugins.json) & manages installed extensions.
 */
public class KonsolePluginsDialog extends Dialog {

    public static final int MODE_LAUNCHABLE = 0;
    public static final int MODE_TEMPLATES = 1;

    private final Context mContext;
    private final TerminalSession mSession;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private int mMode;

    private TextView mTitleView;
    private TextView mSubtitleView;
    private TextView mModeBtn;
    private EditText mSearchBox;
    private GridView mGridView;
    private ListView mListView;
    private TextView mEmptyView;

    private final List<KonsolePlugin> mLaunchablePlugins = new ArrayList<>();
    private final List<KonsolePlugin> mFilteredLaunchables = new ArrayList<>();
    private LaunchableAdapter mLaunchableAdapter;

    private final List<TemplateRowItem> mTemplateRows = new ArrayList<>();
    private final List<TemplateRowItem> mFilteredTemplateRows = new ArrayList<>();
    private TemplateAdapter mTemplateAdapter;
    private boolean mLoadingRemote = false;
    private String mCustomRegistryUrl = KonsolePluginManager.DEFAULT_REGISTRY_URL;

    private static class TemplateRowItem {
        boolean isSectionHeader;
        String sectionTitle;
        KonsolePlugin installedPlugin;
        KonsolePluginManager.RemotePluginTemplate remoteTemplate;
    }

    public KonsolePluginsDialog(Context context, TerminalSession session) {
        this(context, session, MODE_LAUNCHABLE);
    }

    public KonsolePluginsDialog(Context context, TerminalSession session, int initialMode) {
        super(context, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        this.mContext = context;
        this.mSession = session;
        this.mMode = initialMode;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);

        if (getWindow() != null) {
            getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            getWindow().setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT);
        }

        View root = LayoutInflater.from(mContext).inflate(R.layout.dialog_konsole_plugins, null, false);
        setContentView(root);

        View card = root.findViewById(R.id.konsole_plugins_card);
        if (card != null) {
            int maxPx = Math.round(680 * mContext.getResources().getDisplayMetrics().density);
            int availPx = mContext.getResources().getDisplayMetrics().widthPixels
                    - Math.round(32 * mContext.getResources().getDisplayMetrics().density);
            ViewGroup.LayoutParams lp = card.getLayoutParams();
            if (lp != null && availPx > maxPx) {
                lp.width = maxPx;
                card.setLayoutParams(lp);
            }
        }

        mTitleView = root.findViewById(R.id.konsole_plugins_title);
        mSubtitleView = root.findViewById(R.id.konsole_plugins_subtitle);
        mModeBtn = root.findViewById(R.id.konsole_plugins_mode_btn);
        mSearchBox = root.findViewById(R.id.konsole_plugins_search);
        mGridView = root.findViewById(R.id.konsole_plugins_grid);
        mListView = root.findViewById(R.id.konsole_plugins_list);
        mEmptyView = root.findViewById(R.id.konsole_plugins_empty);

        int gridCols = KonsoleConfigManager.getInstance().getUiInt("gridColumns", 3, 2, 6);
        mGridView.setNumColumns(gridCols);

        View closeBtn = root.findViewById(R.id.konsole_plugins_close);
        if (closeBtn != null) closeBtn.setOnClickListener(v -> dismiss());

        if (mModeBtn != null) {
            mModeBtn.setOnClickListener(v -> {
                mMode = (mMode == MODE_LAUNCHABLE) ? MODE_TEMPLATES : MODE_LAUNCHABLE;
                applyModeUi();
            });
            mModeBtn.setOnLongClickListener(v -> {
                promptCustomRegistryUrl();
                return true;
            });
        }

        View refreshBtn = root.findViewById(R.id.konsole_plugins_refresh);
        if (refreshBtn != null) {
            refreshBtn.setOnClickListener(v -> {
                if (mMode == MODE_LAUNCHABLE) {
                    KonsolePluginManager.getInstance().ensureSeedFiles(mContext);
                    reloadLocalLaunchables();
                } else {
                    fetchRemoteTemplates();
                }
            });
        }

        mLaunchableAdapter = new LaunchableAdapter();
        mGridView.setAdapter(mLaunchableAdapter);
        mGridView.setOnItemClickListener((parent, view, position, id) -> {
            if (position >= 0 && position < mFilteredLaunchables.size()) {
                KonsolePlugin plugin = mFilteredLaunchables.get(position);
                dismiss();
                KonsolePluginManager.getInstance().launchPlugin(mContext, plugin, mSession);
            }
        });
        mGridView.setOnItemLongClickListener((parent, view, position, id) -> {
            if (position >= 0 && position < mFilteredLaunchables.size()) {
                showPluginDetailsDialog(mFilteredLaunchables.get(position));
                return true;
            }
            return false;
        });

        mTemplateAdapter = new TemplateAdapter();
        mListView.setAdapter(mTemplateAdapter);

        mSearchBox.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                applyFilter(s.toString());
            }
            @Override public void afterTextChanged(Editable s) {}
        });

        KonsolePluginManager.getInstance().ensureSeedFiles(mContext);
        applyModeUi();
    }

    private void applyModeUi() {
        String query = mSearchBox != null ? mSearchBox.getText().toString() : "";
        if (mMode == MODE_LAUNCHABLE) {
            mTitleView.setText("PLUGIN LAUNCHER");
            mModeBtn.setText("TEMPLATES");
            mSearchBox.setHint("Filter launchable plugins...");
            mGridView.setVisibility(View.VISIBLE);
            mListView.setVisibility(View.GONE);
            reloadLocalLaunchables();
            applyFilter(query);
        } else {
            mTitleView.setText("GITHUB TEMPLATES & EXT");
            mModeBtn.setText("LAUNCHER");
            mSearchBox.setHint("Filter GitHub templates & extensions...");
            mGridView.setVisibility(View.GONE);
            mListView.setVisibility(View.VISIBLE);
            fetchRemoteTemplates();
        }
    }

    private void reloadLocalLaunchables() {
        mLaunchablePlugins.clear();
        mLaunchablePlugins.addAll(KonsolePluginManager.getInstance().getLaunchablePlugins());
        applyFilter(mSearchBox != null ? mSearchBox.getText().toString() : "");
    }

    private void fetchRemoteTemplates() {
        mLoadingRemote = true;
        mSubtitleView.setText("Loading " + mCustomRegistryUrl + "...");
        mTemplateRows.clear();
        buildTemplateRows(null);
        applyFilter(mSearchBox != null ? mSearchBox.getText().toString() : "");

        new Thread(() -> {
            List<KonsolePluginManager.RemotePluginTemplate> remote = null;
            String err = null;
            try {
                remote = KonsolePluginManager.getInstance().fetchGitHubTemplates(mCustomRegistryUrl);
            } catch (Exception e) {
                err = e.getMessage();
            }
            final List<KonsolePluginManager.RemotePluginTemplate> finalRemote = remote;
            final String finalErr = err;
            mHandler.post(() -> {
                mLoadingRemote = false;
                if (finalErr != null) {
                    mSubtitleView.setText("GitHub error: " + finalErr);
                } else {
                    int count = finalRemote != null ? finalRemote.size() : 0;
                    mSubtitleView.setText(count + " GitHub templates · Hold TEMPLATES for custom URL");
                }
                buildTemplateRows(finalRemote);
                applyFilter(mSearchBox != null ? mSearchBox.getText().toString() : "");
            });
        }, "KonsoleFetchTemplates").start();
    }

    private void buildTemplateRows(List<KonsolePluginManager.RemotePluginTemplate> remoteList) {
        mTemplateRows.clear();
        if (remoteList != null && !remoteList.isEmpty()) {
            TemplateRowItem hdr = new TemplateRowItem();
            hdr.isSectionHeader = true;
            hdr.sectionTitle = "GITHUB PLUGIN TEMPLATES";
            mTemplateRows.add(hdr);

            for (KonsolePluginManager.RemotePluginTemplate t : remoteList) {
                TemplateRowItem item = new TemplateRowItem();
                item.remoteTemplate = t;
                mTemplateRows.add(item);
            }
        }

        List<KonsolePlugin> extensions = KonsolePluginManager.getInstance().getExtensionPlugins();
        TemplateRowItem extHdr = new TemplateRowItem();
        extHdr.isSectionHeader = true;
        extHdr.sectionTitle = "INSTALLED EXTENSIONS (" + extensions.size() + ")";
        mTemplateRows.add(extHdr);

        for (KonsolePlugin p : extensions) {
            TemplateRowItem item = new TemplateRowItem();
            item.installedPlugin = p;
            mTemplateRows.add(item);
        }
    }

    private void applyFilter(String raw) {
        String q = raw == null ? "" : raw.trim().toLowerCase(Locale.US);
        if (mMode == MODE_LAUNCHABLE) {
            mFilteredLaunchables.clear();
            for (KonsolePlugin p : mLaunchablePlugins) {
                if (q.isEmpty()
                        || (p.name != null && p.name.toLowerCase(Locale.US).contains(q))
                        || (p.description != null && p.description.toLowerCase(Locale.US).contains(q))) {
                    mFilteredLaunchables.add(p);
                }
            }
            mSubtitleView.setText(mFilteredLaunchables.size() + " launchable plugin"
                    + (mFilteredLaunchables.size() == 1 ? "" : "s"));
            mEmptyView.setVisibility(mFilteredLaunchables.isEmpty() ? View.VISIBLE : View.GONE);
            mEmptyView.setText("No launchable plugins found in $konsole/plugins.\nTap TEMPLATES to install from GitHub.");
            mLaunchableAdapter.notifyDataSetChanged();
        } else {
            mFilteredTemplateRows.clear();
            for (TemplateRowItem row : mTemplateRows) {
                if (row.isSectionHeader) {
                    mFilteredTemplateRows.add(row);
                    continue;
                }
                if (q.isEmpty()) {
                    mFilteredTemplateRows.add(row);
                } else if (row.remoteTemplate != null) {
                    String hay = (row.remoteTemplate.name + " " + row.remoteTemplate.description + " " + row.remoteTemplate.type).toLowerCase(Locale.US);
                    if (hay.contains(q)) mFilteredTemplateRows.add(row);
                } else if (row.installedPlugin != null) {
                    String hay = (row.installedPlugin.name + " " + row.installedPlugin.description + " " + row.installedPlugin.type).toLowerCase(Locale.US);
                    if (hay.contains(q)) mFilteredTemplateRows.add(row);
                }
            }
            mEmptyView.setVisibility(!mLoadingRemote && mFilteredTemplateRows.isEmpty() ? View.VISIBLE : View.GONE);
            mTemplateAdapter.notifyDataSetChanged();
        }
    }

    private void promptCustomRegistryUrl() {
        final EditText input = new EditText(mContext);
        input.setText(mCustomRegistryUrl);
        input.setTextColor(Color.WHITE);
        input.setSingleLine(true);
        new AlertDialog.Builder(mContext, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Plugin Registry JSON URL")
                .setView(input)
                .setPositiveButton("LOAD", (d, w) -> {
                    String val = input.getText().toString().trim();
                    if (!val.isEmpty()) {
                        mCustomRegistryUrl = val;
                        mMode = MODE_TEMPLATES;
                        applyModeUi();
                    }
                })
                .setNegativeButton("CANCEL", null)
                .show();
    }

    private void showPluginDetailsDialog(KonsolePlugin plugin) {
        StringBuilder sb = new StringBuilder();
        sb.append("Name: ").append(plugin.name).append("\n");
        sb.append("Version: ").append(plugin.version).append("\n");
        sb.append("Type: ").append(plugin.getNormalizedType()).append("\n");
        sb.append("Build: ").append(plugin.build).append("\n");
        sb.append("Target OS: ").append(plugin.konsoleTargetVersion).append("\n");
        sb.append("Entry: ").append(plugin.startingFile).append("\n");
        sb.append("Deps: ").append(plugin.deps.isEmpty() ? "none" : TextUtils.join(", ", plugin.deps)).append("\n");
        sb.append("Permissions: ").append(plugin.permissions.isEmpty() ? "none" : TextUtils.join(", ", plugin.permissions)).append("\n");
        sb.append("Files (").append(plugin.flatFiles.size()).append("):\n");
        for (int i = 0; i < Math.min(plugin.flatFiles.size(), 10); i++) {
            sb.append("  • ").append(plugin.flatFiles.get(i)).append("\n");
        }
        if (plugin.flatFiles.size() > 10) {
            sb.append("  ... and ").append(plugin.flatFiles.size() - 10).append(" more\n");
        }

        AlertDialog.Builder b = new AlertDialog.Builder(mContext, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(plugin.name)
                .setMessage(sb.toString())
                .setNegativeButton("REMOVE", (d, w) -> {
                    KonsolePluginManager.getInstance().removePlugin(plugin.dirName);
                    reloadLocalLaunchables();
                    if (mMode == MODE_TEMPLATES) fetchRemoteTemplates();
                })
                .setNeutralButton("CLOSE", null);
        if (plugin.isLaunchable()) {
            b.setPositiveButton("LAUNCH", (d, w) -> {
                dismiss();
                KonsolePluginManager.getInstance().launchPlugin(mContext, plugin, mSession);
            });
        }
        b.show();
    }

    private class LaunchableAdapter extends BaseAdapter {
        @Override public int getCount() { return mFilteredLaunchables.size(); }
        @Override public Object getItem(int position) { return mFilteredLaunchables.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            if (convertView == null) {
                convertView = LayoutInflater.from(mContext).inflate(R.layout.item_konsole_plugin_grid, parent, false);
            }
            KonsolePlugin plugin = mFilteredLaunchables.get(position);
            ImageView iconView = convertView.findViewById(R.id.konsole_plugin_icon);
            TextView nameView = convertView.findViewById(R.id.konsole_plugin_name);
            TextView metaView = convertView.findViewById(R.id.konsole_plugin_meta);

            nameView.setText(plugin.name);
            String ext = plugin.getEntryExtension().toUpperCase(Locale.US);
            metaView.setText("v" + plugin.version + (ext.isEmpty() ? "" : " · " + ext));

            if (plugin.iconFile != null && plugin.iconFile.isFile()) {
                Bitmap bmp = BitmapFactory.decodeFile(plugin.iconFile.getAbsolutePath());
                if (bmp != null) {
                    iconView.setImageBitmap(bmp);
                } else {
                    iconView.setImageResource(android.R.drawable.sym_def_app_icon);
                }
            } else {
                iconView.setImageResource(android.R.drawable.sym_def_app_icon);
            }
            return convertView;
        }
    }

    private class TemplateAdapter extends BaseAdapter {
        @Override public int getCount() { return mFilteredTemplateRows.size(); }
        @Override public Object getItem(int position) { return mFilteredTemplateRows.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            TemplateRowItem row = mFilteredTemplateRows.get(position);
            if (row.isSectionHeader) {
                TextView tv = new TextView(mContext);
                tv.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
                tv.setTextSize(11f);
                tv.setTextColor(0xFF8B949E);
                tv.setPadding(dp(4), dp(8), dp(4), dp(4));
                tv.setText(row.sectionTitle);
                return tv;
            }

            LinearLayout card = new LinearLayout(mContext);
            card.setOrientation(LinearLayout.HORIZONTAL);
            card.setGravity(Gravity.CENTER_VERTICAL);
            card.setBackgroundResource(R.drawable.bg_dashboard_card);
            card.setPadding(dp(12), dp(10), dp(12), dp(10));

            LinearLayout info = new LinearLayout(mContext);
            info.setOrientation(LinearLayout.VERTICAL);
            info.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            if (row.remoteTemplate != null) {
                KonsolePluginManager.RemotePluginTemplate tpl = row.remoteTemplate;
                TextView titleTv = new TextView(mContext);
                titleTv.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
                titleTv.setTextSize(13.5f);
                titleTv.setTextColor(0xFFE6EDF3);
                titleTv.setText(tpl.name + "  v" + tpl.version + "  [" + tpl.type.toUpperCase(Locale.US) + "]");

                TextView descTv = new TextView(mContext);
                descTv.setTypeface(Typeface.MONOSPACE);
                descTv.setTextSize(11f);
                descTv.setTextColor(0xFF8B949E);
                descTv.setText(tpl.description);

                info.addView(titleTv);
                info.addView(descTv);
                card.addView(info);

                boolean alreadyInstalled = KonsolePluginManager.getInstance().findPluginByName(tpl.name) != null;
                TextView actionBtn = new TextView(mContext);
                actionBtn.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
                actionBtn.setTextSize(11f);
                actionBtn.setGravity(Gravity.CENTER);
                actionBtn.setMinWidth(dp(78));
                actionBtn.setMinHeight(dp(48));
                actionBtn.setPadding(dp(10), 0, dp(10), 0);
                actionBtn.setText(alreadyInstalled ? "UPDATE" : "INSTALL");
                actionBtn.setTextColor(alreadyInstalled ? 0xFF00FF88 : 0xFF00E5FF);

                GradientDrawable pill = new GradientDrawable();
                pill.setCornerRadius(dp(8));
                pill.setColor(alreadyInstalled ? 0xFF0E241C : 0xFF0D2530);
                pill.setStroke(dp(1), alreadyInstalled ? 0xFF1E4F3A : 0xFF1D4E63);
                actionBtn.setBackground(pill);

                actionBtn.setOnClickListener(v -> {
                    actionBtn.setEnabled(false);
                    actionBtn.setText("...");
                    KonsolePluginManager.getInstance().installPluginTemplateAsync(mContext, tpl, (ok, msg, installed) ->
                            mHandler.post(() -> {
                                Toast.makeText(mContext, msg, Toast.LENGTH_SHORT).show();
                                actionBtn.setEnabled(true);
                                reloadLocalLaunchables();
                                fetchRemoteTemplates();
                            }));
                });
                card.addView(actionBtn);
            } else if (row.installedPlugin != null) {
                KonsolePlugin p = row.installedPlugin;
                TextView titleTv = new TextView(mContext);
                titleTv.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
                titleTv.setTextSize(13.5f);
                titleTv.setTextColor(0xFFFFB74D);
                titleTv.setText(p.name + "  v" + p.version + "  [" + p.getNormalizedType().toUpperCase(Locale.US) + "]");

                TextView descTv = new TextView(mContext);
                descTv.setTypeface(Typeface.MONOSPACE);
                descTv.setTextSize(11f);
                descTv.setTextColor(0xFF8B949E);
                descTv.setText(p.description + " · Entry: " + p.startingFile);

                info.addView(titleTv);
                info.addView(descTv);
                card.addView(info);

                TextView infoBtn = new TextView(mContext);
                infoBtn.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
                infoBtn.setTextSize(11f);
                infoBtn.setGravity(Gravity.CENTER);
                infoBtn.setMinWidth(dp(64));
                infoBtn.setMinHeight(dp(48));
                infoBtn.setText("INFO");
                infoBtn.setTextColor(0xFFE6EDF3);
                infoBtn.setBackgroundResource(R.drawable.bg_dashboard_chip);
                infoBtn.setOnClickListener(v -> showPluginDetailsDialog(p));
                card.addView(infoBtn);
            }
            return card;
        }
    }

    private int dp(int val) {
        return Math.round(val * mContext.getResources().getDisplayMetrics().density);
    }
}
