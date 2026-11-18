package com.topherdophe.neon;

import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Up next: tap a song to skip to it, swipe it away to remove it. */
final class QueueScreen extends BaseScreen {
    @Override void build() {
        heading("Up next");
        hint("Tap a song to skip to it · swipe it away to remove it");
        LinearLayout list = scrollList();
        backButton();
        if (!requireConnected(list)) return;
        TextView loading = loading(list, "Loading…");
        spotify.request("GET", "/me/player/queue", null, (code, body) -> {
            list.removeView(loading);
            if (code != 200) { note(list, spotify.explain(code, body)); return; }
            try {
                show(list, new JSONObject(body));
            } catch (JSONException e) {
                note(list, "Couldn't read the queue");
            }
        });
    }

    private void show(LinearLayout list, JSONObject j) {
        JSONObject now = j.optJSONObject("currently_playing");
        if (now != null) {
            TextView n = ui.text("Now playing: " + Json.str(now, "name"), 15, false);
            n.setAlpha(.6f);
            n.setPadding(0, ui.dp(4), 0, ui.dp(8));
            list.addView(n, Ui.matchWrap());
        }
        JSONArray q = j.optJSONArray("queue");
        int shown = 0;
        if (q != null) {
            for (int i = 0; i < q.length(); i++) {
                JSONObject t = q.optJSONObject(i);
                if (t == null) continue;
                String uri = Json.str(t, "uri");
                if (playback.skipUris.contains(uri)) continue;
                int steps = i + 1;
                LinearLayout item = ui.listRow(Json.str(t, "name"), Json.subtitle(t), 17, () -> skipTo(steps), null);
                Runnable remove = () -> {
                    playback.skipUris.add(uri);
                    list.removeView(item);
                    app.status("Removed. Spotify can't edit the queue, so the app skips it when it comes up.");
                };
                item.setOnTouchListener(new SwipeDismiss(app, remove));
                list.addView(item, Ui.matchWrap());
                shown++;
            }
        }
        if (shown == 0) note(list, "Nothing queued");
    }

    private void skipTo(int steps) {
        app.status(steps == 1 ? "Skipping…" : "Skipping " + steps + " songs…");
        app.showPlayer();
        playback.skip(steps);
    }
}
