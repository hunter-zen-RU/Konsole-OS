package com.termux.app.konsole;

import android.app.Notification;
import android.app.PendingIntent;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Optional NotificationListenerService capturing incoming app & message notifications
 * for Konsole Automation triggers AND the Konsole Custom Replacement Shade.
 */
public class KonsoleNotificationListenerService extends NotificationListenerService {

    public static class ShadeNotificationItem {
        public final String key;
        public final String packageName;
        public final String appName;
        public final String title;
        public final String text;
        public final long postTime;
        public final boolean clearable;
        public final PendingIntent contentIntent;

        public ShadeNotificationItem(String key, String packageName, String appName,
                                     String title, String text, long postTime,
                                     boolean clearable, PendingIntent contentIntent) {
            this.key = key;
            this.packageName = packageName;
            this.appName = appName;
            this.title = title;
            this.text = text;
            this.postTime = postTime;
            this.clearable = clearable;
            this.contentIntent = contentIntent;
        }
    }

    public interface NotificationsChangedListener {
        void onNotificationsChanged();
    }

    private static volatile KonsoleNotificationListenerService sInstance;
    private static final List<ShadeNotificationItem> sActiveItems = new CopyOnWriteArrayList<>();
    private static final List<NotificationsChangedListener> sListeners = new CopyOnWriteArrayList<>();

    public static boolean isConnected() {
        return sInstance != null;
    }

    public static void addListener(NotificationsChangedListener listener) {
        if (listener != null && !sListeners.contains(listener)) {
            sListeners.add(listener);
        }
    }

    public static void removeListener(NotificationsChangedListener listener) {
        sListeners.remove(listener);
    }

    public static List<ShadeNotificationItem> getActiveShadeNotifications() {
        return new ArrayList<>(sActiveItems);
    }

    public static void dismissNotification(String key) {
        KonsoleNotificationListenerService svc = sInstance;
        if (svc == null || key == null) return;
        try {
            svc.cancelNotification(key);
        } catch (Exception ignored) {
        }
    }

    public static void clearAllClearableNotifications() {
        KonsoleNotificationListenerService svc = sInstance;
        if (svc == null) return;
        try {
            svc.cancelAllNotifications();
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        sInstance = this;
        refreshActiveNotificationsSnapshot();
    }

    @Override
    public void onListenerDisconnected() {
        super.onListenerDisconnected();
        sInstance = null;
        sActiveItems.clear();
        notifyListeners();
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (sbn == null) return;
        refreshActiveNotificationsSnapshot();

        // Ignore our own notifications to prevent feedback loops in automation triggers
        if (getPackageName().equals(sbn.getPackageName())) return;

        Notification n = sbn.getNotification();
        if (n == null || n.extras == null) return;
        Bundle b = n.extras;

        CharSequence titleCs = b.getCharSequence(Notification.EXTRA_TITLE);
        CharSequence textCs = b.getCharSequence(Notification.EXTRA_TEXT);

        Map<String, String> extras = new HashMap<>();
        extras.put("notification_pkg", sbn.getPackageName() != null ? sbn.getPackageName() : "");
        extras.put("notification_title", titleCs != null ? titleCs.toString() : "");
        extras.put("notification_text", textCs != null ? textCs.toString() : "");

        KonsoleAutomationEngine.getInstance().dispatchTrigger(
                getApplicationContext(),
                KonsoleMacroRule.TriggerType.NOTIFICATION,
                extras);
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        refreshActiveNotificationsSnapshot();
    }

    private void refreshActiveNotificationsSnapshot() {
        try {
            StatusBarNotification[] active = getActiveNotifications();
            List<ShadeNotificationItem> next = new ArrayList<>();
            if (active != null) {
                PackageManager pm = getPackageManager();
                String selfPkg = getPackageName();
                for (StatusBarNotification sbn : active) {
                    if (sbn == null || sbn.getNotification() == null) continue;
                    String pkg = sbn.getPackageName() != null ? sbn.getPackageName() : "";
                    if (selfPkg.equals(pkg)) continue;

                    Notification n = sbn.getNotification();
                    Bundle b = n.extras != null ? n.extras : Bundle.EMPTY;
                    CharSequence titleCs = b.getCharSequence(Notification.EXTRA_TITLE);
                    CharSequence textCs = b.getCharSequence(Notification.EXTRA_TEXT);
                    if (textCs == null) textCs = b.getCharSequence(Notification.EXTRA_BIG_TEXT);

                    String title = titleCs != null ? titleCs.toString().trim() : "";
                    String text = textCs != null ? textCs.toString().trim() : "";
                    if (title.isEmpty() && text.isEmpty()) continue;

                    String appName = pkg;
                    try {
                        ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
                        appName = pm.getApplicationLabel(ai).toString();
                    } catch (Exception ignored) {
                    }

                    next.add(new ShadeNotificationItem(
                            sbn.getKey(),
                            pkg,
                            appName,
                            title.isEmpty() ? appName : title,
                            text,
                            sbn.getPostTime(),
                            sbn.isClearable(),
                            n.contentIntent));
                }
                Collections.sort(next, (a, b) -> Long.compare(b.postTime, a.postTime));
            }
            sActiveItems.clear();
            sActiveItems.addAll(next);
            notifyListeners();
        } catch (Exception ignored) {
        }
    }

    private static void notifyListeners() {
        for (NotificationsChangedListener l : sListeners) {
            try {
                l.onNotificationsChanged();
            } catch (Exception ignored) {
            }
        }
    }

    public static void openNotificationAccessSettings(Context context) {
        if (context == null) return;
        try {
            android.content.Intent intent = new android.content.Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS");
            if (!(context instanceof android.app.Activity)) {
                intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            }
            context.startActivity(intent);
        } catch (Exception ignored) {
        }
    }
}
