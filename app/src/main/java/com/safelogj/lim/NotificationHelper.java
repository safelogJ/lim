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

    public static void showNotification(Context context, List<Chat> unreadChats) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null) return;

        if (!manager.areNotificationsEnabled()) {
            Log.w(AppController.LOG_TAG, "Notifications are disabled by the user in the system settings!");
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
            extras.putLong(EXTRA_CHAT_ID, chat.id);

            intent.removeExtra(EXTRA_OPEN_CHAT_LIST);
        } else {
            title = context.getString(R.string.new_msgs);
            text = context.getString(R.string.unread_chats) + " (" + unreadChats.size() + ")";
            intent.putExtra(EXTRA_OPEN_CHAT_LIST, true);

            intent.removeExtra(EXTRA_CHAT_ID);
            intent.removeExtra(EXTRA_CHAT_LOCAL_ID);
            intent.removeExtra(EXTRA_CHAT_NAME);
        }

        PendingIntent pendingIntent = PendingIntent.getActivity(
                context, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        long maxTimestamp = 0;
        for (Chat c : unreadChats) {
            if (c.lastTimestamp > maxTimestamp) maxTimestamp = c.lastTimestamp;
        }

        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, AppController.NOTIFICATION_CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_name)
                .setContentTitle(title)
                .setContentText(text)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setContentIntent(pendingIntent)
                .setVibrate(new long[]{0L})
                .setSilent(false)
                .setAutoCancel(true)
                .setWhen(maxTimestamp)
                .setShowWhen(maxTimestamp > 0)
                .setNumber(unreadChats.size())
                .setExtras(extras);
        manager.notify(NOTIFICATION_ID, builder.build());
    }

    public static void clearNotification(Context context) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.cancel(NOTIFICATION_ID);
        }
    }
}
