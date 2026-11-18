package com.topherdophe.neon;

import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Collections;

/**
 * One playlist: play / shuffle-play it, tap a song to play from there, + for the song menu, hold to select.
 * In your own playlists, swipe a song away to remove it (after a confirmation).
 */
final class PlaylistScreen extends BaseScreen {
    private final String id, uri, name;

    PlaylistScreen(String id, String uri, String name) {
        this.id = id;
        this.uri = uri;
        this.name = name;
    }

    @Override BaseScreen back() { return returnTo != null ? returnTo : new PlaylistsScreen(); }

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
        if (requireConnected(list)) page(list, "/playlists/" + SpotifyClient.enc(id) + "/items?limit=50");
    }

    private void page(LinearLayout list, String path) {
        TextView loading = loading(list, "Loading…");
        spotify.request("GET", path, null, (code, body) -> {
            list.removeView(loading);
            if (code == 403) {
                note(list, "Spotify only lets this app list the songs in playlists you own or collaborate on. "
                        + "You can still play or shuffle this one.");
                return;
            }
            if (code != 200) { note(list, spotify.explain(code, body)); return; }
            // Since Feb 2026 Spotify only lists songs of playlists you own or collaborate on,
            // so if we got here you can edit this one.
            selection.setRemover((uris, done) -> app.library.removeSongs(id, uris, done));
            try {
                JSONObject page = new JSONObject(body);
                JSONArray items = page.getJSONArray("items");
                if (items.length() == 0 && list.getChildCount() == 0) note(list, "This playlist is empty");
                for (int i = 0; i < items.length(); i++) {
                    JSONObject entry = items.optJSONObject(i);
                    if (entry == null) continue;
                    // Since Feb 2026 the song is under "item"; older responses used "track".
                    JSONObject t = entry.optJSONObject("item");
                    if (t == null) t = entry.optJSONObject("track");
                    if (t == null) continue;
                    String songUri = Json.str(t, "uri");
                    if (songUri.isEmpty() || songUri.startsWith("spotify:local:")) continue;
                    LinearLayout row = selection.row(t, Json.subtitle(t), () -> playback.playInContext(uri, songUri));
                    String songName = Json.str(t, "name");
                    row.setOnTouchListener(new SwipeDismiss(app, () -> confirmRemove(list, row, songUri, songName)));
                    list.addView(row, Ui.matchWrap());
                }
                String next = Json.str(page, "next");
                if (!next.isEmpty()) {
                    TextView more = ui.action("MORE", 16, null);
                    more.setContentDescription("More songs");
                    more.setOnClickListener(v -> { list.removeView(more); page(list, next); });
                    list.addView(more);
                }
            } catch (JSONException e) {
                note(list, "Couldn't read this playlist");
            }
        });
    }

    /** After a swipe: ask first, and put the song back if the answer is no. */
    private void confirmRemove(LinearLayout list, View row, String songUri, String songName) {
        Runnable putBack = () -> row.animate().translationX(0).alpha(1f).setDuration(180).start();
        SongMenu.confirm(app, "Remove " + songName + " from " + name + "?", "REMOVE", () ->
                app.library.removeSongs(id, Collections.singletonList(songUri), (ok, err) -> {
                    if (ok == null) { putBack.run(); app.error(err); return; }
                    list.removeView(row);
                    app.status("Removed " + songName);
                }), putBack);
    }
}
