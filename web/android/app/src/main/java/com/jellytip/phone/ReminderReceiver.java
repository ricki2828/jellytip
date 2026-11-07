package com.jellytip.phone;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** A reminder's alarm went off: post the notification (no network needed; the item rides in the intent). */
public class ReminderReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        String item = intent == null ? null : intent.getStringExtra("item");
        if (item != null) ReminderSync.fire(context, item);
    }
}
