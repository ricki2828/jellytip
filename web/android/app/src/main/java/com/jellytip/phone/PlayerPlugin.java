package com.jellytip.phone;

import android.content.Intent;
import androidx.activity.result.ActivityResult;
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

    /** {url, title, mime?, live, resumeMs?, note?}: open the player; resolves when it closes. */
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

    /** From the player activity: a JS event ("progress", "playerError"). */
    void emit(String event, JSObject data) {
        notifyListeners(event, data);
    }

    private static String stringOr(String s, String fallback) {
        return s == null ? fallback : s;
    }
}
