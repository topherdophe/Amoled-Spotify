package com.topherdophe.neon;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Small helpers for reading Spotify's JSON responses. */
final class Json {
    private Json() { }

    /** optString returns "null" for JSON nulls; this returns "". */
    static String str(JSONObject o, String key) { return o.isNull(key) ? "" : o.optString(key, ""); }

    /** Artists for a track, or the show name for a podcast episode. */
    static String subtitle(JSONObject item) {
        if (item.has("artists")) return joinArtists(item);
        JSONObject show = item.optJSONObject("show");
        return show != null ? str(show, "name") : "";
    }

    static String joinArtists(JSONObject item) {
        JSONArray a = item.optJSONArray("artists");
        if (a == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null) continue;
            if (sb.length() > 0) sb.append(", ");
            sb.append(str(o, "name"));
        }
        return sb.toString();
    }

    /** Picks the smallest image at least 300 px wide (or the largest available). */
    static String pickImage(JSONArray images) {
        if (images == null || images.length() == 0) return "";
        String best = "", largest = "";
        int bestW = Integer.MAX_VALUE, largestW = -1;
        for (int i = 0; i < images.length(); i++) {
            JSONObject im = images.optJSONObject(i);
            if (im == null) continue;
            int w = im.optInt("width", 0);
            String url = str(im, "url");
            if (w >= 300 && w < bestW) { bestW = w; best = url; }
            if (w > largestW) { largestW = w; largest = url; }
        }
        return best.isEmpty() ? largest : best;
    }

    static List<JSONObject> parseDevices(String body) {
        List<JSONObject> out = new ArrayList<>();
        try {
            JSONArray a = new JSONObject(body).getJSONArray("devices");
            for (int i = 0; i < a.length(); i++) {
                JSONObject d = a.optJSONObject(i);
                if (d != null) out.add(d);
            }
        } catch (JSONException ignored) { }
        return out;
    }

    static String withDevice(String path, String id) {
        return path + (path.contains("?") ? "&" : "?") + "device_id=" + SpotifyClient.enc(id);
    }

    /** Milliseconds as m:ss. */
    static String fmt(long ms) {
        long s = Math.max(0, ms / 1000);
        return (s / 60) + ":" + (s % 60 < 10 ? "0" : "") + (s % 60);
    }
}
