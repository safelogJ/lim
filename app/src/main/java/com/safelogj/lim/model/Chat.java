package com.safelogj.lim.model;


public class Chat {

    public static final long INVALID_ID = -1;

    public long id;
    public String name;
    public String lastMessage;
    public long lastTimestamp;
    public boolean isHidden;
    public long interlocutorId;
    public boolean isGroup;
    public long lastSendStatus;
    public String lastTimestampFormatted;

    public static Chat createNewChatAction(String name, String lastMessage) {
        Chat action = new Chat();
        action.id = INVALID_ID;
        action.name = name;
        action.lastMessage = lastMessage;
        action.lastTimestamp = 0;
        return action;
    }
}


