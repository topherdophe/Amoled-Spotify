package com.topherdophe.neon;

import android.widget.LinearLayout;

/** One page of the app. MainActivity owns the top bar and status line; a screen fills the space between. */
abstract class BaseScreen {
    MainActivity app;
    Ui ui;
    Playback playback;
    SpotifyClient spotify;
    AppSettings settings;
    LinearLayout content;

    final void attach(MainActivity app, LinearLayout content) {
        this.app = app;
        this.ui = app.ui;
        this.playback = app.playback;
        this.spotify = app.spotify;
        this.settings = app.settings;
        this.content = content;
    }

    /** Adds this screen's views to {@link #content}. */
    abstract void build();

    void onPlaybackChanged() { }
    void onTick() { }
    void onSessionChanged() { }

    /** The screen that opened this one, if back should return there. */
    BaseScreen returnTo;

    /** Back (and the BACK button) returns to the given screen. */
    @SuppressWarnings("unchecked")
    <T extends BaseScreen> T from(BaseScreen previous) {
        returnTo = previous;
        return (T) this;
    }

    /** Where the back button goes; null leaves the app. */
    BaseScreen back() { return returnTo != null ? returnTo : new PlayerScreen(); }

    /** Lets a screen use back for itself first (e.g. to end a selection). Returns true if it did. */
    boolean handleBack() { return selection != null && selection.cancel(); }

    /** Multi-select for this screen's song list, if it has one. */
    Selection selection;

    void onResume() { }

    /** Whether swipes on this screen control playback (see MainActivity.dispatchTouchEvent). */
    boolean swipeControls() { return false; }

    /** True if a swipe starting here (screen coordinates) belongs to a control instead, e.g. the seek bar. */
    boolean blocksSwipe(float rawX, float rawY) { return false; }

    /** Whether this screen rebuilds itself on rotation (so it can switch layout). */
    boolean rebuildOnRotate() { return false; }

    /** Whether the idle timer (dim / clock-only) runs on this screen. */
    boolean idleAllowed() { return false; }

    /** Clock-and-song-only mode; only the player supports it. */
    void setMinimal(boolean minimal) { }

    /** Polls faster when true. */
    boolean wantsFastPolling() { return false; }

    void heading(String s) { content.addView(ui.heading(s), Ui.matchWrap()); }

    LinearLayout scrollList() { return ui.scrollList(content); }

    void backButton() {
        content.addView(ui.action("BACK", 17, () -> {
            if (!handleBack()) app.goBack();
        }));
    }

    void hint(String s) {
        android.widget.TextView hint = ui.text(s, 13, false);
        hint.setAlpha(.6f);
        content.addView(hint, Ui.matchWrap());
    }

    /** Shows a connect button instead of the list when not logged in. */
    boolean requireConnected(LinearLayout list) {
        if (spotify.isConnected()) return true;
        list.addView(ui.action("CONNECT TO SPOTIFY", 20, app::login));
        return false;
    }

    /** "Loading…" placeholder; returns it so the caller can remove it. */
    android.widget.TextView loading(LinearLayout list, String text) {
        android.widget.TextView v = ui.text(text, 15, false);
        list.addView(v);
        return v;
    }

    /** Shows a message in the list. */
    void note(LinearLayout list, String text) {
        list.addView(ui.text(text, 15, false), Ui.matchWrap());
    }
}
