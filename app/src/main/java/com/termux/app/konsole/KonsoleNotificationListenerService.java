package com.termux.app.konsole;

import android.app.Notification;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import java.util.HashMap;
import java.util.Map;

/**
 * Optional NotificationListenerService capturing incoming app & message notifications
 * cleanly without restricted SMS permissions. Disabled until user grants Notification Access.
 */
public class KonsoleNotificationListenerService extends NotificationListenerService {

    private static volatile KonsoleNotificationListenerService sInstance;

    public static boolean isConnected() {
        return sInstance != null;
    }

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        sInstance = this;
    }

    @Override
    public void onListenerDisconnected() {
        super.onListenerDisconnected();
        sInstance = null;
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (sbn == null) return;
        // Ignore our own notifications to prevent feedback loops
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
}
