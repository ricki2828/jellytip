package com.jellytip.phone;

import org.json.JSONObject;

/** A short same-device snapshot shared by the panel and alerts. The lock coalesces concurrent reads. */
final class FantasySnapshot {
    private static String key;
    private static JSONObject value;
    private static int code;
    private static long fetchedAt;

    private FantasySnapshot() {}

    static synchronized Result get(String base, String token) {
        String current = base + "\n" + token;
        long now = System.currentTimeMillis();
        if (current.equals(key) && now - fetchedAt < 10_000) return new Result(value, code);
        CompanionHttp.Answer answer = CompanionHttp.get(base, token, "v1/fantasy/live");
        JSONObject parsed = null;
        if (answer.code == 200 && answer.body != null) {
            try { parsed = new JSONObject(answer.body); } catch (org.json.JSONException ignored) { }
        }
        key = current;
        value = parsed;
        code = answer.code;
        fetchedAt = System.currentTimeMillis();
        return new Result(value, code);
    }

    static final class Result {
        final JSONObject data;
        final int code;
        Result(JSONObject data, int code) { this.data = data; this.code = code; }
    }
}
