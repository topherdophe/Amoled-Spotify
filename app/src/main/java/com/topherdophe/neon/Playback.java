package com.topherdophe.neon;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** What's playing, and the commands that change it. Shared by all screens. */
final class Playback {
    interface Host {
        void status(String message);
        void error(String message);
        /** Clears the status line if it is showing an error. */
        void clearError();
        void onPlaybackChanged();
        void showDevices(String note);
        void showPlayer();
        /** "Sleep after this song" has reached the end of the song. */
        void sleepNow();
    }

    private final SpotifyClient spotify;
    private final AppSettings settings;
    private final Host host;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService images = Executors.newSingleThreadExecutor();
    private boolean closed, refreshing;

    boolean hasPlayback, isPlaying, shuffle;
    String repeat = "off", trackName = "", artistName = "", deviceName = "", trackUri = "";
    /** The full song (or episode) that's playing, for the song menu; null if nothing. */
    JSONObject currentItem;
    long progressMs, durationMs, progressAt;
    private String endRefreshFor = "";

    private String artUrl = "", pendingArtUrl = "";
    Bitmap artBitmap;

    /** Songs swiped out of the queue. Spotify can't remove queue items, so these are skipped when they come up. */
    final Set<String> skipUris = new HashSet<>();

    boolean sleepEndOfSong;
    private String sleepSongUri = "";

    Playback(SpotifyClient spotify, AppSettings settings, Host host) {
        this.spotify = spotify;
        this.settings = settings;
        this.host = host;
    }

    void shutdown() {
        closed = true;
        handler.removeCallbacksAndMessages(null);
        images.shutdownNow();
    }

    long currentProgress() {
        long p = progressMs;
        if (isPlaying) p += SystemClock.elapsedRealtime() - progressAt;
        return durationMs > 0 ? Math.min(p, durationMs) : p;
    }

    void reset() {
        hasPlayback = false;
        isPlaying = false;
    }

    /** Called every second: refresh right when a song should end, for a quicker next-song display. */
    void onTick() {
        if (isPlaying && durationMs > 0 && !trackUri.isEmpty() && !trackUri.equals(endRefreshFor)
                && currentProgress() >= durationMs - 300) {
            endRefreshFor = trackUri;
            handler.postDelayed(this::refresh, 1200);
        }
    }

    void setSleepAfterSong(boolean on) {
        sleepEndOfSong = on;
        sleepSongUri = on ? trackUri : "";
    }

    // ---------------------------------------------------------------- state

    void refresh() {
        if (!spotify.isConnected() || refreshing) return;
        refreshing = true;
        spotify.request("GET", "/me/player?additional_types=track,episode", null, (code, body) -> {
            refreshing = false;
            if (code == 204 || (code == 200 && body.trim().isEmpty())) {
                reset();
                host.onPlaybackChanged();
                return;
            }
            if (code != 200) { if (code != 0) host.error(spotify.explain(code, body)); return; }
            try { parse(new JSONObject(body)); } catch (JSONException e) { return; }
            host.clearError();
            host.onPlaybackChanged();
            afterTrackUpdate();
        });
    }

    /** Queue auto-skip and sleep-after-song both react to the song changing. */
    private void afterTrackUpdate() {
        if (!trackUri.isEmpty() && skipUris.remove(trackUri)) {
            host.status("Skipped a song you removed from the queue");
            command("POST", "/me/player/next", null);
            return;
        }
        if (sleepEndOfSong) {
            if (sleepSongUri.isEmpty()) sleepSongUri = trackUri;
            else if (!trackUri.isEmpty() && !trackUri.equals(sleepSongUri)) host.sleepNow();
        }
    }

    private void parse(JSONObject j) {
        hasPlayback = true;
        isPlaying = j.optBoolean("is_playing");
        shuffle = j.optBoolean("shuffle_state");
        repeat = j.optString("repeat_state", "off");
        progressMs = j.optLong("progress_ms");
        progressAt = SystemClock.elapsedRealtime();
        JSONObject dev = j.optJSONObject("device");
        if (dev != null) {
            deviceName = Json.str(dev, "name");
            settings.setLastDevice(Json.str(dev, "id"));
        }
        JSONObject item = j.optJSONObject("item");
        currentItem = item;
        if (item == null) { trackName = "Unknown item"; artistName = ""; durationMs = 0; trackUri = ""; return; }
        trackName = Json.str(item, "name");
        trackUri = Json.str(item, "uri");
        durationMs = item.optLong("duration_ms");
        artistName = Json.subtitle(item);
        JSONObject album = item.optJSONObject("album");
        pendingArtUrl = Json.pickImage(album != null ? album.optJSONArray("images") : item.optJSONArray("images"));
        if (settings.showArt) loadArt();
    }

    // ---------------------------------------------------------------- album art

    /** Loads the current song's cover (call after turning album art on). */
    void loadArt() {
        String url = pendingArtUrl;
        if (url.equals(artUrl)) return;
        artUrl = url;
        artBitmap = null;
        host.onPlaybackChanged();
        if (!url.startsWith("https://")) return;
        images.execute(() -> {
            Bitmap b = null;
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                c.setConnectTimeout(10_000);
                c.setReadTimeout(10_000);
                try (InputStream in = c.getInputStream()) { b = BitmapFactory.decodeStream(in); }
                finally { c.disconnect(); }
            } catch (Exception ignored) { }
            Bitmap result = b;
            handler.post(() -> {
                if (closed || !url.equals(artUrl)) return;
                artBitmap = result;
                host.onPlaybackChanged();
            });
        });
    }

    // ---------------------------------------------------------------- commands

    void togglePlay() {
        boolean wasPlaying = isPlaying;
        progressMs = currentProgress();
        progressAt = SystemClock.elapsedRealtime();
        isPlaying = !wasPlaying;
        host.onPlaybackChanged();
        command("PUT", wasPlaying ? "/me/player/pause" : "/me/player/play", null);
    }

    void pause() {
        isPlaying = false;
        host.onPlaybackChanged();
        command("PUT", "/me/player/pause", null);
    }

    void previous() { command("POST", "/me/player/previous", null); }
    void next() { command("POST", "/me/player/next", null); }

    void setShuffle(boolean on) {
        shuffle = on;
        host.onPlaybackChanged();
        command("PUT", "/me/player/shuffle?state=" + on, null);
    }

    void toggleShuffle() {
        shuffle = !shuffle;
        host.onPlaybackChanged();
        command("PUT", "/me/player/shuffle?state=" + shuffle, null);
    }

    void cycleRepeat() {
        repeat = "off".equals(repeat) ? "context" : "context".equals(repeat) ? "track" : "off";
        host.onPlaybackChanged();
        command("PUT", "/me/player/repeat?state=" + repeat, null);
    }

    void seek(long ms) {
        progressMs = ms;
        progressAt = SystemClock.elapsedRealtime();
        command("PUT", "/me/player/seek?position_ms=" + ms, null);
    }

    void command(String method, String path, String json) { command(method, path, json, true, null); }

    /** onDone runs only if the command succeeds. */
    private void command(String method, String path, String json, boolean recoverDevice, Runnable onDone) {
        spotify.request(method, path, json, (code, body) -> {
            if (code >= 200 && code < 300) {
                host.clearError();
                handler.postDelayed(this::refresh, 700);
                if (onDone != null) onDone.run();
            } else if (code == 404 && recoverDevice) {
                recoverDevice(method, path, json, onDone);
            } else {
                host.error(spotify.explain(code, body));
                refresh();
            }
        });
    }

    /** No active device: reuse the last one (or the only one), otherwise ask the user to choose. */
    private void recoverDevice(String method, String path, String json, Runnable onDone) {
        host.status("Looking for a playback device…");
        spotify.request("GET", "/me/player/devices", null, (code, body) -> {
            if (code != 200) { host.error(spotify.explain(code, body)); return; }
            List<JSONObject> devices = Json.parseDevices(body);
            String remembered = settings.lastDevice();
            JSONObject pick = null;
            for (JSONObject d : devices) if (Json.str(d, "id").equals(remembered) && !d.optBoolean("is_restricted")) pick = d;
            if (pick == null && devices.size() == 1 && !devices.get(0).optBoolean("is_restricted")) pick = devices.get(0);
            if (pick == null) {
                host.showDevices(devices.isEmpty() ? "Nothing is playing anywhere. Open Spotify on a device first." : "Choose where to play");
                return;
            }
            String id = Json.str(pick, "id");
            host.status("Using " + Json.str(pick, "name"));
            if (path.startsWith("/me/player/play")) command(method, Json.withDevice(path, id), json, false, onDone);
            else transfer(id, false, () -> command(method, path, json, false, onDone));
        });
    }

    void transfer(String id, boolean playNow, Runnable after) {
        try {
            JSONObject j = new JSONObject();
            j.put("device_ids", new JSONArray().put(id));
            j.put("play", playNow);
            spotify.request("PUT", "/me/player", j.toString(), (code, body) -> {
                if (code < 200 || code >= 300) { host.error(spotify.explain(code, body)); return; }
                settings.setLastDevice(id);
                handler.postDelayed(after, 600);
            });
        } catch (JSONException e) {
            host.error("Couldn't build request");
        }
    }

    void playTrack(String uri) {
        try {
            command("PUT", "/me/player/play", new JSONObject().put("uris", new JSONArray().put(uri)).toString());
            host.showPlayer();
        } catch (JSONException ignored) { }
    }

    void playContext(String uri) {
        try {
            command("PUT", "/me/player/play", new JSONObject().put("context_uri", uri).toString());
            host.showPlayer();
        } catch (JSONException ignored) { }
    }

    /** Plays a playlist starting from one of its songs. */
    void playInContext(String contextUri, String songUri) {
        try {
            JSONObject j = new JSONObject();
            j.put("context_uri", contextUri);
            j.put("offset", new JSONObject().put("uri", songUri));
            command("PUT", "/me/player/play", j.toString());
            host.showPlayer();
        } catch (JSONException ignored) { }
    }

    void shufflePlay(String contextUri) {
        shuffle = true;
        playContext(contextUri);
        handler.postDelayed(() -> command("PUT", "/me/player/shuffle?state=true", null), 900);
    }

    /**
     * Spotify closed its recommendations API to new apps in 2024, so true song radio isn't possible.
     * This plays the song, then queues a shuffled mix of other songs by the same artist.
     */
    void artistRadio(String songUri, String artist, boolean alreadyPlaying) {
        String play;
        try {
            play = new JSONObject().put("uris", new JSONArray().put(songUri)).toString();
        } catch (JSONException e) {
            return;
        }
        host.showPlayer();
        host.status("Starting " + artist + " radio…");
        Runnable queueMix = () -> {
            String q = "artist:\"" + artist.replace("\"", "") + "\"";
            spotify.request("GET", "/search?type=track&limit=10&q=" + Uri.encode(q), null, (code, body) -> {
                if (code != 200) { host.error(spotify.explain(code, body)); return; }
                List<String> uris = new ArrayList<>();
                try {
                    JSONArray items = new JSONObject(body).getJSONObject("tracks").getJSONArray("items");
                    for (int i = 0; i < items.length(); i++) {
                        JSONObject t = items.optJSONObject(i);
                        String u = t == null ? "" : Json.str(t, "uri");
                        if (!u.isEmpty() && !u.equals(songUri) && !uris.contains(u)) uris.add(u);
                    }
                } catch (JSONException e) {
                    return;
                }
                Collections.shuffle(uris);
                queueAll(uris, 0, uris.isEmpty() ? "No other songs by " + artist + " found"
                        : artist + " radio: " + uris.size() + " more songs queued");
            });
        };
        if (alreadyPlaying) queueMix.run();
        else command("PUT", "/me/player/play", play, true, queueMix);
    }

    /** Adds several songs to the queue, then shows doneMessage. */
    void queueMany(List<String> uris, String doneMessage) {
        host.status("Adding to queue…");
        queueAll(new ArrayList<>(uris), 0, doneMessage);
    }

    /** Adds songs to the queue one at a time, so they arrive in order. */
    private void queueAll(List<String> uris, int index, String doneMessage) {
        if (closed) return;
        if (index >= uris.size()) { host.status(doneMessage); return; }
        spotify.request("POST", "/me/player/queue?uri=" + SpotifyClient.enc(uris.get(index)), null, (code, body) -> {
            if (code < 200 || code >= 300) { host.error(spotify.explain(code, body)); return; }
            skipUris.remove(uris.get(index));
            handler.postDelayed(() -> queueAll(uris, index + 1, doneMessage), 250);
        });
    }

    void addToQueue(String uri, String name) {
        spotify.request("POST", "/me/player/queue?uri=" + SpotifyClient.enc(uri), null, (code, body) -> {
            if (code >= 200 && code < 300) { skipUris.remove(uri); host.status("Added to queue: " + name); }
            else host.error(spotify.explain(code, body));
        });
    }

    /** Skips forward `steps` songs, one request at a time. */
    void skip(int steps) {
        spotify.request("POST", "/me/player/next", null, (code, body) -> {
            if (code < 200 || code >= 300) { host.error(spotify.explain(code, body)); return; }
            if (steps > 1) handler.postDelayed(() -> skip(steps - 1), 250);
            else { host.status(""); handler.postDelayed(this::refresh, 700); }
        });
    }
}
