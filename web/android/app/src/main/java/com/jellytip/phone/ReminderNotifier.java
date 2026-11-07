package com.jellytip.phone;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import androidx.core.app.NotificationChannelCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import org.json.JSONObject;

/** The "Sport reminders" notification: "<Away> v <Home> starts in 10 min", league · channel; a tap opens the item's deepLink. */
final class ReminderNotifier {
    static final String CHANNEL = "sport_reminders";
    static final String EXTRA_DEEPLINK = "jellytipDeepLink";

    private ReminderNotifier() {}

    static void post(Context c, JSONObject it, long nowS) {
        NotificationManagerCompat nm = NotificationManagerCompat.from(c);
        if (android.os.Build.VERSION.SDK_INT >= 33
            && androidx.core.content.ContextCompat.checkSelfPermission(c, android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) return;
        if (!nm.areNotificationsEnabled()) return;
        nm.createNotificationChannel(
            new NotificationChannelCompat.Builder(CHANNEL, NotificationManagerCompat.IMPORTANCE_HIGH)
                .setName("Sport reminders")
                .setDescription("Your \"remind me\" sport, shortly before kick-off")
                .build()
        );
        String id = it.optString("id");
        long start = it.optLong("start", 0);
        String title = it.isNull("title") ? "" : it.optString("title");
        // "Away at Home" -> "Away v Home"
        title = title.replace(" at ", " v ").replace(" @ ", " v ");
        long mins = Math.max(0, (start - nowS + 30) / 60);
        String when = start <= nowS ? "is on now" : mins <= 0 ? "is about to start" : "starts in " + mins + " min";
        StringBuilder sub = new StringBuilder();
        String league = it.isNull("league") ? "" : it.optString("league");
        if (!league.isEmpty()) sub.append(league);
        JSONObject ch = it.optJSONObject("channel");
        String chName = ch == null || ch.isNull("name") ? "" : ch.optString("name");
        if (!chName.isEmpty()) sub.append(sub.length() > 0 ? " · " : "").append(chName);
        else if (ch == null) sub.append(sub.length() > 0 ? " · " : "").append("No stream found yet");
        String deep = it.isNull("deepLink") ? "" : it.optString("deepLink");

        Intent open = new Intent(c, MainActivity.class);
        open.setAction("com.jellytip.phone.OPEN_REMINDER");
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        open.putExtra(EXTRA_DEEPLINK, deep);
        int code = id.hashCode();
        PendingIntent pi = PendingIntent.getActivity(c, code, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        String head = title.isEmpty() ? "Sport reminder" : title;
        NotificationCompat.Builder b =
            new NotificationCompat.Builder(c, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_live)
                .setContentTitle(head + " " + when)
                .setContentText(sub.toString())
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setAutoCancel(true)
                .setContentIntent(pi)
                .setWhen(start * 1000L)
                .setShowWhen(true);
        try {
            nm.notify(7000 + Math.abs(code % 1000), b.build());
        } catch (SecurityException ignored) {
            // permission withdrawn meanwhile
        }
    }
}
