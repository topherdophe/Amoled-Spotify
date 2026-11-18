package com.topherdophe.neon;

import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.format.DateFormat;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Clock, song, progress and playback controls. */
final class PlayerScreen extends BaseScreen {
    private TextView clock, title, artist, device, elapsed, duration, play, shuffleBtn, repeatBtn;
    private SeekBar progressBar;
    private ImageView art;
    private View ctrlColumn;
    private boolean draggingProgress, minimal;

    @Override BaseScreen back() { return null; }
    @Override boolean rebuildOnRotate() { return true; }
    @Override boolean idleAllowed() { return true; }
    @Override boolean wantsFastPolling() { return playback.isPlaying; }

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

        clock = ui.text("--:--", 16, false);
        clock.setTypeface(Typeface.create("sans-serif-thin", Typeface.NORMAL));
        Ui.fixedSize(clock, clockSize());
        info.addView(clock, Ui.matchWrap());

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

        title = Ui.oneLine(ui.text("", 26, true));
        info.addView(title, Ui.matchWrap());
        artist = Ui.oneLine(ui.text("", 19, false));
        artist.setAlpha(.75f);
        info.addView(artist, Ui.matchWrap());
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

        LinearLayout controls = new LinearLayout(app);
        controls.setGravity(Gravity.CENTER);
        controls.setPadding(0, ui.dp(8), 0, ui.dp(4));
        shuffleBtn = ui.glyph("⇄", 26, "Shuffle", playback::toggleShuffle);
        controls.addView(shuffleBtn);
        controls.addView(ui.glyph("|◀", 34, "Previous song", playback::previous));
        play = ui.glyph("▶", 54, "Play", playback::togglePlay);
        controls.addView(play);
        controls.addView(ui.glyph("▶|", 34, "Next song", playback::next));
        repeatBtn = ui.glyph("↻", 26, "Repeat", playback::cycleRepeat);
        controls.addView(repeatBtn);
        ctrl.addView(controls, Ui.matchWrap());

        if (!spotify.isConnected()) ctrl.addView(ui.action("CONNECT TO SPOTIFY", 20, app::login));

        updateClock();
        onPlaybackChanged();
        playback.refresh();
    }

    private float clockSize() {
        boolean landscape = app.isLandscape();
        if (minimal) return landscape ? 110 : 96;
        return landscape ? 72 : 64;
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
            artist.setText("Tap ◎ to choose a device");
        } else {
            title.setText(pb.trackName);
            artist.setText(pb.artistName);
        }
        updateDeviceLine();
        play.setText(pb.isPlaying ? "Ⅱ" : "▶");
        play.setContentDescription(pb.isPlaying ? "Pause" : "Play");
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
        String pattern = DateFormat.is24HourFormat(app) ? "HH:mm" : "h:mm";
        clock.setText(new SimpleDateFormat(pattern, Locale.getDefault()).format(new Date()));
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

    /** Clock & song only: hides the top bar, controls, device line and art. */
    @Override void setMinimal(boolean m) {
        minimal = m;
        int vis = m ? View.GONE : View.VISIBLE;
        if (ctrlColumn != null) ctrlColumn.setVisibility(vis);
        if (device != null) device.setVisibility(vis);
        if (clock != null) Ui.fixedSize(clock, clockSize());
        updateArt();
    }
}
