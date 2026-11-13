package com.termux.app.konsole;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Receives exact/inexact AlarmManager broadcasts for scheduled Cron macros.
 */
public class KonsoleCronAlarmReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null) return;
        String macroId = intent.getStringExtra(KonsoleAutomationEngine.EXTRA_MACRO_ID);
        KonsoleAutomationEngine.getInstance().onCronAlarmFired(context, macroId);
    }
}
