package com.safelogj.lim.model;

import androidx.annotation.NonNull;

import com.safelogj.lim.NetworkService;

public class Message {

    public static final int TYPE_SYSTEM = 0;
    public static final int TYPE_OUTGOING = 1;
    public static final int TYPE_INCOMING = 2;
    public static final int STATUS_SENDING_OR_RECEIVE = 1;
    public static final int STATUS_SENT = 2;
    public static final int STATUS_WAITING = 3;
    public static final int SYSTEM_SENDER_ID = -1;


    public long id;
    public long localId;
    public long chatId;
    public String chatName;
    public long localChatId;
    public long senderId;
    public long receiverId;
    public String text;
    @NonNull
    public String type = NetworkService.TEXT;      // "TEXT", "FILE", "IMAGE", "SYSTEM"
    public long timestamp;
    public String filePath;  // Для файлов
    public String fileName;
    public String formattedTime;
    public long sendStatus = STATUS_SENDING_OR_RECEIVE;


    public int getMessageTypeByUserId(long userId) {
        if (senderId == SYSTEM_SENDER_ID) return TYPE_SYSTEM;
        return (senderId == userId) ? TYPE_OUTGOING : TYPE_INCOMING;
    }
}
