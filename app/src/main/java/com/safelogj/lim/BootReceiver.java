package com.safelogj.lim;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import androidx.work.BackoffPolicy;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.work.WorkRequest;

import java.util.concurrent.TimeUnit;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(AppController.LIM_SYNC, ExistingPeriodicWorkPolicy.KEEP,
                    new PeriodicWorkRequest.Builder(MessageWorker.class, 15, TimeUnit.MINUTES)
                    .setConstraints(AppController.constraints)
                    .setBackoffCriteria(BackoffPolicy.LINEAR, WorkRequest.MIN_BACKOFF_MILLIS, TimeUnit.MILLISECONDS)
                    .build());
            Log.d(AppController.LOG_TAG, "BootReceiver пнул воркера ");
        } else {
            Log.d(AppController.LOG_TAG, "BootReceiver " + intent.getAction());
        }
    }
}
