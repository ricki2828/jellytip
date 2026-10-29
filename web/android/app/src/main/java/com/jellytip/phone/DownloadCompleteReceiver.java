package com.jellytip.phone;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** A download finished or failed (also fires on cancel): start the next queued file, even with the app closed. */
public class DownloadCompleteReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        PendingResult result = goAsync();
        new Thread(() -> {
            try {
                DownloadQueue.pump(context.getApplicationContext());
            } finally {
                result.finish();
            }
        }).start();
    }
}
