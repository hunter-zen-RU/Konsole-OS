package com.termux.app.konsole;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.IBinder;
import android.provider.Settings;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.termux.R;

/**
 * Optional Foreground Overlay Service providing the Konsole OS Custom Replacement Shade
 * across the device when enabled via $konsole/customize/shade.xml (<replaceSystemShade>true</replaceSystemShade>).
 */
public class KonsoleShadeService extends Service {

    public static final String ACTION_SHOW_SHADE = "com.termux.konsole.ACTION_SHOW_SHADE";
    public static final String ACTION_HIDE_SHADE = "com.termux.konsole.ACTION_HIDE_SHADE";
    public static final String ACTION_REFRESH_SHADE = "com.termux.konsole.ACTION_REFRESH_SHADE";

    private static final String CHANNEL_ID = "konsole_custom_shade_channel";
    private static final int NOTIF_ID = 7331;

    private WindowManager mWindowManager;
    private View mTopTriggerView;
    private FrameLayout mShadeWrapper;
    private KonsoleShadeController mShadeController;
    private boolean mShadeOpen = false;

    public static void startIfEnabled(Context context) {
        if (context == null) return;
        KonsoleConfigManager cfg = KonsoleConfigManager.getInstance();
        if (!cfg.getShadeBoolean("enabled", true) || !cfg.getShadeBoolean("replaceSystemShade", false)) {
            stopService(context);
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(context)) {
            return;
        }
        Intent intent = new Intent(context, KonsoleShadeService.class);
        intent.setAction(ACTION_REFRESH_SHADE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    public static void showGlobalShade(Context context) {
        if (context == null) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(context)) {
            return;
        }
        Intent intent = new Intent(context, KonsoleShadeService.class);
        intent.setAction(ACTION_SHOW_SHADE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    public static void stopService(Context context) {
        if (context == null) return;
        context.stopService(new Intent(context, KonsoleShadeService.class));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        startForegroundNotification();
        mWindowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        attachTopTriggerHandle();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        if (ACTION_SHOW_SHADE.equals(action)) {
            openOverlayShade();
        } else if (ACTION_HIDE_SHADE.equals(action)) {
            closeOverlayShade();
        } else if (ACTION_REFRESH_SHADE.equals(action)) {
            attachTopTriggerHandle();
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        closeOverlayShade();
        detachTopTriggerHandle();
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void startForegroundNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "Konsole Custom Shade",
                    NotificationManager.IMPORTANCE_LOW);
            nm.createNotificationChannel(ch);
        }
        Intent showIntent = new Intent(this, KonsoleShadeService.class).setAction(ACTION_SHOW_SHADE);
        PendingIntent pi = PendingIntent.getService(this, 0, showIntent,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                        ? PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
                        : PendingIntent.FLAG_UPDATE_CURRENT);

        Notification notif = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_service_notification)
                .setContentTitle("Konsole Custom Shade Active")
                .setContentText("Swipe down from top edge or tap to open Konsole Shade")
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
        startForeground(NOTIF_ID, notif);
    }

    private void attachTopTriggerHandle() {
        if (mWindowManager == null) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) return;

        detachTopTriggerHandle();

        KonsoleConfigManager cfg = KonsoleConfigManager.getInstance();
        int heightDp = cfg.getShadeInt("triggerHeightDp", 22, 10, 64);
        int heightPx = Math.round(heightDp * getResources().getDisplayMetrics().density);

        mTopTriggerView = new View(this);
        mTopTriggerView.setBackgroundColor(0x01000000);
        mTopTriggerView.setContentDescription("Konsole Custom Shade top swipe handle");
        mTopTriggerView.setOnTouchListener(new View.OnTouchListener() {
            private float startY;
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        startY = event.getRawY();
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        if (event.getRawY() - startY > 24f) {
                            openOverlayShade();
                            return true;
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (event.getRawY() - startY > 12f) {
                            openOverlayShade();
                        }
                        return true;
                }
                return false;
            }
        });

        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                heightPx,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        try {
            mWindowManager.addView(mTopTriggerView, lp);
        } catch (Exception ignored) {
        }
    }

    private void detachTopTriggerHandle() {
        if (mWindowManager != null && mTopTriggerView != null) {
            try {
                mWindowManager.removeViewImmediate(mTopTriggerView);
            } catch (Exception ignored) {
            }
            mTopTriggerView = null;
        }
    }

    private void openOverlayShade() {
        if (mShadeOpen || mWindowManager == null) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) return;

        Context themed = new ContextThemeWrapper(this, R.style.Theme_TermuxActivity_DayNight_NoActionBar);
        mShadeWrapper = new FrameLayout(themed);
        mShadeController = new KonsoleShadeController(themed, this::closeOverlayShade);
        View shadeView = mShadeController.getOrCreateView(mShadeWrapper);
        mShadeWrapper.addView(shadeView);

        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                type,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;

        try {
            mWindowManager.addView(mShadeWrapper, lp);
            mShadeOpen = true;
            mShadeController.onOpen();
        } catch (Exception ignored) {
            mShadeOpen = false;
        }
    }

    private void closeOverlayShade() {
        if (!mShadeOpen) return;
        mShadeOpen = false;
        if (mShadeController != null) {
            mShadeController.onClose();
        }
        if (mWindowManager != null && mShadeWrapper != null) {
            try {
                mWindowManager.removeViewImmediate(mShadeWrapper);
            } catch (Exception ignored) {
            }
            mShadeWrapper = null;
        }
    }
}
