package com.safelogj.lim;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.safelogj.lim.model.Chat;
import com.safelogj.lim.model.Message;
import com.safelogj.lim.viewmodels.ResultCallback;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public class MessageWorker extends Worker {

    public MessageWorker(@NonNull Context context, @NonNull WorkerParameters workerParams) {
        super(context, workerParams);
    }

    @NonNull
    @Override
    public Result doWork() {
        Log.d(AppController.LOG_TAG, "MessageWorker.doWork()");
        AppController controller = (AppController) getApplicationContext();
        if (controller.getUserId() > 0 && controller.startedActivities.get() == 0) {
            return startDownloadNewMsg(controller);
        }
        return Result.success();
    }

    private Result startDownloadNewMsg(AppController controller) {
        Log.d(AppController.LOG_TAG, "MessageWorker.startDownloadNewMsg()");
        CountDownLatch latch = new CountDownLatch(1);
        AtomicLong lastId = new AtomicLong(-1L);
        controller.getDbHelper().getLastDbMessageId(controller.getUserId(), new ResultCallback<>() {
            @Override
            public void onSuccess(Long lastServerId) { // lastServerId >= 0
                lastId.set(lastServerId);
                latch.countDown();
            }

            @Override
            public void onError(String msg) {
                latch.countDown();
                Log.d(AppController.LOG_TAG, msg);
            }
        });

        try {
            if (latch.await(10, TimeUnit.SECONDS)
                    && lastId.get() != -1L
                    && controller.startedActivities.get() == 0
                    && controller.activeDownloadsCount.get() == 0) {
                controller.activeDownloadsCount.incrementAndGet();
                controller.getNetworkService().getNewMessages(lastId.get());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Result.retry();
        }
        return startSendingMsgList(controller);
    }

    private Result startSendingMsgList(AppController controller) {
        Log.d(AppController.LOG_TAG, "MessageWorker.startSendingMsgList()");
        CountDownLatch latch = new CountDownLatch(1);
        List<Message> list = new ArrayList<>();
        controller.getDbHelper().getPendingMessages(new ResultCallback<>() {
            @Override
            public void onSuccess(List<Message> messages) {
                list.addAll(messages);
                latch.countDown();
            }

            @Override
            public void onError(String msg) {
                latch.countDown();
            }
        });

        try {
            if (latch.await(10, TimeUnit.SECONDS)) {
                for (Message msg : list) {
                    if (controller.startedActivities.get() == 0) {
                        if (msg.type.equals(Message.TYPE_TEXT)) {
                            controller.getNetworkService().sendTextMessage(msg);
                        } else {
                              controller.getNetworkService().sendMediaMessage(msg);
                        }
                    }
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Result.retry();
        }
        return checkNotification(controller);
    }

    private Result checkNotification(AppController controller) {
        Log.d(AppController.LOG_TAG, "MessageWorker.checkNotification()");
        CountDownLatch latch = new CountDownLatch(1);
        List<Chat> list = new ArrayList<>();
        controller.getDbHelper().getUnreadChats(new ResultCallback<>() {
            @Override
            public void onSuccess(List<Chat> unreadChats) {
                Log.w(AppController.LOG_TAG, "список чатов для уведомлений: " + unreadChats.size());
                list.addAll(unreadChats);
                latch.countDown();
            }

            @Override
            public void onError(String msg) {
                latch.countDown();
                Log.w(AppController.LOG_TAG, msg);
            }
        });

        try {
            if (latch.await(10, TimeUnit.SECONDS)
                    && !list.isEmpty()
                    && controller.startedActivities.get() == 0) {
                NotificationHelper.showNotification(controller, list);
            }
            return Result.success();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Result.retry();
        }
    }
}
