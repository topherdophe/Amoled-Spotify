package com.topherdophe.neon;

import android.net.Uri;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** An artist: play or shuffle their music, their songs (from search) and their albums. */
final class ArtistScreen extends BaseScreen {
    private static final int PAGE = 10;
    private final String id, uri, name;

    ArtistScreen(String id, String uri, String name) {
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
        if (!requireConnected(list)) return;

        ui.section(list, "Songs");
        LinearLayout songs = ui.vertical();
        list.addView(songs, Ui.matchWrap());
        ui.section(list, "Albums");
        LinearLayout albums = ui.vertical();
        list.addView(albums, Ui.matchWrap());
        songs(songs, 0);
        albums(albums, "/artists/" + SpotifyClient.enc(id) + "/albums?include_groups=album,single&limit=" + PAGE);
    }

    private void songs(LinearLayout box, int offset) {
        TextView loading = loading(box, "Loading…");
        String q = "artist:\"" + name.replace("\"", "") + "\"";
        spotify.request("GET", "/search?type=track&limit=" + PAGE + "&offset=" + offset + "&q=" + Uri.encode(q), null, (code, body) -> {
            box.removeView(loading);
            if (code != 200) { note(box, spotify.explain(code, body)); return; }
            try {
                JSONObject tracks = new JSONObject(body).getJSONObject("tracks");
                JSONArray items = tracks.getJSONArray("items");
                if (items.length() == 0 && offset == 0) note(box, "No songs found");
                for (int i = 0; i < items.length(); i++) {
                    JSONObject t = items.optJSONObject(i);
                    if (t == null) continue;
                    String songUri = Json.str(t, "uri");
                    box.addView(selection.row(t, Json.joinArtists(t), () -> playback.playTrack(songUri)), Ui.matchWrap());
                }
                if (!tracks.isNull("next")) {
                    TextView more = ui.action("MORE SONGS", 15, null);
                    more.setOnClickListener(v -> { box.removeView(more); songs(box, offset + PAGE); });
                    box.addView(more);
                }
            } catch (JSONException e) {
                note(box, "Couldn't read songs");
            }
        });
    }

    private void albums(LinearLayout box, String path) {
        TextView loading = loading(box, "Loading…");
        spotify.request("GET", path, null, (code, body) -> {
            box.removeView(loading);
            if (code != 200) { note(box, spotify.explain(code, body)); return; }
            try {
                JSONObject page = new JSONObject(body);
                JSONArray items = page.getJSONArray("items");
                if (items.length() == 0 && box.getChildCount() == 0) note(box, "No albums");
                for (int i = 0; i < items.length(); i++) {
                    JSONObject a = items.optJSONObject(i);
                    if (a == null) continue;
                    String albumId = Json.str(a, "id"), albumUri = Json.str(a, "uri"), albumName = Json.str(a, "name");
                    String year = Json.str(a, "release_date");
                    if (year.length() > 4) year = year.substring(0, 4);
                    String kind = "single".equals(Json.str(a, "album_type")) ? "Single" : "Album";
                    box.addView(ui.listRow(albumName, kind + (year.isEmpty() ? "" : " · " + year), 17,
                            () -> app.open(new AlbumScreen(albumId, albumUri, albumName).from(this)), null), Ui.matchWrap());
                }
                String next = Json.str(page, "next");
                if (!next.isEmpty()) {
                    TextView more = ui.action("MORE ALBUMS", 15, null);
                    more.setOnClickListener(v -> { box.removeView(more); albums(box, next); });
                    box.addView(more);
                }
            } catch (JSONException e) {
                note(box, "Couldn't read albums");
            }
        });
    }
}
