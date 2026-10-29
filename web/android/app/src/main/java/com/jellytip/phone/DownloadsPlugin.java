package com.jellytip.phone;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.pm.ActivityInfo;
import android.content.Context;
import android.database.Cursor;
import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import java.io.File;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import org.json.JSONArray;
import org.json.JSONException;

/**
 * Offline downloads through Android's DownloadManager: runs in the system download service (survives the app being
 * closed or killed), resumes after network drops when the server supports ranges, and can be held to Wi-Fi.
 * Files go to the app-specific external directory (private to the app, removed on uninstall, no storage permission).
 */
@CapacitorPlugin(name = "JellytipDownloads")
public class DownloadsPlugin extends Plugin {

    private static final String SUBDIR = DownloadQueue.SUBDIR;

    private DownloadManager dm() {
        return (DownloadManager) getContext().getSystemService(Context.DOWNLOAD_SERVICE);
    }

    private File dir() {
        return DownloadQueue.dir(getContext());
    }

    /** Replace the waiting list ({key,url,headers,fileName,title,wifiOnly}[] in order) and the slot limit, then pump. */
    @PluginMethod
    public void setQueue(PluginCall call) {
        int limit = call.getInt("limit", 2);
        JSArray items = call.getArray("items", new JSArray());
        new Thread(() -> {
            DownloadQueue.setQueue(getContext(), limit, items);
            JSObject ret = new JSObject();
            ret.put("started", DownloadQueue.started(getContext()));
            ret.put("active", DownloadQueue.active(getContext()));
            call.resolve(ret);
        }).start();
    }

    /** Items the queue handed to DownloadManager (while the app was open or closed) that the web app hasn't recorded. */
    @PluginMethod
    public void takeStarted(PluginCall call) {
        JSObject ret = new JSObject();
        ret.put("started", DownloadQueue.started(getContext()));
        call.resolve(ret);
    }

    @PluginMethod
    public void ackStarted(PluginCall call) {
        DownloadQueue.ack(getContext(), call.getArray("keys", new JSArray()));
        call.resolve();
    }

    @PluginMethod
    public void query(PluginCall call) {
        long[] ids = ids(call);
        JSArray items = new JSArray();
        if (ids.length > 0) {
            try (Cursor c = dm().query(new DownloadManager.Query().setFilterById(ids))) {
                while (c != null && c.moveToNext()) {
                    JSObject o = new JSObject();
                    o.put("downloadId", c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_ID)));
                    o.put("status", status(c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))));
                    o.put("bytesDone", c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)));
                    o.put("bytesTotal", c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)));
                    o.put("reason", c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON)));
                    items.put(o);
                }
            }
        }
        JSObject ret = new JSObject();
        ret.put("items", items);
        call.resolve(ret);
    }

    /** Cancels / forgets downloads (DownloadManager deletes their files) and deletes any listed leftover files. */
    @PluginMethod
    public void remove(PluginCall call) {
        long[] ids = ids(call);
        if (ids.length > 0) dm().remove(ids);
        JSArray paths = call.getArray("paths", new JSArray());
        String root = dir().getAbsolutePath() + File.separator;
        for (int i = 0; i < paths.length(); i++) {
            String p = paths.optString(i, "");
            if (p.startsWith(root) && !p.contains("..")) {
                //noinspection ResultOfMethodCallIgnored
                new File(p).delete();
            }
        }
        call.resolve();
    }

    @PluginMethod
    public void storage(PluginCall call) {
        File d = dir();
        long used = 0;
        File[] files = d.listFiles();
        if (files != null) for (File f : files) used += f.length();
        JSObject ret = new JSObject();
        ret.put("usedBytes", used);
        ret.put("freeBytes", d.getUsableSpace());
        ret.put("dir", d.getAbsolutePath());
        call.resolve(ret);
    }

    @PluginMethod
    public void exists(PluginCall call) {
        String p = call.getString("path", "");
        File f = new File(p);
        JSObject ret = new JSObject();
        ret.put("exists", p.startsWith(dir().getAbsolutePath()) && f.isFile());
        ret.put("bytes", f.isFile() ? f.length() : 0);
        call.resolve(ret);
    }

    /**
     * Player mode: landscape + system bars hidden (swipe to peek), or back to normal. The WebView can't do element
     * fullscreen or lock orientation itself inside Capacitor, so the player asks for it here.
     */
    @PluginMethod
    public void setPlayerMode(PluginCall call) {
        boolean on = Boolean.TRUE.equals(call.getBoolean("fullscreen", false));
        Activity a = getActivity();
        a.runOnUiThread(() -> {
            WindowInsetsControllerCompat c = WindowCompat.getInsetsController(a.getWindow(), a.getWindow().getDecorView());
            if (on) {
                c.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                c.hide(WindowInsetsCompat.Type.systemBars());
                a.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
            } else {
                c.show(WindowInsetsCompat.Type.systemBars());
                a.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
            }
            call.resolve();
        });
    }

    private static long[] ids(PluginCall call) {
        JSONArray arr = call.getArray("ids", new JSArray());
        long[] out = new long[arr.length()];
        for (int i = 0; i < arr.length(); i++) {
            try {
                out[i] = arr.getLong(i);
            } catch (JSONException e) {
                out[i] = -1;
            }
        }
        return out;
    }

    private static String status(int s) {
        switch (s) {
            case DownloadManager.STATUS_PENDING:
                return "pending";
            case DownloadManager.STATUS_RUNNING:
                return "running";
            case DownloadManager.STATUS_PAUSED:
                return "paused";
            case DownloadManager.STATUS_SUCCESSFUL:
                return "done";
            default:
                return "failed";
        }
    }
}
