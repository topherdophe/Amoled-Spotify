package com.topherdophe.neon;

import android.net.Uri;
import android.view.Gravity;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Search songs, artists, albums or playlists. Songs: tap to play, + for the song menu, hold to select. */
final class SearchScreen extends BaseScreen {
    private static final int PAGE = 10; // Spotify's Development Mode search limit since Feb 2026
    private static final String[] TYPES = {"track", "artist", "album", "playlist"};
    private static final String[] TYPE_LABELS = {"Songs", "Artists", "Albums", "Playlists"};

    /** Kept between visits so going back to search shows the last results. */
    private static String lastQuery = "";
    private static int lastType = 0;

    /** Opens search and runs it (used by voice). */
    static void openWith(MainActivity app, String query) {
        lastQuery = query;
        lastType = 0;
        app.open(new SearchScreen());
    }

    private LinearLayout list;

    @Override void build() {
        heading("Search");
        LinearLayout box = new LinearLayout(app);
        box.setGravity(Gravity.CENTER_VERTICAL);
        EditText q = ui.input("Song, artist, album or playlist");
        q.setText(lastQuery);
        q.setContentDescription("Search");
        q.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        box.addView(q, new LinearLayout.LayoutParams(0, -2, 1));
        box.addView(ui.imageIcon(R.drawable.ic_mic, "Voice: say a song or a command", () -> Voice.listen(app)));
        content.addView(box, Ui.matchWrap());
        content.addView(ui.choices("Search for", TYPE_LABELS, lastType, i -> {
            lastType = i;
            run(q);
        }), Ui.matchWrap());
        TextView go = ui.action("SEARCH", 18, null);
        content.addView(go);
        list = scrollList();
        selection = new Selection(this);
        backButton();

        go.setOnClickListener(v -> run(q));
        q.setOnEditorActionListener((v, actionId, ev) -> {
            if (actionId != EditorInfo.IME_ACTION_SEARCH) return false;
            run(q);
            return true;
        });
        if (requireConnected(list) && !lastQuery.isEmpty()) page(lastQuery, 0);
    }

    private void run(EditText q) {
        lastQuery = q.getText().toString().trim();
        if (lastQuery.isEmpty()) return;
        app.hideKeyboard(q);
        selection.cancel();
        list.removeAllViews();
        if (requireConnected(list)) page(lastQuery, 0);
    }

    private void page(String query, int offset) {
        String type = TYPES[lastType];
        int typeAtStart = lastType;
        TextView loading = loading(list, "Searching…");
        spotify.request("GET", "/search?type=" + type + "&limit=" + PAGE + "&offset=" + offset + "&q=" + Uri.encode(query), null, (code, body) -> {
            list.removeView(loading);
            if (typeAtStart != lastType || !query.equals(lastQuery)) return; // a newer search replaced this one
            if (code != 200) { note(list, spotify.explain(code, body)); return; }
            try {
                JSONObject results = new JSONObject(body).getJSONObject(type + "s");
                JSONArray items = results.getJSONArray("items");
                if (items.length() == 0 && offset == 0) note(list, "No results");
                for (int i = 0; i < items.length(); i++) {
                    JSONObject item = items.optJSONObject(i);
                    if (item != null) list.addView(row(type, item), Ui.matchWrap());
                }
                if (!results.isNull("next")) {
                    TextView more = ui.action("MORE", 16, null);
                    more.setContentDescription("More results");
                    more.setOnClickListener(v -> { list.removeView(more); page(query, offset + PAGE); });
                    list.addView(more);
                }
            } catch (JSONException e) {
                note(list, "Couldn't read results");
            }
        });
    }

    private LinearLayout row(String type, JSONObject item) {
        String id = Json.str(item, "id"), uri = Json.str(item, "uri"), name = Json.str(item, "name");
        switch (type) {
            case "artist":
                return ui.listRow(name, "Artist", 19, () -> app.open(new ArtistScreen(id, uri, name).from(this)), null);
            case "album": {
                String year = Json.str(item, "release_date");
                if (year.length() > 4) year = year.substring(0, 4);
                String sub = Json.joinArtists(item) + (year.isEmpty() ? "" : " · " + year);
                return ui.listRow(name, sub, 17, () -> app.open(new AlbumScreen(id, uri, name).from(this)), null);
            }
            case "playlist": {
                JSONObject owner = item.optJSONObject("owner");
                String by = owner == null ? "" : Json.str(owner, "display_name");
                return ui.listRow(name, by, 17, () -> app.open(new PlaylistScreen(id, uri, name).from(this)), null);
            }
            default:
                return selection.row(item, Json.joinArtists(item), () -> playback.playTrack(uri));
        }
    }
}
