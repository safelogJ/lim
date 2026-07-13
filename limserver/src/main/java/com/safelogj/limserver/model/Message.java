package com.safelogj.limserver.model;


public class Message {

    public static final int INVALID_MSG_ID = -1;

    public long id;
    public long chatId;
    public long senderId;
    public String text;
    public String type;      // "TEXT", "FILE", "IMAGE", "SYSTEM"
    public long timestamp;
    public String filePath;  // Для файлов
    public String fileName;
}
