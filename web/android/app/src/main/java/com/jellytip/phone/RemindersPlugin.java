package com.jellytip.phone;

import android.content.Context;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

/** Sport reminders as phone notifications: the web app hands over the API base + token, native syncs and schedules. */
@CapacitorPlugin(name = "JellytipReminders")
public class RemindersPlugin extends Plugin {
    /** A tapped notification's deepLink waiting for the web app ("#/iptv/play?..."), taken once. */
    static volatile String pendingDeepLink;

    /** {apiBase, token, enabled?}: save them, make sure the 15-min job runs, sync now. An empty token = signed out. */
    @PluginMethod
    public void configure(PluginCall call) {
        final Context c = getContext().getApplicationContext();
        final String base = call.getString("apiBase", "");
        final String token = call.getString("token", "");
        final boolean enabled = !Boolean.FALSE.equals(call.getBoolean("enabled", true));
        ReminderSync.configure(c, base, token, enabled);
        ReminderWorker.enqueue(c);
        call.resolve();
        if (token != null && !token.isEmpty()) new Thread(() -> ReminderSync.sync(c)).start();
    }

    /** Sync now (app resumed). */
    @PluginMethod
    public void sync(PluginCall call) {
        final Context c = getContext().getApplicationContext();
        call.resolve();
        new Thread(() -> ReminderSync.sync(c)).start();
    }

    /** {deepLink: string|null}: the tapped notification's link, once. */
    @PluginMethod
    public void takeDeepLink(PluginCall call) {
        String d = pendingDeepLink;
        pendingDeepLink = null;
        JSObject r = new JSObject();
        r.put("deepLink", d == null || d.isEmpty() ? null : d);
        call.resolve(r);
    }
}
