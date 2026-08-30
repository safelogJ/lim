package com.safelogj.lim.model;

public class Caller {

    public static final long END_AUTO = 3_000;
    public static final long MUTE_END = 4_000;
    public static final long MUTE_ERROR = 10_000;
    public static final long MUTE_START = 3_100;
    public static final long MUTE_BAN = 120_000;

    private final int userId;
    private int chatId;
    private String chatName;
    private int color;
    private boolean isBlocked;
    private String publicKey;

    public Caller(String chatName, int userId, int chatId) {
        this.chatName = chatName;
        this.userId = userId;
        this.chatId = chatId;
    }

    public Caller(int userId, String chatName, String publicKey) {
        this.userId = userId;
        this.chatName = chatName;
        this.publicKey = publicKey;
    }

    public Caller(int chatId, int userId, String name, int color, boolean isBlocked) {
        this.chatId = chatId;
        this.userId = userId;
        this.chatName = name;
        this.color = color;
        this.isBlocked = isBlocked;
    }

    public Caller (Caller old) {
        this.userId = old.userId;
        this.chatId = old.chatId;
        this.chatName = old.chatName;
        this.color = old.color;
        this.isBlocked = old.isBlocked;
        this.publicKey = old.publicKey;
    }

    public int getUserId() {
        return userId;
    }

    public boolean isBlocked() {
        return isBlocked;
    }

    public void setBlocked(boolean blocked) {
        isBlocked = blocked;
    }

    public String getPublicKey() {
        return publicKey;
    }

    public void setPublicKey(String publicKey) {
        this.publicKey = publicKey;
    }

    public int getColor() {
        return color;
    }

    public void setColor(int color) {
        this.color = color;
    }

    public String getChatName() {
        return chatName;
    }

    public void setChatName(String chatName) {
        this.chatName = chatName;
    }

    public int getChatId() {
        return chatId;
    }

    public void setChatId(int chatId) {
        this.chatId = chatId;
    }
}
