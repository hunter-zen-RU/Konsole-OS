package com.termux.app.konsole;

import android.app.admin.DeviceAdminReceiver;
import android.content.Context;
import android.content.Intent;
import android.widget.Toast;

/**
 * Device Admin Receiver for Konsole OS.
 * Enables True Kiosk Lockdown mode when elevated to Device Owner via ADB.
 */
public class KonsoleDeviceAdminReceiver extends DeviceAdminReceiver {

    @Override
    public void onEnabled(Context context, Intent intent) {
        super.onEnabled(context, intent);
        Toast.makeText(context, "Konsole OS Device Admin Enabled", Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onDisabled(Context context, Intent intent) {
        super.onDisabled(context, intent);
        Toast.makeText(context, "Konsole OS Device Admin Disabled", Toast.LENGTH_SHORT).show();
    }
}
