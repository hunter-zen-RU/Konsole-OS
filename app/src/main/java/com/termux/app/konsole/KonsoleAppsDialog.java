package com.termux.app.konsole;

import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.text.Editable;
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
import android.widget.TextView;
import android.widget.Toast;

import com.termux.R;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Terminal-styled GUI modal overlay displaying installed launchable apps in a grid view.
 */
public class KonsoleAppsDialog extends Dialog {

    public interface AppLaunchListener {
        void onAppLaunched(String appName, String packageName);
    }

    private final Context mContext;
    private final AppLaunchListener mListener;
    private AppAdapter mAdapter;
    private final List<AppInfoItem> mAllApps = new ArrayList<>();
    private final List<AppInfoItem> mFilteredApps = new ArrayList<>();

    public static class AppInfoItem {
        public String label;
        public String packageName;

        public AppInfoItem(String label, String packageName) {
            this.label = label;
            this.packageName = packageName;
        }
    }

    public KonsoleAppsDialog(Context context, AppLaunchListener listener) {
        super(context, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        this.mContext = context;
        this.mListener = listener;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);

        if (getWindow() != null) {
            getWindow().setBackgroundDrawable(new ColorDrawable(Color.parseColor("#EE0A0F14")));
            getWindow().setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT);
        }

        View rootView = LayoutInflater.from(mContext).inflate(R.layout.dialog_konsole_apps, null, false);
        setContentView(rootView);

        EditText searchBox = rootView.findViewById(R.id.konsole_app_search);
        GridView gridView = rootView.findViewById(R.id.konsole_app_grid);

        loadInstalledApps();

        mAdapter = new AppAdapter();
        gridView.setAdapter(mAdapter);

        gridView.setOnItemClickListener((parent, view, position, id) -> {
            if (position >= 0 && position < mFilteredApps.size()) {
                AppInfoItem item = mFilteredApps.get(position);
                launchApp(item);
            }
        });

        searchBox.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                filterApps(s.toString());
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });
    }

    private void loadInstalledApps() {
        mAllApps.clear();
        PackageManager pm = mContext.getPackageManager();
        List<ApplicationInfo> apps = pm.getInstalledApplications(PackageManager.GET_META_DATA);
        for (ApplicationInfo app : apps) {
            if (pm.getLaunchIntentForPackage(app.packageName) != null) {
                String label = pm.getApplicationLabel(app).toString();
                mAllApps.add(new AppInfoItem(label, app.packageName));
            }
        }

        Collections.sort(mAllApps, (a, b) -> a.label.compareToIgnoreCase(b.label));

        mFilteredApps.clear();
        mFilteredApps.addAll(mAllApps);
    }

    private void filterApps(String query) {
        mFilteredApps.clear();
        if (query.trim().isEmpty()) {
            mFilteredApps.addAll(mAllApps);
        } else {
            String lower = query.toLowerCase();
            for (AppInfoItem item : mAllApps) {
                if (item.label.toLowerCase().contains(lower) || item.packageName.toLowerCase().contains(lower)) {
                    mFilteredApps.add(item);
                }
            }
        }
        if (mAdapter != null) {
            mAdapter.notifyDataSetChanged();
        }
    }

    private void launchApp(AppInfoItem item) {
        try {
            Intent intent = mContext.getPackageManager().getLaunchIntentForPackage(item.packageName);
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                mContext.startActivity(intent);
                if (mListener != null) {
                    mListener.onAppLaunched(item.label, item.packageName);
                }
                dismiss();
            } else {
                Toast.makeText(mContext, "Cannot launch " + item.label, Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            Toast.makeText(mContext, "Failed launching " + item.label + ": " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private class AppAdapter extends BaseAdapter {

        @Override
        public int getCount() {
            return mFilteredApps.size();
        }

        @Override
        public Object getItem(int position) {
            return mFilteredApps.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            if (convertView == null) {
                convertView = LayoutInflater.from(mContext).inflate(R.layout.item_konsole_app_grid, parent, false);
            }

            AppInfoItem item = mFilteredApps.get(position);

            ImageView iconView = convertView.findViewById(R.id.konsole_app_icon);
            TextView nameView = convertView.findViewById(R.id.konsole_app_name);

            nameView.setText(item.label);
            try {
                iconView.setImageDrawable(mContext.getPackageManager().getApplicationIcon(item.packageName));
            } catch (Exception e) {
                iconView.setImageResource(android.R.drawable.sym_def_app_icon);
            }

            return convertView;
        }
    }
}
