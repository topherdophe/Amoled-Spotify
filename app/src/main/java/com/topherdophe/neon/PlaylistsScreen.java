package com.topherdophe.neon;

import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** The user's playlists. */
final class PlaylistsScreen extends BaseScreen {
    @Override void build() {
        heading("Playlists");
        content.addView(ui.action("+  NEW PLAYLIST", 16, () -> SongMenu.newPlaylist(app,
                pl -> app.open(new PlaylistScreen(pl.id, pl.uri, pl.name)))));
        LinearLayout list = scrollList();
        backButton();
        if (!requireConnected(list)) return;
        list.addView(ui.listRow("Recently played", "Your last 50 songs", 19,
                () -> app.open(new RecentScreen().from(this)), null), Ui.matchWrap());
        page(list, "/me/playlists?limit=50");
    }

    private void page(LinearLayout list, String path) {
        TextView loading = loading(list, "Loading…");
        spotify.request("GET", path, null, (code, body) -> {
            list.removeView(loading);
            if (code != 200) { note(list, spotify.explain(code, body)); return; }
            try {
                JSONObject page = new JSONObject(body);
                JSONArray items = page.getJSONArray("items");
                if (items.length() == 0 && list.getChildCount() <= 1) note(list, "No playlists");
                for (int i = 0; i < items.length(); i++) {
                    JSONObject pl = items.optJSONObject(i);
                    if (pl == null) continue;
                    String id = Json.str(pl, "id"), uri = Json.str(pl, "uri"), name = Json.str(pl, "name");
                    JSONObject owner = pl.optJSONObject("owner");
                    String by = owner == null ? "" : Json.str(owner, "display_name");
                    list.addView(ui.listRow(name, by, 19, () -> app.open(new PlaylistScreen(id, uri, name).from(this)), null), Ui.matchWrap());
                }
                String next = Json.str(page, "next");
                if (!next.isEmpty()) {
                    TextView more = ui.action("MORE", 16, null);
                    more.setContentDescription("More playlists");
                    more.setOnClickListener(v -> { list.removeView(more); page(list, next); });
                    list.addView(more);
                }
            } catch (JSONException e) {
                note(list, "Couldn't read playlists");
            }
        });
    }
}
