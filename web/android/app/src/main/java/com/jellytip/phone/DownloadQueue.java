package com.jellytip.phone;

import android.app.DownloadManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import java.io.File;
import java.util.Iterator;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * "Downloads at once": DownloadManager runs everything it's given in parallel, so files wait here (SharedPreferences)
 * and are handed over only while fewer than `limit` of the app's downloads are pending/running/paused. The web app
 * keeps this list current (setQueue); pump() runs on every change and from DownloadCompleteReceiver, so the queue keeps
 * moving while the app is closed. Started items are reported back to the web app via takeStarted / ackStarted.
 * Only ready-to-fetch files are queued here: server-side preparation is scheduled by the web app.
 */
final class DownloadQueue {
    static final String SUBDIR = "jellytip";
    private static final String PREFS = "jellytip_download_queue";

    private DownloadQueue() {}

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static File dir(Context c) {
        File d = new File(c.getExternalFilesDir(Environment.DIRECTORY_MOVIES), SUBDIR);
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        return d;
    }

    static synchronized void setQueue(Context c, int limit, JSONArray pending) {
        prefs(c).edit().putInt("limit", Math.max(1, limit)).putString("pending", pending.toString()).apply();
        pump(c);
    }

    /** key -> {downloadId, path} for items handed to DownloadManager that the web app hasn't recorded yet. */
    static synchronized JSONObject started(Context c) {
        try {
            return new JSONObject(prefs(c).getString("started", "{}"));
        } catch (JSONException e) {
            return new JSONObject();
        }
    }

    static synchronized void ack(Context c, JSONArray keys) {
        JSONObject s = started(c);
        for (int i = 0; i < keys.length(); i++) s.remove(keys.optString(i));
        prefs(c).edit().putString("started", s.toString()).apply();
    }

    /** Count of this app's downloads that still hold a slot (DownloadManager queries only see the calling app's). */
    static int active(Context c) {
        DownloadManager dm = (DownloadManager) c.getSystemService(Context.DOWNLOAD_SERVICE);
        DownloadManager.Query q = new DownloadManager.Query()
                .setFilterByStatus(DownloadManager.STATUS_PENDING | DownloadManager.STATUS_RUNNING | DownloadManager.STATUS_PAUSED);
        try (Cursor cur = dm.query(q)) {
            return cur == null ? 0 : cur.getCount();
        }
    }

    static synchronized void pump(Context c) {
        SharedPreferences p = prefs(c);
        int limit = p.getInt("limit", 2);
        JSONArray pending;
        JSONObject started;
        try {
            pending = new JSONArray(p.getString("pending", "[]"));
            started = new JSONObject(p.getString("started", "{}"));
        } catch (JSONException e) {
            return;
        }
        int running = active(c);
        JSONArray rest = new JSONArray();
        for (int i = 0; i < pending.length(); i++) {
            JSONObject item = pending.optJSONObject(i);
            if (item == null) continue;
            String key = item.optString("key");
            if (started.has(key)) continue; // already handed over, the web app just hasn't seen it yet
            if (running >= limit) {
                rest.put(item);
                continue;
            }
            try {
                JSONObject r = enqueue(c, item);
                started.put(key, r);
                running++;
            } catch (Exception e) {
                // Bad URL / storage unavailable: report it as a failed start so the web app can show it
                try {
                    started.put(key, new JSONObject().put("error", String.valueOf(e.getMessage())));
                } catch (JSONException ignored) {
                    // unreachable
                }
            }
        }
        p.edit().putString("pending", rest.toString()).putString("started", started.toString()).apply();
    }

    private static JSONObject enqueue(Context c, JSONObject item) throws JSONException {
        String fileName = item.getString("fileName");
        if (fileName.contains("/") || fileName.contains("..")) throw new IllegalArgumentException("bad fileName");
        DownloadManager.Request req = new DownloadManager.Request(Uri.parse(item.getString("url")));
        JSONObject headers = item.optJSONObject("headers");
        if (headers != null) {
            Iterator<String> keys = headers.keys();
            while (keys.hasNext()) {
                String k = keys.next();
                req.addRequestHeader(k, headers.getString(k));
            }
        }
        boolean wifiOnly = item.optBoolean("wifiOnly", false);
        req.setTitle(item.optString("title", "Jellytip"));
        req.setDescription("Jellytip download");
        req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE);
        req.setDestinationInExternalFilesDir(c, Environment.DIRECTORY_MOVIES, SUBDIR + "/" + fileName);
        req.setAllowedOverMetered(!wifiOnly);
        req.setAllowedOverRoaming(!wifiOnly);
        long id = ((DownloadManager) c.getSystemService(Context.DOWNLOAD_SERVICE)).enqueue(req);
        return new JSONObject().put("downloadId", id).put("path", new File(dir(c), fileName).getAbsolutePath());
    }
}
