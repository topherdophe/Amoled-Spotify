package com.topherdophe.neon;

import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Your last 50 songs. */
final class RecentScreen extends BaseScreen {
    @Override BaseScreen back() { return returnTo != null ? returnTo : new PlaylistsScreen(); }

    @Override void build() {
        heading("Recently played");
        LinearLayout list = scrollList();
        selection = new Selection(this);
        backButton();
        if (!requireConnected(list)) return;
        TextView loading = loading(list, "Loading…");
        spotify.request("GET", "/me/player/recently-played?limit=50", null, (code, body) -> {
            list.removeView(loading);
            if (code != 200) { note(list, spotify.explain(code, body)); return; }
            try {
                JSONArray items = new JSONObject(body).getJSONArray("items");
                if (items.length() == 0) note(list, "Nothing played recently");
                for (int i = 0; i < items.length(); i++) {
                    JSONObject entry = items.optJSONObject(i);
                    JSONObject t = entry == null ? null : entry.optJSONObject("track");
                    if (t == null) continue;
                    String uri = Json.str(t, "uri");
                    list.addView(selection.row(t, Json.joinArtists(t), () -> playback.playTrack(uri)), Ui.matchWrap());
                }
            } catch (JSONException e) {
                note(list, "Couldn't read recently played");
            }
        });
    }
}
