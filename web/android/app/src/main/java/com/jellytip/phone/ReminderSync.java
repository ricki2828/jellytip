package com.jellytip.phone;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Sport reminders on the phone: fetch GET /v1/iptv/reminders with the saved Jellyfin token, schedule one local alarm
 * per item at its notifyAt, cancel the ones that disappeared, never notify twice for the same id + start. No push
 * service: WorkManager (15 min) plus a sync when the app opens keep the alarms current.
 */
final class ReminderSync {
    static final String PREFS = "jellytip-reminders";
    private static final String K_BASE = "apiBase";
    private static final String K_TOKEN = "token";
    private static final String K_ENABLED = "enabled";
    private static final String K_SCHEDULED = "scheduled"; // ids with a live alarm
    private static final String K_FIRED = "fired"; // "id|start" already notified
    static final String ACTION = "com.jellytip.phone.REMINDER";
    private static final long LATE_S = 300;
    private static final Object lock = new Object();

    private ReminderSync() {}

    static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static void configure(Context c, String apiBase, String token, boolean enabled) {
        SharedPreferences.Editor e = prefs(c).edit().putBoolean(K_ENABLED, enabled);
        if (apiBase != null && !apiBase.isEmpty()) e.putString(K_BASE, apiBase);
        // An empty token = signed out: forget it and drop the alarms
        if (token != null) e.putString(K_TOKEN, token);
        e.apply();
        if (token != null && token.isEmpty()) cancelAll(c);
    }

    /** Blocking (network): call off the main thread. Returns false when the server could not be reached. */
    static boolean sync(Context ctx) {
        Context c = ctx.getApplicationContext();
        SharedPreferences p = prefs(c);
        String base = p.getString(K_BASE, "");
        String token = p.getString(K_TOKEN, "");
        if (!p.getBoolean(K_ENABLED, true) || base == null || base.isEmpty() || token == null || token.isEmpty()) {
            cancelAll(c);
            return true;
        }
        CompanionHttp.Answer a = CompanionHttp.get(base, token, "v1/iptv/reminders");
        if (a.code == 404 || a.code == 403) {
            cancelAll(c);
            return true;
        }
        if (a.code != 200 || a.body == null) return false;
        JSONArray items;
        try {
            items = new JSONObject(a.body).optJSONArray("items");
        } catch (org.json.JSONException ex) {
            return false;
        }
        schedule(c, items == null ? new JSONArray() : items);
        return true;
    }

    private static void schedule(Context c, JSONArray items) {
        synchronized (lock) {
            SharedPreferences p = prefs(c);
            long now = System.currentTimeMillis() / 1000;
            Set<String> fired = new HashSet<>(p.getStringSet(K_FIRED, new HashSet<String>()));
            Set<String> old = new HashSet<>(p.getStringSet(K_SCHEDULED, new HashSet<String>()));
            Set<String> keep = new HashSet<>();
            AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
            for (int i = 0; i < items.length(); i++) {
                JSONObject it = items.optJSONObject(i);
                if (it == null) continue;
                String id = it.isNull("id") ? "" : it.optString("id");
                long start = it.optLong("start", 0);
                if (id.isEmpty() || start <= 0) continue;
                long notifyAt = it.has("notifyAt") && !it.isNull("notifyAt") ? it.optLong("notifyAt") : start - 600;
                if (fired.contains(id + "|" + start)) continue;
                if (now > start + LATE_S) continue; // already started more than 5 min ago
                if (notifyAt <= now) {
                    // Due now (the app just opened, or the reminder was set inside the lead time)
                    cancelAlarm(c, am, id);
                    fire(c, it.toString());
                    continue;
                }
                keep.add(id);
                setAlarm(c, am, id, notifyAt * 1000L, it.toString());
            }
            for (String id : old) if (!keep.contains(id)) cancelAlarm(c, am, id);
            // Forget fired marks for events long over
            Iterator<String> fi = fired.iterator();
            while (fi.hasNext()) {
                String k = fi.next();
                int bar = k.lastIndexOf('|');
                long s = 0;
                try {
                    s = Long.parseLong(k.substring(bar + 1));
                } catch (RuntimeException ignored) {
                    // malformed: drop
                }
                if (bar < 0 || s < now - 86_400) fi.remove();
            }
            p.edit().putStringSet(K_SCHEDULED, keep).putStringSet(K_FIRED, fired).apply();
        }
    }

    static void cancelAll(Context c) {
        synchronized (lock) {
            SharedPreferences p = prefs(c);
            AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
            for (String id : new HashSet<>(p.getStringSet(K_SCHEDULED, new HashSet<String>()))) cancelAlarm(c, am, id);
            p.edit().putStringSet(K_SCHEDULED, new HashSet<String>()).apply();
        }
    }

    private static PendingIntent pending(Context c, String id, String json, int flags) {
        Intent i = new Intent(c, ReminderReceiver.class);
        i.setAction(ACTION);
        i.setData(Uri.parse("jellytip-reminder://" + Uri.encode(id)));
        if (json != null) i.putExtra("item", json);
        return PendingIntent.getBroadcast(c, 0, i, flags | PendingIntent.FLAG_IMMUTABLE);
    }

    private static void setAlarm(Context c, AlarmManager am, String id, long atMs, String json) {
        if (am == null) return;
        PendingIntent pi = pending(c, id, json, PendingIntent.FLAG_UPDATE_CURRENT);
        try {
            boolean exact = android.os.Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms();
            if (exact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, pi);
            else if (android.os.Build.VERSION.SDK_INT >= 19) am.setWindow(AlarmManager.RTC_WAKEUP, atMs, 120_000L, pi);
            else am.set(AlarmManager.RTC_WAKEUP, atMs, pi);
        } catch (SecurityException e) {
            am.set(AlarmManager.RTC_WAKEUP, atMs, pi);
        }
    }

    private static void cancelAlarm(Context c, AlarmManager am, String id) {
        if (am == null) return;
        PendingIntent pi = pending(c, id, null, PendingIntent.FLAG_NO_CREATE);
        if (pi != null) {
            am.cancel(pi);
            pi.cancel();
        }
    }

    /** Post the notification for [json] (a reminder item) unless it already fired or started over 5 min ago. */
    static void fire(Context ctx, String json) {
        Context c = ctx.getApplicationContext();
        try {
            JSONObject it = new JSONObject(json);
            String id = it.optString("id");
            long start = it.optLong("start", 0);
            long now = System.currentTimeMillis() / 1000;
            synchronized (lock) {
                SharedPreferences p = prefs(c);
                String key = id + "|" + start;
                Set<String> fired = new HashSet<>(p.getStringSet(K_FIRED, new HashSet<String>()));
                if (id.isEmpty() || fired.contains(key) || now > start + LATE_S) return;
                fired.add(key);
                p.edit().putStringSet(K_FIRED, fired).apply();
            }
            ReminderNotifier.post(c, it, now);
        } catch (org.json.JSONException ignored) {
            // bad item: nothing to show
        }
    }
}
