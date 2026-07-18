package com.safelogj.lim.model;


public class Chat {

    public static final long INVALID_ID = -1;


    public long localId;
    public long id;
    public String name;
    public boolean isGroup;
    public long interlocutorId;
    public String lastMessage;
    public long lastSendStatus;
    public boolean isHidden;
    public boolean isBlocked;
    public boolean hasNewMsg;
    public long lastTimestamp;
    public String lastTimestampFormatted;

    public static Chat createNewChatAction(String name, String lastMessage) {
        Chat action = new Chat();
        action.id = INVALID_ID;
        action.localId = INVALID_ID;
        action.name = name;
        action.lastMessage = lastMessage;
        action.lastTimestamp = 0;
        return action;
    }
}


