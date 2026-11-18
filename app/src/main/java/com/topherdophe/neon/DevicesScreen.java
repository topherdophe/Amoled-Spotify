package com.topherdophe.neon;

import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

import java.util.List;
import java.util.Locale;

/** Choose which Spotify device plays. */
final class DevicesScreen extends BaseScreen {
    private final String note;

    DevicesScreen(String note) { this.note = note; }

    @Override void build() {
        heading("Play on");
        if (note != null) content.addView(ui.text(note, 15, false), Ui.matchWrap());
        LinearLayout list = scrollList();
        content.addView(ui.action("OPEN SPOTIFY ON THIS PHONE", 15, app::openSpotifyApp));
        content.addView(ui.action("REFRESH", 15, () -> app.open(new DevicesScreen(null).from(returnTo))));
        backButton();
        if (!requireConnected(list)) return;
        TextView loading = loading(list, "Loading…");
        spotify.request("GET", "/me/player/devices", null, (code, body) -> {
            list.removeView(loading);
            if (code != 200) { note(list, spotify.explain(code, body)); return; }
            List<JSONObject> devices = Json.parseDevices(body);
            if (devices.isEmpty())
                note(list, "No devices found.\nOpen Spotify on this phone, a computer or a speaker, then tap REFRESH.");
            for (JSONObject d : devices) {
                String id = Json.str(d, "id"), name = Json.str(d, "name");
                boolean active = d.optBoolean("is_active"), restricted = d.optBoolean("is_restricted");
                String type = Json.str(d, "type").toLowerCase(Locale.ROOT);
                String label = (active ? "● " : "") + name + "\n" + type + (restricted ? " · can't be controlled" : "");
                TextView item = ui.action(label, 18, () -> {
                    app.status("Switching to " + name + "…");
                    playback.transfer(id, playback.isPlaying, app::showPlayer);
                });
                item.setContentDescription(name + ", " + type + (active ? ", playing now" : "")
                        + (restricted ? ", can't be controlled" : ""));
                if (restricted || id.isEmpty()) { item.setEnabled(false); item.setAlpha(.4f); }
                list.addView(item, Ui.matchWrap());
            }
        });
    }
}
