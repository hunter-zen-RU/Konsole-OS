package com.termux.app.konsole;

import android.app.AlertDialog;
import android.app.NotificationManager;
import android.bluetooth.BluetoothAdapter;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.hardware.camera2.CameraManager;
import android.media.AudioManager;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import com.termux.R;
import com.termux.app.TermuxActivity;
import com.termux.app.activities.SettingsActivity;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Reusable controller for the Konsole OS Custom Replacement Shade (view_konsole_shade.xml).
 * Can be mounted inside TermuxActivity or inside KonsoleShadeService's global overlay window.
 */
public class KonsoleShadeController implements KonsoleNotificationListenerService.NotificationsChangedListener {

    public interface HostCallbacks {
        void onRequestClose();
    }

    private final Context mContext;
    private final HostCallbacks mCallbacks;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private View mRootView;
    private LinearLayout mPanelView;
    private TextView mClockView;
    private TextView mTelemetryView;
    private TextView mSetDefaultBtn;
    private GridLayout mTilesGrid;
    private View mBrightnessRow;
    private SeekBar mBrightnessSeekbar;
    private TextView mBrightnessVal;
    private View mMediaCard;
    private View mNotifHeader;
    private View mNotifScroll;
    private LinearLayout mNotifContainer;
    private TextView mNotifSectionTitle;
    private TextView mNotifAccessBtn;

    private boolean mFlashlightOn = false;
    private boolean mVisible = false;
    private volatile boolean mSshdRunning = false;
    private volatile boolean mKexRunning = false;
    private final java.util.Map<String, KonsolePlugin.ExtensionShadeTile> mExtTiles = new java.util.HashMap<>();

    private final Runnable mTicker = new Runnable() {
        @Override
        public void run() {
            if (!mVisible) return;
            refreshHeaderAndTelemetry();
            refreshTiles();
            mHandler.postDelayed(this, 1000);
        }
    };

    public KonsoleShadeController(Context context, HostCallbacks callbacks) {
        this.mContext = context;
        this.mCallbacks = callbacks;
    }

    public View getOrCreateView(ViewGroup parent) {
        if (mRootView == null) {
            mRootView = LayoutInflater.from(mContext).inflate(R.layout.view_konsole_shade, parent, false);
            bindViews();
        }
        applyXmlStyling();
        return mRootView;
    }

    private void bindViews() {
        mPanelView = mRootView.findViewById(R.id.konsole_shade_panel);
        mClockView = mRootView.findViewById(R.id.shade_clock_text);
        mTelemetryView = mRootView.findViewById(R.id.shade_date_telemetry_text);
        mSetDefaultBtn = mRootView.findViewById(R.id.shade_set_default_btn);
        mTilesGrid = mRootView.findViewById(R.id.shade_tiles_grid);
        mBrightnessRow = mRootView.findViewById(R.id.shade_brightness_row);
        mBrightnessSeekbar = mRootView.findViewById(R.id.shade_brightness_seekbar);
        mBrightnessVal = mRootView.findViewById(R.id.shade_brightness_value);
        mMediaCard = mRootView.findViewById(R.id.shade_media_card);
        mNotifHeader = mRootView.findViewById(R.id.shade_notifications_header);
        mNotifScroll = mRootView.findViewById(R.id.shade_notifications_scroll);
        mNotifContainer = mRootView.findViewById(R.id.shade_notifications_container);
        mNotifSectionTitle = mRootView.findViewById(R.id.shade_notif_section_title);
        mNotifAccessBtn = mRootView.findViewById(R.id.shade_notif_access_btn);

        View closeBtn = mRootView.findViewById(R.id.shade_close_btn);
        if (closeBtn != null) {
            closeBtn.setOnClickListener(v -> close());
        }

        View bottomHandle = mRootView.findViewById(R.id.shade_bottom_handle);
        if (bottomHandle != null) {
            bottomHandle.setOnClickListener(v -> close());
            bottomHandle.setOnTouchListener(new View.OnTouchListener() {
                private float startY;
                @Override
                public boolean onTouch(View v, MotionEvent event) {
                    if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                        startY = event.getRawY();
                        return true;
                    } else if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                        if (startY - event.getRawY() > dp(24) || Math.abs(startY - event.getRawY()) < dp(8)) {
                            close();
                        }
                        return true;
                    }
                    return false;
                }
            });
        }

        if (mSetDefaultBtn != null) {
            mSetDefaultBtn.setOnClickListener(v -> showSetCustomShadeDialog());
        }

        if (mNotifAccessBtn != null) {
            mNotifAccessBtn.setOnClickListener(v -> KonsoleNotificationListenerService.openNotificationAccessSettings(mContext));
        }

        View clearAllBtn = mRootView.findViewById(R.id.shade_notif_clear_all);
        if (clearAllBtn != null) {
            clearAllBtn.setOnClickListener(v -> {
                KonsoleNotificationListenerService.clearAllClearableNotifications();
                refreshNotifications();
            });
        }

        setupMediaButtons();
        setupBrightnessSlider();
    }

    public void applyXmlStyling() {
        if (mRootView == null) return;
        KonsoleConfigManager cfg = KonsoleConfigManager.getInstance();

        int bgColor = cfg.getShadeColor("backgroundColor", 0xF2090D12);
        int textColor = cfg.getShadeColor("textColor", 0xFFE6EDF3);
        int accentColor = cfg.getShadeColor("tileActiveColor", 0xFF00E5FF);
        float dim = cfg.getShadeFloat("blurDimAmount", 0.72f, 0.1f, 0.98f);

        int backdropAlpha = Math.round(dim * 255);
        mRootView.setBackgroundColor(Color.argb(backdropAlpha, 4, 7, 11));
        if (mPanelView != null) {
            mPanelView.setBackgroundColor(bgColor);
        }
        if (mClockView != null) {
            mClockView.setTextColor(textColor);
            mClockView.setVisibility(cfg.getShadeBoolean("showClock", true) ? View.VISIBLE : View.GONE);
        }
        if (mTelemetryView != null) {
            mTelemetryView.setTextColor(accentColor);
            mTelemetryView.setVisibility(cfg.getShadeBoolean("showTelemetryStrip", true) ? View.VISIBLE : View.GONE);
        }
        if (mBrightnessRow != null) {
            mBrightnessRow.setVisibility(cfg.getShadeBoolean("showBrightnessSlider", true) ? View.VISIBLE : View.GONE);
        }
        if (mMediaCard != null) {
            mMediaCard.setVisibility(cfg.getShadeBoolean("showMediaControls", true) ? View.VISIBLE : View.GONE);
        }
        boolean showNotifs = cfg.getShadeBoolean("showNotifications", true);
        if (mNotifHeader != null) mNotifHeader.setVisibility(showNotifs ? View.VISIBLE : View.GONE);
        if (mNotifScroll != null) mNotifScroll.setVisibility(showNotifs ? View.VISIBLE : View.GONE);

        if (mSetDefaultBtn != null) {
            boolean replace = cfg.getShadeBoolean("replaceSystemShade", false);
            boolean overlayOk = Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(mContext);
            mSetDefaultBtn.setText(replace && overlayOk ? "SHADE ON" : "SET SHADE");
            mSetDefaultBtn.setTextColor(replace && overlayOk ? 0xFF00FF88 : accentColor);
        }
    }

    public void onOpen() {
        mVisible = true;
        KonsoleConfigManager.getInstance().loadAllConfigurations();
        applyXmlStyling();
        refreshHeaderAndTelemetry();
        syncCurrentBrightness();
        probeDaemonStatusAsync();
        refreshTiles();
        refreshNotifications();
        KonsoleNotificationListenerService.addListener(this);

        if (mPanelView != null) {
            float speed = KonsoleConfigManager.getInstance().getExperienceFloat("animationSpeed", 1.0f, 0.2f, 3.0f);
            long dur = Math.round(220 / speed);
            mPanelView.setTranslationY(-dp(280));
            mPanelView.setAlpha(0.7f);
            mPanelView.animate()
                    .translationY(0f)
                    .alpha(1f)
                    .setDuration(dur)
                    .setInterpolator(new DecelerateInterpolator())
                    .start();
        }
        mHandler.removeCallbacks(mTicker);
        mHandler.post(mTicker);
    }

    public void onClose() {
        mVisible = false;
        KonsoleNotificationListenerService.removeListener(this);
        mHandler.removeCallbacks(mTicker);
    }

    private void close() {
        onClose();
        if (mCallbacks != null) {
            mCallbacks.onRequestClose();
        }
    }

    @Override
    public void onNotificationsChanged() {
        mHandler.post(this::refreshNotifications);
    }

    private void refreshHeaderAndTelemetry() {
        if (mClockView != null) {
            mClockView.setText(new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date()));
        }
        if (mTelemetryView != null) {
            Map<String, String> v = KonsoleTelemetryManager.getInstance().getLiveVariables();
            String date = new SimpleDateFormat("EEE, MMM d", Locale.getDefault()).format(new Date());
            String bat = v.getOrDefault("battery", "--");
            if (bat.contains(" ")) bat = bat.substring(0, bat.indexOf(' '));
            String cpu = v.getOrDefault("cpu_usage", "--");
            String ram = v.getOrDefault("ram_percent", "--");
            String net = v.getOrDefault("network_type", "--");
            mTelemetryView.setText(date + " · BAT " + bat + " · CPU " + cpu + " · RAM " + ram + " · " + net);
        }
    }

    private void setupBrightnessSlider() {
        if (mBrightnessSeekbar == null) return;
        syncCurrentBrightness();
        mBrightnessSeekbar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (mBrightnessVal != null) {
                    mBrightnessVal.setText(progress + "%");
                }
                if (fromUser) {
                    applyBrightnessPercent(progress);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.System.canWrite(mContext)) {
                    Toast.makeText(mContext, "Grant Modify System Settings for global brightness control", Toast.LENGTH_SHORT).show();
                }
            }
        });
    }

    private void syncCurrentBrightness() {
        if (mBrightnessSeekbar == null) return;
        try {
            int cur = Settings.System.getInt(mContext.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, 180);
            int pct = Math.max(0, Math.min(100, Math.round((cur / 255f) * 100f)));
            mBrightnessSeekbar.setProgress(pct);
            if (mBrightnessVal != null) mBrightnessVal.setText(pct + "%");
        } catch (Exception ignored) {
        }
    }

    private void applyBrightnessPercent(int pct) {
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.System.canWrite(mContext)) {
                int val = Math.max(5, Math.min(255, Math.round((pct / 100f) * 255f)));
                Settings.System.putInt(mContext.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS_MODE,
                        Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);
                Settings.System.putInt(mContext.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, val);
            } else if (mContext instanceof android.app.Activity) {
                android.view.Window w = ((android.app.Activity) mContext).getWindow();
                if (w != null) {
                    android.view.WindowManager.LayoutParams lp = w.getAttributes();
                    lp.screenBrightness = Math.max(0.05f, pct / 100f);
                    w.setAttributes(lp);
                }
            }
        } catch (Exception ignored) {
        }
    }

    private void setupMediaButtons() {
        View prev = mRootView.findViewById(R.id.shade_media_prev);
        View playPause = mRootView.findViewById(R.id.shade_media_play_pause);
        View next = mRootView.findViewById(R.id.shade_media_next);

        if (prev != null) prev.setOnClickListener(v -> sendMediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS));
        if (playPause != null) playPause.setOnClickListener(v -> sendMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE));
        if (next != null) next.setOnClickListener(v -> sendMediaKey(KeyEvent.KEYCODE_MEDIA_NEXT));
    }

    private void sendMediaKey(int keyCode) {
        AudioManager am = (AudioManager) mContext.getSystemService(Context.AUDIO_SERVICE);
        if (am != null) {
            am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, keyCode));
            am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, keyCode));
        }
    }

    private void probeDaemonStatusAsync() {
        new Thread(() -> {
            mSshdRunning = KonsoleDaemonAndDistroManager.isLocalPortOpen(8022, 80);
            mKexRunning = KonsoleDaemonAndDistroManager.isLocalPortOpen(5901, 80);
            if (mVisible) {
                mHandler.post(this::refreshTiles);
            }
        }).start();
    }

    private void refreshTiles() {
        if (mTilesGrid == null) return;
        KonsoleConfigManager cfg = KonsoleConfigManager.getInstance();
        int cols = cfg.getShadeInt("tileColumns", 4, 2, 6);
        mTilesGrid.setColumnCount(cols);
        mTilesGrid.removeAllViews();
        mExtTiles.clear();

        String rawTiles = cfg.getShadeProperty("tiles",
                "workstation,wifi,bluetooth,pcap,daemon_sshd,daemon_kex,mode,plugins,dashboard,panic,settings,flashlight");
        String[] tileIds = rawTiles.split(",");
        for (String rawId : tileIds) {
            String id = rawId.trim().toLowerCase();
            if (id.isEmpty()) continue;
            addTileView(id, null);
        }

        List<KonsolePlugin.ExtensionShadeTile> extTiles = KonsolePluginManager.getInstance().getExtensionShadeTiles();
        for (KonsolePlugin.ExtensionShadeTile ext : extTiles) {
            if (ext == null || ext.id == null || ext.id.trim().isEmpty()) continue;
            String key = ext.id.trim().toLowerCase();
            mExtTiles.put(key, ext);
            addTileView(key, ext);
        }
    }

    private void addTileView(String id, KonsolePlugin.ExtensionShadeTile extTile) {
        KonsoleConfigManager cfg = KonsoleConfigManager.getInstance();
        int activeColor = cfg.getShadeColor("tileActiveColor", 0xFF00E5FF);
        int inactiveColor = cfg.getShadeColor("tileInactiveColor", 0xFF131C27);
        int textColor = cfg.getShadeColor("textColor", 0xFFE6EDF3);

        String title;
        String status;
        boolean active = false;

        if (extTile != null) {
            active = true;
            title = TextUtils.isEmpty(extTile.title) ? id.toUpperCase(Locale.US) : extTile.title;
            status = TextUtils.isEmpty(extTile.subtitle) ? "EXT" : extTile.subtitle;
        } else switch (id) {
            case "workstation": {
                active = true;
                title = "PRO HUB";
                status = cfg.getActiveProfile().toUpperCase(Locale.US);
                break;
            }
            case "pcap": {
                active = KonsolePcapVpnService.isRunning();
                title = "PCAP";
                status = active ? (KonsolePcapVpnService.getPacketCount() + " PKTS") : "OFF";
                break;
            }
            case "daemon_sshd":
            case "sshd": {
                active = mSshdRunning;
                title = "SSHD";
                status = active ? ":8022 ON" : ":8022 OFF";
                break;
            }
            case "daemon_kex":
            case "kex": {
                active = mKexRunning;
                title = "KEX VNC";
                status = active ? ":5901 ON" : ":5901 OFF";
                break;
            }
            case "profile": {
                active = !"default".equalsIgnoreCase(cfg.getActiveProfile());
                title = "PROFILE";
                status = cfg.getActiveProfile().toUpperCase(Locale.US);
                break;
            }
            case "panic": {
                active = KonsoleSecurityVault.getInstance().isLocked(mContext);
                title = "PANIC";
                status = active ? "LOCKED" : "LOCK/WIPE";
                break;
            }
            case "wifi": {
                WifiManager wm = (WifiManager) mContext.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                active = wm != null && wm.isWifiEnabled();
                String ssid = KonsoleTelemetryManager.getInstance().getLiveVariables().getOrDefault("wifi_ssid", "");
                title = "WI-FI";
                status = active ? (ssid.isEmpty() || "None".equals(ssid) ? "ON" : ssid) : "OFF";
                break;
            }
            case "bluetooth": {
                BluetoothAdapter bt = BluetoothAdapter.getDefaultAdapter();
                try {
                    active = bt != null && bt.isEnabled();
                } catch (SecurityException ignored) {
                }
                title = "BT";
                status = active ? "ON" : "OFF";
                break;
            }
            case "flashlight":
            case "torch": {
                active = mFlashlightOn;
                title = "TORCH";
                status = active ? "ON" : "OFF";
                break;
            }
            case "dnd": {
                NotificationManager nm = (NotificationManager) mContext.getSystemService(Context.NOTIFICATION_SERVICE);
                if (nm != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    active = nm.getCurrentInterruptionFilter() != NotificationManager.INTERRUPTION_FILTER_ALL;
                }
                title = "DND";
                status = active ? "SILENT" : "OFF";
                break;
            }
            case "ringer":
            case "sound": {
                AudioManager am = (AudioManager) mContext.getSystemService(Context.AUDIO_SERVICE);
                int ringer = am != null ? am.getRingerMode() : AudioManager.RINGER_MODE_NORMAL;
                active = ringer == AudioManager.RINGER_MODE_NORMAL;
                title = "SOUND";
                status = ringer == AudioManager.RINGER_MODE_SILENT ? "MUTE"
                        : (ringer == AudioManager.RINGER_MODE_VIBRATE ? "VIB" : "RING");
                break;
            }
            case "mode": {
                boolean isKons = "konsole".equalsIgnoreCase(cfg.getMode());
                active = isKons;
                title = "SHELL";
                status = isKons ? "KONSOLE" : "TERMUX";
                break;
            }
            case "keep_screen": {
                active = cfg.getExperienceBoolean("keepScreenOn", false);
                title = "AWAKE";
                status = active ? "ON" : "OFF";
                break;
            }
            case "plugins": {
                active = true;
                title = "PLUGINS";
                status = KonsolePluginManager.getInstance().getLaunchablePlugins().size() + " APP";
                break;
            }
            case "dashboard": {
                active = false;
                title = "DASH";
                status = "STATS";
                break;
            }
            case "settings": {
                active = false;
                title = "CONFIG";
                status = "SETUP";
                break;
            }
            default:
                title = id.toUpperCase(Locale.US);
                status = "RUN";
                break;
        }

        LinearLayout tile = new LinearLayout(mContext);
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setGravity(Gravity.CENTER_VERTICAL);
        tile.setPadding(dp(8), dp(8), dp(8), dp(8));
        tile.setMinimumHeight(dp(54));
        tile.setClickable(true);
        tile.setFocusable(true);
        tile.setContentDescription(title + " " + status);

        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(10));
        bg.setColor(active ? blendAlpha(activeColor, 36) : inactiveColor);
        bg.setStroke(dp(1), active ? activeColor : 0xFF223042);
        tile.setBackground(bg);

        TextView titleTv = new TextView(mContext);
        titleTv.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        titleTv.setTextSize(10.5f);
        titleTv.setSingleLine(true);
        titleTv.setText(title);
        titleTv.setTextColor(active ? activeColor : textColor);

        TextView subTv = new TextView(mContext);
        subTv.setTypeface(Typeface.MONOSPACE);
        subTv.setTextSize(9.5f);
        subTv.setSingleLine(true);
        subTv.setEllipsize(TextUtils.TruncateAt.END);
        subTv.setText(status);
        subTv.setTextColor(active ? 0xFFE6EDF3 : 0xFF8B949E);

        tile.addView(titleTv);
        tile.addView(subTv);

        GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
        lp.width = 0;
        lp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
        lp.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f);
        lp.setMargins(dp(3), dp(3), dp(3), dp(3));
        tile.setLayoutParams(lp);

        tile.setOnClickListener(v -> handleTileClick(id));
        mTilesGrid.addView(tile);
    }

    private void handleTileClick(String id) {
        KonsoleConfigManager cfg = KonsoleConfigManager.getInstance();
        switch (id) {
            case "workstation": {
                close();
                Intent i = new Intent(mContext, KonsoleWorkstationActivity.class);
                if (!(mContext instanceof android.app.Activity)) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                mContext.startActivity(i);
                return;
            }
            case "pcap": {
                if (KonsolePcapVpnService.isRunning()) {
                    KonsolePcapVpnService.stopCapture(mContext);
                    Toast.makeText(mContext, "Rootless PCAP capture stopped", Toast.LENGTH_SHORT).show();
                } else if (android.net.VpnService.prepare(mContext) == null) {
                    Intent i = new Intent(mContext, KonsolePcapVpnService.class)
                            .setAction(KonsolePcapVpnService.ACTION_START);
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        mContext.startForegroundService(i);
                    } else {
                        mContext.startService(i);
                    }
                    Toast.makeText(mContext, "Rootless PCAP capture started", Toast.LENGTH_SHORT).show();
                } else {
                    close();
                    Intent i = new Intent(mContext, KonsoleWorkstationActivity.class);
                    if (!(mContext instanceof android.app.Activity)) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    mContext.startActivity(i);
                    Toast.makeText(mContext, "Approve VPN capture consent in Pro Workstation", Toast.LENGTH_SHORT).show();
                    return;
                }
                break;
            }
            case "daemon_sshd":
            case "sshd": {
                boolean next = !mSshdRunning;
                new Thread(() -> {
                    KonsoleDaemonAndDistroManager.startOrStopDaemonInSession(mContext, "sshd", next, null);
                    mSshdRunning = KonsoleDaemonAndDistroManager.isLocalPortOpen(8022, 120);
                    mHandler.post(() -> {
                        Toast.makeText(mContext, "sshd " + (next ? "start requested" : "stop requested"), Toast.LENGTH_SHORT).show();
                        refreshTiles();
                    });
                }).start();
                break;
            }
            case "daemon_kex":
            case "kex": {
                boolean next = !mKexRunning;
                new Thread(() -> {
                    KonsoleDaemonAndDistroManager.startOrStopDaemonInSession(mContext, "kex", next, null);
                    mKexRunning = KonsoleDaemonAndDistroManager.isLocalPortOpen(5901, 120);
                    mHandler.post(() -> {
                        Toast.makeText(mContext, "KeX VNC " + (next ? "start requested" : "stop requested"), Toast.LENGTH_SHORT).show();
                        refreshTiles();
                    });
                }).start();
                break;
            }
            case "profile": {
                String cur = cfg.getActiveProfile();
                String next = "default".equalsIgnoreCase(cur) ? "lab" : ("lab".equalsIgnoreCase(cur) ? "stealth" : "default");
                cfg.setActiveProfile(next);
                if (mContext instanceof TermuxActivity) {
                    ((TermuxActivity) mContext).applyKonsoleXmlCustomization(false);
                }
                Toast.makeText(mContext, "Profile switched: " + next.toUpperCase(Locale.US), Toast.LENGTH_SHORT).show();
                break;
            }
            case "panic": {
                String summary = KonsoleSecurityVault.getInstance().triggerPanicLockdown(mContext);
                Toast.makeText(mContext, summary, Toast.LENGTH_LONG).show();
                close();
                return;
            }
            case "wifi": {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    Intent panelIntent = new Intent(Settings.Panel.ACTION_WIFI);
                    panelIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    mContext.startActivity(panelIntent);
                } else {
                    WifiManager wm = (WifiManager) mContext.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                    if (wm != null) wm.setWifiEnabled(!wm.isWifiEnabled());
                }
                break;
            }
            case "bluetooth": {
                try {
                    BluetoothAdapter bt = BluetoothAdapter.getDefaultAdapter();
                    if (bt != null && Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                        if (bt.isEnabled()) bt.disable();
                        else bt.enable();
                    } else {
                        Intent i = new Intent(Settings.ACTION_BLUETOOTH_SETTINGS);
                        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        mContext.startActivity(i);
                    }
                } catch (SecurityException e) {
                    Intent i = new Intent(Settings.ACTION_BLUETOOTH_SETTINGS);
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    mContext.startActivity(i);
                }
                break;
            }
            case "flashlight":
            case "torch": {
                try {
                    CameraManager cm = (CameraManager) mContext.getSystemService(Context.CAMERA_SERVICE);
                    if (cm != null && cm.getCameraIdList().length > 0) {
                        mFlashlightOn = !mFlashlightOn;
                        cm.setTorchMode(cm.getCameraIdList()[0], mFlashlightOn);
                    }
                } catch (Exception e) {
                    Toast.makeText(mContext, "Torch unavailable: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                }
                break;
            }
            case "dnd": {
                NotificationManager nm = (NotificationManager) mContext.getSystemService(Context.NOTIFICATION_SERVICE);
                if (nm != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    if (!nm.isNotificationPolicyAccessGranted()) {
                        Intent i = new Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS);
                        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        mContext.startActivity(i);
                    } else {
                        boolean isDnd = nm.getCurrentInterruptionFilter() != NotificationManager.INTERRUPTION_FILTER_ALL;
                        nm.setInterruptionFilter(isDnd
                                ? NotificationManager.INTERRUPTION_FILTER_ALL
                                : NotificationManager.INTERRUPTION_FILTER_PRIORITY);
                    }
                }
                break;
            }
            case "ringer":
            case "sound": {
                try {
                    AudioManager am = (AudioManager) mContext.getSystemService(Context.AUDIO_SERVICE);
                    if (am != null) {
                        int cur = am.getRingerMode();
                        int next = cur == AudioManager.RINGER_MODE_NORMAL
                                ? AudioManager.RINGER_MODE_VIBRATE
                                : (cur == AudioManager.RINGER_MODE_VIBRATE
                                ? AudioManager.RINGER_MODE_SILENT
                                : AudioManager.RINGER_MODE_NORMAL);
                        am.setRingerMode(next);
                    }
                } catch (SecurityException e) {
                    Toast.makeText(mContext, "Grant Notification Policy Access for silent mode", Toast.LENGTH_SHORT).show();
                }
                break;
            }
            case "mode": {
                String next = "konsole".equalsIgnoreCase(cfg.getMode()) ? "termux" : "konsole";
                cfg.setBehaviorMode(next);
                KonsoleFishBridge.syncStateFiles(mContext);
                if (mContext instanceof TermuxActivity) {
                    ((TermuxActivity) mContext).refreshKonsoleHudVisibility();
                }
                break;
            }
            case "keep_screen": {
                boolean next = !cfg.getExperienceBoolean("keepScreenOn", false);
                cfg.setExperienceProperty("keepScreenOn", String.valueOf(next));
                if (mContext instanceof TermuxActivity) {
                    TermuxActivity act = (TermuxActivity) mContext;
                    if (act.getPreferences() != null) act.getPreferences().setKeepScreenOn(next);
                    if (act.getTerminalView() != null) act.getTerminalView().setKeepScreenOn(next);
                }
                break;
            }
            case "plugins": {
                close();
                if (mContext instanceof TermuxActivity) {
                    ((TermuxActivity) mContext).showPluginsLauncherDialog();
                }
                return;
            }
            case "dashboard": {
                close();
                Intent i = new Intent(mContext, KonsoleDashboardActivity.class);
                if (!(mContext instanceof android.app.Activity)) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                mContext.startActivity(i);
                return;
            }
            case "settings": {
                close();
                Intent i = new Intent(mContext, SettingsActivity.class);
                if (!(mContext instanceof android.app.Activity)) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                mContext.startActivity(i);
                return;
            }
            default: {
                KonsolePlugin.ExtensionShadeTile ext = mExtTiles.get(id);
                if (ext != null && ext.command != null && !ext.command.trim().isEmpty()) {
                    new Thread(() -> {
                        StringBuilder out = new StringBuilder();
                        KonsoleCommandDispatcher.getInstance().executeBridgeCommand(mContext, ext.command,
                                new KonsoleCommandDispatcher.CommandSink() {
                                    @Override public void write(String text) {
                                        if (text != null) out.append(text);
                                    }
                                    @Override public void finish(int exitCode) {
                                        mHandler.post(() -> {
                                            String clean = out.toString().replaceAll("\\u001B\\[[;\\d]*m", "").trim();
                                            String summary = clean.isEmpty() ? ("Ran " + ext.title) : clean.split("\\r?\\n")[0];
                                            Toast.makeText(mContext, summary, Toast.LENGTH_LONG).show();
                                        });
                                    }
                                });
                    }).start();
                }
                break;
            }
        }
        refreshTiles();
    }

    private void refreshNotifications() {
        if (mNotifContainer == null) return;
        mNotifContainer.removeAllViews();

        boolean connected = KonsoleNotificationListenerService.isConnected();
        if (mNotifAccessBtn != null) {
            mNotifAccessBtn.setVisibility(connected ? View.GONE : View.VISIBLE);
        }

        List<KonsoleNotificationListenerService.ShadeNotificationItem> items =
                KonsoleNotificationListenerService.getActiveShadeNotifications();

        if (mNotifSectionTitle != null) {
            mNotifSectionTitle.setText("NOTIFICATIONS (" + items.size() + ")");
        }

        if (!connected) {
            addInfoNotifCard("Notification Listener Not Enabled",
                    "Tap ENABLE ACCESS to mirror & manage Android notifications inside Konsole Custom Shade.",
                    v -> KonsoleNotificationListenerService.openNotificationAccessSettings(mContext));
            return;
        }

        if (items.isEmpty()) {
            addInfoNotifCard("No Active Notifications",
                    "You're all caught up. Configure shade appearance in $konsole/customize/shade.xml.",
                    null);
            return;
        }

        int maxNotifs = KonsoleConfigManager.getInstance().getShadeInt("maxNotifications", 15, 1, 50);
        int count = Math.min(items.size(), maxNotifs);
        for (int i = 0; i < count; i++) {
            addNotificationRow(items.get(i));
        }
    }

    private void addInfoNotifCard(String title, String body, View.OnClickListener clickListener) {
        LinearLayout card = new LinearLayout(mContext);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(12), dp(12), dp(12), dp(12));
        card.setBackgroundResource(R.drawable.bg_dashboard_card);
        if (clickListener != null) {
            card.setClickable(true);
            card.setFocusable(true);
            card.setOnClickListener(clickListener);
        }

        TextView t = new TextView(mContext);
        t.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        t.setTextColor(0xFF00E5FF);
        t.setTextSize(12f);
        t.setText(title);

        TextView b = new TextView(mContext);
        b.setTypeface(Typeface.MONOSPACE);
        b.setTextColor(0xFF8B949E);
        b.setTextSize(11f);
        b.setPadding(0, dp(4), 0, 0);
        b.setText(body);

        card.addView(t);
        card.addView(b);
        mNotifContainer.addView(card);
    }

    private void addNotificationRow(KonsoleNotificationListenerService.ShadeNotificationItem item) {
        LinearLayout row = new LinearLayout(mContext);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), dp(10), dp(8), dp(10));
        row.setBackgroundResource(R.drawable.bg_dashboard_card);

        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowLp.bottomMargin = dp(6);
        row.setLayoutParams(rowLp);

        LinearLayout textCol = new LinearLayout(mContext);
        textCol.setOrientation(LinearLayout.VERTICAL);
        textCol.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView appTv = new TextView(mContext);
        appTv.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        appTv.setTextSize(10f);
        appTv.setTextColor(0xFF00E5FF);
        appTv.setText(item.appName + " · " + new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date(item.postTime)));

        TextView titleTv = new TextView(mContext);
        titleTv.setTypeface(Typeface.DEFAULT_BOLD);
        titleTv.setTextSize(13f);
        titleTv.setTextColor(0xFFE6EDF3);
        titleTv.setMaxLines(1);
        titleTv.setEllipsize(TextUtils.TruncateAt.END);
        titleTv.setText(item.title);

        textCol.addView(appTv);
        textCol.addView(titleTv);

        if (!TextUtils.isEmpty(item.text)) {
            TextView bodyTv = new TextView(mContext);
            bodyTv.setTextSize(11.5f);
            bodyTv.setTextColor(0xFF9FB0C3);
            bodyTv.setMaxLines(2);
            bodyTv.setEllipsize(TextUtils.TruncateAt.END);
            bodyTv.setText(item.text);
            textCol.addView(bodyTv);
        }

        row.addView(textCol);

        if (item.clearable) {
            TextView dismissBtn = new TextView(mContext);
            dismissBtn.setText("✕");
            dismissBtn.setTextColor(0xFFFF7B72);
            dismissBtn.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
            dismissBtn.setTextSize(14f);
            dismissBtn.setGravity(Gravity.CENTER);
            dismissBtn.setMinWidth(dp(48));
            dismissBtn.setMinHeight(dp(48));
            dismissBtn.setContentDescription("Dismiss notification " + item.title);
            dismissBtn.setOnClickListener(v -> KonsoleNotificationListenerService.dismissNotification(item.key));
            row.addView(dismissBtn);
        }

        row.setClickable(true);
        row.setFocusable(true);
        row.setOnClickListener(v -> {
            if (item.contentIntent != null) {
                try {
                    item.contentIntent.send();
                    close();
                } catch (Exception ignored) {
                }
            }
        });

        mNotifContainer.addView(row);
    }

    private void showSetCustomShadeDialog() {
        KonsoleConfigManager cfg = KonsoleConfigManager.getInstance();
        boolean currentlyReplacing = cfg.getShadeBoolean("replaceSystemShade", false);
        boolean canOverlay = Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(mContext);
        boolean notifConnected = KonsoleNotificationListenerService.isConnected();
        boolean a11yConnected = KonsoleAutomationAccessibilityService.isConnected();

        String msg = "Status:\n"
                + "• Global Shade Replacement: " + (currentlyReplacing ? "ENABLED" : "DISABLED") + "\n"
                + "• Display Over Other Apps: " + (canOverlay ? "GRANTED" : "NOT GRANTED") + "\n"
                + "• Notification Access: " + (notifConnected ? "CONNECTED" : "NOT GRANTED") + "\n"
                + "• Accessibility Shade Intercept: " + (a11yConnected ? "ACTIVE" : "OPTIONAL / OFF") + "\n\n"
                + "Setting Konsole Shade replaces the top swipe-down shade across your device (customized via $konsole/customize/shade.xml).";

        AlertDialog.Builder builder = new AlertDialog.Builder(mContext, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Konsole Custom Replacement Shade")
                .setMessage(msg)
                .setPositiveButton(currentlyReplacing ? "DISABLE GLOBAL SHADE" : "SET AS CUSTOM SHADE", (d, w) -> {
                    boolean next = !currentlyReplacing;
                    cfg.setShadeProperty("replaceSystemShade", String.valueOf(next));
                    if (next) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(mContext)) {
                            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    Uri.parse("package:" + mContext.getPackageName()));
                            if (!(mContext instanceof android.app.Activity)) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            mContext.startActivity(intent);
                            Toast.makeText(mContext, "Grant Overlay permission to activate global top-edge shade", Toast.LENGTH_LONG).show();
                        } else {
                            KonsoleShadeService.startIfEnabled(mContext);
                            Toast.makeText(mContext, "Konsole Custom Shade set & active!", Toast.LENGTH_SHORT).show();
                        }
                    } else {
                        KonsoleShadeService.stopService(mContext);
                        Toast.makeText(mContext, "Global Custom Shade disabled", Toast.LENGTH_SHORT).show();
                    }
                    applyXmlStyling();
                })
                .setNeutralButton(!notifConnected ? "NOTIF ACCESS" : "A11Y SETTINGS", (d, w) -> {
                    if (!notifConnected) {
                        KonsoleNotificationListenerService.openNotificationAccessSettings(mContext);
                    } else {
                        Intent i = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
                        if (!(mContext instanceof android.app.Activity)) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        mContext.startActivity(i);
                    }
                })
                .setNegativeButton("CLOSE", null);

        AlertDialog dialog = builder.create();
        if (!(mContext instanceof android.app.Activity) && dialog.getWindow() != null) {
            dialog.getWindow().setType(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    ? android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    : android.view.WindowManager.LayoutParams.TYPE_PHONE);
        }
        dialog.show();
    }

    private int blendAlpha(int color, int alpha) {
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
    }

    private int dp(int val) {
        return Math.round(val * mContext.getResources().getDisplayMetrics().density);
    }
}
