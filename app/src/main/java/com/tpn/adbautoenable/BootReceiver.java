package com.tpn.adbautoenable;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

public class BootReceiver extends BroadcastReceiver {
    private static final String TAG = "ADBAutoEnable";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        Log.i(TAG, "Received broadcast: " + action);

        if (Intent.ACTION_BOOT_COMPLETED.equals(action)) {
            // CE-backed ADB keys are usable only after the user has unlocked.
            Log.i(TAG, "Boot completed; starting full ADB recovery");
            startServiceNow(context, true);
        } else if (Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action)) {
            // Start the settings-only recovery while credential-encrypted
            // storage is still unavailable. Full ADB recovery runs later.
            Log.i(TAG, "Locked boot completed; starting wireless debugging recovery");
            startServiceNow(context, false);
        } else {
            Log.i(TAG, "Ignoring broadcast: " + action);
        }
    }

    private void startServiceNow(Context context, boolean fullConfiguration) {
        Intent serviceIntent = new Intent(context, AdbConfigService.class);
        serviceIntent.putExtra("boot_config", fullConfiguration);
        context.startForegroundService(serviceIntent);
    }
}