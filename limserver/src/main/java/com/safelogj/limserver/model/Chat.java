package com.safelogj.limserver.model;

public class Chat {

    public long id;
    public String name;
    public String lastMessage;
    public long lastTimestamp;
    public boolean isGroup;
    public boolean isHidden;
    public long interlocutorId;
    public long createdAt;
}
