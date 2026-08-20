package com.safelogj.limserver.model;

public class Chat {

    public int id;
    public String name;
    public String lastMessage;
    public long lastTimestamp;
    public boolean isGroup;
    public boolean isHidden;
    public boolean isBlocked;
    public int interlocutorId;
    public long createdAt;
}
