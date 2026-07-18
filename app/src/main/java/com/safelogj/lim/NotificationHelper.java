package com.safelogj.lim;

import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import com.safelogj.lim.model.Chat;

import java.util.List;

public class NotificationHelper {

    private NotificationHelper() {  }
    public static final int NOTIFICATION_ID = 1;
    public static final String EXTRA_CHAT_ID = "extra_chat_id";
    public static final String EXTRA_CHAT_LOCAL_ID = "extra_chat_local_id";
    public static final String EXTRA_CHAT_NAME = "extra_chat_name";
    public static final String EXTRA_OPEN_CHAT_LIST = "extra_open_chat_list";
    public static final int MULTI_CHAT_ID = -2;

    public static void showNotification(Context context, List<Chat> unreadChats) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null) return;

        if (!manager.areNotificationsEnabled()) {
            Log.w(AppController.LOG_TAG, "Уведомления запрещены пользователем в настройках системы!");
            return;
        }

        Intent intent = new Intent(context, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);

        String title;
        String text;
        if (unreadChats.size() == 1) {
            Chat chat = unreadChats.get(0);
            title = "Новое сообщение";
            text = "От: " + chat.name;
            intent.putExtra(EXTRA_CHAT_ID, chat.id);
            intent.putExtra(EXTRA_CHAT_LOCAL_ID, chat.localId);
            intent.putExtra(EXTRA_CHAT_NAME, chat.name);
        } else {
            title = "Новые сообщения";
            text = "У вас есть непрочитанные сообщения (" + unreadChats.size() + ")";
            intent.putExtra(EXTRA_OPEN_CHAT_LIST, true);
        }

        PendingIntent pendingIntent = PendingIntent.getActivity(
                context, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, AppController.NOTIFICATION_CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_name)
                .setContentTitle(title)
                .setContentText(text)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setContentIntent(pendingIntent)
                .setOnlyAlertOnce(true)
                .setVibrate(new long[]{0L})
                .setSilent(false)
                .setAutoCancel(true)
                .addExtras(new Bundle());

        if (unreadChats.size() == 1) {
            builder.getExtras().putLong(EXTRA_CHAT_ID, unreadChats.get(0).id);
        } else {
            builder.getExtras().putLong(EXTRA_CHAT_ID, MULTI_CHAT_ID);
        }

        manager.notify(NOTIFICATION_ID, builder.build());
    }

    public static void clearNotification(Context context) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.cancel(NOTIFICATION_ID);
        }
    }
}
