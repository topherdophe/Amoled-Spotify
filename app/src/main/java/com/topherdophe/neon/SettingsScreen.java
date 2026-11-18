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

/** Sleep timer, playback device, appearance, date and time link, screen behaviour, battery and the account link. */
final class SettingsScreen extends BaseScreen {
    private static final String[] COLOUR_MODES = {"One tone", "Gradient"};

    /** Page scroll position, restored after a colour change rebuilds the screen. */
    private final int scrollY;
    private TextView batteryRow;

    SettingsScreen() { this(0); }

    SettingsScreen(int scrollY) { this.scrollY = scrollY; }

    @Override void build() {
        heading("Settings");
        LinearLayout list = scrollList();
        View page = (View) list.getParent();
        AppSettings s = settings;

        // Sleep timer: one row; the time limits only appear after tapping it.
        LinearLayout sleep = ui.vertical();
        TextView sleepRow = ui.action(sleepLabel(), 16, null);
        sleep.addView(sleepRow, Ui.matchWrap());
        View[] sleepChoices = new View[1];
        sleepChoices[0] = ui.choices("Sleep timer", AppSettings.SLEEP_LABELS, app.sleepChoice(), i -> {
            app.setSleep(i);
            sleepRow.setText(sleepLabel());
            sleepChoices[0].setVisibility(View.GONE);
        });
        sleepChoices[0].setVisibility(View.GONE);
        sleep.addView(sleepChoices[0], Ui.matchWrap());
        sleepRow.setOnClickListener(v -> sleepChoices[0].setVisibility(
                sleepChoices[0].getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE));
        ui.setting(list, sleep);

        String device = playback.hasPlayback && !playback.deviceName.isEmpty() ? playback.deviceName : "choose a device";
        ui.setting(list, ui.action("Play on: " + device + "  ›", 16, () -> app.open(new DevicesScreen(null).from(this))));

        TextView colourLabel = ui.label("Highlight colour");
        colourLabel.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        colourLabel.setPadding(0, ui.dp(20), 0, 0);
        list.addView(colourLabel, Ui.matchWrap());
        HorizontalScrollView colors = ui.choices("Highlight colour", COLOUR_MODES, s.gradient ? 1 : 0, i ->
                ColourPicker.show(app, i == 1, s.color1, s.color2, (c1, c2) -> {
                    s.color1 = c1;
                    if (i == 1) s.color2 = c2;
                    s.gradient = i == 1;
                    s.save();
                    app.rebuildShell(new SettingsScreen(page.getScrollY()));
                }, () -> app.open(new SettingsScreen(page.getScrollY()))));
        LinearLayout chips = (LinearLayout) colors.getChildAt(0);
        ui.tint((TextView) chips.getChildAt(0), s.color1, s.color1);
        ui.tint((TextView) chips.getChildAt(1), s.color1, s.color2);
        ui.setting(list, colors);
        if (scrollY > 0) {
            // Restore the position before the first frame is drawn, so the page doesn't visibly jump.
            page.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
                @Override public boolean onPreDraw() {
                    page.getViewTreeObserver().removeOnPreDrawListener(this);
                    page.scrollTo(0, scrollY);
                    return true;
                }
            });
        }
        ui.setting(list, ui.switchRow("Show album art", s.showArt, on -> {
            s.showArt = on;
            s.save();
            if (on) playback.loadArt();
        }));

        ui.setting(list, ui.action("Date and time  ›", 16, () -> app.open(new DateTimeScreen())));

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

    private String sleepLabel() {
        return "Sleep timer: " + AppSettings.SLEEP_LABELS[app.sleepChoice()] + "  ›";
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
