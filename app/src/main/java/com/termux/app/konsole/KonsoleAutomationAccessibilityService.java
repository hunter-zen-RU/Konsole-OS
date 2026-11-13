package com.termux.app.konsole;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.os.Build;
import android.view.accessibility.AccessibilityEvent;

import java.util.HashMap;
import java.util.Map;

/**
 * Optional opt-in AccessibilityService providing:
 * 1) Foreground app launch event triggers (TYPE_WINDOW_STATE_CHANGED)
 * 2) Screen tap & swipe gesture automation for macros and the CLI bridge
 * Note: Disabled by default until explicitly enabled by the user in Android Accessibility Settings.
 */
public class KonsoleAutomationAccessibilityService extends AccessibilityService {

    private static volatile KonsoleAutomationAccessibilityService sInstance;
    private static volatile String sLastForegroundPkg = "";

    public static boolean isConnected() {
        return sInstance != null;
    }

    public static String getLastForegroundPkg() {
        return sLastForegroundPkg;
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        sInstance = this;
    }

    @Override
    public boolean onUnbind(android.content.Intent intent) {
        sInstance = null;
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        sInstance = null;
        super.onDestroy();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;
        if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            CharSequence pkgCs = event.getPackageName();
            if (pkgCs == null) return;
            String pkg = pkgCs.toString();
            if (pkg.isEmpty() || pkg.equals(sLastForegroundPkg)) return;
            sLastForegroundPkg = pkg;

            Map<String, String> extras = new HashMap<>();
            extras.put("foreground_app", pkg);
            KonsoleAutomationEngine.getInstance().dispatchTrigger(
                    getApplicationContext(),
                    KonsoleMacroRule.TriggerType.APP_OPENED,
                    extras);
        }
    }

    @Override
    public void onInterrupt() {
    }

    public static boolean performTap(float x, float y) {
        KonsoleAutomationAccessibilityService svc = sInstance;
        if (svc == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false;
        Path path = new Path();
        path.moveTo(x, y);
        GestureDescription.StrokeDescription stroke = new GestureDescription.StrokeDescription(path, 0L, 60L);
        GestureDescription gesture = new GestureDescription.Builder().addStroke(stroke).build();
        return svc.dispatchGesture(gesture, null, null);
    }

    public static boolean performSwipe(float x1, float y1, float x2, float y2, long durationMs) {
        KonsoleAutomationAccessibilityService svc = sInstance;
        if (svc == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false;
        Path path = new Path();
        path.moveTo(x1, y1);
        path.lineTo(x2, y2);
        long safeDuration = Math.max(50L, Math.min(5000L, durationMs));
        GestureDescription.StrokeDescription stroke = new GestureDescription.StrokeDescription(path, 0L, safeDuration);
        GestureDescription gesture = new GestureDescription.Builder().addStroke(stroke).build();
        return svc.dispatchGesture(gesture, null, null);
    }
}
