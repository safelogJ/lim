package com.safelogj.lim;

import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import com.safelogj.lim.model.Caller;
import com.safelogj.lim.model.Chat;

import java.util.List;

public class NotificationHelper {

    private NotificationHelper() {  }
    public static final int MSG_NOTIFICATION_ID = 1;
    public static final int CALL_NOTIFICATION_ID = 2;
    public static final String EXTRA_CHAT_ID = "extra_chat_id";
    public static final String EXTRA_CHAT_LOCAL_ID = "extra_chat_local_id";
    public static final String EXTRA_CHAT_NAME = "extra_chat_name";
    public static final String EXTRA_OPEN_CHAT_LIST = "extra_open_chat_list";
    public static final String EXTRA_CALL_CHAT = "extra_call_chat";
    private static final String NOTIFICATIONS_DISABLED_MESSAGE = "Notifications are disabled by the user in the system settings!";

    public static void showMsgNotification(Context context, List<Chat> unreadChats) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null) return;

        if (!manager.areNotificationsEnabled()) {
            Log.w(AppController.LOG_TAG, NOTIFICATIONS_DISABLED_MESSAGE);
            return;
        }

        Intent intent = new Intent(context, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);

        String title;
        String text;
        Bundle extras = new Bundle();
        if (unreadChats.size() == 1) {
            Chat chat = unreadChats.get(0);
            title = context.getString(R.string.new_msg);
            text = context.getString(R.string.new_msg_from) + " " + chat.name;
            intent.putExtra(EXTRA_CHAT_ID, chat.id);
            intent.putExtra(EXTRA_CHAT_LOCAL_ID, chat.localId);
            intent.putExtra(EXTRA_CHAT_NAME, chat.name);
            extras.putInt(EXTRA_CHAT_ID, chat.id);
        } else {
            title = context.getString(R.string.new_msgs);
            text = context.getString(R.string.unread_chats) + " (" + unreadChats.size() + ")";
            intent.putExtra(EXTRA_OPEN_CHAT_LIST, true);
        }

        long maxTimestamp = 0;
        for (Chat c : unreadChats) {
            if (c.lastTimestamp > maxTimestamp) maxTimestamp = c.lastTimestamp;
        }

        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, AppController.NOTIFICATION_CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_name)
                .setContentTitle(title)
                .setContentText(text)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setContentIntent(PendingIntent.getActivity(context, 0, intent,PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE))
                .setVibrate(new long[]{0L})
                .setWhen(maxTimestamp)
                .setShowWhen(maxTimestamp > 0)
                .setNumber(unreadChats.size())
                .setExtras(extras);
        manager.notify(MSG_NOTIFICATION_ID, builder.build());
    }

    public static void showCallNotification(AppController appController, int interlocutorId, long timestamp) {
        NotificationManager manager = appController.getSystemService(NotificationManager.class);
        if (manager == null) return;

        if (!manager.areNotificationsEnabled()) {
            Log.w(AppController.LOG_TAG, NOTIFICATIONS_DISABLED_MESSAGE);
            return;
        }

        NotificationCompat.Builder builder = getCallNotificationBuilder(appController, interlocutorId, true)
                .setContentTitle(appController.getString(R.string.incoming_call))
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setOngoing(true)
                .setWhen(timestamp)
                .setShowWhen(timestamp > 0)
                .setAutoCancel(false);

        manager.notify(CALL_NOTIFICATION_ID, builder.build());
    }

    public static void showMissedCallNotification(AppController appController, int interlocutorId, long timestamp) {
        NotificationManager manager = appController.getSystemService(NotificationManager.class);
        if (manager == null) return;

        if (!manager.areNotificationsEnabled()) {
            Log.w(AppController.LOG_TAG, NOTIFICATIONS_DISABLED_MESSAGE);
            return;
        }

        NotificationCompat.Builder builder = getCallNotificationBuilder(appController, interlocutorId, false)
                .setContentTitle(appController.getString(R.string.missing_call))
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setOngoing(false)
                .setWhen(timestamp)
                .setShowWhen(timestamp > 0)
                .setAutoCancel(true);
        manager.notify(CALL_NOTIFICATION_ID, builder.build());
    }

    public static void clearNotification(Context context, int notificationId) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.cancel(notificationId);
        }
    }

    private static NotificationCompat.Builder getCallNotificationBuilder(AppController appController, int interlocutorId, boolean isCall) {
        Intent intent = new Intent(appController, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        intent.putExtra(EXTRA_CALL_CHAT, true);
        PendingIntent callPendingIntent = PendingIntent.getActivity(appController, 1, intent,PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Caller caller = appController.getDbHelper().getCaller(interlocutorId);
        NotificationCompat.Builder builder = new NotificationCompat.Builder(appController, AppController.CALL_CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_name)
                .setContentText(caller == null ? AppController.EMPTY_STRING : caller.getChatName())
                .setContentIntent(callPendingIntent)
                .setSound(null)
                .setVibrate(new long[]{0L});
        if (isCall) {
            builder.setFullScreenIntent(callPendingIntent, true);
        }
        return builder;
    }
}
