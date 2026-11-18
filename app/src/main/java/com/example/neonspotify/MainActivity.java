package com.example.neonspotify;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.format.DateFormat;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** AMOLED-black Spotify remote. Single activity, framework views only. */
public class MainActivity extends Activity implements SpotifyClient.Listener {
    private static final int BLACK = Color.BLACK;
    private static final float OFF = .35f;
    private static final long SHIFT_MS = 30_000;
    private static final int PAGE = 10; // Spotify's Development Mode search limit since Feb 2026

    // Highlight colours: solid colours first, then gradients (start, end).
    private static final String[] COLOR_NAMES = {"Neon", "Cyan", "Magenta", "Amber", "Red", "Violet", "White",
            "Aurora", "Vapor", "Sunset", "Ocean"};
    private static final int[][] COLORS = {
            {0xFF00FF00, 0xFF00FF00}, {0xFF00E5FF, 0xFF00E5FF}, {0xFFFF00FF, 0xFFFF00FF}, {0xFFFFB000, 0xFFFFB000},
            {0xFFFF1744, 0xFFFF1744}, {0xFFB388FF, 0xFFB388FF}, {0xFFFFFFFF, 0xFFFFFFFF},
            {0xFF00FF00, 0xFF00E5FF}, {0xFFFF00FF, 0xFF00E5FF}, {0xFFFFB000, 0xFFFF1744}, {0xFF2979FF, 0xFF00E5FF}};
    private static final String[] IDLE_MODES = {"Nothing", "Dim", "Clock & song", "Clock & song + dim"};
    private static final int[] IDLE_SECONDS = {30, 60, 120, 300};
    private static final String[] IDLE_LABELS = {"30 sec", "1 min", "2 min", "5 min"};
    private static final int[] SLEEP_MINUTES = {0, 15, 30, 45, 60, 90, -1}; // -1 = after the current song
    private static final String[] SLEEP_LABELS = {"Off", "15 min", "30 min", "45 min", "60 min", "90 min", "End of song"};

    private enum Screen { PLAYER, QUEUE, SEARCH, PLAYLISTS, PLAYLIST, DEVICES, SETTINGS, SETUP }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService images = Executors.newSingleThreadExecutor();
    private SpotifyClient spotify;
    private SharedPreferences settings;
    private Screen screen = Screen.PLAYER;

    // Shell views
    private LinearLayout shifter, content, topBar;
    private TextView battery, message;
    private boolean errorShown;

    // Player views (null unless the player screen is showing)
    private TextView clock, title, artist, device, elapsed, duration, play, shuffleBtn, repeatBtn;
    private SeekBar progressBar;
    private ImageView art;
    private View ctrlColumn;
    private boolean draggingProgress;

    // Playback state
    private boolean hasPlayback, isPlaying, shuffle, refreshing;
    private String repeat = "off", trackName = "", artistName = "", deviceName = "", trackUri = "";
    private long progressMs, durationMs, progressAt;
    private String endRefreshFor = "", lastQuery = "";
    private String artUrl = "", pendingArtUrl = "";
    private Bitmap artBitmap;
    /** Songs swiped out of the queue. Spotify can't remove queue items, so these are skipped when they come up. */
    private final Set<String> skipUris = new HashSet<>();

    // Sleep timer
    private int sleepChoice;
    private long sleepAt;
    private boolean sleepEndOfSong;
    private String sleepSongUri = "";
    private final Runnable sleepTimer = this::sleepNow;

    // Settings
    private int colorIndex, accent, accent2, idleMode, idleChoice, brightNormal, brightDim;
    private boolean keepOn, showArt, pixelShift;

    // Idle state
    private boolean idling, minimal, swallowing;
    private long lastTouch = SystemClock.elapsedRealtime();
    private int ticks;

    // ================================================================ lifecycle

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        settings = getSharedPreferences("settings", MODE_PRIVATE);
        loadSettings();
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
        if (spotify.hasClientId()) showPlayer(); else showSetup();
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
        exitIdle();
        handler.removeCallbacks(tick);
        handler.removeCallbacks(poll);
        handler.post(tick);
        handler.post(poll);
        if (sleepAt > 0 && System.currentTimeMillis() >= sleepAt) sleepNow();
    }

    @Override protected void onPause() {
        // No polling or timers while the screen is off or the app is hidden (the sleep timer keeps running).
        handler.removeCallbacks(tick);
        handler.removeCallbacks(poll);
        super.onPause();
    }

    @Override protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        spotify.shutdown();
        images.shutdownNow();
        super.onDestroy();
    }

    /** Rotation is handled here (see configChanges in the manifest) so state and typed text survive. */
    @Override public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        applyShellPadding();
        if (screen == Screen.PLAYER) showPlayer();
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemUi();
    }

    @SuppressWarnings("deprecation")
    @Override public void onBackPressed() {
        if (screen == Screen.PLAYLIST) showPlaylists();
        else if (screen != Screen.PLAYER) showPlayer();
        else super.onBackPressed();
    }

    @Override public boolean dispatchTouchEvent(MotionEvent ev) {
        lastTouch = SystemClock.elapsedRealtime();
        int a = ev.getActionMasked();
        // The first touch on an idle screen only wakes it, so it can't press a button by accident.
        if (a == MotionEvent.ACTION_DOWN && idling) { exitIdle(); swallowing = true; }
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
            if (sleepAt > 0 || sleepEndOfSong) updateDeviceLine();
            if (pixelShift) {
                // Burn-in protection: drift the whole UI a few dp every 30 s.
                long step = System.currentTimeMillis() / SHIFT_MS;
                shifter.setTranslationX(dpf((float) Math.sin(step * 0.7) * 6f));
                shifter.setTranslationY(dpf((float) Math.cos(step * 1.3) * 6f));
            } else {
                shifter.setTranslationX(0);
                shifter.setTranslationY(0);
            }
            if (idleMode != 0 && !idling && screen == Screen.PLAYER
                    && SystemClock.elapsedRealtime() - lastTouch > IDLE_SECONDS[idleChoice] * 1000L) enterIdle();
            // Refresh right when a song should end: faster next-song display, queue auto-skip and sleep-after-song.
            if (isPlaying && durationMs > 0 && !trackUri.isEmpty() && !trackUri.equals(endRefreshFor)
                    && currentProgress() >= durationMs - 300) {
                endRefreshFor = trackUri;
                handler.postDelayed(MainActivity.this::refreshPlayback, 1200);
            }
            handler.postDelayed(this, 1000);
        }
    };

    private final Runnable poll = new Runnable() {
        @Override public void run() {
            boolean limited = spotify.isRateLimited();
            if (spotify.isConnected() && !limited) refreshPlayback();
            long delay = limited ? 20_000 : (screen == Screen.PLAYER && isPlaying) ? 5_000 : 15_000;
            handler.postDelayed(this, delay);
        }
    };

    // ================================================================ shell

    private void buildShell() {
        FrameLayout frame = new FrameLayout(this);
        frame.setBackgroundColor(BLACK);
        setContentView(frame);
        shifter = vertical();
        applyShellPadding();
        frame.addView(shifter, new FrameLayout.LayoutParams(-1, -1));

        topBar = new LinearLayout(this);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        battery = text("--%", 16, false);
        topBar.addView(battery);
        topBar.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
        topBar.addView(icon("◎", "Devices", () -> showDevices(null)));
        topBar.addView(icon("♫︎", "Up next", this::showQueue));
        topBar.addView(icon("⌕", "Search", this::showSearch));
        topBar.addView(icon("☷", "Playlists", this::showPlaylists));
        topBar.addView(icon("⚙︎", "Settings", this::showSettings));
        shifter.addView(topBar, new LinearLayout.LayoutParams(-1, dp(52)));

        content = vertical();
        shifter.addView(content, new LinearLayout.LayoutParams(-1, 0, 1));

        message = text("", 13, false);
        message.setAlpha(.8f);
        message.setPadding(dp(8), dp(4), dp(8), dp(4));
        shifter.addView(message, new LinearLayout.LayoutParams(-1, -2));
        updateBattery();
    }

    private void applyShellPadding() {
        if (shifter == null) return;
        // Landscape: extra side padding clears the camera cutout; less top padding saves height.
        if (isLandscape()) shifter.setPadding(dp(40), dp(8), dp(40), dp(4));
        else shifter.setPadding(dp(16), dp(28), dp(16), dp(12));
    }

    private void clearContent(Screen s) {
        screen = s;
        content.removeAllViews();
        clock = title = artist = device = elapsed = duration = play = shuffleBtn = repeatBtn = null;
        progressBar = null;
        art = null;
        ctrlColumn = null;
        draggingProgress = false;
        if (minimal) setMinimal(false);
    }

    // ================================================================ player

    private void showPlayer() {
        clearContent(Screen.PLAYER);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        content.addView(scroll, new LinearLayout.LayoutParams(-1, -1));
        LinearLayout p = vertical();
        p.setGravity(Gravity.CENTER);
        scroll.addView(p, new FrameLayout.LayoutParams(-1, -1));

        // Portrait: one centred column. Landscape: track info on the left, controls on the right.
        boolean landscape = isLandscape();
        LinearLayout info = vertical(), ctrl = vertical();
        info.setGravity(Gravity.CENTER);
        ctrl.setGravity(Gravity.CENTER);
        ctrlColumn = ctrl;
        if (landscape) {
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.addView(info, new LinearLayout.LayoutParams(0, -2, 1));
            LinearLayout.LayoutParams ctrlLp = new LinearLayout.LayoutParams(0, -2, 1);
            ctrlLp.leftMargin = dp(16);
            row.addView(ctrl, ctrlLp);
            p.addView(row, matchWrap());
        } else {
            p.addView(info, matchWrap());
            p.addView(ctrl, matchWrap());
        }

        clock = text("--:--", clockSize(), false);
        clock.setTypeface(Typeface.create("sans-serif-thin", Typeface.NORMAL));
        info.addView(clock, matchWrap());

        art = new ImageView(this);
        art.setScaleType(ImageView.ScaleType.CENTER_CROP);
        GradientDrawable artBg = new GradientDrawable();
        artBg.setCornerRadius(dp(12));
        artBg.setColor(BLACK);
        art.setBackground(artBg);
        art.setClipToOutline(true);
        int artSize = dp(landscape ? 130 : 220);
        LinearLayout.LayoutParams artLp = new LinearLayout.LayoutParams(artSize, artSize);
        artLp.gravity = Gravity.CENTER_HORIZONTAL;
        artLp.topMargin = dp(4);
        artLp.bottomMargin = dp(12);
        info.addView(art, artLp);

        title = oneLine(text("", 26, true));
        info.addView(title, matchWrap());
        artist = oneLine(text("", 19, false));
        artist.setAlpha(.75f);
        info.addView(artist, matchWrap());
        device = oneLine(text("", 14, false));
        device.setAlpha(.55f);
        device.setPadding(0, dp(4), 0, 0);
        device.setOnClickListener(v -> showDevices(null));
        info.addView(device, matchWrap());

        progressBar = seekBar();
        LinearLayout.LayoutParams barLp = matchWrap();
        barLp.topMargin = dp(landscape ? 0 : 24);
        ctrl.addView(progressBar, barLp);
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
        ctrl.addView(times, matchWrap());

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
        ctrl.addView(controls, matchWrap());

        if (!spotify.isConnected()) ctrl.addView(action("CONNECT TO SPOTIFY", 20, this::login));

        updateClock();
        updatePlayerViews();
        if (idling && idleMode >= 2) setMinimal(true);
        refreshPlayback();
    }

    private int clockSize() {
        boolean landscape = isLandscape();
        if (minimal) return landscape ? 110 : 96;
        return landscape ? 72 : 64;
    }

    private void updatePlayerViews() {
        if (screen != Screen.PLAYER || title == null) return;
        if (!spotify.isConnected()) {
            title.setText("Not connected");
            artist.setText("");
        } else if (!hasPlayback) {
            title.setText("Nothing playing");
            artist.setText("Tap ◎ to choose a device");
        } else {
            title.setText(trackName);
            artist.setText(artistName);
        }
        updateDeviceLine();
        play.setText(isPlaying ? "Ⅱ" : "▶");
        shuffleBtn.setAlpha(shuffle ? 1f : OFF);
        repeatBtn.setText("track".equals(repeat) ? "↻1" : "↻");
        repeatBtn.setAlpha("off".equals(repeat) ? OFF : 1f);
        progressBar.setMax((int) Math.max(durationMs, 1));
        duration.setText(fmt(durationMs));
        updateProgressViews();
        updateArt();
    }

    private void updateDeviceLine() {
        if (device == null) return;
        String s = hasPlayback && !deviceName.isEmpty() ? "on " + deviceName : "";
        if (sleepAt > 0) {
            long mins = Math.max(1, (sleepAt - System.currentTimeMillis() + 59_999) / 60_000);
            s += (s.isEmpty() ? "" : " · ") + "sleep in " + mins + " min";
        } else if (sleepEndOfSong) {
            s += (s.isEmpty() ? "" : " · ") + "sleep after this song";
        }
        if (!s.contentEquals(device.getText())) device.setText(s);
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

    private void updateArt() {
        if (art == null) return;
        boolean show = showArt && artBitmap != null && !minimal && hasPlayback;
        art.setVisibility(show ? View.VISIBLE : View.GONE);
        if (show) art.setImageBitmap(artBitmap);
    }

    private void loadArt(String url) {
        if (url.equals(artUrl)) { updateArt(); return; }
        artUrl = url;
        artBitmap = null;
        updateArt();
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
                if (!url.equals(artUrl) || isDestroyed()) return;
                artBitmap = result;
                updateArt();
            });
        });
    }

    // ================================================================ queue

    private void showQueue() {
        clearContent(Screen.QUEUE);
        heading("Up next");
        TextView hint = text("Tap a song to skip to it · swipe it away to remove it", 13, false);
        hint.setAlpha(.6f);
        content.addView(hint, matchWrap());
        LinearLayout list = scrollList();
        content.addView(action("BACK", 17, this::showPlayer));
        if (!requireConnected(list)) return;
        TextView loading = text("Loading…", 15, false);
        list.addView(loading);
        spotify.request("GET", "/me/player/queue", null, (code, body) -> {
            list.removeView(loading);
            if (code != 200) { list.addView(text(spotify.explain(code, body), 15, false)); return; }
            try {
                JSONObject j = new JSONObject(body);
                JSONObject now = j.optJSONObject("currently_playing");
                if (now != null) {
                    TextView n = text("Now playing: " + str(now, "name"), 15, false);
                    n.setAlpha(.6f);
                    n.setPadding(0, dp(4), 0, dp(8));
                    list.addView(n, matchWrap());
                }
                JSONArray q = j.optJSONArray("queue");
                int shown = 0;
                if (q != null) {
                    for (int i = 0; i < q.length(); i++) {
                        JSONObject t = q.optJSONObject(i);
                        if (t == null) continue;
                        String uri = str(t, "uri");
                        if (skipUris.contains(uri)) continue;
                        int steps = i + 1;
                        LinearLayout item = listRow(str(t, "name"), subtitle(t), 17, null, null);
                        item.setOnTouchListener(new SwipeDismiss(() -> skipTo(steps), () -> {
                            skipUris.add(uri);
                            list.removeView(item);
                            status("Removed. Spotify can't edit the queue, so the app skips it when it comes up.");
                        }));
                        list.addView(item, matchWrap());
                        shown++;
                    }
                }
                if (shown == 0) list.addView(text("Nothing queued", 15, false));
            } catch (JSONException e) {
                list.addView(text("Couldn't read the queue", 15, false));
            }
        });
    }

    private void skipTo(int steps) {
        status(steps == 1 ? "Skipping…" : "Skipping " + steps + " songs…");
        showPlayer();
        skipNext(steps);
    }

    private void skipNext(int remaining) {
        spotify.request("POST", "/me/player/next", null, (code, body) -> {
            if (code < 200 || code >= 300) { error(spotify.explain(code, body)); return; }
            if (remaining > 1) handler.postDelayed(() -> skipNext(remaining - 1), 250);
            else { status(""); handler.postDelayed(this::refreshPlayback, 700); }
        });
    }

    private void addToQueue(String uri, String name) {
        spotify.request("POST", "/me/player/queue?uri=" + SpotifyClient.enc(uri), null, (code, body) -> {
            if (code >= 200 && code < 300) { skipUris.remove(uri); status("Added to queue: " + name); }
            else error(spotify.explain(code, body));
        });
    }

    /** Horizontal swipe removes a row; a tap without movement runs onTap. Vertical drags go to the ScrollView. */
    private final class SwipeDismiss implements View.OnTouchListener {
        private final Runnable onTap, onDismiss;
        private final int slop = ViewConfiguration.get(MainActivity.this).getScaledTouchSlop();
        private float downX, downY;
        private boolean swiping;

        SwipeDismiss(Runnable onTap, Runnable onDismiss) { this.onTap = onTap; this.onDismiss = onDismiss; }

        @Override public boolean onTouch(View v, MotionEvent e) {
            float dx = e.getRawX() - downX, dy = e.getRawY() - downY;
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = e.getRawX();
                    downY = e.getRawY();
                    swiping = false;
                    v.setPressed(true);
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (!swiping && Math.abs(dx) > slop && Math.abs(dx) > Math.abs(dy)) {
                        swiping = true;
                        v.setPressed(false);
                        v.getParent().requestDisallowInterceptTouchEvent(true);
                    }
                    if (swiping) {
                        v.setTranslationX(dx);
                        v.setAlpha(Math.max(.15f, 1f - Math.abs(dx) / Math.max(1, v.getWidth())));
                    } else if (Math.abs(dy) > slop) {
                        v.setPressed(false);
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    v.setPressed(false);
                    if (swiping) {
                        if (Math.abs(dx) > v.getWidth() / 3f) {
                            v.animate().translationX(Math.signum(dx) * v.getWidth()).alpha(0f).setDuration(180).withEndAction(onDismiss).start();
                        } else {
                            v.animate().translationX(0).alpha(1f).setDuration(180).start();
                        }
                    } else if (Math.abs(dx) < slop && Math.abs(dy) < slop) {
                        v.performClick();
                        onTap.run();
                    }
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    v.setPressed(false);
                    v.animate().translationX(0).alpha(1f).setDuration(180).start();
                    return true;
                default:
                    return false;
            }
        }
    }

    // ================================================================ search & playlists

    private void showSearch() {
        clearContent(Screen.SEARCH);
        heading("Search");
        EditText q = input("Song, artist or album");
        q.setText(lastQuery);
        q.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        content.addView(q, matchWrap());
        TextView go = action("SEARCH", 18, null);
        content.addView(go);
        TextView hint = text("Tap to play · + adds to queue", 13, false);
        hint.setAlpha(.6f);
        content.addView(hint, matchWrap());
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
                    String uri = str(t, "uri"), name = str(t, "name");
                    LinearLayout r = listRow(name, joinArtists(t), 17, () -> playTrack(uri), () -> addToQueue(uri, name));
                    r.setOnLongClickListener(v -> { addToQueue(uri, name); return true; });
                    list.addView(r, matchWrap());
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
                    String id = str(pl, "id"), uri = str(pl, "uri"), name = str(pl, "name");
                    JSONObject owner = pl.optJSONObject("owner");
                    String by = owner == null ? "" : str(owner, "display_name");
                    list.addView(listRow(name, by, 19, () -> showPlaylist(id, uri, name), null), matchWrap());
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

    /** One playlist: play / shuffle-play it, tap a song to play from there, + to add a song to the queue. */
    private void showPlaylist(String id, String uri, String name) {
        clearContent(Screen.PLAYLIST);
        heading(name);
        LinearLayout buttons = new LinearLayout(this);
        buttons.setGravity(Gravity.CENTER);
        buttons.addView(action("▶  PLAY", 16, () -> playContext(uri)));
        buttons.addView(action("⇄  SHUFFLE", 16, () -> shufflePlay(uri)));
        content.addView(buttons, matchWrap());
        LinearLayout list = scrollList();
        content.addView(action("BACK", 17, this::showPlaylists));
        if (requireConnected(list)) playlistItems(list, uri, "/playlists/" + SpotifyClient.enc(id) + "/items?limit=50");
    }

    private void playlistItems(LinearLayout list, String playlistUri, String path) {
        TextView loading = text("Loading…", 15, false);
        list.addView(loading);
        spotify.request("GET", path, null, (code, body) -> {
            list.removeView(loading);
            if (code == 403) {
                list.addView(text("Spotify only lets this app list the songs in playlists you own or collaborate on. "
                        + "You can still play or shuffle this one.", 15, false), matchWrap());
                return;
            }
            if (code != 200) { list.addView(text(spotify.explain(code, body), 15, false)); return; }
            try {
                JSONObject page = new JSONObject(body);
                JSONArray items = page.getJSONArray("items");
                if (items.length() == 0 && list.getChildCount() == 0) list.addView(text("This playlist is empty", 15, false));
                for (int i = 0; i < items.length(); i++) {
                    JSONObject entry = items.optJSONObject(i);
                    if (entry == null) continue;
                    // Since Feb 2026 the song is under "item"; older responses used "track".
                    JSONObject t = entry.optJSONObject("item");
                    if (t == null) t = entry.optJSONObject("track");
                    if (t == null) continue;
                    String uri = str(t, "uri"), name = str(t, "name");
                    if (uri.isEmpty() || uri.startsWith("spotify:local:")) continue;
                    list.addView(listRow(name, subtitle(t), 17, () -> playInContext(playlistUri, uri), () -> addToQueue(uri, name)), matchWrap());
                }
                String next = str(page, "next");
                if (!next.isEmpty()) {
                    TextView more = action("MORE", 16, null);
                    more.setOnClickListener(v -> { list.removeView(more); playlistItems(list, playlistUri, next); });
                    list.addView(more);
                }
            } catch (JSONException e) {
                list.addView(text("Couldn't read this playlist", 15, false));
            }
        });
    }

    /** Plays a playlist starting from one of its songs. */
    private void playInContext(String contextUri, String trackUri) {
        try {
            JSONObject j = new JSONObject();
            j.put("context_uri", contextUri);
            j.put("offset", new JSONObject().put("uri", trackUri));
            command("PUT", "/me/player/play", j.toString());
            showPlayer();
        } catch (JSONException ignored) { }
    }

    private void shufflePlay(String contextUri) {
        shuffle = true;
        playContext(contextUri);
        handler.postDelayed(() -> command("PUT", "/me/player/shuffle?state=true", null), 900);
    }

    // ================================================================ devices

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
                list.addView(item, matchWrap());
            }
        });
    }

    // ================================================================ settings

    private interface Toggle { void set(boolean on); }
    private interface IntChoice { void pick(int value); }
    private interface Labeler { String label(int value); }

    private void showSettings() {
        clearContent(Screen.SETTINGS);
        heading("Settings");
        LinearLayout list = scrollList();

        // Each setting is followed by a thin divider in a colour that complements the highlight colour.
        section(list, "Sleep timer");
        setting(list, choices(SLEEP_LABELS, sleepChoice, this::setSleep));

        section(list, "Appearance");
        list.addView(label("Highlight colour"), matchWrap());
        HorizontalScrollView colors = choices(COLOR_NAMES, colorIndex, i -> {
            colorIndex = i;
            saveSettings();
            applyColors();
            buildShell();
            showSettings();
        });
        LinearLayout chips = (LinearLayout) colors.getChildAt(0);
        for (int i = 0; i < chips.getChildCount(); i++) tint((TextView) chips.getChildAt(i), COLORS[i][0], COLORS[i][1]);
        setting(list, colors);
        setting(list, switchRow("Show album art", showArt, on -> {
            showArt = on;
            saveSettings();
            if (on && !pendingArtUrl.isEmpty()) loadArt(pendingArtUrl);
        }));

        section(list, "Screen");
        setting(list, switchRow("Keep screen awake", keepOn, on -> { keepOn = on; saveSettings(); applyKeepOn(); }));
        list.addView(label("When idle"), matchWrap());
        setting(list, choices(IDLE_MODES, idleMode, i -> { idleMode = i; saveSettings(); }));
        list.addView(label("Idle after"), matchWrap());
        setting(list, choices(IDLE_LABELS, idleChoice, i -> { idleChoice = i; saveSettings(); }));
        setting(list, slider("Normal brightness", 0, brightNormal, v -> v == 0 ? "system" : v + "%",
                v -> { brightNormal = v; applyBrightness(false); }, this::saveSettings));
        // Dragging previews the dimmed level; letting go returns to normal.
        setting(list, slider("Dimmed brightness", 1, brightDim, v -> v + "%",
                v -> { brightDim = v; applyBrightness(true); }, () -> { saveSettings(); applyBrightness(false); }));
        setting(list, switchRow("Pixel shift (burn-in protection)", pixelShift, on -> { pixelShift = on; saveSettings(); }));

        section(list, "Spotify account");
        TextView account = text("Client ID: " + spotify.clientIdHint() + "\n"
                + (spotify.isConnected() ? "Connected" : "Not connected"), 14, false);
        list.addView(account, matchWrap());
        if (spotify.isConnected()) {
            list.addView(action("CHECK ACCOUNT", 15, () -> spotify.request("GET", "/me", null, (code, body) -> {
                if (code != 200) { account.setText(spotify.explain(code, body)); return; }
                try {
                    JSONObject j = new JSONObject(body);
                    String name = str(j, "display_name");
                    account.setText("Connected as " + (name.isEmpty() ? str(j, "id") : name) + "\nClient ID: " + spotify.clientIdHint());
                } catch (JSONException e) {
                    account.setText("Account response unreadable");
                }
            })));
        }
        list.addView(action("CHANGE CLIENT ID", 15, this::showSetup));
        if (spotify.isConnected()) {
            list.addView(action("DISCONNECT", 15, () -> { forgetSession(); status("Disconnected"); showSettings(); }));
        } else {
            list.addView(action("CONNECT TO SPOTIFY", 15, this::login));
        }
        content.addView(action("BACK", 17, this::showPlayer));
    }

    /** First-run / change screen for the Spotify Client ID, so it doesn't have to be baked into the build. */
    private void showSetup() {
        clearContent(Screen.SETUP);
        heading("Spotify setup");
        LinearLayout list = scrollList();
        TextView steps = text("1. Create an app at developer.spotify.com/dashboard and tick Web API.\n"
                + "2. Add this Redirect URI exactly: " + SpotifyClient.REDIRECT + "\n"
                + "3. Under User Management, add your Spotify account's name and email.\n"
                + "4. Copy the app's Client ID and paste it below.", 15, false);
        steps.setGravity(Gravity.START);
        steps.setLineSpacing(dp(4), 1f);
        list.addView(steps, matchWrap());
        list.addView(action("OPEN SPOTIFY DASHBOARD", 15, () -> {
            try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://developer.spotify.com/dashboard"))); }
            catch (ActivityNotFoundException e) { error("No browser found"); }
        }));
        list.addView(action("COPY REDIRECT URI", 15, () -> {
            ClipboardManager cm = getSystemService(ClipboardManager.class);
            if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("Redirect URI", SpotifyClient.REDIRECT));
            status("Copied " + SpotifyClient.REDIRECT);
        }));

        EditText id = input("Client ID (32 characters)");
        id.setText(spotify.clientId());
        id.setTypeface(Typeface.MONOSPACE);
        id.setImeOptions(EditorInfo.IME_ACTION_DONE);
        id.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        LinearLayout.LayoutParams idLp = matchWrap();
        idLp.topMargin = dp(12);
        list.addView(id, idLp);

        Runnable save = () -> {
            String value = id.getText().toString().trim();
            if (!SpotifyClient.isValidClientId(value)) {
                error("That doesn't look like a Client ID: it should be 32 letters and numbers (0-9, a-f).");
                return;
            }
            hideKeyboard(id);
            spotify.setClientId(value);
            status("Client ID saved");
            showPlayer();
            if (!spotify.isConnected()) login();
        };
        id.setOnEditorActionListener((v, actionId, ev) -> {
            if (actionId != EditorInfo.IME_ACTION_DONE) return false;
            save.run();
            return true;
        });
        list.addView(action("SAVE AND CONNECT", 18, save));
        if (spotify.hasClientId()) content.addView(action("BACK", 17, this::showPlayer));
    }

    private void loadSettings() {
        keepOn = settings.getBoolean("keepOn", true);
        showArt = settings.getBoolean("showArt", false);
        pixelShift = settings.getBoolean("pixelShift", true);
        idleMode = clamp(settings.getInt("idleMode", 2), 0, IDLE_MODES.length - 1);
        idleChoice = clamp(settings.getInt("idleChoice", 0), 0, IDLE_SECONDS.length - 1);
        brightNormal = clamp(settings.getInt("brightNormal", 0), 0, 100);
        brightDim = clamp(settings.getInt("brightDim", 5), 1, 100);
        colorIndex = clamp(settings.getInt("color", 0), 0, COLORS.length - 1);
        applyColors();
    }

    private void saveSettings() {
        settings.edit().putBoolean("keepOn", keepOn).putBoolean("showArt", showArt).putBoolean("pixelShift", pixelShift)
                .putInt("idleMode", idleMode).putInt("idleChoice", idleChoice).putInt("brightNormal", brightNormal)
                .putInt("brightDim", brightDim).putInt("color", colorIndex).apply();
    }

    private void applyColors() {
        accent = COLORS[colorIndex][0];
        accent2 = COLORS[colorIndex][1];
    }

    // ================================================================ sleep timer

    private void setSleep(int choice) {
        handler.removeCallbacks(sleepTimer);
        sleepChoice = choice;
        sleepAt = 0;
        sleepEndOfSong = false;
        sleepSongUri = "";
        int minutes = SLEEP_MINUTES[choice];
        if (minutes > 0) {
            sleepAt = System.currentTimeMillis() + minutes * 60_000L;
            handler.postDelayed(sleepTimer, minutes * 60_000L);
            status("Sleep timer: pausing in " + minutes + " min");
        } else if (minutes < 0) {
            sleepEndOfSong = true;
            sleepSongUri = trackUri;
            status("Sleep timer: pausing after this song");
        } else {
            status("Sleep timer off");
        }
    }

    private void sleepNow() {
        handler.removeCallbacks(sleepTimer);
        sleepChoice = 0;
        sleepAt = 0;
        sleepEndOfSong = false;
        sleepSongUri = "";
        isPlaying = false;
        command("PUT", "/me/player/pause", null);
        updatePlayerViews();
        status("Sleep timer: paused");
    }

    // ================================================================ idle / brightness

    private void enterIdle() {
        idling = true;
        if (idleMode == 1 || idleMode == 3) applyBrightness(true);
        if (idleMode >= 2) setMinimal(true);
    }

    private void exitIdle() {
        idling = false;
        applyBrightness(false);
        if (minimal) setMinimal(false);
    }

    /** Clock & song only: hides the top bar, controls, device line and art. */
    private void setMinimal(boolean m) {
        minimal = m;
        int vis = m ? View.GONE : View.VISIBLE;
        if (topBar != null) topBar.setVisibility(vis);
        if (message != null) message.setVisibility(vis);
        if (ctrlColumn != null) ctrlColumn.setVisibility(vis);
        if (device != null) device.setVisibility(vis);
        if (clock != null) clock.setTextSize(clockSize());
        updateArt();
    }

    private void applyBrightness(boolean dim) {
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        // Affects this app's window only; the system brightness setting is untouched.
        if (dim) lp.screenBrightness = Math.max(0.01f, brightDim / 100f);
        else lp.screenBrightness = brightNormal == 0 ? WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE : brightNormal / 100f;
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
            afterTrackUpdate();
        });
    }

    /** Queue auto-skip and sleep-after-song both react to the song changing. */
    private void afterTrackUpdate() {
        if (!trackUri.isEmpty() && skipUris.remove(trackUri)) {
            status("Skipped a song you removed from the queue");
            command("POST", "/me/player/next", null);
            return;
        }
        if (sleepEndOfSong) {
            if (sleepSongUri.isEmpty()) sleepSongUri = trackUri;
            else if (!trackUri.isEmpty() && !trackUri.equals(sleepSongUri)) sleepNow();
        }
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
            String id = str(dev, "id");
            if (!id.isEmpty() && !id.equals(settings.getString("lastDevice", ""))) settings.edit().putString("lastDevice", id).apply();
        }
        JSONObject item = j.optJSONObject("item");
        if (item == null) { trackName = "Unknown item"; artistName = ""; durationMs = 0; trackUri = ""; return; }
        trackName = str(item, "name");
        trackUri = str(item, "uri");
        durationMs = item.optLong("duration_ms");
        artistName = subtitle(item);
        JSONObject album = item.optJSONObject("album");
        pendingArtUrl = pickImage(album != null ? album.optJSONArray("images") : item.optJSONArray("images"));
        if (showArt) loadArt(pendingArtUrl);
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

    // ================================================================ misc actions

    private void login() {
        if (!spotify.hasClientId()) { showSetup(); return; }
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

    private boolean isLandscape() {
        return getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
    }

    // ================================================================ status line

    private void status(String s) { errorShown = false; if (message != null) message.setText(s); }
    private void error(String s) { errorShown = true; if (message != null) message.setText(s); }

    // ================================================================ view helpers

    private abstract static class SeekAdapter implements SeekBar.OnSeekBarChangeListener {
        @Override public void onProgressChanged(SeekBar sb, int value, boolean fromUser) { }
        @Override public void onStartTrackingTouch(SeekBar sb) { }
        @Override public void onStopTrackingTouch(SeekBar sb) { }
    }

    private int dp(float d) { return Math.round(d * getResources().getDisplayMetrics().density); }
    private float dpf(float d) { return d * getResources().getDisplayMetrics().density; }
    private static int clamp(int v, int min, int max) { return Math.max(min, Math.min(max, v)); }
    private static int withAlpha(int color, int alpha) { return (color & 0x00FFFFFF) | (alpha << 24); }

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
        v.setTextSize(size);
        v.setGravity(Gravity.CENTER);
        if (bold) v.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        tint(v, accent, accent2);
        return v;
    }

    /**
     * Colours a TextView with a solid colour, or a left-to-right gradient across the text itself.
     * The colours live in the view's tag so re-tinting replaces them instead of stacking listeners.
     */
    private void tint(TextView v, int c1, int c2) {
        boolean first = v.getTag() == null;
        v.setTag(new int[]{c1, c2});
        v.setTextColor(c1);
        if (first) {
            v.addOnLayoutChangeListener((view, l, t, r, b, ol, ot, or2, ob) -> applyShader(v));
            v.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
                @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
                @Override public void afterTextChanged(Editable s) { v.post(() -> applyShader(v)); }
            });
        }
        applyShader(v);
    }

    private static void applyShader(TextView v) {
        Object tag = v.getTag();
        if (!(tag instanceof int[])) return;
        int[] c = (int[]) tag;
        if (c[0] == c[1]) {
            if (v.getPaint().getShader() != null) { v.getPaint().setShader(null); v.invalidate(); }
            return;
        }
        int inner = v.getWidth() - v.getTotalPaddingLeft() - v.getTotalPaddingRight();
        if (inner <= 0) return;
        float textWidth = 0;
        for (String line : v.getText().toString().split("\n")) textWidth = Math.max(textWidth, v.getPaint().measureText(line));
        textWidth = Math.max(1, Math.min(textWidth, inner));
        boolean centred = (v.getGravity() & Gravity.HORIZONTAL_GRAVITY_MASK) == Gravity.CENTER_HORIZONTAL;
        float start = v.getTotalPaddingLeft() + (centred ? (inner - textWidth) / 2f : 0);
        v.getPaint().setShader(new LinearGradient(start, 0, start + textWidth, 0, c[0], c[1], Shader.TileMode.CLAMP));
        v.invalidate();
    }

    private TextView oneLine(TextView v) {
        v.setSingleLine(true);
        v.setEllipsize(TextUtils.TruncateAt.END);
        return v;
    }

    /** A tappable label with a ripple. A null action means the caller sets its own listener. */
    private TextView action(String s, int size, Runnable r) {
        TextView v = text(s, size, false);
        v.setPadding(dp(12), dp(12), dp(12), dp(12));
        v.setBackground(new RippleDrawable(ColorStateList.valueOf(withAlpha(accent, 0x40)), null, new ColorDrawable(Color.WHITE)));
        if (r != null) v.setOnClickListener(x -> r.run());
        return v;
    }

    /**
     * A list entry: a title with a smaller, dimmed subtitle underneath, and an optional "+" (add to queue)
     * button on the right. A null onTap leaves the row's click handling to the caller.
     */
    private LinearLayout listRow(String titleText, String sub, int titleSize, Runnable onTap, Runnable onQueue) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackground(new RippleDrawable(ColorStateList.valueOf(withAlpha(accent, 0x40)), null, new ColorDrawable(Color.WHITE)));
        row.setPadding(dp(12), dp(8), dp(4), dp(8));
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView t = oneLine(text(titleText, titleSize, false));
        t.setGravity(Gravity.START);
        texts.addView(t, matchWrap());
        if (sub != null && !sub.isEmpty()) {
            TextView s = oneLine(text(sub, Math.max(12, titleSize - 4), false));
            s.setGravity(Gravity.START);
            s.setAlpha(.6f);
            texts.addView(s, matchWrap());
        }
        row.addView(texts, new LinearLayout.LayoutParams(0, -2, 1));
        if (onQueue != null) {
            TextView plus = action("+", 26, onQueue);
            plus.setContentDescription("Add to queue");
            plus.setMinWidth(dp(48));
            row.addView(plus);
        }
        if (onTap != null) row.setOnClickListener(v -> onTap.run());
        return row;
    }

    private TextView icon(String glyph, String description, Runnable r) {
        TextView v = action(glyph, 26, r);
        v.setContentDescription(description);
        v.setMinWidth(dp(44));
        v.setPadding(dp(8), dp(8), dp(8), dp(8));
        return v;
    }

    private EditText input(String hint) {
        EditText e = new EditText(this);
        e.setSingleLine(true);
        e.setTextColor(accent);
        e.setHintTextColor(withAlpha(accent, 0x80));
        e.setHint(hint);
        e.setBackgroundTintList(ColorStateList.valueOf(accent));
        return e;
    }

    private SeekBar seekBar() {
        SeekBar s = new SeekBar(this);
        s.setProgressTintList(ColorStateList.valueOf(accent));
        s.setThumbTintList(ColorStateList.valueOf(accent2));
        s.setProgressBackgroundTintList(ColorStateList.valueOf(withAlpha(accent, 0x40)));
        return s;
    }

    private void heading(String s) {
        TextView h = text(s, 22, true);
        h.setPadding(0, dp(4), 0, dp(12));
        content.addView(h, matchWrap());
    }

    private void section(LinearLayout list, String s) {
        TextView h = text(s.toUpperCase(Locale.ROOT), 13, true);
        h.setAlpha(.6f);
        h.setPadding(0, dp(24), 0, dp(4));
        list.addView(h, matchWrap());
    }

    private void setting(LinearLayout list, View v) {
        list.addView(v, matchWrap());
        View line = new View(this);
        // Hairline that fades out at both ends.
        int c = complement();
        line.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{withAlpha(c, 0), withAlpha(c, 0xB0), withAlpha(c, 0)}));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, Math.max(1, dp(1)));
        lp.topMargin = dp(6);
        lp.bottomMargin = dp(6);
        list.addView(line, lp);
    }

    /** Gradients use their second colour; solid colours use a neighbouring hue; white uses grey. */
    private int complement() {
        if (accent != accent2) return accent2;
        float[] hsv = new float[3];
        Color.colorToHSV(accent, hsv);
        if (hsv[1] < 0.1f) return 0xFF777777;
        hsv[0] = (hsv[0] + 50f) % 360f;
        return Color.HSVToColor(hsv);
    }

    private TextView label(String s) {
        TextView l = text(s, 14, false);
        l.setAlpha(.8f);
        l.setPadding(0, dp(10), 0, 0);
        return l;
    }

    /** A horizontally scrolling row of options; the selected one is bright, the rest faded. */
    private HorizontalScrollView choices(String[] options, int selected, IntChoice onPick) {
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        hs.setFillViewport(true);
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER);
        for (int i = 0; i < options.length; i++) {
            int index = i;
            TextView chip = action(options[i], 15, null);
            chip.setPadding(dp(10), dp(10), dp(10), dp(10));
            chip.setAlpha(i == selected ? 1f : OFF);
            chip.setOnClickListener(v -> {
                for (int j = 0; j < row.getChildCount(); j++) row.getChildAt(j).setAlpha(j == index ? 1f : OFF);
                onPick.pick(index);
            });
            row.addView(chip);
        }
        hs.addView(row, new FrameLayout.LayoutParams(-1, -2));
        return hs;
    }

    private TextView switchRow(String label, boolean initial, Toggle onChange) {
        TextView v = action("", 16, null);
        boolean[] state = {initial};
        v.setText(label + ":  " + (initial ? "ON" : "OFF"));
        v.setAlpha(initial ? 1f : .6f);
        v.setOnClickListener(x -> {
            state[0] = !state[0];
            v.setText(label + ":  " + (state[0] ? "ON" : "OFF"));
            v.setAlpha(state[0] ? 1f : .6f);
            onChange.set(state[0]);
        });
        return v;
    }

    private LinearLayout slider(String name, int min, int value, Labeler fmt, IntChoice onMove, Runnable onDone) {
        LinearLayout box = vertical();
        box.setPadding(0, dp(10), 0, dp(4));
        TextView l = text(name + ": " + fmt.label(value), 14, false);
        l.setAlpha(.8f);
        box.addView(l, matchWrap());
        SeekBar s = seekBar();
        s.setMax(100 - min);
        s.setProgress(value - min);
        s.setOnSeekBarChangeListener(new SeekAdapter() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                if (!fromUser) return;
                int v = p + min;
                l.setText(name + ": " + fmt.label(v));
                onMove.pick(v);
            }
            @Override public void onStopTrackingTouch(SeekBar sb) { onDone.run(); }
        });
        box.addView(s, matchWrap());
        return box;
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

    /** Artists for a track, or the show name for a podcast episode. */
    private static String subtitle(JSONObject item) {
        if (item.has("artists")) return joinArtists(item);
        JSONObject show = item.optJSONObject("show");
        return show != null ? str(show, "name") : "";
    }

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

    /** Picks the smallest image at least 300 px wide (or the largest available). */
    private static String pickImage(JSONArray images) {
        if (images == null || images.length() == 0) return "";
        String best = "", largest = "";
        int bestW = Integer.MAX_VALUE, largestW = -1;
        for (int i = 0; i < images.length(); i++) {
            JSONObject im = images.optJSONObject(i);
            if (im == null) continue;
            int w = im.optInt("width", 0);
            String url = str(im, "url");
            if (w >= 300 && w < bestW) { bestW = w; best = url; }
            if (w > largestW) { largestW = w; largest = url; }
        }
        return best.isEmpty() ? largest : best;
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
