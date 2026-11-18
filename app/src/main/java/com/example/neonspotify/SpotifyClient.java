package com.example.neonspotify;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Minimal Spotify Web API client: PKCE login, token storage and refresh, and JSON requests.
 * Uses only Android framework classes (no third-party libraries).
 *
 * Callback codes: HTTP status, or 0 = not connected, -1 = network error (body holds the message).
 */
final class SpotifyClient {
    interface Result { void done(int code, String body); }
    interface Listener { void onSessionChanged(String message); }

    static final String API = "https://api.spotify.com/v1";
    static final String REDIRECT = "neonspotify://callback";
    private static final String TOKEN_URL = "https://accounts.spotify.com/api/token";
    private static final String SCOPES = "user-read-playback-state user-modify-playback-state "
            + "user-read-currently-playing playlist-read-private playlist-read-collaborative";

    private final Activity activity;
    private final Listener listener;
    private final SharedPreferences prefs;
    private final String buildClientId;
    private volatile String clientId;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    private volatile String accessToken, refreshToken;
    private volatile long expiresAt;
    private volatile long rateLimitedUntil;
    volatile String lastDiagnostic = "No requests yet";

    SpotifyClient(Activity activity, String clientId, Listener listener) {
        this.activity = activity;
        this.listener = listener;
        buildClientId = clientId == null ? "" : clientId.trim();
        // New prefs file name on purpose: logins saved by older builds are ignored.
        prefs = activity.getSharedPreferences("spotify_session", Activity.MODE_PRIVATE);
        // A Client ID typed in the app wins over the one baked into the build.
        this.clientId = prefs.getString("client_id", buildClientId);
        accessToken = prefs.getString("access", "");
        refreshToken = prefs.getString("refresh", "");
        expiresAt = prefs.getLong("expiry", 0);
    }

    boolean hasClientId() { return isValidClientId(clientId); }
    String clientId() { return hasClientId() ? clientId : ""; }

    /** Spotify Client IDs are 32 hexadecimal characters. */
    static boolean isValidClientId(String id) { return id != null && id.trim().matches("[0-9a-fA-F]{32}"); }

    /** Saves a Client ID entered in the app. Changing it signs you out, since tokens belong to one Client ID. */
    void setClientId(String id) {
        String clean = id.trim();
        if (!clean.equals(clientId)) clearSession();
        clientId = clean;
        prefs.edit().putString("client_id", clean).apply();
    }
    boolean isConnected() { return !accessToken.isEmpty() || !refreshToken.isEmpty(); }
    boolean isRateLimited() { return System.currentTimeMillis() < rateLimitedUntil; }
    void shutdown() { io.shutdownNow(); }

    String clientIdHint() {
        if (!hasClientId()) return "(not set)";
        return clientId.length() > 8 ? clientId.substring(0, 4) + "…" + clientId.substring(clientId.length() - 4) : clientId;
    }

    // ---------------------------------------------------------------- login

    void login() throws Exception {
        byte[] bytes = new byte[48];
        new SecureRandom().nextBytes(bytes);
        String verifier = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
        String challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        String state = UUID.randomUUID().toString();
        prefs.edit().putString("verifier", verifier).putString("state", state).commit();
        Uri url = Uri.parse("https://accounts.spotify.com/authorize").buildUpon()
                .appendQueryParameter("response_type", "code")
                .appendQueryParameter("client_id", clientId)
                .appendQueryParameter("redirect_uri", REDIRECT)
                .appendQueryParameter("scope", SCOPES)
                .appendQueryParameter("code_challenge_method", "S256")
                .appendQueryParameter("code_challenge", challenge)
                .appendQueryParameter("state", state)
                // Always show the consent screen (with "Not you?") so a different account isn't reused silently.
                .appendQueryParameter("show_dialog", "true")
                .build();
        activity.startActivity(new Intent(Intent.ACTION_VIEW, url));
    }

    /** Handles neonspotify://callback. Returns true if the URI was a Spotify redirect. */
    boolean handleRedirect(Uri uri) {
        if (uri == null || !"neonspotify".equals(uri.getScheme())) return false;
        String error = uri.getQueryParameter("error");
        String code = uri.getQueryParameter("code");
        String state = uri.getQueryParameter("state");
        String expected = prefs.getString("state", "");
        String verifier = prefs.getString("verifier", "");
        prefs.edit().remove("state").remove("verifier").apply();
        if (error != null) { notifySession("Spotify login cancelled (" + error + ")"); return true; }
        if (code == null || expected.isEmpty() || !expected.equals(state)) {
            notifySession("Spotify login failed. Tap CONNECT to try again.");
            return true;
        }
        io.execute(() -> {
            try {
                saveToken(tokenPost("grant_type=authorization_code&code=" + enc(code)
                        + "&redirect_uri=" + enc(REDIRECT) + "&client_id=" + enc(clientId)
                        + "&code_verifier=" + enc(verifier)));
                lastDiagnostic = "Login successful";
                notifySession("Connected to Spotify");
            } catch (Exception e) {
                lastDiagnostic = "Login failed: " + e.getMessage();
                notifySession("Login failed: " + e.getMessage());
            }
        });
        return true;
    }

    void clearSession() {
        accessToken = ""; refreshToken = ""; expiresAt = 0;
        prefs.edit().remove("access").remove("refresh").remove("expiry").apply();
    }

    // ---------------------------------------------------------------- API requests

    void request(String method, String path, String json, Result cb) {
        io.execute(() -> {
            String label = method + " " + path.replace(API, "").split("\\?")[0];
            try {
                if (!ensureToken()) { deliver(cb, 0, ""); return; }
                Response r = http(method, path, json);
                if (r.code == 401 && !refreshToken.isEmpty() && refresh()) r = http(method, path, json);
                if (r.code == 429) rateLimitedUntil = System.currentTimeMillis() + parseLong(r.retryAfter, 10) * 1000L;
                lastDiagnostic = label + " → HTTP " + r.code + (r.code >= 400 ? ": " + shortError(r.body) : "");
                deliver(cb, r.code, r.body);
            } catch (Exception e) {
                lastDiagnostic = label + " → network error: " + e.getMessage();
                deliver(cb, -1, String.valueOf(e.getMessage()));
            }
        });
    }

    /** Human-readable explanation of a failed request. */
    String explain(int code, String body) {
        if (code == -1) return "Network error: " + body;
        if (code == 0) return "Not connected. Tap CONNECT.";
        String msg = shortError(body);
        String lower = msg.toLowerCase(Locale.ROOT);
        if (code == 403 && lower.contains("registered"))
            return "This Spotify account isn't allowlisted for Client ID " + clientIdHint()
                    + ". Check User Management in the Spotify dashboard, then Settings → Disconnect and connect again.";
        if (code == 403 && lower.contains("premium")) return "Spotify Premium is required for playback control.";
        if (code == 404) return "No active playback device. Tap ◎ to choose one.";
        if (code == 429) return "Spotify rate limit reached. Slowing down…";
        return "Spotify HTTP " + code + ": " + msg;
    }

    // ---------------------------------------------------------------- internals

    private static final class Response {
        final int code; final String body; final String retryAfter;
        Response(int code, String body, String retryAfter) { this.code = code; this.body = body; this.retryAfter = retryAfter; }
    }

    /** A 4xx from the token endpoint: the saved login is no longer valid. */
    private static final class TokenRejected extends IOException {
        TokenRejected(String m) { super(m); }
    }

    private boolean ensureToken() throws IOException {
        if (accessToken.isEmpty() && refreshToken.isEmpty()) return false;
        if (!accessToken.isEmpty() && System.currentTimeMillis() < expiresAt - 60_000) return true;
        if (refreshToken.isEmpty()) return !accessToken.isEmpty();
        return refresh();
    }

    private boolean refresh() throws IOException {
        try {
            saveToken(tokenPost("grant_type=refresh_token&refresh_token=" + enc(refreshToken) + "&client_id=" + enc(clientId)));
            return true;
        } catch (TokenRejected e) {
            clearSession();
            lastDiagnostic = "Saved login rejected: " + e.getMessage();
            notifySession("Your Spotify login expired. Tap CONNECT.");
            return false;
        }
    }

    private Response http(String method, String path, String json) throws IOException {
        if (path.startsWith("https://") && !path.startsWith(API + "/")) throw new IOException("Refusing non-Spotify URL");
        HttpURLConnection c = (HttpURLConnection) new URL(path.startsWith("https://") ? path : API + path).openConnection();
        try {
            c.setRequestMethod(method);
            c.setConnectTimeout(10_000);
            c.setReadTimeout(10_000);
            c.setRequestProperty("Authorization", "Bearer " + accessToken);
            if (!"GET".equals(method)) {
                // Spotify rejects body-less PUT/POST without Content-Length, so always send one.
                byte[] data = json == null ? new byte[0] : json.getBytes(StandardCharsets.UTF_8);
                c.setDoOutput(true);
                c.setFixedLengthStreamingMode(data.length);
                c.setRequestProperty("Content-Type", "application/json");
                try (OutputStream out = c.getOutputStream()) { out.write(data); }
            }
            int code = c.getResponseCode();
            String body = readAll(code < 400 ? c.getInputStream() : c.getErrorStream());
            return new Response(code, body, c.getHeaderField("Retry-After"));
        } finally {
            c.disconnect();
        }
    }

    private JSONObject tokenPost(String form) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(TOKEN_URL).openConnection();
        try {
            byte[] data = form.getBytes(StandardCharsets.UTF_8);
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setFixedLengthStreamingMode(data.length);
            c.setConnectTimeout(10_000);
            c.setReadTimeout(10_000);
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            try (OutputStream out = c.getOutputStream()) { out.write(data); }
            int code = c.getResponseCode();
            String body = readAll(code < 400 ? c.getInputStream() : c.getErrorStream());
            if (code >= 400 && code < 500 && code != 429) throw new TokenRejected("HTTP " + code + ": " + shortError(body));
            if (code != 200) throw new IOException("Token request HTTP " + code);
            return new JSONObject(body);
        } catch (JSONException e) {
            throw new IOException("Unreadable token response");
        } finally {
            c.disconnect();
        }
    }

    private void saveToken(JSONObject t) {
        accessToken = t.optString("access_token", accessToken);
        String newRefresh = t.optString("refresh_token", "");
        if (!newRefresh.isEmpty()) refreshToken = newRefresh;
        expiresAt = System.currentTimeMillis() + t.optLong("expires_in", 3600) * 1000L;
        prefs.edit().putString("access", accessToken).putString("refresh", refreshToken).putLong("expiry", expiresAt).apply();
    }

    private void deliver(Result cb, int code, String body) {
        main.post(() -> { if (!activity.isFinishing() && !activity.isDestroyed()) cb.done(code, body); });
    }

    private void notifySession(String message) {
        main.post(() -> { if (!activity.isFinishing() && !activity.isDestroyed()) listener.onSessionChanged(message); });
    }

    static String shortError(String body) {
        if (body == null || body.isEmpty()) return "No details";
        try {
            JSONObject j = new JSONObject(body);
            JSONObject e = j.optJSONObject("error");
            if (e != null) return e.optString("message", body);
            return j.optString("error_description", j.optString("error", body));
        } catch (JSONException ignored) {
            return body.length() > 240 ? body.substring(0, 240) : body;
        }
    }

    /** InputStream.readAllBytes() needs Android 13+, so read manually. */
    private static String readAll(InputStream in) throws IOException {
        if (in == null) return "";
        try (InputStream s = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = s.read(buf)) > 0) out.write(buf, 0, n);
            return out.toString("UTF-8");
        }
    }

    /** URLEncoder.encode(String, Charset) needs Android 13+, so use the String overload. */
    static String enc(String s) {
        try { return URLEncoder.encode(s, "UTF-8"); }
        catch (UnsupportedEncodingException e) { throw new IllegalStateException(e); }
    }

    private static long parseLong(String s, long fallback) {
        try { return s == null ? fallback : Long.parseLong(s.trim()); } catch (NumberFormatException e) { return fallback; }
    }
}
