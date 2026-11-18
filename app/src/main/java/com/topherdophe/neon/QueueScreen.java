package com.topherdophe.neon;

import android.os.Bundle;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Up next: tap a song to skip to it, swipe it away (or long-press) to remove it. */
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
                // Screen readers can't swipe a row away, so offer long-press and a "Remove from queue" action too.
                item.setOnLongClickListener(v -> { remove.run(); return true; });
                item.setAccessibilityDelegate(new View.AccessibilityDelegate() {
                    @Override public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
                        super.onInitializeAccessibilityNodeInfo(host, info);
                        info.addAction(new AccessibilityNodeInfo.AccessibilityAction(
                                AccessibilityNodeInfo.ACTION_CLICK, "Skip to this song"));
                        info.addAction(new AccessibilityNodeInfo.AccessibilityAction(
                                R.id.action_remove_from_queue, "Remove from queue"));
                    }

                    @Override public boolean performAccessibilityAction(View host, int action, Bundle args) {
                        if (action == R.id.action_remove_from_queue) { remove.run(); return true; }
                        return super.performAccessibilityAction(host, action, args);
                    }
                });
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
