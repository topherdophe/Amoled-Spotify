package com.topherdophe.neon;

import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

/** The pop-ups behind the "+" button: song options, choose a playlist, and name a new playlist. */
final class SongMenu {
    private SongMenu() { }

    /** Add to queue / Add to playlist / Play artist radio, for one song. */
    static void show(MainActivity app, JSONObject track) {
        Ui ui = app.ui;
        String uri = Json.str(track, "uri"), name = Json.str(track, "name");
        JSONArray artists = track.optJSONArray("artists");
        JSONObject firstArtist = artists != null ? artists.optJSONObject(0) : null;
        String artist = firstArtist != null ? Json.str(firstArtist, "name") : "";

        Ui.Popup p = ui.popup(name);
        p.list.addView(ui.action("Add to queue", 17, () -> {
            p.dismiss();
            app.playback.addToQueue(uri, name);
        }), Ui.matchWrap());
        p.list.addView(ui.action("Add to playlist", 17, () -> {
            p.dismiss();
            pickPlaylist(app, uri, name);
        }), Ui.matchWrap());
        if (!artist.isEmpty()) {
            TextView radio = ui.action("Play artist radio", 17, () -> {
                p.dismiss();
                app.playback.artistRadio(uri, artist);
            });
            p.list.addView(radio, Ui.matchWrap());
        }
        p.list.addView(cancel(ui, p), Ui.matchWrap());
        p.show();
    }

    /** Lists the playlists you can add to, with "New playlist" first. */
    static void pickPlaylist(MainActivity app, String songUri, String songName) {
        Ui ui = app.ui;
        Ui.Popup p = ui.popup("Add to playlist");
        p.list.addView(ui.action("+  NEW PLAYLIST", 16, () -> {
            p.dismiss();
            newPlaylist(app, created -> add(app, created, songUri, songName));
        }), Ui.matchWrap());
        TextView loading = ui.text("Loading…", 15, false);
        p.list.addView(loading, Ui.matchWrap());
        TextView cancel = cancel(ui, p);
        p.list.addView(cancel, Ui.matchWrap());
        p.show();
        app.library.editablePlaylists((lists, err) -> {
            if (!p.dialog.isShowing()) return;
            if (lists == null) { loading.setText(err); return; }
            p.list.removeView(loading);
            if (lists.isEmpty()) {
                p.list.addView(ui.text("You don't have any playlists of your own yet.", 15, false),
                        p.list.indexOfChild(cancel), Ui.matchWrap());
            }
            for (Library.Playlist pl : lists) {
                LinearLayout row = ui.listRow(pl.name, "", 17, () -> {
                    p.dismiss();
                    add(app, pl, songUri, songName);
                }, null);
                p.list.addView(row, p.list.indexOfChild(cancel), Ui.matchWrap());
            }
        });
    }

    private static void add(MainActivity app, Library.Playlist pl, String songUri, String songName) {
        app.library.addSong(pl, songUri, (ok, err) -> {
            if (ok != null) app.status("Added " + songName + " to " + pl.name);
            else app.error(err);
        });
    }

    interface Created { void created(Library.Playlist playlist); }

    /** Asks for a name, creates a private playlist, then calls onCreated. */
    static void newPlaylist(MainActivity app, Created onCreated) {
        Ui ui = app.ui;
        Ui.Popup p = ui.popup("New playlist");
        EditText name = ui.input("Playlist name");
        name.setImeOptions(EditorInfo.IME_ACTION_DONE);
        p.list.addView(name, Ui.matchWrap());
        Runnable create = () -> {
            String n = name.getText().toString().trim();
            if (n.isEmpty()) return;
            p.dismiss();
            app.status("Creating " + n + "…");
            app.library.create(n, (pl, err) -> {
                if (pl == null) { app.error(err); return; }
                app.status("Created " + pl.name);
                onCreated.created(pl);
            });
        };
        name.setOnEditorActionListener((v, actionId, ev) -> {
            if (actionId != EditorInfo.IME_ACTION_DONE) return false;
            create.run();
            return true;
        });
        p.list.addView(ui.action("CREATE", 17, create), Ui.matchWrap());
        p.list.addView(cancel(ui, p), Ui.matchWrap());
        if (p.dialog.getWindow() != null) {
            p.dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        }
        p.show();
        name.requestFocus();
    }

    private static TextView cancel(Ui ui, Ui.Popup p) {
        TextView c = ui.action("Cancel", 15, p::dismiss);
        c.setAlpha(.6f);
        return c;
    }
}
