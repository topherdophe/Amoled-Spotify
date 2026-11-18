package com.topherdophe.neon;

import android.graphics.Typeface;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONException;
import org.json.JSONObject;

/** Sleep timer, appearance, screen behaviour and the Spotify account. */
final class SettingsScreen extends BaseScreen {
    /** Page and colour-row scroll positions, restored after a colour change rebuilds the screen. */
    private final int scrollY, chipsX;

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

        ui.section(list, "Spotify account");
        TextView account = ui.text("Client ID: " + spotify.clientIdHint() + "\n"
                + (spotify.isConnected() ? "Connected" : "Not connected"), 14, false);
        list.addView(account, Ui.matchWrap());
        if (spotify.isConnected()) {
            list.addView(ui.action("CHECK ACCOUNT", 15, () -> spotify.request("GET", "/me", null, (code, body) -> {
                if (code != 200) { account.setText(spotify.explain(code, body)); return; }
                try {
                    JSONObject j = new JSONObject(body);
                    String name = Json.str(j, "display_name");
                    account.setText("Connected as " + (name.isEmpty() ? Json.str(j, "id") : name)
                            + "\nClient ID: " + spotify.clientIdHint());
                } catch (JSONException e) {
                    account.setText("Account response unreadable");
                }
            })));
        }
        list.addView(ui.action("CHANGE CLIENT ID", 15, () -> app.open(new SetupScreen())));
        if (spotify.isConnected()) {
            list.addView(ui.action("DISCONNECT", 15, () -> {
                app.forgetSession();
                app.status("Disconnected");
                app.open(new SettingsScreen());
            }));
        } else {
            list.addView(ui.action("CONNECT TO SPOTIFY", 15, app::login));
        }
        backButton();
    }

    @Override void onSessionChanged() { app.open(new SettingsScreen()); }
}
