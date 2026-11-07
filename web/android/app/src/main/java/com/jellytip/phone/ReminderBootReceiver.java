package com.jellytip.phone;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** After a reboot (or an app update) the alarms are gone: sync again to put them back. */
public class ReminderBootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        String a = intent == null ? null : intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(a) && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(a)) return;
        final PendingResult result = goAsync();
        final Context c = context.getApplicationContext();
        new Thread(() -> {
            try {
                ReminderSync.sync(c);
            } finally {
                result.finish();
            }
        }).start();
    }
}
