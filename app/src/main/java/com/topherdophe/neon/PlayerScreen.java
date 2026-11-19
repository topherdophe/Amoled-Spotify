package com.topherdophe.neon;

import android.animation.ValueAnimator;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.format.DateFormat;
import android.view.Gravity;
import android.view.View;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Clock, song, progress, shuffle and repeat. Play, next and previous are swipes (see MainActivity). */
final class PlayerScreen extends BaseScreen {
    private TextView clock, date, title, artist, device, elapsed, duration, shuffleBtn, repeatBtn, plus;
    private View modeColumn;
    private SeekBar progressBar;
    private ImageView art;
    private View ctrlColumn;
    /** Idle clock mode: the clock and song drift slowly up and down the screen (burn-in protection). */
    private View infoView, stage;
    private ValueAnimator drift;
    private boolean draggingProgress, minimal;

    @Override BaseScreen back() { return null; }
    @Override boolean rebuildOnRotate() { return true; }
    @Override boolean idleAllowed() { return true; }
    @Override boolean wantsFastPolling() { return playback.isPlaying; }
    @Override boolean swipeControls() { return true; }

    @Override boolean blocksSwipe(float rawX, float rawY) {
        if (progressBar == null || progressBar.getVisibility() != View.VISIBLE) return false;
        android.graphics.Rect r = new android.graphics.Rect();
        if (!progressBar.getGlobalVisibleRect(r)) return false;
        r.inset(0, -ui.dp(12)); // a little extra room above and below the thin bar
        return r.contains((int) rawX, (int) rawY);
    }

    @Override void build() {
        ScrollView scroll = new ScrollView(app);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        content.addView(scroll, new LinearLayout.LayoutParams(-1, -1));
        LinearLayout p = ui.vertical();
        p.setGravity(Gravity.CENTER);
        scroll.addView(p, new FrameLayout.LayoutParams(-1, -1));

        // Portrait: one centred column. Landscape: track info on the left, controls on the right.
        boolean landscape = app.isLandscape();
        LinearLayout info = ui.vertical(), ctrl = ui.vertical();
        info.setGravity(Gravity.CENTER);
        ctrl.setGravity(Gravity.CENTER);
        ctrlColumn = ctrl;
        infoView = info;
        stage = scroll;
        if (landscape) {
            LinearLayout row = new LinearLayout(app);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.addView(info, new LinearLayout.LayoutParams(0, -2, 1));
            LinearLayout.LayoutParams ctrlLp = new LinearLayout.LayoutParams(0, -2, 1);
            ctrlLp.leftMargin = ui.dp(16);
            row.addView(ctrl, ctrlLp);
            p.addView(row, Ui.matchWrap());
        } else {
            p.addView(info, Ui.matchWrap());
            p.addView(ctrl, Ui.matchWrap());
        }

        clock = Ui.oneLine(ui.text("--:--", 16, false));
        clock.setTypeface(clockTypeface());
        Ui.fixedSize(clock, clockSize());
        info.addView(clock, Ui.matchWrap());
        date = ui.text("", 18, false);
        date.setAlpha(.7f);
        date.setVisibility(settings.clockDate ? View.VISIBLE : View.GONE);
        info.addView(date, Ui.matchWrap());
        // Re-fit once the real width is known.
        clock.post(() -> { if (clock != null) Ui.fixedSize(clock, clockSize()); });

        art = new ImageView(app);
        art.setScaleType(ImageView.ScaleType.CENTER_CROP);
        art.setContentDescription("Album art");
        GradientDrawable artBg = new GradientDrawable();
        artBg.setCornerRadius(ui.dp(12));
        artBg.setColor(0xFF000000);
        art.setBackground(artBg);
        art.setClipToOutline(true);
        int artSize = ui.dp(landscape ? 130 : 220);
        LinearLayout.LayoutParams artLp = new LinearLayout.LayoutParams(artSize, artSize);
        artLp.gravity = Gravity.CENTER_HORIZONTAL;
        artLp.topMargin = ui.dp(4);
        artLp.bottomMargin = ui.dp(12);
        info.addView(art, artLp);

        // Shuffle above repeat on the left, title and artist in the middle, + (song menu) on the right.
        LinearLayout songRow = new LinearLayout(app);
        songRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout modes = ui.vertical();
        modeColumn = modes;
        shuffleBtn = ui.glyph("⇄", 24, "Shuffle", playback::toggleShuffle);
        repeatBtn = ui.glyph("↻", 24, "Repeat", playback::cycleRepeat);
        shuffleBtn.setPadding(0, ui.dp(4), 0, ui.dp(4));
        repeatBtn.setPadding(0, ui.dp(4), 0, ui.dp(4));
        shuffleBtn.setMinHeight(ui.dp(40));
        repeatBtn.setMinHeight(ui.dp(40));
        modes.addView(shuffleBtn);
        modes.addView(repeatBtn);
        songRow.addView(modes, new LinearLayout.LayoutParams(ui.dp(48), -2));
        LinearLayout texts = ui.vertical();
        title = Ui.oneLine(ui.text("", 26, true));
        texts.addView(title, Ui.matchWrap());
        artist = Ui.oneLine(ui.text("", 19, false));
        artist.setAlpha(.75f);
        texts.addView(artist, Ui.matchWrap());
        songRow.addView(texts, new LinearLayout.LayoutParams(0, -2, 1));
        plus = ui.glyph("+", 30, "Options for this song", () -> {
            if (playback.currentItem != null) SongMenu.show(app, playback.currentItem, true);
        });
        songRow.addView(plus, new LinearLayout.LayoutParams(ui.dp(48), -2));
        info.addView(songRow, Ui.matchWrap());
        device = Ui.oneLine(ui.text("", 14, false));
        device.setAlpha(.55f);
        device.setPadding(0, ui.dp(4), 0, 0);
        device.setOnClickListener(v -> app.showDevices(null));
        info.addView(device, Ui.matchWrap());

        progressBar = ui.seekBar();
        progressBar.setContentDescription("Song position");
        LinearLayout.LayoutParams barLp = Ui.matchWrap();
        barLp.topMargin = ui.dp(landscape ? 0 : 24);
        ctrl.addView(progressBar, barLp);
        progressBar.setOnSeekBarChangeListener(new Ui.SeekAdapter() {
            @Override public void onProgressChanged(SeekBar sb, int value, boolean fromUser) {
                if (fromUser) showPosition(value);
            }
            @Override public void onStartTrackingTouch(SeekBar sb) { draggingProgress = true; }
            @Override public void onStopTrackingTouch(SeekBar sb) {
                draggingProgress = false;
                playback.seek(sb.getProgress());
            }
        });
        LinearLayout times = new LinearLayout(app);
        times.setPadding(ui.dp(16), 0, ui.dp(16), 0);
        // The seek bar already announces the position, so screen readers skip these.
        times.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        elapsed = ui.text("0:00", 13, false);
        duration = ui.text("0:00", 13, false);
        times.addView(elapsed);
        times.addView(new View(app), new LinearLayout.LayoutParams(0, 1, 1));
        times.addView(duration);
        ctrl.addView(times, Ui.matchWrap());

        if (!spotify.isConnected()) ctrl.addView(ui.action("CONNECT TO SPOTIFY", 20, app::login));

        updateClock();
        onPlaybackChanged();
        playback.refresh();
    }

    private float clockSize() {
        boolean landscape = app.isLandscape();
        float base = minimal ? (landscape ? 110 : 96) : (landscape ? 72 : 64);
        float size = base * AppSettings.CLOCK_SCALE[settings.clockSize];
        // Shrink to fit the width (e.g. large size with seconds on a narrow phone).
        int width = clock != null && clock.getWidth() > 0 ? clock.getWidth()
                : app.getResources().getDisplayMetrics().widthPixels / (landscape ? 2 : 1) - ui.dp(48);
        android.graphics.Paint paint = new android.graphics.Paint();
        paint.setTypeface(clockTypeface());
        paint.setTextSize(ui.dpf(size));
        float textWidth = paint.measureText(settings.clockSeconds ? "00:00:00" : "00:00");
        if (textWidth > width * .95f) size *= width * .95f / textWidth;
        return size;
    }

    private Typeface clockTypeface() {
        switch (settings.clockStyle) {
            case 1: return Typeface.create("sans-serif", Typeface.NORMAL);
            case 2: return Typeface.create("sans-serif", Typeface.BOLD);
            case 3: return Typeface.create("monospace", Typeface.NORMAL);
            default: return Typeface.create("sans-serif-thin", Typeface.NORMAL);
        }
    }

    @Override void onTick() {
        updateClock();
        updateProgressViews();
        updateDeviceLine();
    }

    @Override void onSessionChanged() { app.open(new PlayerScreen()); }

    @Override void onPlaybackChanged() {
        if (title == null) return;
        Playback pb = playback;
        if (!spotify.isConnected()) {
            title.setText("Not connected");
            artist.setText("");
        } else if (!pb.hasPlayback) {
            title.setText("Nothing playing");
            artist.setText("Choose a device in Settings → Play on");
        } else {
            title.setText(pb.trackName);
            artist.setText(pb.artistName);
        }
        updateDeviceLine();
        shuffleBtn.setAlpha(pb.shuffle ? 1f : Ui.OFF);
        shuffleBtn.setContentDescription(pb.shuffle ? "Shuffle, on" : "Shuffle, off");
        repeatBtn.setText("track".equals(pb.repeat) ? "↻1" : "↻");
        repeatBtn.setAlpha("off".equals(pb.repeat) ? Ui.OFF : 1f);
        repeatBtn.setContentDescription("track".equals(pb.repeat) ? "Repeat, this song"
                : "context".equals(pb.repeat) ? "Repeat, all" : "Repeat, off");
        progressBar.setMax((int) Math.max(pb.durationMs, 1));
        duration.setText(Json.fmt(pb.durationMs));
        updateProgressViews();
        updateArt();
    }

    private void updateClock() {
        if (clock == null) return;
        boolean h24 = settings.clockHours == 0 ? DateFormat.is24HourFormat(app) : settings.clockHours == 2;
        String pattern = (h24 ? "HH:mm" : "h:mm") + (settings.clockSeconds ? ":ss" : "");
        Date now = new Date();
        clock.setText(new SimpleDateFormat(pattern, Locale.getDefault()).format(now));
        if (settings.clockDate) {
            String datePattern = DateFormat.getBestDateTimePattern(Locale.getDefault(), "EEEEdMMMM");
            date.setText(new SimpleDateFormat(datePattern, Locale.getDefault()).format(now));
        }
    }

    private void updateDeviceLine() {
        if (device == null) return;
        String s = playback.hasPlayback && !playback.deviceName.isEmpty() ? "on " + playback.deviceName : "";
        long sleepAt = SleepTimer.endTime(app);
        if (sleepAt > 0) {
            long mins = Math.max(1, (sleepAt - System.currentTimeMillis() + 59_999) / 60_000);
            s += (s.isEmpty() ? "" : " · ") + "sleep in " + mins + " min";
        } else if (playback.sleepEndOfSong) {
            s += (s.isEmpty() ? "" : " · ") + "sleep after this song";
        }
        if (!s.contentEquals(device.getText())) device.setText(s);
    }

    private void updateProgressViews() {
        if (progressBar == null || draggingProgress) return;
        long p = playback.hasPlayback ? playback.currentProgress() : 0;
        progressBar.setProgress((int) p);
        showPosition(p);
    }

    private void showPosition(long p) {
        elapsed.setText(Json.fmt(p));
        Ui.describeState(progressBar, Json.fmt(p) + " of " + Json.fmt(playback.durationMs));
    }

    private void updateArt() {
        if (art == null) return;
        boolean show = settings.showArt && playback.artBitmap != null && !minimal && playback.hasPlayback;
        art.setVisibility(show ? View.VISIBLE : View.GONE);
        if (show) art.setImageBitmap(playback.artBitmap);
    }

    /** Clock & song only: hides the top bar, shuffle/repeat, +, seek bar, device line and art. */
    @Override void setMinimal(boolean m) {
        minimal = m;
        int vis = m ? View.GONE : View.VISIBLE;
        if (ctrlColumn != null) ctrlColumn.setVisibility(vis);
        if (device != null) device.setVisibility(vis);
        if (plus != null) { plus.setVisibility(vis); modeColumn.setVisibility(vis); }
        if (clock != null) Ui.fixedSize(clock, clockSize());
        updateArt();
        stopDrift();
        // Start once the bigger idle clock has been laid out, so the travel fits the screen.
        if (m && infoView != null) infoView.postDelayed(this::startDrift, 400);
    }

    private static final float DRIFT_DP_PER_SEC = 6f;

    /** A slow sine wave from the centre to the top, down to the bottom and back. */
    private void startDrift() {
        if (!minimal || infoView == null || stage == null || drift != null) return;
        float room = (stage.getHeight() - infoView.getHeight()) / 2f - ui.dp(8);
        if (room < ui.dp(4)) return;
        // One full cycle travels 4 x room; this keeps the speed about the same on any screen.
        long cycleMs = (long) (4 * room / ui.dpf(DRIFT_DP_PER_SEC) * 1000);
        drift = ValueAnimator.ofFloat(0f, (float) (2 * Math.PI));
        drift.setDuration(Math.max(cycleMs, 20_000));
        drift.setRepeatCount(ValueAnimator.INFINITE);
        drift.setInterpolator(new LinearInterpolator());
        drift.addUpdateListener(a -> {
            if (infoView != null) infoView.setTranslationY(room * (float) Math.sin((float) a.getAnimatedValue()));
        });
        drift.start();
    }

    private void stopDrift() {
        if (drift != null) { drift.cancel(); drift = null; }
        if (infoView != null) infoView.setTranslationY(0);
    }
}
