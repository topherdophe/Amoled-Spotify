package com.example.neonspotify;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** AMOLED black / neon green Spotify remote. Single activity, framework views only. */
public class MainActivity extends Activity implements SpotifyClient.Listener {
    private static final int GREEN = 0xFF00FF00, TRACK = 0xFF1F4D1F, HINT = 0xFF4D804D, BLACK = Color.BLACK;
    private static final long POLL_PLAYING_MS = 5_000, POLL_IDLE_MS = 15_000, POLL_LIMITED_MS = 20_000;
    private static final long IDLE_DIM_MS = 90_000, SHIFT_MS = 30_000;
    private static final int PAGE = 10; // Spotify's Development Mode search limit since Feb 2026
    private static final float OFF = .35f;

    private enum Screen { PLAYER, SEARCH, PLAYLISTS, DEVICES, INFO }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private SpotifyClient spotify;
    private SharedPreferences settings;
    private Screen screen = Screen.PLAYER;

    // Shell views
    private LinearLayout shifter, content;
    private TextView battery, message;
    private boolean errorShown;

    // Player views (null unless the player screen is showing)
    private TextView clock, title, artist, device, elapsed, duration, play, shuffleBtn, repeatBtn;
    private SeekBar progressBar, volumeBar;
    private boolean draggingProgress, draggingVolume;

    // Playback state
    private boolean hasPlayback, isPlaying, shuffle, supportsVolume = true, refreshing;
    private String repeat = "off", trackName = "", artistName = "", deviceName = "";
    private long progressMs, durationMs, progressAt;
    private int volume = -1;
    private String lastQuery = "";

    // OLED care
    private boolean keepOn, nightMode, autoDim, dimmed, swallowing;
    private long lastTouch = SystemClock.elapsedRealtime();
    private int ticks;

    // ================================================================ lifecycle

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        settings = getSharedPreferences("settings", MODE_PRIVATE);
        keepOn = settings.getBoolean("keepOn", true);
        nightMode = settings.getBoolean("nightMode", false);
        autoDim = settings.getBoolean("autoDim", true);
        spotify = new SpotifyClient(this, getString(R.string.spotify_client_id), this);

        Window w = getWindow();
        w.setStatusBarColor(BLACK);
        w.setNavigationBarColor(BLACK);
        if (Build.VERSION.SDK_INT >= 28) {
            WindowManager.LayoutParams lp = w.getAttributes();
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            w.setAttributes(lp);
        }
        applyKeepOn();
        buildShell();
        showPlayer();
        if (saved == null) handleIntent(getIntent());
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleIntent(intent);
    }

    @Override protected void onResume() {
        super.onResume();
        hideSystemUi();
        lastTouch = SystemClock.elapsedRealtime();
        setDimmed(nightMode);
        handler.removeCallbacks(tick);
        handler.removeCallbacks(poll);
        handler.post(tick);
        handler.post(poll);
    }

    @Override protected void onPause() {
        // Nothing runs in the background: no polling, no timers, while the screen is off or the app is hidden.
        handler.removeCallbacks(tick);
        handler.removeCallbacks(poll);
        super.onPause();
    }

    @Override protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        spotify.shutdown();
        super.onDestroy();
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemUi();
    }

    @SuppressWarnings("deprecation")
    @Override public void onBackPressed() {
        if (screen != Screen.PLAYER) showPlayer(); else super.onBackPressed();
    }

    @Override public boolean dispatchTouchEvent(MotionEvent ev) {
        lastTouch = SystemClock.elapsedRealtime();
        int a = ev.getActionMasked();
        // The first touch on an auto-dimmed screen only wakes it, so it can't trigger a button by accident.
        if (a == MotionEvent.ACTION_DOWN && dimmed && !nightMode) { setDimmed(false); swallowing = true; }
        if (swallowing) {
            if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) swallowing = false;
            return true;
        }
        return super.dispatchTouchEvent(ev);
    }

    @Override public void onSessionChanged(String msg) {
        if (screen == Screen.PLAYER) showPlayer();
        status(msg);
    }

    private void handleIntent(Intent intent) {
        if (intent != null && spotify.handleRedirect(intent.getData())) {
            setIntent(new Intent());
            status("Finishing Spotify login…");
        }
    }

    // ================================================================ timers

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            updateClock();
            if (ticks++ % 30 == 0) updateBattery();
            updateProgressViews();
            // Burn-in protection: drift the whole UI (including the top bar) a few dp every 30 s.
            long step = System.currentTimeMillis() / SHIFT_MS;
            shifter.setTranslationX(dpf((float) Math.sin(step * 0.7) * 6f));
            shifter.setTranslationY(dpf((float) Math.cos(step * 1.3) * 6f));
            if (autoDim && !nightMode && !dimmed && screen == Screen.PLAYER
                    && SystemClock.elapsedRealtime() - lastTouch > IDLE_DIM_MS) setDimmed(true);
            handler.postDelayed(this, 1000);
        }
    };

    private final Runnable poll = new Runnable() {
        @Override public void run() {
            boolean limited = spotify.isRateLimited();
            if (screen == Screen.PLAYER && spotify.isConnected() && !limited) refreshPlayback();
            handler.postDelayed(this, limited ? POLL_LIMITED_MS : isPlaying ? POLL_PLAYING_MS : POLL_IDLE_MS);
        }
    };

    // ================================================================ screens

    private void buildShell() {
        FrameLayout frame = new FrameLayout(this);
        frame.setBackgroundColor(BLACK);
        setContentView(frame);
        shifter = vertical();
        shifter.setPadding(dp(16), dp(28), dp(16), dp(12));
        frame.addView(shifter, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        battery = text("--%", 16, false);
        top.addView(battery);
        top.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
        top.addView(icon("◎", "Devices", () -> showDevices(null)));
        top.addView(icon("⌕", "Search", this::showSearch));
        top.addView(icon("☷", "Playlists", this::showPlaylists));
        top.addView(icon("ⓘ", "Diagnostics", this::showInfo));
        shifter.addView(top, new LinearLayout.LayoutParams(-1, dp(52)));

        content = vertical();
        shifter.addView(content, new LinearLayout.LayoutParams(-1, 0, 1));

        message = text("", 13, false);
        message.setAlpha(.8f);
        message.setPadding(dp(8), dp(4), dp(8), dp(4));
        shifter.addView(message, new LinearLayout.LayoutParams(-1, -2));
        updateBattery();
    }

    private void clearContent(Screen s) {
        screen = s;
        content.removeAllViews();
        clock = title = artist = device = elapsed = duration = play = shuffleBtn = repeatBtn = null;
        progressBar = volumeBar = null;
        draggingProgress = draggingVolume = false;
    }

    private void showPlayer() {
        clearContent(Screen.PLAYER);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        content.addView(scroll, new LinearLayout.LayoutParams(-1, -1));
        LinearLayout p = vertical();
        p.setGravity(Gravity.CENTER);
        scroll.addView(p, new FrameLayout.LayoutParams(-1, -1));

        clock = text("--:--", 64, false);
        clock.setTypeface(Typeface.create("sans-serif-thin", Typeface.NORMAL));
        p.addView(clock, matchWrap());
        title = oneLine(text("", 26, true));
        p.addView(title, matchWrap());
        artist = oneLine(text("", 19, false));
        artist.setAlpha(.75f);
        p.addView(artist, matchWrap());
        device = oneLine(text("", 14, false));
        device.setAlpha(.55f);
        device.setPadding(0, dp(4), 0, 0);
        device.setOnClickListener(v -> showDevices(null));
        p.addView(device, matchWrap());

        progressBar = seekBar();
        LinearLayout.LayoutParams barLp = matchWrap();
        barLp.topMargin = dp(24);
        p.addView(progressBar, barLp);
        progressBar.setOnSeekBarChangeListener(new SeekAdapter() {
            @Override public void onProgressChanged(SeekBar sb, int value, boolean fromUser) {
                if (fromUser && elapsed != null) elapsed.setText(fmt(value));
            }
            @Override public void onStartTrackingTouch(SeekBar sb) { draggingProgress = true; }
            @Override public void onStopTrackingTouch(SeekBar sb) {
                draggingProgress = false;
                progressMs = sb.getProgress();
                progressAt = SystemClock.elapsedRealtime();
                command("PUT", "/me/player/seek?position_ms=" + progressMs, null);
            }
        });
        LinearLayout times = new LinearLayout(this);
        times.setPadding(dp(16), 0, dp(16), 0);
        elapsed = text("0:00", 13, false);
        duration = text("0:00", 13, false);
        times.addView(elapsed);
        times.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
        times.addView(duration);
        p.addView(times, matchWrap());

        LinearLayout controls = new LinearLayout(this);
        controls.setGravity(Gravity.CENTER);
        controls.setPadding(0, dp(8), 0, dp(4));
        shuffleBtn = action("⇄", 26, () -> {
            shuffle = !shuffle;
            updatePlayerViews();
            command("PUT", "/me/player/shuffle?state=" + shuffle, null);
        });
        controls.addView(shuffleBtn);
        controls.addView(action("|◀", 34, () -> command("POST", "/me/player/previous", null)));
        play = action("▶", 54, () -> {
            boolean wasPlaying = isPlaying;
            progressMs = currentProgress();
            progressAt = SystemClock.elapsedRealtime();
            isPlaying = !wasPlaying;
            updatePlayerViews();
            command("PUT", wasPlaying ? "/me/player/pause" : "/me/player/play", null);
        });
        controls.addView(play);
        controls.addView(action("▶|", 34, () -> command("POST", "/me/player/next", null)));
        repeatBtn = action("↻", 26, () -> {
            repeat = "off".equals(repeat) ? "context" : "context".equals(repeat) ? "track" : "off";
            updatePlayerViews();
            command("PUT", "/me/player/repeat?state=" + repeat, null);
        });
        controls.addView(repeatBtn);
        p.addView(controls, matchWrap());

        LinearLayout vol = new LinearLayout(this);
        vol.setGravity(Gravity.CENTER_VERTICAL);
        vol.addView(action("−", 22, () -> changeVolume(-10)));
        volumeBar = seekBar();
        volumeBar.setMax(100);
        volumeBar.setOnSeekBarChangeListener(new SeekAdapter() {
            @Override public void onStartTrackingTouch(SeekBar sb) { draggingVolume = true; }
            @Override public void onStopTrackingTouch(SeekBar sb) { draggingVolume = false; setVolume(sb.getProgress()); }
        });
        vol.addView(volumeBar, new LinearLayout.LayoutParams(0, -2, 1));
        vol.addView(action("+", 22, () -> changeVolume(10)));
        p.addView(vol, matchWrap());

        LinearLayout toggles = new LinearLayout(this);
        toggles.setGravity(Gravity.CENTER);
        toggles.setPadding(0, dp(12), 0, 0);
        toggles.addView(toggle("SCREEN ON", keepOn, on -> { keepOn = on; saveSettings(); applyKeepOn(); }));
        toggles.addView(toggle("AUTO-DIM", autoDim, on -> { autoDim = on; saveSettings(); }));
        toggles.addView(toggle("NIGHT", nightMode, on -> { nightMode = on; saveSettings(); setDimmed(on); }));
        p.addView(toggles, matchWrap());

        if (!spotify.isConnected()) p.addView(action("CONNECT TO SPOTIFY", 20, this::login));

        updateClock();
        updatePlayerViews();
        refreshPlayback();
    }

    private void showSearch() {
        clearContent(Screen.SEARCH);
        heading("Search");
        EditText q = new EditText(this);
        q.setSingleLine(true);
        q.setTextColor(GREEN);
        q.setHintTextColor(HINT);
        q.setHint("Song, artist or album");
        q.setText(lastQuery);
        q.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        q.setBackgroundTintList(ColorStateList.valueOf(GREEN));
        content.addView(q, matchWrap());
        TextView go = action("SEARCH", 18, null);
        content.addView(go);
        LinearLayout list = scrollList();
        content.addView(action("BACK", 17, this::showPlayer));

        Runnable run = () -> {
            lastQuery = q.getText().toString().trim();
            if (lastQuery.isEmpty()) return;
            hideKeyboard(q);
            list.removeAllViews();
            if (requireConnected(list)) searchPage(list, lastQuery, 0);
        };
        go.setOnClickListener(v -> run.run());
        q.setOnEditorActionListener((v, actionId, ev) -> {
            if (actionId != EditorInfo.IME_ACTION_SEARCH) return false;
            run.run();
            return true;
        });
        if (requireConnected(list) && !lastQuery.isEmpty()) searchPage(list, lastQuery, 0);
    }

    private void searchPage(LinearLayout list, String query, int offset) {
        TextView loading = text("Searching…", 15, false);
        list.addView(loading);
        spotify.request("GET", "/search?type=track&limit=" + PAGE + "&offset=" + offset + "&q=" + Uri.encode(query), null, (code, body) -> {
            list.removeView(loading);
            if (code != 200) { list.addView(text(spotify.explain(code, body), 15, false)); return; }
            try {
                JSONObject tracks = new JSONObject(body).getJSONObject("tracks");
                JSONArray items = tracks.getJSONArray("items");
                if (items.length() == 0 && offset == 0) list.addView(text("No results", 15, false));
                for (int i = 0; i < items.length(); i++) {
                    JSONObject t = items.optJSONObject(i);
                    if (t == null) continue;
                    String uri = t.optString("uri");
                    list.addView(action(t.optString("name") + "\n" + joinArtists(t), 17, () -> playTrack(uri)));
                }
                if (!tracks.isNull("next")) {
                    TextView more = action("MORE", 16, null);
                    more.setOnClickListener(v -> { list.removeView(more); searchPage(list, query, offset + PAGE); });
                    list.addView(more);
                }
            } catch (JSONException e) {
                list.addView(text("Couldn't read results", 15, false));
            }
        });
    }

    private void showPlaylists() {
        clearContent(Screen.PLAYLISTS);
        heading("Playlists");
        LinearLayout list = scrollList();
        content.addView(action("BACK", 17, this::showPlayer));
        if (requireConnected(list)) playlistPage(list, "/me/playlists?limit=50");
    }

    private void playlistPage(LinearLayout list, String path) {
        TextView loading = text("Loading…", 15, false);
        list.addView(loading);
        spotify.request("GET", path, null, (code, body) -> {
            list.removeView(loading);
            if (code != 200) { list.addView(text(spotify.explain(code, body), 15, false)); return; }
            try {
                JSONObject page = new JSONObject(body);
                JSONArray items = page.getJSONArray("items");
                if (items.length() == 0 && list.getChildCount() == 0) list.addView(text("No playlists", 15, false));
                for (int i = 0; i < items.length(); i++) {
                    JSONObject pl = items.optJSONObject(i);
                    if (pl == null) continue;
                    String uri = pl.optString("uri");
                    JSONObject owner = pl.optJSONObject("owner");
                    String by = owner == null ? "" : str(owner, "display_name");
                    list.addView(action(str(pl, "name") + (by.isEmpty() ? "" : "\n" + by), 18, () -> playContext(uri)));
                }
                String next = str(page, "next");
                if (!next.isEmpty()) {
                    TextView more = action("MORE", 16, null);
                    more.setOnClickListener(v -> { list.removeView(more); playlistPage(list, next); });
                    list.addView(more);
                }
            } catch (JSONException e) {
                list.addView(text("Couldn't read playlists", 15, false));
            }
        });
    }

    private void showDevices(String note) {
        clearContent(Screen.DEVICES);
        heading("Play on");
        if (note != null) content.addView(text(note, 15, false));
        LinearLayout list = scrollList();
        content.addView(action("OPEN SPOTIFY ON THIS PHONE", 15, this::openSpotifyApp));
        content.addView(action("REFRESH", 15, () -> showDevices(null)));
        content.addView(action("BACK", 17, this::showPlayer));
        if (!requireConnected(list)) return;
        TextView loading = text("Loading…", 15, false);
        list.addView(loading);
        spotify.request("GET", "/me/player/devices", null, (code, body) -> {
            list.removeView(loading);
            if (code != 200) { list.addView(text(spotify.explain(code, body), 15, false)); return; }
            List<JSONObject> devices = parseDevices(body);
            if (devices.isEmpty())
                list.addView(text("No devices found.\nOpen Spotify on this phone, a computer or a speaker, then tap REFRESH.", 15, false));
            for (JSONObject d : devices) {
                String id = str(d, "id"), name = str(d, "name");
                boolean active = d.optBoolean("is_active"), restricted = d.optBoolean("is_restricted");
                String label = (active ? "● " : "") + name + "\n" + str(d, "type").toLowerCase(Locale.ROOT)
                        + (restricted ? " · can't be controlled" : "");
                TextView item = action(label, 18, () -> {
                    status("Switching to " + name + "…");
                    transfer(id, isPlaying, this::showPlayer);
                });
                if (restricted || id.isEmpty()) { item.setEnabled(false); item.setAlpha(.4f); }
                list.addView(item);
            }
        });
    }

    private void showInfo() {
        clearContent(Screen.INFO);
        heading("Diagnostics");
        LinearLayout list = scrollList();
        list.addView(text("Session: " + (spotify.isConnected() ? "connected" : "disconnected"), 16, false));
        list.addView(text("Client ID in this build: " + spotify.clientIdHint() + "\nRedirect URI: " + SpotifyClient.REDIRECT, 14, false));
        TextView result = text("Last request:\n" + spotify.lastDiagnostic, 14, false);
        result.setPadding(dp(8), dp(20), dp(8), dp(20));
        list.addView(result);
        list.addView(action("TEST ACCOUNT", 17, () -> spotify.request("GET", "/me", null, (code, body) -> {
            if (code != 200) { result.setText("Account check failed\n" + spotify.explain(code, body)); return; }
            try {
                JSONObject j = new JSONObject(body);
                String name = str(j, "display_name");
                result.setText("Connected as: " + (name.isEmpty() ? str(j, "id") : name) + "\nAccount ID: " + str(j, "id"));
            } catch (JSONException e) {
                result.setText("Account response unreadable");
            }
        })));
        list.addView(action("DISCONNECT AND RECONNECT", 16, () -> { forgetSession(); login(); }));
        list.addView(action("DISCONNECT", 16, () -> { forgetSession(); status("Disconnected"); showPlayer(); }));
        content.addView(action("BACK", 17, this::showPlayer));
    }

    // ================================================================ playback

    private void refreshPlayback() {
        if (!spotify.isConnected() || refreshing) return;
        refreshing = true;
        spotify.request("GET", "/me/player?additional_types=track,episode", null, (code, body) -> {
            refreshing = false;
            if (code == 204 || (code == 200 && body.trim().isEmpty())) {
                hasPlayback = false;
                isPlaying = false;
                updatePlayerViews();
                return;
            }
            if (code != 200) { if (code != 0) error(spotify.explain(code, body)); return; }
            try { parsePlayback(new JSONObject(body)); } catch (JSONException e) { return; }
            if (errorShown) status("");
            updatePlayerViews();
        });
    }

    private void parsePlayback(JSONObject j) {
        hasPlayback = true;
        isPlaying = j.optBoolean("is_playing");
        shuffle = j.optBoolean("shuffle_state");
        repeat = j.optString("repeat_state", "off");
        progressMs = j.optLong("progress_ms");
        progressAt = SystemClock.elapsedRealtime();
        JSONObject dev = j.optJSONObject("device");
        if (dev != null) {
            deviceName = str(dev, "name");
            supportsVolume = dev.optBoolean("supports_volume", true);
            volume = dev.isNull("volume_percent") ? -1 : dev.optInt("volume_percent", -1);
            String id = str(dev, "id");
            if (!id.isEmpty() && !id.equals(settings.getString("lastDevice", ""))) settings.edit().putString("lastDevice", id).apply();
        }
        JSONObject item = j.optJSONObject("item");
        if (item == null) { trackName = "Unknown item"; artistName = ""; durationMs = 0; return; }
        trackName = str(item, "name");
        durationMs = item.optLong("duration_ms");
        JSONObject show = item.optJSONObject("show");
        artistName = item.has("artists") ? joinArtists(item) : show != null ? str(show, "name") : "";
    }

    private void command(String method, String path, String json) { command(method, path, json, true); }

    private void command(String method, String path, String json, boolean recoverDevice) {
        spotify.request(method, path, json, (code, body) -> {
            if (code >= 200 && code < 300) {
                if (errorShown) status("");
                handler.postDelayed(this::refreshPlayback, 700);
            } else if (code == 404 && recoverDevice) {
                recoverDevice(method, path, json);
            } else {
                error(spotify.explain(code, body));
                refreshPlayback();
            }
        });
    }

    /** No active device: reuse the last one (or the only one), otherwise ask the user to choose. */
    private void recoverDevice(String method, String path, String json) {
        status("Looking for a playback device…");
        spotify.request("GET", "/me/player/devices", null, (code, body) -> {
            if (code != 200) { error(spotify.explain(code, body)); return; }
            List<JSONObject> devices = parseDevices(body);
            String remembered = settings.getString("lastDevice", "");
            JSONObject pick = null;
            for (JSONObject d : devices) if (str(d, "id").equals(remembered) && !d.optBoolean("is_restricted")) pick = d;
            if (pick == null && devices.size() == 1 && !devices.get(0).optBoolean("is_restricted")) pick = devices.get(0);
            if (pick == null) {
                showDevices(devices.isEmpty() ? "Nothing is playing anywhere. Open Spotify on a device first." : "Choose where to play");
                return;
            }
            String id = str(pick, "id");
            status("Using " + str(pick, "name"));
            if (path.startsWith("/me/player/play")) command(method, withDevice(path, id), json, false);
            else transfer(id, false, () -> command(method, path, json, false));
        });
    }

    private void transfer(String id, boolean playNow, Runnable after) {
        try {
            JSONObject j = new JSONObject();
            j.put("device_ids", new JSONArray().put(id));
            j.put("play", playNow);
            spotify.request("PUT", "/me/player", j.toString(), (code, body) -> {
                if (code < 200 || code >= 300) { error(spotify.explain(code, body)); return; }
                settings.edit().putString("lastDevice", id).apply();
                handler.postDelayed(after, 600);
            });
        } catch (JSONException e) {
            error("Couldn't build request");
        }
    }

    private void playTrack(String uri) {
        try {
            JSONObject j = new JSONObject();
            j.put("uris", new JSONArray().put(uri));
            command("PUT", "/me/player/play", j.toString());
            showPlayer();
        } catch (JSONException ignored) { }
    }

    private void playContext(String uri) {
        try {
            JSONObject j = new JSONObject();
            j.put("context_uri", uri);
            command("PUT", "/me/player/play", j.toString());
            showPlayer();
        } catch (JSONException ignored) { }
    }

    private void changeVolume(int delta) {
        if (!supportsVolume || volume < 0) { error("This device doesn't allow volume control"); return; }
        setVolume(volume + delta);
    }

    private void setVolume(int v) {
        volume = Math.max(0, Math.min(100, v));
        updatePlayerViews();
        command("PUT", "/me/player/volume?volume_percent=" + volume, null);
    }

    private void updatePlayerViews() {
        if (screen != Screen.PLAYER || title == null) return;
        if (!spotify.isConnected()) {
            title.setText("Not connected");
            artist.setText("");
            device.setText("");
        } else if (!hasPlayback) {
            title.setText("Nothing playing");
            artist.setText("Tap ◎ to choose a device");
            device.setText("");
        } else {
            title.setText(trackName);
            artist.setText(artistName);
            device.setText(deviceName.isEmpty() ? "" : "on " + deviceName);
        }
        play.setText(isPlaying ? "Ⅱ" : "▶");
        shuffleBtn.setAlpha(shuffle ? 1f : OFF);
        repeatBtn.setText("track".equals(repeat) ? "↻1" : "↻");
        repeatBtn.setAlpha("off".equals(repeat) ? OFF : 1f);
        progressBar.setMax((int) Math.max(durationMs, 1));
        duration.setText(fmt(durationMs));
        boolean canVolume = hasPlayback && supportsVolume && volume >= 0;
        volumeBar.setEnabled(canVolume);
        volumeBar.setAlpha(canVolume ? 1f : OFF);
        if (!draggingVolume && volume >= 0) volumeBar.setProgress(volume);
        updateProgressViews();
    }

    private long currentProgress() {
        long p = progressMs;
        if (isPlaying) p += SystemClock.elapsedRealtime() - progressAt;
        return durationMs > 0 ? Math.min(p, durationMs) : p;
    }

    private void updateProgressViews() {
        if (progressBar == null || draggingProgress) return;
        long p = hasPlayback ? currentProgress() : 0;
        progressBar.setProgress((int) p);
        elapsed.setText(fmt(p));
    }

    // ================================================================ misc actions

    private void login() {
        if (!spotify.hasClientId()) {
            error("No Spotify Client ID in this build. Set the SPOTIFY_CLIENT_ID repository variable and rebuild.");
            return;
        }
        try {
            spotify.login();
            status("Opening Spotify login…");
        } catch (ActivityNotFoundException e) {
            error("No browser found to open the Spotify login page");
        } catch (Exception e) {
            error("Couldn't start login: " + e.getMessage());
        }
    }

    private void forgetSession() {
        spotify.clearSession();
        hasPlayback = false;
        isPlaying = false;
    }

    private void openSpotifyApp() {
        Intent i = getPackageManager().getLaunchIntentForPackage("com.spotify.music");
        if (i == null) error("The Spotify app isn't installed on this phone");
        else startActivity(i);
    }

    private boolean requireConnected(LinearLayout list) {
        if (spotify.isConnected()) return true;
        list.addView(action("CONNECT TO SPOTIFY", 20, this::login));
        return false;
    }

    // ================================================================ OLED / window

    private void setDimmed(boolean d) {
        dimmed = d;
        if (shifter != null) shifter.setAlpha(d ? .35f : 1f);
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        // Lowers brightness for this app's window only; system brightness is untouched.
        lp.screenBrightness = d ? 0.01f : WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
        getWindow().setAttributes(lp);
    }

    private void applyKeepOn() {
        if (keepOn) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    @SuppressWarnings("deprecation")
    private void hideSystemUi() {
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
    }

    private void saveSettings() {
        settings.edit().putBoolean("keepOn", keepOn).putBoolean("nightMode", nightMode).putBoolean("autoDim", autoDim).apply();
    }

    private void updateClock() {
        if (clock == null) return;
        String pattern = DateFormat.is24HourFormat(this) ? "HH:mm" : "h:mm";
        clock.setText(new SimpleDateFormat(pattern, Locale.getDefault()).format(new Date()));
    }

    private void updateBattery() {
        if (battery == null) return;
        BatteryManager bm = getSystemService(BatteryManager.class);
        int pct = bm == null ? -1 : bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
        battery.setText(pct > 0 ? pct + "%" : "--%");
    }

    private void hideKeyboard(View v) {
        InputMethodManager imm = getSystemService(InputMethodManager.class);
        if (imm != null) imm.hideSoftInputFromWindow(v.getWindowToken(), 0);
    }

    // ================================================================ status line

    private void status(String s) { errorShown = false; if (message != null) message.setText(s); }
    private void error(String s) { errorShown = true; if (message != null) message.setText(s); }

    // ================================================================ view helpers

    private interface Toggle { void set(boolean on); }

    private abstract static class SeekAdapter implements SeekBar.OnSeekBarChangeListener {
        @Override public void onProgressChanged(SeekBar sb, int value, boolean fromUser) { }
        @Override public void onStartTrackingTouch(SeekBar sb) { }
        @Override public void onStopTrackingTouch(SeekBar sb) { }
    }

    private int dp(float d) { return Math.round(d * getResources().getDisplayMetrics().density); }
    private float dpf(float d) { return d * getResources().getDisplayMetrics().density; }

    private LinearLayout vertical() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setGravity(Gravity.CENTER_HORIZONTAL);
        return l;
    }

    private LinearLayout.LayoutParams matchWrap() { return new LinearLayout.LayoutParams(-1, -2); }

    private TextView text(String s, int size, boolean bold) {
        TextView v = new TextView(this);
        v.setText(s);
        v.setTextColor(GREEN);
        v.setTextSize(size);
        v.setGravity(Gravity.CENTER);
        if (bold) v.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return v;
    }

    private TextView oneLine(TextView v) {
        v.setSingleLine(true);
        v.setEllipsize(TextUtils.TruncateAt.END);
        return v;
    }

    /** A tappable label with a subtle green ripple. A null action means the caller sets its own listener. */
    private TextView action(String s, int size, Runnable r) {
        TextView v = text(s, size, false);
        v.setPadding(dp(12), dp(12), dp(12), dp(12));
        v.setBackground(new RippleDrawable(ColorStateList.valueOf(0x4000FF00), null, new ColorDrawable(Color.WHITE)));
        if (r != null) v.setOnClickListener(x -> r.run());
        return v;
    }

    private TextView icon(String glyph, String description, Runnable r) {
        TextView v = action(glyph, 26, r);
        v.setContentDescription(description);
        v.setMinWidth(dp(48));
        return v;
    }

    private TextView toggle(String label, boolean initial, Toggle t) {
        TextView v = action(label, 13, null);
        v.setPadding(dp(10), dp(12), dp(10), dp(12));
        v.setAlpha(initial ? 1f : OFF);
        v.setOnClickListener(x -> {
            boolean on = v.getAlpha() < .5f;
            v.setAlpha(on ? 1f : OFF);
            t.set(on);
        });
        return v;
    }

    private SeekBar seekBar() {
        SeekBar s = new SeekBar(this);
        ColorStateList g = ColorStateList.valueOf(GREEN);
        s.setProgressTintList(g);
        s.setThumbTintList(g);
        s.setProgressBackgroundTintList(ColorStateList.valueOf(TRACK));
        return s;
    }

    private void heading(String s) {
        TextView h = text(s, 22, true);
        h.setPadding(0, dp(4), 0, dp(12));
        content.addView(h, matchWrap());
    }

    private LinearLayout scrollList() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout list = vertical();
        scroll.addView(list, new FrameLayout.LayoutParams(-1, -2));
        content.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        return list;
    }

    // ================================================================ JSON helpers

    /** optString returns "null" for JSON nulls; this returns "". */
    private static String str(JSONObject o, String key) { return o.isNull(key) ? "" : o.optString(key, ""); }

    private static String joinArtists(JSONObject item) {
        JSONArray a = item.optJSONArray("artists");
        if (a == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null) continue;
            if (sb.length() > 0) sb.append(", ");
            sb.append(str(o, "name"));
        }
        return sb.toString();
    }

    private static List<JSONObject> parseDevices(String body) {
        List<JSONObject> out = new ArrayList<>();
        try {
            JSONArray a = new JSONObject(body).getJSONArray("devices");
            for (int i = 0; i < a.length(); i++) { JSONObject d = a.optJSONObject(i); if (d != null) out.add(d); }
        } catch (JSONException ignored) { }
        return out;
    }

    private static String withDevice(String path, String id) {
        return path + (path.contains("?") ? "&" : "?") + "device_id=" + SpotifyClient.enc(id);
    }

    private static String fmt(long ms) {
        long s = Math.max(0, ms / 1000);
        return (s / 60) + ":" + (s % 60 < 10 ? "0" : "") + (s % 60);
    }
}
