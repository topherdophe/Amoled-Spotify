package com.topherdophe.neon;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Fired by the sleep-timer alarm: pauses Spotify, even if the app isn't open. */
public class SleepReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        SleepTimer.clear(context);
        PendingResult result = goAsync();
        Context app = context.getApplicationContext();
        new Thread(() -> {
            SpotifyClient client = new SpotifyClient(app, app.getString(R.string.spotify_client_id), null);
            try {
                client.requestBlocking("PUT", "/me/player/pause", null);
            } finally {
                client.shutdown();
                result.finish();
            }
        }, "sleep-timer").start();
    }
}
