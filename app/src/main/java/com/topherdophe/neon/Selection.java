package com.topherdophe.neon;

import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Multi-select for song lists. Long-press a song to start; taps then add or remove songs; a bar
 * above BACK offers Add to playlist, Add to queue and (in your own playlists) Remove.
 */
final class Selection {
    /** Removes songs from a playlist; call done with true once they're gone. */
    interface Remover { void remove(List<String> uris, Library.Done<Boolean> done); }

    private final MainActivity app;
    private final Ui ui;
    private final Map<String, View> picked = new LinkedHashMap<>();
    private final LinearLayout bar;
    private final TextView count, removeButton;
    private Remover remover;

    Selection(BaseScreen screen) {
        app = screen.app;
        ui = screen.ui;
        bar = ui.vertical();
        bar.setVisibility(View.GONE);
        count = ui.text("", 14, true);
        bar.addView(count, Ui.matchWrap());
        HorizontalScrollView scroll = new HorizontalScrollView(app);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setFillViewport(true);
        LinearLayout actions = new LinearLayout(app);
        actions.setGravity(Gravity.CENTER);
        actions.addView(ui.action("+ PLAYLIST", 15, this::addToPlaylist));
        actions.addView(ui.action("+ QUEUE", 15, this::addToQueue));
        removeButton = ui.action("REMOVE", 15, this::confirmRemove);
        removeButton.setVisibility(View.GONE);
        actions.addView(removeButton);
        actions.addView(ui.action("CANCEL", 15, this::cancel));
        scroll.addView(actions);
        bar.addView(scroll, Ui.matchWrap());
        screen.content.addView(bar, Ui.matchWrap());
    }

    /** Turns on REMOVE (only for playlists you can edit). */
    void setRemover(Remover r) {
        remover = r;
        update();
    }

    boolean active() { return !picked.isEmpty(); }

    /**
     * A song row: tap plays it (or toggles it while selecting), + opens the song menu, long-press selects.
     */
    LinearLayout row(JSONObject track, String subtitle, Runnable onPlay) {
        String uri = Json.str(track, "uri"), name = Json.str(track, "name");
        LinearLayout[] holder = new LinearLayout[1];
        LinearLayout r = ui.listRow(name, subtitle, 17, () -> {
            if (active()) toggle(uri, holder[0]);
            else onPlay.run();
        }, () -> SongMenu.show(app, track, false));
        holder[0] = r;
        r.setOnLongClickListener(v -> { toggle(uri, r); return true; });
        return r;
    }

    private void toggle(String uri, View row) {
        if (picked.remove(uri) != null) mark(row, false);
        else { picked.put(uri, row); mark(row, true); }
        update();
    }

    private void mark(View row, boolean on) {
        row.setForeground(on ? new ColorDrawable(Ui.withAlpha(ui.accent(), 0x38)) : null);
        row.setSelected(on);
    }

    private void update() {
        int n = picked.size();
        bar.setVisibility(n > 0 ? View.VISIBLE : View.GONE);
        count.setText(n == 1 ? "1 song selected" : n + " songs selected");
        removeButton.setVisibility(remover != null ? View.VISIBLE : View.GONE);
    }

    /** Ends the selection. Returns true if there was one. */
    boolean cancel() {
        if (picked.isEmpty()) return false;
        for (View row : picked.values()) mark(row, false);
        picked.clear();
        update();
        return true;
    }

    private List<String> uris() { return new ArrayList<>(picked.keySet()); }

    private String label() { return picked.size() == 1 ? "1 song" : picked.size() + " songs"; }

    private void addToPlaylist() {
        SongMenu.pickPlaylist(app, uris(), label());
        cancel();
    }

    private void addToQueue() {
        app.playback.queueMany(uris(), label() + " added to queue");
        cancel();
    }

    private void confirmRemove() {
        if (remover == null || picked.isEmpty()) return;
        List<String> uris = uris();
        List<View> rows = new ArrayList<>(picked.values());
        String what = label();
        SongMenu.confirm(app, "Remove " + what + " from this playlist?", "REMOVE", () ->
                remover.remove(uris, (ok, err) -> {
                    if (ok == null) { app.error(err); return; }
                    picked.clear();
                    for (View row : rows) if (row.getParent() instanceof LinearLayout) ((LinearLayout) row.getParent()).removeView(row);
                    update();
                    app.status("Removed " + what);
                }), null);
    }
}
