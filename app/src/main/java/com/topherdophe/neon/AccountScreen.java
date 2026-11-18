package com.topherdophe.neon;

import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONException;
import org.json.JSONObject;

/** Spotify account: who's connected, the Client ID, connect / disconnect. */
final class AccountScreen extends BaseScreen {
    @Override BaseScreen back() { return new SettingsScreen(); }

    @Override void build() {
        heading("Account");
        LinearLayout list = scrollList();
        TextView account = ui.text("Client ID: " + spotify.clientIdHint() + "\n"
                + (spotify.isConnected() ? "Connected" : "Not connected"), 15, false);
        account.setPadding(0, ui.dp(8), 0, ui.dp(8));
        list.addView(account, Ui.matchWrap());
        if (spotify.isConnected()) {
            spotify.request("GET", "/me", null, (code, body) -> {
                if (code != 200) { account.setText(spotify.explain(code, body)); return; }
                try {
                    JSONObject j = new JSONObject(body);
                    String name = Json.str(j, "display_name");
                    account.setText("Connected as " + (name.isEmpty() ? Json.str(j, "id") : name)
                            + "\nClient ID: " + spotify.clientIdHint());
                } catch (JSONException e) {
                    account.setText("Account response unreadable");
                }
            });
        }
        list.addView(ui.action("CHANGE CLIENT ID", 15, () -> app.open(new SetupScreen().from(this))), Ui.matchWrap());
        if (spotify.isConnected()) {
            list.addView(ui.action("DISCONNECT", 15, () -> {
                app.forgetSession();
                app.status("Disconnected");
                app.open(new AccountScreen());
            }), Ui.matchWrap());
        } else {
            list.addView(ui.action("CONNECT TO SPOTIFY", 15, app::login), Ui.matchWrap());
        }
        TextView hint = ui.text("New features sometimes need extra Spotify permissions. If something says it needs "
                + "new permissions, tap DISCONNECT, then connect again.", 13, false);
        hint.setAlpha(.6f);
        hint.setPadding(0, ui.dp(16), 0, 0);
        list.addView(hint, Ui.matchWrap());
        backButton();
    }

    @Override void onSessionChanged() { app.open(new AccountScreen()); }
}
