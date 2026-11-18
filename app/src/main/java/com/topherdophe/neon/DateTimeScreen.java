package com.topherdophe.neon;

import android.widget.LinearLayout;

/** Clock and date options for the player screen. */
final class DateTimeScreen extends BaseScreen {
    @Override BaseScreen back() { return new SettingsScreen(); }

    @Override void build() {
        heading("Date and time");
        LinearLayout list = scrollList();
        AppSettings s = settings;

        list.addView(ui.label("Clock style"), Ui.matchWrap());
        ui.setting(list, ui.choices("Clock style", AppSettings.CLOCK_STYLES, s.clockStyle, i -> { s.clockStyle = i; s.save(); }));
        list.addView(ui.label("Clock size"), Ui.matchWrap());
        ui.setting(list, ui.choices("Clock size", AppSettings.CLOCK_SIZES, s.clockSize, i -> { s.clockSize = i; s.save(); }));
        list.addView(ui.label("Hours"), Ui.matchWrap());
        ui.setting(list, ui.choices("Clock hours", AppSettings.CLOCK_HOURS, s.clockHours, i -> { s.clockHours = i; s.save(); }));
        ui.setting(list, ui.switchRow("Show seconds", s.clockSeconds, on -> { s.clockSeconds = on; s.save(); }));
        ui.setting(list, ui.switchRow("Show date", s.clockDate, on -> { s.clockDate = on; s.save(); }));
        backButton();
    }
}
