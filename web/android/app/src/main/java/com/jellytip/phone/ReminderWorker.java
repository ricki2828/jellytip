package com.jellytip.phone;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;
import java.util.concurrent.TimeUnit;

/** Every 15 min (network required): re-fetch the reminders and refresh the local alarms. */
public class ReminderWorker extends Worker {
    private static final String NAME = "jellytip-reminder-sync";

    public ReminderWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        return ReminderSync.sync(getApplicationContext()) ? Result.success() : Result.retry();
    }

    static void enqueue(Context c) {
        PeriodicWorkRequest req =
            new PeriodicWorkRequest.Builder(ReminderWorker.class, 15, TimeUnit.MINUTES)
                .setConstraints(new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build();
        WorkManager.getInstance(c.getApplicationContext()).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, req);
    }
}
