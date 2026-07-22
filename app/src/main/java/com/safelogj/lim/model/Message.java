package com.safelogj.lim.model;

import androidx.annotation.NonNull;

public class Message {

    public static final String TYPE_TEXT = "TEXT";
    public static final String TYPE_IMAGE = "IMAGE";
    public static final String TYPE_FILE = "FILE";
    public static final int TYPE_SYSTEM = 0;
    public static final int TYPE_OUTGOING = 1;
    public static final int TYPE_INCOMING = 2;
    public static final int STATUS_SENDING_OR_RECEIVE = 1;
    public static final int STATUS_SENT = 2;
    public static final int STATUS_WAITING = 3;
    public static final int SYSTEM_SENDER_ID = -1;
    public static final int MEDIA_STATUS_NONE = 0;      // Обычное текстовое сообщение
    public static final int MEDIA_STATUS_PENDING = 1;   // Файл на сервере, нужно скачать
    public static final int MEDIA_STATUS_DOWNLOADED = 2; // Файл скачан и доступен локально
    public static final int MEDIA_STATUS_ERROR = 3;  // Критическая ошибка (404/403), больше не качаем


    public long id;
    public long localId;
    public long chatId;
    public String chatName;
    public long localChatId;
    public long senderId;
    public long receiverId;
    public String text;
    @NonNull
    public String type = TYPE_TEXT;      // "TEXT", "FILE", "IMAGE", "SYSTEM"
    public long timestamp;
    public String filePath;  // Для файлов
    public String fileName;
    public String formattedTime;
    public long sendStatus = STATUS_SENDING_OR_RECEIVE;


    public int getMessageTypeByUserId(long userId) {
        if (senderId == SYSTEM_SENDER_ID) return TYPE_SYSTEM;
        return (senderId == userId) ? TYPE_OUTGOING : TYPE_INCOMING;
    }

    public boolean isLocalFile() {
        return filePath != null && !filePath.isEmpty() && (filePath.startsWith("content://") || filePath.startsWith("file://"));
    }

//    public boolean isDownloadFile() {
//        return filePath != null && (!filePath.startsWith("content://") && !filePath.startsWith("file://"));
//    }
}
