package com.jellytip.phone;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;

/** GETs against the companion API with the caller's Jellyfin token (the native player's own polling). */
final class CompanionHttp {

    /** HTTP status and body (body null unless 200); status 0 = unreachable. */
    static final class Answer {
        final int code;
        final String body;

        Answer(int code, String body) {
            this.code = code;
            this.body = body;
        }
    }

    private CompanionHttp() {}

    /**
     * Match routes' status codes (plan live-sport, "Pinned 31-Oct (match routes' status codes)"): 404 = the route is
     * missing (try again in 10 min); 403 = no IPTV provider (hide match features); 200 `{unknown: true}` = hide this
     * match only.
     */
    static final int OK = 0;
    static final int MISSING = 1;
    static final int NO_IPTV = 2;
    static final int UNKNOWN = 3;
    static final int FAILED = 4;

    static int verdict(Answer a) {
        if (a.code == 404) return MISSING;
        if (a.code == 403) return NO_IPTV;
        if (a.code != 200 || a.body == null) return FAILED;
        try {
            return new org.json.JSONObject(a.body).optBoolean("unknown", false) ? UNKNOWN : OK;
        } catch (org.json.JSONException e) {
            return FAILED;
        }
    }

    static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            return s;
        }
    }

    /** Blocking; call off the main thread. [path] has no leading slash ("v1/sport/match/x/live"). */
    static Answer get(String apiBase, String token, String path) {
        HttpURLConnection c = null;
        try {
            URL url = new URL(apiBase.replaceAll("/+$", "") + "/" + path);
            c = (HttpURLConnection) url.openConnection();
            c.setConnectTimeout(8_000);
            c.setReadTimeout(8_000);
            if (token != null && !token.isEmpty()) c.setRequestProperty("Authorization", "MediaBrowser Token=\"" + token + "\"");
            int code = c.getResponseCode();
            return new Answer(code, code == 200 ? read(c.getInputStream()) : null);
        } catch (Exception e) {
            return new Answer(0, null);
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static String read(InputStream in) throws java.io.IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return out.toString("UTF-8");
    }
}
