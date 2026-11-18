package com.topherdophe.neon;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Sleep timer, playback device, appearance, clock, screen behaviour, battery and the account link. */
final class SettingsScreen extends BaseScreen {
    /** Page and colour-row scroll positions, restored after a colour change rebuilds the screen. */
    private final int scrollY, chipsX;
    private TextView batteryRow;

    SettingsScreen() { this(0, 0); }

    SettingsScreen(int scrollY, int chipsX) {
        this.scrollY = scrollY;
        this.chipsX = chipsX;
    }

    @Override void build() {
        heading("Settings");
        LinearLayout list = scrollList();
        View page = (View) list.getParent();
        AppSettings s = settings;

        ui.section(list, "Sleep timer");
        ui.setting(list, ui.choices("Sleep timer", AppSettings.SLEEP_LABELS, app.sleepChoice(), app::setSleep));

        String device = playback.hasPlayback && !playback.deviceName.isEmpty() ? playback.deviceName : "choose a device";
        ui.setting(list, ui.action("Play on: " + device + "  ›", 16, () -> app.open(new DevicesScreen(null).from(this))));

        TextView colourLabel = ui.label("Highlight colour");
        colourLabel.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        colourLabel.setPadding(0, ui.dp(20), 0, 0);
        list.addView(colourLabel, Ui.matchWrap());
        HorizontalScrollView[] colorRow = new HorizontalScrollView[1];
        HorizontalScrollView colors = ui.choices("Highlight colour", AppSettings.COLOR_NAMES, s.colorIndex, i -> {
            int y = page.getScrollY(), x = colorRow[0].getScrollX();
            s.colorIndex = i;
            s.save();
            app.rebuildShell(new SettingsScreen(y, x));
        });
        colorRow[0] = colors;
        if (scrollY > 0 || chipsX > 0) {
            // Restore positions before the first frame is drawn, so the page doesn't visibly jump.
            page.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
                @Override public boolean onPreDraw() {
                    page.getViewTreeObserver().removeOnPreDrawListener(this);
                    page.scrollTo(0, scrollY);
                    colors.scrollTo(chipsX, 0);
                    return true;
                }
            });
        }
        LinearLayout chips = (LinearLayout) colors.getChildAt(0);
        for (int i = 0; i < chips.getChildCount(); i++) {
            ui.tint((TextView) chips.getChildAt(i), AppSettings.COLORS[i][0], AppSettings.COLORS[i][1]);
        }
        ui.setting(list, colors);
        ui.setting(list, ui.switchRow("Show album art", s.showArt, on -> {
            s.showArt = on;
            s.save();
            if (on) playback.loadArt();
        }));

        ui.section(list, "Clock");
        list.addView(ui.label("Style"), Ui.matchWrap());
        ui.setting(list, ui.choices("Clock style", AppSettings.CLOCK_STYLES, s.clockStyle, i -> { s.clockStyle = i; s.save(); }));
        list.addView(ui.label("Size"), Ui.matchWrap());
        ui.setting(list, ui.choices("Clock size", AppSettings.CLOCK_SIZES, s.clockSize, i -> { s.clockSize = i; s.save(); }));
        list.addView(ui.label("Hours"), Ui.matchWrap());
        ui.setting(list, ui.choices("Clock hours", AppSettings.CLOCK_HOURS, s.clockHours, i -> { s.clockHours = i; s.save(); }));
        ui.setting(list, ui.switchRow("Show seconds", s.clockSeconds, on -> { s.clockSeconds = on; s.save(); }));
        ui.setting(list, ui.switchRow("Show date", s.clockDate, on -> { s.clockDate = on; s.save(); }));

        ui.section(list, "Screen");
        ui.setting(list, ui.switchRow("Keep screen awake", s.keepOn, on -> { s.keepOn = on; s.save(); app.applyKeepOn(); }));
        ui.setting(list, ui.switchRow("Show over lock screen", s.overLock, on -> { s.overLock = on; s.save(); app.applyLockScreen(); }));
        list.addView(ui.label("When idle"), Ui.matchWrap());
        ui.setting(list, ui.choices("When idle", AppSettings.IDLE_MODES, s.idleMode, i -> { s.idleMode = i; s.save(); }));
        list.addView(ui.label("Idle after"), Ui.matchWrap());
        ui.setting(list, ui.choices("Idle after", AppSettings.IDLE_LABELS, s.idleChoice, i -> { s.idleChoice = i; s.save(); }));
        ui.setting(list, ui.slider("Normal brightness", 0, s.brightNormal, v -> v == 0 ? "system" : v + "%",
                v -> { s.brightNormal = v; app.applyBrightness(false); }, s::save));
        // Dragging previews the dimmed level; letting go returns to normal.
        ui.setting(list, ui.slider("Dimmed brightness", 1, s.brightDim, v -> v + "%",
                v -> { s.brightDim = v; app.applyBrightness(true); }, () -> { s.save(); app.applyBrightness(false); }));
        ui.setting(list, ui.switchRow("Pixel shift (burn-in protection)", s.pixelShift, on -> {
            s.pixelShift = on;
            s.save();
            app.applyShift();
        }));

        batteryRow = ui.action("", 16, this::openBatterySettings);
        updateBattery();
        ui.setting(list, batteryRow);

        ui.setting(list, ui.action("Account  ›", 17, () -> app.open(new AccountScreen())));
        backButton();
    }

    /** Coming back from Android's settings: show the new battery state. */
    @Override void onResume() { updateBattery(); }

    private void updateBattery() {
        if (batteryRow == null) return;
        PowerManager pm = app.getSystemService(PowerManager.class);
        boolean free = pm != null && pm.isIgnoringBatteryOptimizations(app.getPackageName());
        batteryRow.setText(free ? "Background activity: allowed" : "Background activity: limited  ›");
        batteryRow.setAlpha(free ? .6f : 1f);
        batteryRow.setContentDescription(free ? "Background activity allowed"
                : "Background activity limited. Opens app settings: choose Battery, then allow background activity.");
    }

    /** Opens this app's page in Android settings, where Battery → Unrestricted / Allow background activity lives. */
    private void openBatterySettings() {
        app.status("Choose Battery, then Unrestricted (or Allow background activity)");
        try {
            app.startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", app.getPackageName(), null)));
        } catch (ActivityNotFoundException e) {
            app.error("Couldn't open the app settings");
        }
    }
}
