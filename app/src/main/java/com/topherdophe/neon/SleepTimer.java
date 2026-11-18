package com.topherdophe.neon;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

/**
 * Sleep timer backed by AlarmManager, so it fires on time even if the phone is asleep or the app
 * is closed. When it fires, {@link SleepReceiver} pauses Spotify.
 */
final class SleepTimer {
    private static final String PREFS = "sleep_timer";

    private SleepTimer() { }

    /** Starts the timer. Returns true if an exact alarm was set, false if Android only allowed an approximate one. */
    static boolean start(Context context, int minutes, int choice) {
        long at = System.currentTimeMillis() + minutes * 60_000L;
        prefs(context).edit().putLong("at", at).putInt("choice", choice).apply();
        AlarmManager am = context.getSystemService(AlarmManager.class);
        if (am == null) return false;
        PendingIntent pi = pendingIntent(context);
        try {
            if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
                return true;
            }
        } catch (SecurityException ignored) {
            // Exact alarms not permitted: fall through to an approximate one.
        }
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
        return false;
    }

    static void cancel(Context context) {
        AlarmManager am = context.getSystemService(AlarmManager.class);
        if (am != null) am.cancel(pendingIntent(context));
        clear(context);
    }

    /** Called when the alarm has fired. */
    static void clear(Context context) {
        prefs(context).edit().remove("at").remove("choice").apply();
    }

    /** When the timer will fire (wall-clock ms), or 0 if no timer is set. */
    static long endTime(Context context) {
        long at = prefs(context).getLong("at", 0);
        // An alarm that should have fired long ago was lost (e.g. the phone restarted): forget it.
        if (at > 0 && at < System.currentTimeMillis() - 5 * 60_000L) { clear(context); return 0; }
        return at;
    }

    /** Which option on the Settings screen started the timer. */
    static int choice(Context context) {
        return prefs(context).getInt("choice", 0);
    }

    private static PendingIntent pendingIntent(Context context) {
        Intent intent = new Intent(context, SleepReceiver.class);
        return PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
