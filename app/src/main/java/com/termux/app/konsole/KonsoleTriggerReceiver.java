package com.termux.app.konsole;

import android.bluetooth.BluetoothAdapter;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.wifi.WifiManager;
import android.util.Log;

import java.util.HashMap;
import java.util.Map;

/**
 * Dynamic BroadcastReceiver registered inside the persistent foreground TermuxService
 * to detect hardware, power, connectivity, screen, Bluetooth, and headset events in real time.
 */
public class KonsoleTriggerReceiver extends BroadcastReceiver {

    private static final String TAG = "KonsoleTriggerReceiver";
    private boolean mRegistered = false;
    private Boolean mLastWifiConnected = null;

    public synchronized void register(Context context) {
        if (context == null || mRegistered) return;
        try {
            IntentFilter filter = new IntentFilter();
            filter.addAction(Intent.ACTION_BATTERY_LOW);
            filter.addAction(Intent.ACTION_BATTERY_OKAY);
            filter.addAction(Intent.ACTION_POWER_CONNECTED);
            filter.addAction(Intent.ACTION_POWER_DISCONNECTED);
            filter.addAction(WifiManager.WIFI_STATE_CHANGED_ACTION);
            filter.addAction(ConnectivityManager.CONNECTIVITY_ACTION);
            filter.addAction(Intent.ACTION_SCREEN_ON);
            filter.addAction(Intent.ACTION_SCREEN_OFF);
            filter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
            filter.addAction(Intent.ACTION_HEADSET_PLUG);

            context.getApplicationContext().registerReceiver(this, filter);
            mRegistered = true;
            mLastWifiConnected = KonsoleConstraintEvaluator.captureSnapshot(context).wifiConnected;
        } catch (Exception e) {
            Log.w(TAG, "Failed registering trigger receiver: " + e.getMessage());
        }
    }

    public synchronized void unregister(Context context) {
        if (context == null || !mRegistered) return;
        try {
            context.getApplicationContext().unregisterReceiver(this);
        } catch (Exception ignored) {
        } finally {
            mRegistered = false;
        }
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null || intent.getAction() == null) return;
        String action = intent.getAction();
        KonsoleAutomationEngine engine = KonsoleAutomationEngine.getInstance();
        Map<String, String> extras = new HashMap<>();

        switch (action) {
            case Intent.ACTION_BATTERY_LOW:
                engine.dispatchTrigger(context, KonsoleMacroRule.TriggerType.BATTERY_LOW, extras);
                break;

            case Intent.ACTION_BATTERY_OKAY:
                engine.dispatchTrigger(context, KonsoleMacroRule.TriggerType.BATTERY_OKAY, extras);
                break;

            case Intent.ACTION_POWER_CONNECTED:
                engine.dispatchTrigger(context, KonsoleMacroRule.TriggerType.POWER_CONNECTED, extras);
                break;

            case Intent.ACTION_POWER_DISCONNECTED:
                engine.dispatchTrigger(context, KonsoleMacroRule.TriggerType.POWER_DISCONNECTED, extras);
                break;

            case Intent.ACTION_SCREEN_ON:
                engine.dispatchTrigger(context, KonsoleMacroRule.TriggerType.SCREEN_ON, extras);
                break;

            case Intent.ACTION_SCREEN_OFF:
                engine.dispatchTrigger(context, KonsoleMacroRule.TriggerType.SCREEN_OFF, extras);
                break;

            case BluetoothAdapter.ACTION_STATE_CHANGED: {
                int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR);
                if (state == BluetoothAdapter.STATE_ON) {
                    engine.dispatchTrigger(context, KonsoleMacroRule.TriggerType.BLUETOOTH_ON, extras);
                }
                break;
            }

            case Intent.ACTION_HEADSET_PLUG: {
                int state = intent.getIntExtra("state", -1);
                if (state == 1) {
                    extras.put("headset_name", String.valueOf(intent.getStringExtra("name")));
                    engine.dispatchTrigger(context, KonsoleMacroRule.TriggerType.HEADSET_PLUGGED, extras);
                }
                break;
            }

            case WifiManager.WIFI_STATE_CHANGED_ACTION:
            case ConnectivityManager.CONNECTIVITY_ACTION: {
                boolean nowWifi = KonsoleConstraintEvaluator.captureSnapshot(context).wifiConnected;
                if (mLastWifiConnected == null || nowWifi != mLastWifiConnected) {
                    mLastWifiConnected = nowWifi;
                    engine.dispatchTrigger(
                            context,
                            nowWifi ? KonsoleMacroRule.TriggerType.WIFI_CONNECTED
                                    : KonsoleMacroRule.TriggerType.WIFI_DISCONNECTED,
                            extras);
                }
                break;
            }
        }
    }
}
