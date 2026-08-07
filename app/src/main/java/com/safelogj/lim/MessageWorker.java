package com.safelogj.lim;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.safelogj.lim.model.Chat;
import com.safelogj.lim.model.Message;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class MessageWorker extends Worker {

    public MessageWorker(@NonNull Context context, @NonNull WorkerParameters workerParams) {
        super(context, workerParams);
    }

    @NonNull
    @Override
    public Result doWork() {
        AppController controller = (AppController) getApplicationContext();
        if (controller.getUserId() > 0 && controller.startedActivities.get() == 0) {
            return startDownloadNewMsg(controller);
        }
        return Result.success();
    }

    private Result startDownloadNewMsg(AppController controller) {
        Log.d(AppController.LOG_TAG, "MessageWorker.startDownloadNewMsg()");
        CountDownLatch workerLatch = null;
        if (controller.startedActivities.get() == 0 && controller.activeDownloadsCount.get() == 0) {
            controller.activeDownloadsCount.incrementAndGet();
            workerLatch = new CountDownLatch(1);
            controller.getNetworkService().getNewMessages(controller.getDbHelper().getLastDbMessageId(), null, workerLatch::countDown);
        }
        return startSendingMsgList(controller, workerLatch);
    }

    private Result startSendingMsgList(AppController controller, CountDownLatch latch) {
        Log.d(AppController.LOG_TAG, "MessageWorker.startSendingMsgList()");
        for (Message msg : controller.getDbHelper().getPendingMessages()) {
            if (msg.type.equals(Message.TYPE_TEXT)) {
                controller.getNetworkService().sendTextMessage(msg);
            } else {
                controller.getNetworkService().sendMediaMessage(msg);
            }
        }
        return checkNotification(controller, latch);
    }

    private Result checkNotification(AppController controller, CountDownLatch latch) {
        Log.d(AppController.LOG_TAG, "MessageWorker.checkNotification()");
        waitNotification(latch);
        List<Chat> allUnread = controller.getDbHelper().getUnreadChats();
        if (!allUnread.isEmpty()
                && allUnread.stream().anyMatch(chat -> chat.lastTimestamp > controller.lastWorkerRunTime)
                && controller.startedActivities.get() == 0) {
            NotificationHelper.showNotification(controller, allUnread);
            controller.lastWorkerRunTime = System.currentTimeMillis();
        }
        return Result.success();
    }

    private void waitNotification(CountDownLatch latch) {
        try {
            if (latch != null && !latch.await(30, TimeUnit.SECONDS)) {
                Log.w(AppController.LOG_TAG, "MessageWorker timed out waiting for DB save");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
