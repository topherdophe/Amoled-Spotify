package com.topherdophe.neon;

import android.app.admin.DeviceAdminReceiver;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;

/**
 * Device admin with one power only: locking the screen. "Lock phone when idle" uses it to lock
 * the phone and then light the screen again with the app showing over the lock screen.
 */
public class LockAdmin extends DeviceAdminReceiver {
    static ComponentName name(Context c) { return new ComponentName(c, LockAdmin.class); }

    static boolean isActive(Context c) {
        DevicePolicyManager dpm = c.getSystemService(DevicePolicyManager.class);
        return dpm != null && dpm.isAdminActive(name(c));
    }

    /** Turning the setting off removes the admin, so the app can be uninstalled normally. */
    static void remove(Context c) {
        DevicePolicyManager dpm = c.getSystemService(DevicePolicyManager.class);
        if (dpm != null && dpm.isAdminActive(name(c))) dpm.removeActiveAdmin(name(c));
    }
}
