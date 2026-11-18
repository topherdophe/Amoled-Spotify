package com.topherdophe.neon;

import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** An album: play or shuffle it, tap a song to play from there, + for the song menu, hold to select. */
final class AlbumScreen extends BaseScreen {
    private final String id, uri, name;

    AlbumScreen(String id, String uri, String name) {
        this.id = id;
        this.uri = uri;
        this.name = name;
    }

    @Override void build() {
        heading(name);
        LinearLayout buttons = new LinearLayout(app);
        buttons.setGravity(Gravity.CENTER);
        TextView play = ui.action("▶  PLAY", 16, () -> playback.playContext(uri));
        play.setContentDescription("Play " + name);
        buttons.addView(play);
        TextView shuffle = ui.action("⇄  SHUFFLE", 16, () -> playback.shufflePlay(uri));
        shuffle.setContentDescription("Shuffle " + name);
        buttons.addView(shuffle);
        content.addView(buttons, Ui.matchWrap());
        LinearLayout list = scrollList();
        selection = new Selection(this);
        backButton();
        if (requireConnected(list)) page(list, "/albums/" + SpotifyClient.enc(id) + "/tracks?limit=50");
    }

    private void page(LinearLayout list, String path) {
        TextView loading = loading(list, "Loading…");
        spotify.request("GET", path, null, (code, body) -> {
            list.removeView(loading);
            if (code != 200) { note(list, spotify.explain(code, body)); return; }
            try {
                JSONObject page = new JSONObject(body);
                JSONArray items = page.getJSONArray("items");
                for (int i = 0; i < items.length(); i++) {
                    JSONObject t = items.optJSONObject(i);
                    if (t == null) continue;
                    String songUri = Json.str(t, "uri");
                    list.addView(selection.row(t, Json.joinArtists(t), () -> playback.playInContext(uri, songUri)), Ui.matchWrap());
                }
                String next = Json.str(page, "next");
                if (!next.isEmpty()) {
                    TextView more = ui.action("MORE", 16, null);
                    more.setContentDescription("More songs");
                    more.setOnClickListener(v -> { list.removeView(more); page(list, next); });
                    list.addView(more);
                }
            } catch (JSONException e) {
                note(list, "Couldn't read this album");
            }
        });
    }
}
