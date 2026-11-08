package com.tpn.adbautoenable;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;

import java.io.BufferedReader;
import java.io.FileReader;

/**
 * Boot autostart is an OEM policy, not an Android permission.
 *
 * MIUI/HyperOS, EMUI, ColorOS and FuntouchOS/OriginOS refuse to launch a
 * third-party process for BOOT_COMPLETED unless the user enables the app in
 * the vendor's "Autostart" screen. There is no public API to read that switch
 * and no non-root way to write it, so this helper does only two things:
 * detect whether a boot broadcast actually reached the app, and take the
 * user to the vendor screen in one tap. Everything here uses public APIs.
 */
final class AutostartHelper {
    private static final String TAG = "ADBAutoEnable";
    private static final String PREFS_NAME = "ADBAutoEnablePrefs";
    private static final String KEY_BOOT_ID = "last_boot_receiver_boot_id";
    private static final String KEY_BOOT_ACTION = "last_boot_receiver_action";
    private static final String BOOT_ID_PATH = "/proc/sys/kernel/random/boot_id";

    // Some OEMs postpone BOOT_COMPLETED until the first unlock. Do not claim
    // autostart is broken while the device has only just come up.
    private static final long BOOT_GRACE_MS = 5L * 60L * 1000L;

    static final int BOOT_OK = 0;
    static final int BOOT_PENDING = 1;
    static final int BOOT_MISSING = 2;

    // Vendor autostart screens, ordered by the devices this project targets.
    // They are tried directly rather than filtered with resolveActivity():
    // some ROMs hide these activities from package resolution but still let
    // an explicit Intent start them.
    private static final String[] AUTOSTART_ACTIVITIES = {
            // Xiaomi / Redmi / POCO (MIUI, HyperOS)
            "com.miui.securitycenter/com.miui.permcenter.autostart.AutoStartManagementActivity",
            // vivo / iQOO (FuntouchOS, OriginOS)
            "com.vivo.permissionmanager/com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
            "com.iqoo.secure/com.iqoo.secure.ui.phoneoptimize.BgStartUpManager",
            // Huawei / Honor (EMUI, MagicOS)
            "com.huawei.systemmanager/com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
            "com.huawei.systemmanager/com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity",
            // OPPO / OnePlus / realme (ColorOS, OxygenOS)
            "com.coloros.safecenter/com.coloros.safecenter.permission.startup.StartupAppListActivity",
            "com.oppo.safe/com.oppo.safe.permission.startup.StartupAppListActivity",
            "com.oneplus.security/com.oneplus.security.autostart.AutoStartManagementActivity",
            // Samsung / Meizu
            "com.samsung.android.lool/com.samsung.android.sm.ui.battery.BatteryActivity",
            "com.meizu.safe/com.meizu.safe.permission.SmartBGActivity"
    };

    private AutostartHelper() {
    }

    /** Called by BootReceiver so the UI can prove a boot broadcast arrived. */
    static void recordBootBroadcast(Context context, String action) {
        String bootId = readBootId();
        SharedPreferences.Editor editor = NetworkUtils
                .getDeviceProtectedPrefs(context, PREFS_NAME).edit()
                .putString(KEY_BOOT_ID, bootId == null ? "" : bootId)
                .putString(KEY_BOOT_ACTION, action == null ? "" : action);
        editor.apply();
        Log.i(TAG, "BootReceiver reached for " + action + " at boot_id=" + bootId);
    }

    /**
     * Compares the boot_id recorded by BootReceiver with the current one.
     * Device-protected storage is used because LOCKED_BOOT_COMPLETED can
     * arrive before the user unlocks the device.
     */
    static int getBootState(Context context) {
        String current = readBootId();
        if (current == null || current.isEmpty()) {
            return BOOT_PENDING;
        }
        SharedPreferences prefs = NetworkUtils.getDeviceProtectedPrefs(context, PREFS_NAME);
        if (!prefs.contains(KEY_BOOT_ID)) {
            // Fresh install: no boot broadcast has ever been recorded. Do not
            // warn about a blocked autostart switch until the device has
            // actually been rebooted with this installation.
            return BOOT_PENDING;
        }
        String recorded = prefs.getString(KEY_BOOT_ID, "");
        if (current.equals(recorded)) {
            return BOOT_OK;
        }
        return SystemClock.elapsedRealtime() < BOOT_GRACE_MS ? BOOT_PENDING : BOOT_MISSING;
    }

    static String getBootStateText(Context context) {
        switch (getBootState(context)) {
            case BOOT_OK:
                return "ok";
            case BOOT_MISSING:
                return "missing";
            default:
                return "pending";
        }
    }

    /** Returns the vendor screen that opened, or null if none could be opened. */
    static String openAutostartSettings(Context context) {
        for (String candidate : AUTOSTART_ACTIVITIES) {
            ComponentName component = ComponentName.unflattenFromString(candidate);
            if (component == null) {
                continue;
            }
            Intent intent = new Intent();
            intent.setComponent(component);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                context.startActivity(intent);
                Log.i(TAG, "Opened vendor autostart screen: " + candidate);
                return candidate;
            } catch (Exception ignored) {
                // Not present (or not exported) on this ROM; try the next one.
            }
        }
        try {
            Intent fallback = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + context.getPackageName()));
            fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(fallback);
            Log.i(TAG, "No vendor autostart screen found; opened app details");
            return "fallback";
        } catch (Exception e) {
            Log.e(TAG, "Could not open any autostart settings screen", e);
            return null;
        }
    }

    static void openBatteryOptimizationSettings(Context context) {
        try {
            Intent intent = new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        } catch (Exception e) {
            Log.w(TAG, "Battery optimization settings unavailable", e);
        }
    }

    static boolean isIgnoringBatteryOptimizations(Context context) {
        try {
            PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            return pm != null && pm.isIgnoringBatteryOptimizations(context.getPackageName());
        } catch (Exception e) {
            return false;
        }
    }

    private static String readBootId() {
        try (BufferedReader reader = new BufferedReader(new FileReader(BOOT_ID_PATH))) {
            String line = reader.readLine();
            return line == null ? null : line.trim();
        } catch (Exception e) {
            return null;
        }
    }
}
