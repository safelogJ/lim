package com.safelogj.lim.model;

import androidx.annotation.NonNull;

import java.util.LinkedHashMap;
import java.util.Map;

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
    public static final int MEDIA_STATUS_NO_MEDIA = 0;      // Обычное текстовое сообщение или файла для отправки уже нет
    public static final int MEDIA_STATUS_PENDING = 1;   // Файл на сервере, нужно скачать
    public static final int MEDIA_STATUS_DOWNLOADED = 2; // Файл скачан и доступен локально
    public static final int MEDIA_STATUS_ERROR = 3;  // Критическая ошибка (404/403), больше не качаем
    public static final int MEDIA_STATUS_LOADING = 4;  // качаем

    private static final Map<String, String> EMOJI_MAP = new LinkedHashMap<>();


    public long id;
    public long localId;
    public long chatId;
    public String chatName;
    public String interlocutorPublicKey;
    public long localChatId;
    public long senderId;
    public long receiverId;
    public String text;
    @NonNull
    public String type = TYPE_TEXT;      // "TEXT", "FILE", "IMAGE", "SYSTEM"
    public long timestamp;
    public String filePath;  // Для файлов
    public String fileName;
    public long sendStatus = STATUS_SENDING_OR_RECEIVE;
    public int mediaStatus = MEDIA_STATUS_NO_MEDIA;



    public int getMessageTypeByUserId(long userId) {
        if (senderId == SYSTEM_SENDER_ID) return TYPE_SYSTEM;
        return (senderId == userId) ? TYPE_OUTGOING : TYPE_INCOMING;
    }

    public boolean isLocalFile() {
        return filePath != null && !filePath.isEmpty() && (filePath.startsWith("content://") || filePath.startsWith("file://"));
    }

    public boolean hasServerPath() {
        return filePath != null && !filePath.isEmpty() && !filePath.startsWith("content://") && !filePath.startsWith("file://");
    }

    public static String replaceEmoji(String text) {
        for (Map.Entry<String, String> e : EMOJI_MAP.entrySet()) {
            text = text.replace(e.getKey(), e.getValue());
        }
        return text;
    }

    public Message copy() {
        Message copy = new Message();
        copy.id = this.id;
        copy.localId = this.localId;
        copy.chatId = this.chatId;
        copy.chatName = this.chatName;
        copy.interlocutorPublicKey = this.interlocutorPublicKey;
        copy.localChatId = this.localChatId;
        copy.senderId = this.senderId;
        copy.receiverId = this.receiverId;
        copy.text = this.text;
        copy.type = this.type;
        copy.timestamp = this.timestamp;
        copy.filePath = this.filePath;
        copy.fileName = this.fileName;
        copy.sendStatus = this.sendStatus;
        copy.mediaStatus = this.mediaStatus;
        return copy;
    }

    static {
        // Happy
        EMOJI_MAP.put(" :-)", " 🙂");
        EMOJI_MAP.put(" :)", " 🙂");
        EMOJI_MAP.put(" (:", " 🙂");
        EMOJI_MAP.put(" :-D", " 😃");
        EMOJI_MAP.put(" :D", " 😃");
        EMOJI_MAP.put(" =D", " 😃");
        EMOJI_MAP.put(" XD", " 😆");
        EMOJI_MAP.put(" xD", " 😆");
        EMOJI_MAP.put(" X-D", " 😆");
        EMOJI_MAP.put(" ^_^", " 😊");
        EMOJI_MAP.put(" ^^", " 😊");
        EMOJI_MAP.put(" :-(", " 🙁");
        EMOJI_MAP.put(" :(", " 🙁");
        EMOJI_MAP.put(" :'-(", " 😢");
        EMOJI_MAP.put(" :'(", " 😢");
        EMOJI_MAP.put(" T_T", " 😭");
        EMOJI_MAP.put(" ;-)", " 😉");
        EMOJI_MAP.put(" ;)", " 😉");
        EMOJI_MAP.put(" :-P", " 😛");
        EMOJI_MAP.put(" :P", " 😛");
        EMOJI_MAP.put(" :-p", " 😛");
        EMOJI_MAP.put(" :p", " 😛");
        EMOJI_MAP.put(" :-*", " 😘");
        EMOJI_MAP.put(" :*", " 😘");
        EMOJI_MAP.put(" :-O", " 😮");
        EMOJI_MAP.put(" :O", " 😮");
        EMOJI_MAP.put(" :-o", " 😮");
        EMOJI_MAP.put(" :o", " 😮");
        EMOJI_MAP.put(" :-|", " 😐");
        EMOJI_MAP.put(" :|", " 😐");
        EMOJI_MAP.put(" :-/", " 😕");
        EMOJI_MAP.put(" :/", " 😕");
        EMOJI_MAP.put(" :-\\", " 😕");
        EMOJI_MAP.put(" :\\", " 😕");
        EMOJI_MAP.put(" >:(", " 😠");
        EMOJI_MAP.put(" >:O", " 🤬");
        EMOJI_MAP.put(" B-)", " 😎");
        EMOJI_MAP.put(" 8-)", " 😎");
        EMOJI_MAP.put(" O:-)", " 😇");
        EMOJI_MAP.put(" O:)", " 😇");
        EMOJI_MAP.put(" }:)", " 😈");
        EMOJI_MAP.put(" :-X", " 🤐");
        EMOJI_MAP.put(" :X", " 🤐");
        EMOJI_MAP.put(" :-$", " 🙄");
        EMOJI_MAP.put(" |-)", " 😴");
        EMOJI_MAP.put(" 8-|", " 🤓");
        EMOJI_MAP.put(" B|", " 😎");
        EMOJI_MAP.put(" [:|]", " 🤖");
        EMOJI_MAP.put(" =^.^=", " 🐱");
        EMOJI_MAP.put(" \\o/", "🙌");
        EMOJI_MAP.put(" \\o", "👋");
    }
}
