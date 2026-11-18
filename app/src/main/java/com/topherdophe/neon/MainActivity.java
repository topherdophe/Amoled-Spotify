package com.topherdophe.neon;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Color;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * AMOLED-black Spotify remote. One activity holds the top bar and status line, and swaps
 * {@link BaseScreen} pages in between. Framework views only.
 */
public class MainActivity extends Activity implements SpotifyClient.Listener, Playback.Host {
    private static final long SHIFT_MS = 30_000;

    private final Handler handler = new Handler(Looper.getMainLooper());
    AppSettings settings;
    SpotifyClient spotify;
    Playback playback;
    Library library;
    Ui ui;
    private BaseScreen screen;

    // Shell views
    private LinearLayout shifter, content, topBar;
    private TextView battery, message;
    private boolean errorShown;

    // Idle state
    private boolean idling, minimal, swallowing;

    // Player gestures: swipe right = next, swipe left = previous, two-finger swipe down = play/pause.
    private float downX, downY, twoFingerStartY;
    private boolean gesture, twoFinger, gestureDone, onSeekBar;
    private long lastTouch = SystemClock.elapsedRealtime();
    private int ticks;

    // ================================================================ lifecycle

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        settings = new AppSettings(this);
        ui = new Ui(this, settings);
        spotify = new SpotifyClient(this, getString(R.string.spotify_client_id), this);
        playback = new Playback(spotify, settings, this);
        library = new Library(spotify);

        Window w = getWindow();
        w.setStatusBarColor(Color.BLACK);
        w.setNavigationBarColor(Color.BLACK);
        if (Build.VERSION.SDK_INT >= 28) {
            WindowManager.LayoutParams lp = w.getAttributes();
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            w.setAttributes(lp);
        }
        applyKeepOn();
        applyLockScreen();
        buildShell();
        open(spotify.hasClientId() ? new PlayerScreen() : new SetupScreen());
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
        if (screen != null) screen.onResume();
    }

    @Override protected void onPause() {
        // No polling while the screen is off or the app is hidden. The sleep timer is an alarm, so it still fires.
        handler.removeCallbacks(tick);
        handler.removeCallbacks(poll);
        super.onPause();
    }

    @Override protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        playback.shutdown();
        spotify.shutdown();
        super.onDestroy();
    }

    /** Rotation is handled here (see configChanges in the manifest) so state and typed text survive. */
    @Override public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        applyShellPadding();
        if (screen.rebuildOnRotate()) open(screen);
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemUi();
    }

    @SuppressWarnings("deprecation")
    @Override public void onBackPressed() {
        if (screen.handleBack()) return;
        BaseScreen back = screen.back();
        if (back != null) open(back);
        else super.onBackPressed();
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == Voice.REQUEST) Voice.onResult(this, resultCode, data);
    }

    /**
     * On an idle screen nothing reaches the buttons: a tap only wakes it, so it can't press one by accident.
     * On the player (idle or not) swipes control playback; see {@link BaseScreen#swipeControls()}.
     */
    @Override public boolean dispatchTouchEvent(MotionEvent ev) {
        lastTouch = SystemClock.elapsedRealtime();
        int a = ev.getActionMasked();
        boolean swipes = screen != null && screen.swipeControls();
        if (a == MotionEvent.ACTION_DOWN) {
            downX = ev.getRawX();
            downY = ev.getRawY();
            gesture = twoFinger = gestureDone = false;
            onSeekBar = swipes && screen.blocksSwipe(downX, downY);
            swallowing = idling;
        }
        if (swipes && !onSeekBar) trackGesture(ev, a);
        if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
            boolean wasGesture = gesture, wasIdle = swallowing;
            gesture = twoFinger = swallowing = false;
            if (wasIdle && !wasGesture && a == MotionEvent.ACTION_UP) exitIdle();
            if (wasIdle || wasGesture) return true;
            return super.dispatchTouchEvent(ev);
        }
        if (swallowing || gesture) return true;
        return super.dispatchTouchEvent(ev);
    }

    private void trackGesture(MotionEvent ev, int a) {
        if (a == MotionEvent.ACTION_POINTER_DOWN && ev.getPointerCount() == 2 && !gestureDone) {
            startGesture(ev);
            twoFinger = true;
            twoFingerStartY = averageY(ev);
        } else if (a == MotionEvent.ACTION_MOVE) {
            float dx = ev.getRawX() - downX, dy = ev.getRawY() - downY;
            if (!gesture && ev.getPointerCount() == 1 && Math.abs(dx) > ui.dp(24) && Math.abs(dx) > 1.5f * Math.abs(dy)) {
                startGesture(ev);
            } else if (twoFinger && !gestureDone && averageY(ev) - twoFingerStartY > ui.dp(80)) {
                gestureDone = true;
                status(playback.isPlaying ? "Paused" : "Playing");
                playback.togglePlay();
            }
        } else if (a == MotionEvent.ACTION_UP && gesture && !twoFinger && !gestureDone) {
            float dx = ev.getRawX() - downX;
            if (Math.abs(dx) > ui.dp(80)) {
                gestureDone = true;
                if (dx > 0) { status("Next song"); playback.next(); }
                else { status("Previous song"); playback.previous(); }
            }
        }
    }

    /** A swipe has started: buttons under the finger must not also see a tap. */
    private void startGesture(MotionEvent ev) {
        gesture = true;
        if (swallowing) return; // nothing below saw the touch start
        MotionEvent cancel = MotionEvent.obtain(ev);
        cancel.setAction(MotionEvent.ACTION_CANCEL);
        super.dispatchTouchEvent(cancel);
        cancel.recycle();
    }

    private static float averageY(MotionEvent ev) {
        float sum = 0;
        for (int i = 0; i < ev.getPointerCount(); i++) sum += ev.getY(i);
        return sum / ev.getPointerCount();
    }

    @Override public void onSessionChanged(String msg) {
        screen.onSessionChanged();
        status(msg);
    }

    private void handleIntent(Intent intent) {
        if (intent != null && spotify.handleRedirect(intent.getData())) {
            setIntent(new Intent());
            status("Finishing Spotify login…");
        }
    }

    // ================================================================ navigation

    void open(BaseScreen next) {
        if (minimal) setMinimal(false);
        screen = next;
        content.removeAllViews();
        next.attach(this, content);
        next.build();
        // Rebuilt while idle (e.g. rotated): stay in clock-and-song mode.
        if (idling && settings.minimalWhenIdle() && next.idleAllowed()) setMinimal(true);
    }

    void goBack() {
        BaseScreen back = screen.back();
        open(back != null ? back : new PlayerScreen());
    }

    @Override public void showPlayer() { open(new PlayerScreen()); }
    @Override public void showDevices(String note) { open(new DevicesScreen(note).from(screen)); }

    /** Rebuilds everything (e.g. after a colour change) and then shows the given screen. */
    void rebuildShell(BaseScreen next) {
        buildShell();
        open(next);
    }

    // ================================================================ timers

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (ticks++ % 30 == 0) updateBattery();
            playback.onTick();
            screen.onTick();
            applyShift();
            if (settings.idleMode != AppSettings.IDLE_NOTHING && !idling && screen.idleAllowed()
                    && SystemClock.elapsedRealtime() - lastTouch > settings.idleAfterMs()) enterIdle();
            handler.postDelayed(this, 1000);
        }
    };

    private final Runnable poll = new Runnable() {
        @Override public void run() {
            boolean limited = spotify.isRateLimited();
            if (spotify.isConnected() && !limited) playback.refresh();
            long delay = limited ? 20_000 : screen.wantsFastPolling() ? 5_000 : 15_000;
            handler.postDelayed(this, delay);
        }
    };

    // ================================================================ shell

    private void buildShell() {
        FrameLayout frame = new FrameLayout(this);
        frame.setBackgroundColor(Color.BLACK);
        setContentView(frame);
        shifter = ui.vertical();
        applyShellPadding();
        applyShift();
        frame.addView(shifter, new FrameLayout.LayoutParams(-1, -1));

        topBar = new LinearLayout(this);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        // Grows with large system fonts instead of clipping the battery text.
        topBar.setMinimumHeight(ui.dp(52));
        battery = ui.text("--%", 16, false);
        topBar.addView(battery);
        topBar.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
        topBar.addView(ui.imageIcon(R.drawable.ic_mic, "Voice: say a song or a command", () -> Voice.listen(this)));
        topBar.addView(ui.icon("♫︎", "Up next", () -> open(new QueueScreen())));
        topBar.addView(ui.icon("⌕", "Search", () -> open(new SearchScreen())));
        topBar.addView(ui.icon("☷", "Playlists", () -> open(new PlaylistsScreen())));
        topBar.addView(ui.icon("⚙︎", "Settings", () -> open(new SettingsScreen())));
        shifter.addView(topBar, new LinearLayout.LayoutParams(-1, -2));

        content = ui.vertical();
        shifter.addView(content, new LinearLayout.LayoutParams(-1, 0, 1));

        message = ui.text("", 13, false);
        message.setAlpha(.8f);
        message.setPadding(ui.dp(8), ui.dp(4), ui.dp(8), ui.dp(4));
        // Screen readers announce status and error messages as they appear.
        message.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        shifter.addView(message, new LinearLayout.LayoutParams(-1, -2));
        updateBattery();
    }

    /** Burn-in protection: drift the whole UI a few dp every 30 s. Also called on rebuild so nothing jumps. */
    void applyShift() {
        if (shifter == null) return;
        if (settings.pixelShift) {
            long step = System.currentTimeMillis() / SHIFT_MS;
            shifter.setTranslationX(ui.dpf((float) Math.sin(step * 0.7) * 6f));
            shifter.setTranslationY(ui.dpf((float) Math.cos(step * 1.3) * 6f));
        } else {
            shifter.setTranslationX(0);
            shifter.setTranslationY(0);
        }
    }

    private void applyShellPadding() {
        if (shifter == null) return;
        // Landscape: extra side padding clears the camera cutout; less top padding saves height.
        if (isLandscape()) shifter.setPadding(ui.dp(40), ui.dp(8), ui.dp(40), ui.dp(4));
        else shifter.setPadding(ui.dp(16), ui.dp(28), ui.dp(16), ui.dp(12));
    }

    private void updateBattery() {
        if (battery == null) return;
        BatteryManager bm = getSystemService(BatteryManager.class);
        int pct = bm == null ? -1 : bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
        battery.setText(pct > 0 ? pct + "%" : "--%");
        battery.setContentDescription(pct > 0 ? "Battery " + pct + "%" : "Battery level unknown");
    }

    // ================================================================ Playback.Host

    @Override public void onPlaybackChanged() { screen.onPlaybackChanged(); }

    @Override public void status(String s) { errorShown = false; if (message != null) message.setText(s); }
    @Override public void error(String s) { errorShown = true; if (message != null) message.setText(s); }
    @Override public void clearError() { if (errorShown) status(""); }

    // ================================================================ sleep timer

    /** Which sleep-timer option is active, for the Settings screen. */
    int sleepChoice() {
        if (SleepTimer.endTime(this) > 0) return SleepTimer.choice(this);
        return playback.sleepEndOfSong ? AppSettings.SLEEP_MINUTES.length - 1 : 0;
    }

    void setSleep(int choice) {
        SleepTimer.cancel(this);
        playback.setSleepAfterSong(false);
        int minutes = AppSettings.SLEEP_MINUTES[choice];
        if (minutes > 0) {
            boolean exact = SleepTimer.start(this, minutes, choice);
            status("Sleep timer: pausing in " + minutes + " min"
                    + (exact ? "" : " (approximately: Android hasn't allowed exact alarms for this app)"));
        } else if (minutes < 0) {
            playback.setSleepAfterSong(true);
            status("Sleep timer: pausing after this song");
        } else {
            status("Sleep timer off");
        }
    }

    /** End of "sleep after this song". Timed sleeps are paused by {@link SleepReceiver}. */
    @Override public void sleepNow() {
        playback.setSleepAfterSong(false);
        playback.pause();
        status("Sleep timer: paused");
    }

    // ================================================================ idle / brightness

    private void enterIdle() {
        idling = true;
        if (settings.dimsWhenIdle()) applyBrightness(true);
        if (settings.minimalWhenIdle()) setMinimal(true);
    }

    private void exitIdle() {
        idling = false;
        applyBrightness(false);
        if (minimal) setMinimal(false);
    }

    /** Clock & song only: hides the top bar and status line; the screen hides the rest. */
    private void setMinimal(boolean m) {
        minimal = m;
        int vis = m ? View.GONE : View.VISIBLE;
        if (topBar != null) topBar.setVisibility(vis);
        if (message != null) message.setVisibility(vis);
        if (screen != null) screen.setMinimal(m);
    }

    void applyBrightness(boolean dim) {
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        // Affects this app's window only; the system brightness setting is untouched.
        if (dim) lp.screenBrightness = Math.max(0.01f, settings.brightDim / 100f);
        else lp.screenBrightness = settings.brightNormal == 0
                ? WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE : settings.brightNormal / 100f;
        getWindow().setAttributes(lp);
    }

    /**
     * When on, the app stays visible over the lock screen if it was open when the phone locked.
     * Anything that leaves the app (browser, Spotify) still asks the phone to be unlocked.
     */
    @SuppressWarnings("deprecation")
    void applyLockScreen() {
        if (Build.VERSION.SDK_INT >= 27) setShowWhenLocked(settings.overLock);
        else if (settings.overLock) getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED);
    }

    void applyKeepOn() {
        if (settings.keepOn) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    @SuppressWarnings("deprecation")
    private void hideSystemUi() {
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
    }

    // ================================================================ misc actions

    void login() {
        if (!spotify.hasClientId()) { open(new SetupScreen()); return; }
        try {
            spotify.login(this);
            status("Opening Spotify login…");
        } catch (ActivityNotFoundException e) {
            error("No browser found to open the Spotify login page");
        } catch (Exception e) {
            error("Couldn't start login: " + e.getMessage());
        }
    }

    void forgetSession() {
        spotify.clearSession();
        playback.reset();
    }

    void openSpotifyApp() {
        Intent i = getPackageManager().getLaunchIntentForPackage("com.spotify.music");
        if (i == null) error("The Spotify app isn't installed on this phone");
        else startActivity(i);
    }

    void hideKeyboard(View v) {
        InputMethodManager imm = getSystemService(InputMethodManager.class);
        if (imm != null) imm.hideSoftInputFromWindow(v.getWindowToken(), 0);
    }

    boolean isLandscape() {
        return getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
    }
}
