package com.topherdophe.neon;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Playlist editing: list the playlists you can add to, create playlists, add songs. */
final class Library {
    interface Done<T> { void done(T result, String error); }

    static final class Playlist {
        final String id, uri, name;
        Playlist(String id, String uri, String name) { this.id = id; this.uri = uri; this.name = name; }
    }

    private final SpotifyClient spotify;
    private String userId = "";

    Library(SpotifyClient spotify) { this.spotify = spotify; }

    /** Your Spotify user ID (cached), needed to tell which playlists you own. */
    void userId(Done<String> cb) {
        if (!userId.isEmpty()) { cb.done(userId, null); return; }
        spotify.request("GET", "/me", null, (code, body) -> {
            if (code != 200) { cb.done(null, spotify.explain(code, body)); return; }
            try {
                userId = Json.str(new JSONObject(body), "id");
                cb.done(userId, null);
            } catch (JSONException e) {
                cb.done(null, "Couldn't read your Spotify account");
            }
        });
    }

    /** Playlists you own or collaborate on: the only ones Spotify lets this app add songs to. */
    void editablePlaylists(Done<List<Playlist>> cb) {
        userId((me, err) -> {
            if (me == null) { cb.done(null, err); return; }
            collect(me, "/me/playlists?limit=50", new ArrayList<>(), cb);
        });
    }

    private void collect(String me, String path, List<Playlist> out, Done<List<Playlist>> cb) {
        spotify.request("GET", path, null, (code, body) -> {
            if (code != 200) { cb.done(null, spotify.explain(code, body)); return; }
            try {
                JSONObject page = new JSONObject(body);
                JSONArray items = page.getJSONArray("items");
                for (int i = 0; i < items.length(); i++) {
                    JSONObject pl = items.optJSONObject(i);
                    if (pl == null) continue;
                    JSONObject owner = pl.optJSONObject("owner");
                    boolean mine = owner != null && me.equals(Json.str(owner, "id"));
                    if (mine || pl.optBoolean("collaborative")) {
                        out.add(new Playlist(Json.str(pl, "id"), Json.str(pl, "uri"), Json.str(pl, "name")));
                    }
                }
                String next = Json.str(page, "next");
                // Up to 200 playlists, to keep this quick.
                if (!next.isEmpty() && out.size() < 200 && page.optInt("offset") < 150) collect(me, next, out, cb);
                else cb.done(out, null);
            } catch (JSONException e) {
                cb.done(null, "Couldn't read playlists");
            }
        });
    }

    /** Creates a private playlist. */
    void create(String name, Done<Playlist> cb) {
        String json;
        try {
            json = new JSONObject().put("name", name).put("public", false).toString();
        } catch (JSONException e) {
            cb.done(null, "Couldn't build request");
            return;
        }
        spotify.request("POST", "/me/playlists", json, (code, body) -> {
            if (code == 404 || code == 405) {
                // Older form of the same request.
                userId((me, err) -> {
                    if (me == null) { cb.done(null, err); return; }
                    spotify.request("POST", "/users/" + SpotifyClient.enc(me) + "/playlists", json,
                            (c2, b2) -> created(c2, b2, cb));
                });
                return;
            }
            created(code, body, cb);
        });
    }

    private void created(int code, String body, Done<Playlist> cb) {
        if (code != 200 && code != 201) { cb.done(null, spotify.explain(code, body)); return; }
        try {
            JSONObject j = new JSONObject(body);
            cb.done(new Playlist(Json.str(j, "id"), Json.str(j, "uri"), Json.str(j, "name")), null);
        } catch (JSONException e) {
            cb.done(null, "Playlist created, but the reply was unreadable");
        }
    }

    /** Adds songs to a playlist, 100 at a time (Spotify's limit per request). */
    void addSongs(Playlist playlist, List<String> uris, Done<Boolean> cb) {
        addChunk(playlist.id, uris, 0, cb);
    }

    private void addChunk(String playlistId, List<String> uris, int from, Done<Boolean> cb) {
        if (from >= uris.size()) { cb.done(true, null); return; }
        int to = Math.min(uris.size(), from + 100);
        String json;
        try {
            JSONArray a = new JSONArray();
            for (String u : uris.subList(from, to)) a.put(u);
            json = new JSONObject().put("uris", a).toString();
        } catch (JSONException e) {
            cb.done(null, "Couldn't build request");
            return;
        }
        spotify.request("POST", "/playlists/" + SpotifyClient.enc(playlistId) + "/items", json, (code, body) -> {
            if (code >= 200 && code < 300) addChunk(playlistId, uris, to, cb);
            else cb.done(null, spotify.explain(code, body));
        });
    }

    /** Removes every copy of these songs from a playlist you own. */
    void removeSongs(String playlistId, List<String> uris, Done<Boolean> cb) {
        removeChunk(playlistId, uris, 0, "items", cb);
    }

    private void removeChunk(String playlistId, List<String> uris, int from, String key, Done<Boolean> cb) {
        if (from >= uris.size()) { cb.done(true, null); return; }
        int to = Math.min(uris.size(), from + 100);
        String json;
        try {
            JSONArray a = new JSONArray();
            for (String u : uris.subList(from, to)) a.put(new JSONObject().put("uri", u));
            json = new JSONObject().put(key, a).toString();
        } catch (JSONException e) {
            cb.done(null, "Couldn't build request");
            return;
        }
        spotify.request("DELETE", "/playlists/" + SpotifyClient.enc(playlistId) + "/items", json, (code, body) -> {
            if (code >= 200 && code < 300) removeChunk(playlistId, uris, to, key, cb);
            // Spotify renamed "tracks" to "items" in Feb 2026; try the old name if the new one is refused.
            else if (code == 400 && "items".equals(key)) removeChunk(playlistId, uris, from, "tracks", cb);
            else cb.done(null, spotify.explain(code, body));
        });
    }

    /** Saves a song to Liked Songs. */
    void saveToLiked(String uri, Done<Boolean> cb) {
        spotify.request("PUT", "/me/library?uris=" + SpotifyClient.enc(uri), null, (code, body) -> {
            if (code >= 200 && code < 300) { cb.done(true, null); return; }
            if (code != 404) { cb.done(null, spotify.explain(code, body)); return; }
            // Older form of the same request.
            String id = uri.substring(uri.lastIndexOf(':') + 1);
            spotify.request("PUT", "/me/tracks?ids=" + SpotifyClient.enc(id), null, (c2, b2) -> {
                if (c2 >= 200 && c2 < 300) cb.done(true, null);
                else cb.done(null, spotify.explain(c2, b2));
            });
        });
    }
}
