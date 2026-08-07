package com.safelogj.lim;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.safelogj.lim.model.Chat;
import com.safelogj.lim.model.Message;

import java.util.List;

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
        if (controller.startedActivities.get() == 0 && controller.activeDownloadsCount.get() == 0) {
            controller.activeDownloadsCount.incrementAndGet();
            controller.getNetworkService().getNewMessages(controller.getDbHelper().getLastDbMessageId(), null);
        }
        return startSendingMsgList(controller);
    }

    private Result startSendingMsgList(AppController controller) {
        Log.d(AppController.LOG_TAG, "MessageWorker.startSendingMsgList()");
        for (Message msg : controller.getDbHelper().getPendingMessages()) {
            if (msg.type.equals(Message.TYPE_TEXT)) {
                controller.getNetworkService().sendTextMessage(msg);
            } else {
                controller.getNetworkService().sendMediaMessage(msg);
            }
        }
        return checkNotification(controller);
    }

    private Result checkNotification(AppController controller) {
        Log.d(AppController.LOG_TAG, "MessageWorker.checkNotification()");
        List<Chat> list = controller.getDbHelper().getUnreadChats();
        if (!list.isEmpty() && controller.startedActivities.get() == 0) {
            NotificationHelper.showNotification(controller, list);
        }
        return Result.success();
    }
}
