package com.jellytip.phone;

import android.content.Intent;
import androidx.activity.result.ActivityResult;
import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.ActivityCallback;
import com.getcapacitor.annotation.CapacitorPlugin;

/**
 * IPTV playback on the phone (~/ai/plans/jellytip-live-sport.md, "IPTV playback on phones", part A): Media3 ExoPlayer
 * in a full-screen activity, because mpegts.js in the WebView can't pass AC-3 through MSE and dies on long live
 * streams. The web app asks /play first (409 pop-up, renewals stay in JS), then calls {@link #open} with the link;
 * the call resolves when the player closes ({reason: back|ended|error, positionMs, durationMs, error…}). While it
 * plays, "progress" (films, episodes, recordings) and "playerError" events go to the web app.
 */
@CapacitorPlugin(name = "JellytipPlayer")
public class PlayerPlugin extends Plugin {

    /** The plugin the open player reports to (one player at a time). */
    static volatile PlayerPlugin current;

    @Override
    public void load() {
        current = this;
    }

    /** {url, title, mime?, live, growing?, resumeMs?, note?, channelId?, alternates?, programmeStart?, programmeStop?}: open the player; resolves when it closes. */
    @PluginMethod
    public void open(PluginCall call) {
        String url = call.getString("url");
        if (url == null || url.isEmpty()) {
            call.reject("No stream link");
            return;
        }
        current = this;
        Intent i = new Intent(getContext(), PlayerActivity.class);
        i.putExtra(PlayerActivity.EXTRA_URL, url);
        i.putExtra(PlayerActivity.EXTRA_TITLE, call.getString("title", ""));
        i.putExtra(PlayerActivity.EXTRA_MIME, call.getString("mime", ""));
        i.putExtra(PlayerActivity.EXTRA_LIVE, Boolean.TRUE.equals(call.getBoolean("live", false)));
        i.putExtra(PlayerActivity.EXTRA_RESUME_MS, call.getLong("resumeMs", 0L));
        i.putExtra(PlayerActivity.EXTRA_NOTE, call.getString("note", ""));
        i.putExtra(PlayerActivity.EXTRA_GROWING, Boolean.TRUE.equals(call.getBoolean("growing", false)));
        // Live extras (plan "8h Block 3"): the channel, its event's alternates (failover) and the programme on now
        // (From kick-off); links for them come later through "needUrl" -> provideUrl
        i.putExtra(PlayerActivity.EXTRA_CHANNEL_ID, call.getString("channelId"));
        JSArray alts = call.getArray("alternates", new JSArray());
        java.util.List<String> ids = new java.util.ArrayList<>();
        java.util.List<String> names = new java.util.ArrayList<>();
        for (int k = 0; k < alts.length(); k++) {
            org.json.JSONObject a = alts.optJSONObject(k);
            if (a == null || a.optString("id").isEmpty()) continue;
            ids.add(a.optString("id"));
            names.add(a.optString("name", a.optString("id")));
        }
        i.putExtra(PlayerActivity.EXTRA_ALT_IDS, ids.toArray(new String[0]));
        i.putExtra(PlayerActivity.EXTRA_ALT_NAMES, names.toArray(new String[0]));
        i.putExtra(PlayerActivity.EXTRA_PROG_START, call.getLong("programmeStart", 0L));
        i.putExtra(PlayerActivity.EXTRA_PROG_STOP, call.getLong("programmeStop", 0L));
        // Pre-game (plan live-sport "Pinned 31-Oct (native phone player)"): phase, startsAt (ISO UTC), altChannels
        i.putExtra(PlayerActivity.EXTRA_PHASE, call.getString("phase"));
        i.putExtra(PlayerActivity.EXTRA_STARTS_AT, isoMs(call.getString("startsAt")));
        // Match info / live stats (plan "In-event panel"): the fixture id, and the API base + Jellyfin token for the
        // native live drawer's own polling
        i.putExtra(PlayerActivity.EXTRA_MATCH_ID, call.getString("matchId"));
        i.putExtra(PlayerActivity.EXTRA_API_BASE, call.getString("apiBase"));
        i.putExtra(PlayerActivity.EXTRA_TOKEN, call.getString("token"));
        if (call.getData().has("altChannels")) {
            JSArray pre = call.getArray("altChannels", new JSArray());
            java.util.List<String> pIds = new java.util.ArrayList<>();
            java.util.List<String> pNames = new java.util.ArrayList<>();
            for (int k = 0; k < pre.length(); k++) {
                org.json.JSONObject a = pre.optJSONObject(k);
                if (a == null || a.optString("id").isEmpty()) continue;
                pIds.add(a.optString("id"));
                pNames.add(a.optString("name", a.optString("id")));
            }
            i.putExtra(PlayerActivity.EXTRA_PRE_ALT_IDS, pIds.toArray(new String[0]));
            i.putExtra(PlayerActivity.EXTRA_PRE_ALT_NAMES, pNames.toArray(new String[0]));
        }
        startActivityForResult(call, i, "onPlayerClosed");
    }

    @ActivityCallback
    private void onPlayerClosed(PluginCall call, ActivityResult result) {
        if (call == null) return;
        Intent data = result.getData();
        JSObject ret = new JSObject();
        if (data == null) {
            ret.put("reason", "back");
            ret.put("positionMs", 0);
            ret.put("durationMs", 0);
            call.resolve(ret);
            return;
        }
        ret.put("reason", stringOr(data.getStringExtra(PlayerActivity.RESULT_REASON), "back"));
        String ch = data.getStringExtra(PlayerActivity.RESULT_CHANNEL_ID);
        if (ch != null) ret.put("channelId", ch);
        ret.put("positionMs", data.getLongExtra(PlayerActivity.RESULT_POSITION_MS, 0));
        ret.put("durationMs", data.getLongExtra(PlayerActivity.RESULT_DURATION_MS, 0));
        String error = data.getStringExtra(PlayerActivity.RESULT_ERROR);
        if (error != null) {
            ret.put("error", error);
            ret.put("errorCode", stringOr(data.getStringExtra(PlayerActivity.RESULT_ERROR_CODE), ""));
            ret.put("httpStatus", data.getIntExtra(PlayerActivity.RESULT_HTTP_STATUS, 0));
            String iptvError = data.getStringExtra(PlayerActivity.RESULT_IPTV_ERROR);
            if (iptvError != null) ret.put("iptvError", iptvError);
        }
        call.resolve(ret);
    }

    /**
     * The web app's answer to a "needUrl" event: {requestId, url?, mime?, error?}. A missing url (with error) tells the
     * player that link can't be had (failover tries the next alternate).
     */
    @PluginMethod
    public void provideUrl(PluginCall call) {
        Integer id = call.getInt("requestId");
        String url = call.getString("url");
        String mime = call.getString("mime");
        String error = call.getString("error");
        PlayerActivity a = PlayerActivity.instance.get();
        if (id == null || a == null) {
            call.resolve();
            return;
        }
        a.runOnUiThread(() -> a.onProvidedUrl(id, url, mime, error));
        call.resolve();
    }

    /** From the player activity: a JS event ("progress", "playerError", "needUrl", "failover"). */
    void emit(String event, JSObject data) {
        notifyListeners(event, data);
    }

    /** ISO-8601 ("2026-10-31T18:00:00Z", offsets allowed) or epoch seconds -> epoch ms; 0 when absent or unreadable. */
    private static long isoMs(String v) {
        if (v == null || v.isEmpty()) return 0L;
        try {
            return Long.parseLong(v) * 1000L;
        } catch (NumberFormatException ignored) {
            // ISO below
        }
        // minSdk 24: no java.time; "2026-10-31T18:00:00Z" / "...+01:00", fractional seconds dropped
        String t = v.replaceAll("\\.\\d+", "").replaceAll("Z$", "+00:00");
        try {
            java.util.Date d = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", java.util.Locale.US).parse(t);
            return d == null ? 0L : d.getTime();
        } catch (java.text.ParseException e) {
            return 0L;
        }
    }

    private static String stringOr(String s, String fallback) {
        return s == null ? fallback : s;
    }
}
