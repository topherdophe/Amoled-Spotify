package com.topherdophe.neon;

import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.text.InputType;
import android.view.Gravity;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

/** First-run / change screen for the Spotify Client ID, so it doesn't have to be baked into the build. */
final class SetupScreen extends BaseScreen {
    /** With no Client ID yet there's nowhere to go back to. */
    @Override BaseScreen back() { return spotify.hasClientId() ? new PlayerScreen() : null; }

    @Override void build() {
        heading("Spotify setup");
        LinearLayout list = scrollList();
        TextView steps = ui.text("1. Create an app at developer.spotify.com/dashboard and tick Web API.\n"
                + "2. Add this Redirect URI exactly: " + SpotifyClient.REDIRECT + "\n"
                + "3. Under User Management, add your Spotify account's name and email.\n"
                + "4. Copy the app's Client ID and paste it below.", 15, false);
        steps.setGravity(Gravity.START);
        steps.setLineSpacing(ui.dp(4), 1f);
        list.addView(steps, Ui.matchWrap());
        list.addView(ui.action("OPEN SPOTIFY DASHBOARD", 15, () -> {
            try { app.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://developer.spotify.com/dashboard"))); }
            catch (ActivityNotFoundException e) { app.error("No browser found"); }
        }));
        list.addView(ui.action("COPY REDIRECT URI", 15, () -> {
            ClipboardManager cm = app.getSystemService(ClipboardManager.class);
            if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("Redirect URI", SpotifyClient.REDIRECT));
            app.status("Copied " + SpotifyClient.REDIRECT);
        }));

        EditText id = ui.input("Client ID (32 characters)");
        id.setText(spotify.clientId());
        id.setContentDescription("Spotify Client ID");
        id.setTypeface(Typeface.MONOSPACE);
        id.setImeOptions(EditorInfo.IME_ACTION_DONE);
        id.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        LinearLayout.LayoutParams idLp = Ui.matchWrap();
        idLp.topMargin = ui.dp(12);
        list.addView(id, idLp);

        Runnable save = () -> {
            String value = id.getText().toString().trim();
            if (!SpotifyClient.isValidClientId(value)) {
                app.error("That doesn't look like a Client ID: it should be 32 letters and numbers (0-9, a-f).");
                return;
            }
            app.hideKeyboard(id);
            spotify.setClientId(value);
            app.status("Client ID saved");
            app.showPlayer();
            if (!spotify.isConnected()) app.login();
        };
        id.setOnEditorActionListener((v, actionId, ev) -> {
            if (actionId != EditorInfo.IME_ACTION_DONE) return false;
            save.run();
            return true;
        });
        list.addView(ui.action("SAVE AND CONNECT", 18, save));
        if (spotify.hasClientId()) backButton();
    }
}
