package com.topherdophe.neon;

import android.net.Uri;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Song search: tap to play, + (or long-press) to add to the queue. */
final class SearchScreen extends BaseScreen {
    private static final int PAGE = 10; // Spotify's Development Mode search limit since Feb 2026
    /** Kept between visits so going back to search shows the last results. */
    private static String lastQuery = "";

    @Override void build() {
        heading("Search");
        EditText q = ui.input("Song, artist or album");
        q.setText(lastQuery);
        q.setContentDescription("Search for a song, artist or album");
        q.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        content.addView(q, Ui.matchWrap());
        TextView go = ui.action("SEARCH", 18, null);
        content.addView(go);
        hint("Tap to play · + adds to queue");
        LinearLayout list = scrollList();
        backButton();

        Runnable run = () -> {
            lastQuery = q.getText().toString().trim();
            if (lastQuery.isEmpty()) return;
            app.hideKeyboard(q);
            list.removeAllViews();
            if (requireConnected(list)) page(list, lastQuery, 0);
        };
        go.setOnClickListener(v -> run.run());
        q.setOnEditorActionListener((v, actionId, ev) -> {
            if (actionId != EditorInfo.IME_ACTION_SEARCH) return false;
            run.run();
            return true;
        });
        if (requireConnected(list) && !lastQuery.isEmpty()) page(list, lastQuery, 0);
    }

    private void page(LinearLayout list, String query, int offset) {
        TextView loading = loading(list, "Searching…");
        spotify.request("GET", "/search?type=track&limit=" + PAGE + "&offset=" + offset + "&q=" + Uri.encode(query), null, (code, body) -> {
            list.removeView(loading);
            if (code != 200) { note(list, spotify.explain(code, body)); return; }
            try {
                JSONObject tracks = new JSONObject(body).getJSONObject("tracks");
                JSONArray items = tracks.getJSONArray("items");
                if (items.length() == 0 && offset == 0) note(list, "No results");
                for (int i = 0; i < items.length(); i++) {
                    JSONObject t = items.optJSONObject(i);
                    if (t == null) continue;
                    String uri = Json.str(t, "uri"), name = Json.str(t, "name");
                    LinearLayout r = ui.listRow(name, Json.joinArtists(t), 17,
                            () -> playback.playTrack(uri), () -> playback.addToQueue(uri, name));
                    r.setOnLongClickListener(v -> { playback.addToQueue(uri, name); return true; });
                    list.addView(r, Ui.matchWrap());
                }
                if (!tracks.isNull("next")) {
                    TextView more = ui.action("MORE", 16, null);
                    more.setContentDescription("More results");
                    more.setOnClickListener(v -> { list.removeView(more); page(list, query, offset + PAGE); });
                    list.addView(more);
                }
            } catch (JSONException e) {
                note(list, "Couldn't read results");
            }
        });
    }
}
