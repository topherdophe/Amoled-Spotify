package com.topherdophe.neon;

import android.content.Context;
import android.content.SharedPreferences;

/** User preferences, plus the option lists shown on the Settings screen. */
final class AppSettings {
    // The old fixed highlight colours (solid, then gradients), only used to carry over an old choice.
    private static final int[][] OLD_COLORS = {
            {0xFF00FF00, 0xFF00FF00}, {0xFF00E5FF, 0xFF00E5FF}, {0xFFFF00FF, 0xFFFF00FF}, {0xFFFFB000, 0xFFFFB000},
            {0xFFFF1744, 0xFFFF1744}, {0xFFB388FF, 0xFFB388FF}, {0xFFFFFFFF, 0xFFFFFFFF},
            {0xFF00FF00, 0xFF00E5FF}, {0xFFFF00FF, 0xFF00E5FF}, {0xFFFFB000, 0xFFFF1744}, {0xFF2979FF, 0xFF00E5FF}};

    static final int IDLE_NOTHING = 0, IDLE_DIM = 1, IDLE_MINIMAL = 2, IDLE_MINIMAL_DIM = 3;
    static final String[] IDLE_MODES = {"Nothing", "Dim", "Clock & song", "Clock & song + dim"};
    static final int[] IDLE_SECONDS = {30, 60, 120, 300};
    static final String[] IDLE_LABELS = {"30 sec", "1 min", "2 min", "5 min"};

    /** Minutes; -1 means "after the current song". */
    static final int[] SLEEP_MINUTES = {0, 15, 30, 45, 60, 90, -1};
    static final String[] SLEEP_LABELS = {"Off", "15 min", "30 min", "45 min", "60 min", "90 min", "End of song"};

    static final String[] CLOCK_STYLES = {"Thin", "Regular", "Bold", "Mono"};
    static final String[] CLOCK_SIZES = {"Small", "Medium", "Large"};
    static final float[] CLOCK_SCALE = {.75f, 1f, 1.3f};
    static final String[] CLOCK_HOURS = {"Phone setting", "12-hour", "24-hour"};

    private final SharedPreferences prefs;
    /** Highlight colour: color1 alone (one tone), or a gradient from color1 to color2. */
    int color1, color2;
    boolean gradient;
    int idleMode, idleChoice, brightNormal, brightDim;
    boolean keepOn, showArt, pixelShift, overLock, lockIdle;
    int clockStyle, clockSize, clockHours;
    boolean clockSeconds, clockDate;

    AppSettings(Context context) {
        prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE);
        keepOn = prefs.getBoolean("keepOn", true);
        overLock = prefs.getBoolean("overLock", true);
        lockIdle = prefs.getBoolean("lockIdle", false);
        showArt = prefs.getBoolean("showArt", false);
        pixelShift = prefs.getBoolean("pixelShift", true);
        idleMode = clamp(prefs.getInt("idleMode", IDLE_MINIMAL), 0, IDLE_MODES.length - 1);
        idleChoice = clamp(prefs.getInt("idleChoice", 0), 0, IDLE_SECONDS.length - 1);
        brightNormal = clamp(prefs.getInt("brightNormal", 0), 0, 100);
        brightDim = clamp(prefs.getInt("brightDim", 5), 1, 100);
        int[] old = OLD_COLORS[clamp(prefs.getInt("color", 0), 0, OLD_COLORS.length - 1)];
        color1 = prefs.getInt("color1", old[0]);
        color2 = prefs.getInt("color2", old[0] == old[1] ? 0xFF00E5FF : old[1]);
        gradient = prefs.getBoolean("gradient", old[0] != old[1]);
        clockStyle = clamp(prefs.getInt("clockStyle", 0), 0, CLOCK_STYLES.length - 1);
        clockSize = clamp(prefs.getInt("clockSize", 1), 0, CLOCK_SIZES.length - 1);
        clockHours = clamp(prefs.getInt("clockHours", 0), 0, CLOCK_HOURS.length - 1);
        clockSeconds = prefs.getBoolean("clockSeconds", false);
        clockDate = prefs.getBoolean("clockDate", false);
    }

    void save() {
        prefs.edit()
                .putBoolean("keepOn", keepOn).putBoolean("overLock", overLock).putBoolean("lockIdle", lockIdle)
                .putBoolean("showArt", showArt).putBoolean("pixelShift", pixelShift)
                .putInt("idleMode", idleMode).putInt("idleChoice", idleChoice)
                .putInt("brightNormal", brightNormal).putInt("brightDim", brightDim)
                .putInt("color1", color1).putInt("color2", color2).putBoolean("gradient", gradient)
                .putInt("clockStyle", clockStyle).putInt("clockSize", clockSize).putInt("clockHours", clockHours)
                .putBoolean("clockSeconds", clockSeconds).putBoolean("clockDate", clockDate)
                .apply();
    }

    int accent() { return color1; }
    int accent2() { return gradient ? color2 : color1; }

    boolean dimsWhenIdle() { return idleMode == IDLE_DIM || idleMode == IDLE_MINIMAL_DIM; }
    boolean minimalWhenIdle() { return idleMode == IDLE_MINIMAL || idleMode == IDLE_MINIMAL_DIM; }
    long idleAfterMs() { return IDLE_SECONDS[idleChoice] * 1000L; }

    String lastDevice() { return prefs.getString("lastDevice", ""); }

    void setLastDevice(String id) {
        if (!id.isEmpty() && !id.equals(lastDevice())) prefs.edit().putString("lastDevice", id).apply();
    }

    static int clamp(int v, int min, int max) { return Math.max(min, Math.min(max, v)); }
}
