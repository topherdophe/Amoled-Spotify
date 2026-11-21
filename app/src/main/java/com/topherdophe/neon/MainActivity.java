package com.topherdophe.neon;

import android.app.Activity;
import android.app.KeyguardManager;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.graphics.Color;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
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
    private final Handler handler = new Handler(Looper.getMainLooper());
    AppSettings settings;
    SpotifyClient spotify;
    Playback playback;
    Library library;
    Ui ui;
    private BaseScreen screen;

    // Shell views
    private LinearLayout shell, content, topBar;
    private TextView battery, message;

    // Idle state
    private boolean idling, minimal, swallowing;

    // Player gestures: swipe right = next, swipe left = previous, two-finger swipe down = play/pause.
    private float downX, downY, twoFingerStartY;
    private boolean gesture, twoFinger, gestureDone, onSeekBar;
    private long lastTouch = SystemClock.elapsedRealtime();
    private int ticks;

    // Show over lock screen: whether the app was in front when the screen went off.
    private long pausedAt;
    private boolean leftApp, armed, relit;
    private final BroadcastReceiver screenOff = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            if (Intent.ACTION_SCREEN_OFF.equals(i.getAction())) onScreenOff();
            else onScreenOn();
        }
    };
    private final Runnable refront = this::bringToFront;

    // Lock phone when idle: after the phone's own screen timeout, wait for it to lock, then light it again.
    private PowerManager.WakeLock lockWait;
    private long lockWaitUntil;
    /** Whether FLAG_KEEP_SCREEN_ON is set, so it's only changed when it needs to be. */
    private Boolean keptOn;

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
        IntentFilter off = new IntentFilter(Intent.ACTION_SCREEN_OFF);
        off.addAction(Intent.ACTION_SCREEN_ON);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(screenOff, off, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(screenOff, off);
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleIntent(intent);
    }

    @Override protected void onStart() {
        super.onStart();
        // Re-applied every time, so it's in place before the phone next locks.
        applyLockScreen();
        applyKeepOn();
    }

    @Override protected void onResume() {
        super.onResume();
        pausedAt = 0;
        leftApp = false;
        armed = false;
        handler.removeCallbacks(refront);
        hideSystemUi();
        applyKeepOn();
        lastTouch = SystemClock.elapsedRealtime();
        if (relit) {
            // Back on screen after "Lock phone when idle": stay in idle mode over the lock screen.
            relit = false;
            setTurnOn(false);
            enterIdle();
        } else {
            exitIdle();
        }
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
        pausedAt = SystemClock.elapsedRealtime();
        super.onPause();
    }

    /** Home, recents or another app: the user chose to leave, so don't jump back over the lock screen. */
    @Override protected void onUserLeaveHint() {
        super.onUserLeaveHint();
        leftApp = true;
    }

    /** Browser, Spotify, App info: those stay on top, so don't cover them. */
    @Override public void startActivityForResult(Intent intent, int requestCode, Bundle options) {
        leftApp = true;
        super.startActivityForResult(intent, requestCode, options);
    }

    @Override protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        stopLockWait();
        try { unregisterReceiver(screenOff); } catch (IllegalArgumentException ignored) { }
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
            // Lock phone when idle: the screen stays on only while the app is over the lock screen.
            if (settings.lockIdle) applyKeepOn();
            if ((settings.idleMode != AppSettings.IDLE_NOTHING || settings.lockIdle) && !idling && screen.idleAllowed()
                    && SystemClock.elapsedRealtime() - lastTouch > settings.idleAfterMs(systemTimeoutMs())) enterIdle();
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
        shell = ui.vertical();
        applyShellPadding();
        frame.addView(shell, new FrameLayout.LayoutParams(-1, -1));

        topBar = new LinearLayout(this);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        // Grows with large system fonts instead of clipping the battery text.
        topBar.setMinimumHeight(ui.dp(52));
        battery = ui.text("--%", 16, false);
        topBar.addView(battery);
        topBar.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
        topBar.addView(ui.icon("♫︎", "Up next", () -> open(new QueueScreen())));
        topBar.addView(ui.icon("⌕", "Search", () -> open(new SearchScreen())));
        topBar.addView(ui.icon("☷", "Playlists", () -> open(new PlaylistsScreen())));
        topBar.addView(ui.icon("⚙︎", "Settings", () -> open(new SettingsScreen())));
        shell.addView(topBar, new LinearLayout.LayoutParams(-1, -2));

        content = ui.vertical();
        shell.addView(content, new LinearLayout.LayoutParams(-1, 0, 1));

        // Only problems show here (and fade after a few seconds); routine notes like "Next song" don't.
        message = ui.text("", 13, false);
        message.setAlpha(.8f);
        message.setVisibility(View.GONE);
        message.setPadding(ui.dp(8), ui.dp(4), ui.dp(8), ui.dp(4));
        // Screen readers announce status and error messages as they appear.
        message.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        shell.addView(message, new LinearLayout.LayoutParams(-1, -2));
        updateBattery();
    }

    private void applyShellPadding() {
        if (shell == null) return;
        // Landscape: extra side padding clears the camera cutout; less top padding saves height.
        if (isLandscape()) shell.setPadding(ui.dp(40), ui.dp(8), ui.dp(40), ui.dp(4));
        else shell.setPadding(ui.dp(16), ui.dp(28), ui.dp(16), ui.dp(12));
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

    /** Routine notes ("Next song", "Using …") are no longer shown. */
    @Override public void status(String s) { }

    @Override public void error(String s) {
        if (message == null) return;
        message.setText(s);
        message.setVisibility(minimal || s.isEmpty() ? View.GONE : View.VISIBLE);
        handler.removeCallbacks(hideError);
        handler.postDelayed(hideError, 8000);
    }

    @Override public void clearError() { hideError.run(); }

    private final Runnable hideError = () -> {
        if (message == null) return;
        message.setText("");
        message.setVisibility(View.GONE);
    };

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

    /** The phone's own screen timeout (Settings → Display), in ms. */
    long systemTimeoutMs() {
        try { return Settings.System.getInt(getContentResolver(), Settings.System.SCREEN_OFF_TIMEOUT); }
        catch (Settings.SettingNotFoundException e) { return 30_000; }
    }

    /**
     * Lock phone when idle, without any special permission: the phone locks itself when its screen
     * timeout runs out. If the screen went off that way (not the power button) while the app was in front,
     * wait for the lock (up to 30 s, for phones with a lock delay), then light the screen again with the
     * app over the lock screen. While it's over the lock screen it keeps the screen on, so the timeout
     * can't fire again and nothing loops.
     */
    private void startLockWait() {
        stopLockWait();
        PowerManager pm = getSystemService(PowerManager.class);
        if (pm != null) {
            lockWait = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "neon:lockwait");
            lockWait.acquire(32_000);
        }
        lockWaitUntil = SystemClock.elapsedRealtime() + 30_000;
        handler.postDelayed(waitForLock, 300);
    }

    private final Runnable waitForLock = new Runnable() {
        @Override public void run() {
            KeyguardManager km = getSystemService(KeyguardManager.class);
            if (km != null && km.isKeyguardLocked()) {
                stopLockWait();
                relit = true;
                relight.run();
            } else if (SystemClock.elapsedRealtime() < lockWaitUntil) {
                handler.postDelayed(this, 500);
            } else {
                stopLockWait();
            }
        }
    };

    private void stopLockWait() {
        handler.removeCallbacks(waitForLock);
        if (lockWait != null && lockWait.isHeld()) lockWait.release();
        lockWait = null;
    }

    @SuppressWarnings("deprecation")
    private final Runnable relight = () -> {
        if (!relit || isFinishing()) return;
        setTurnOn(true);
        PowerManager pm = getSystemService(PowerManager.class);
        if (pm != null) {
            PowerManager.WakeLock wl = pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK
                    | PowerManager.ACQUIRE_CAUSES_WAKEUP, "neon:relight");
            wl.acquire(4000);
        }
        armed = true;
        bringToFront();
    };

    @SuppressWarnings("deprecation")
    private void setTurnOn(boolean on) {
        if (Build.VERSION.SDK_INT >= 27) setTurnScreenOn(on);
        if (on) getWindow().addFlags(WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
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
        if (message != null) message.setVisibility(m || message.getText().length() == 0 ? View.GONE : View.VISIBLE);
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
        // The older window flag too: some phones only honour one of the two.
        if (settings.overLock) getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED);
    }

    /**
     * Some phones (Samsung, OnePlus) put their own lock screen on top of the app, even though it may
     * show over it. If the app was in front when the screen went off, it brings itself back on top:
     * straight away, a few more times while the phone locks (on a timeout the lock can come a few
     * seconds later), and again when the screen wakes. It never wakes the screen and never unlocks the phone.
     */
    private void onScreenOff() {
        if (!settings.overLock || leftApp || isFinishing()) return;
        boolean wasInFront = pausedAt == 0 || SystemClock.elapsedRealtime() - pausedAt < 3000;
        if (!wasInFront) return;
        // Screen timeout rather than the power button: nobody touched the app for the whole timeout.
        boolean timedOut = SystemClock.elapsedRealtime() - lastTouch >= systemTimeoutMs() - 2000;
        if (settings.lockIdle && timedOut) startLockWait();
        armed = true;
        handler.removeCallbacks(refront);
        bringToFront();
        for (long ms : new long[]{1500, 5000, 10_000, 30_000}) handler.postDelayed(refront, ms);
    }

    private void onScreenOn() {
        handler.removeCallbacks(refront);
        // Woken by the user (or by relight, which has already finished waiting).
        stopLockWait();
        KeyguardManager km = getSystemService(KeyguardManager.class);
        if (armed && settings.overLock && km != null && km.isKeyguardLocked()) bringToFront();
    }

    private void bringToFront() {
        if (!armed || !settings.overLock || isFinishing()) return;
        applyLockScreen();
        Intent front = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        // The application context skips startActivityForResult above, so leftApp stays false.
        try { getApplicationContext().startActivity(front); } catch (RuntimeException ignored) { }
    }

    /**
     * Keep screen awake. With Lock phone when idle, the screen stays on only while the phone is locked
     * (the app showing over the lock screen); while unlocked the phone's own timeout must be free to lock it.
     */
    void applyKeepOn() {
        boolean on;
        if (settings.lockIdle) {
            KeyguardManager km = getSystemService(KeyguardManager.class);
            on = km != null && km.isKeyguardLocked();
        } else {
            on = settings.keepOn;
        }
        if (keptOn != null && keptOn == on) return;
        keptOn = on;
        if (on) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
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
