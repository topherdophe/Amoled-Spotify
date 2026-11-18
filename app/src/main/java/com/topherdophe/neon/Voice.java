package com.topherdophe.neon;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.speech.RecognizerIntent;

import java.util.ArrayList;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Voice control through the phone's own speech pop-up (no microphone permission needed).
 * Understands a few commands; anything else becomes a search.
 */
final class Voice {
    static final int REQUEST = 41;

    private static final Pattern PLAY_TYPE = Pattern.compile("^play (?:the )?(playlist|album|artist) (.+)$");
    private static final Pattern PLAY = Pattern.compile("^play (.+)$");
    private static final Pattern QUEUE = Pattern.compile("^(?:queue|add) (.+?)(?: to (?:the |my )?queue)?$");
    private static final Pattern SEARCH = Pattern.compile("^(?:search|search for|find|look up) (.+)$");

    private Voice() { }

    static void listen(MainActivity app) {
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_PROMPT, "Say a song, or: pause · skip · back · play… · queue…");
        i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
        try {
            app.startActivityForResult(i, REQUEST);
        } catch (ActivityNotFoundException e) {
            app.error("This phone has no speech recognition app");
        }
    }

    static void onResult(MainActivity app, int resultCode, Intent data) {
        if (resultCode != Activity.RESULT_OK || data == null) return;
        ArrayList<String> heard = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
        if (heard == null || heard.isEmpty()) return;
        handle(app, heard.get(0));
    }

    static void handle(MainActivity app, String said) {
        String text = said.trim();
        String l = text.toLowerCase(Locale.ROOT).replaceAll("[.!?]+$", "");
        Playback pb = app.playback;
        switch (l) {
            case "pause": case "stop": case "pause music": case "stop music":
                app.status("Paused");
                if (pb.isPlaying) pb.togglePlay();
                return;
            case "play": case "resume": case "continue": case "play music":
                app.status("Playing");
                if (!pb.isPlaying) pb.togglePlay();
                return;
            case "next": case "skip": case "next song": case "skip song": case "skip this":
                app.status("Next song");
                pb.next();
                return;
            case "previous": case "back": case "go back": case "previous song": case "last song":
                app.status("Previous song");
                pb.previous();
                return;
            case "shuffle": case "shuffle on":
                pb.setShuffle(true);
                app.status("Shuffle on");
                return;
            case "shuffle off": case "stop shuffle":
                pb.setShuffle(false);
                app.status("Shuffle off");
                return;
            default:
                break;
        }
        Matcher m = PLAY_TYPE.matcher(l);
        if (m.matches()) { pb.playSearch(m.group(1), m.group(2), false); return; }
        m = QUEUE.matcher(l);
        if (m.matches()) { pb.playSearch("track", m.group(1), true); return; }
        m = PLAY.matcher(l);
        if (m.matches()) { pb.playSearch("track", m.group(1), false); return; }
        m = SEARCH.matcher(l);
        SearchScreen.openWith(app, m.matches() ? m.group(1) : text);
    }
}
