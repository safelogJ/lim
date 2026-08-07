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
    public int color;
    public boolean isBlocked;
    public boolean isOnline;
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

    public Chat copy() {
        Chat copy = new Chat();
        copy.localId = this.localId;
        copy.id = this.id;
        copy.name = this.name;
        copy.isGroup = this.isGroup;
        copy.interlocutorId = this.interlocutorId;
        copy.lastMessage = this.lastMessage;
        copy.lastSendStatus = this.lastSendStatus;
        copy.isHidden = this.isHidden;
        copy.color = this.color;
        copy.isBlocked = this.isBlocked;
        copy.isOnline = this.isOnline;
        copy.hasNewMsg = this.hasNewMsg;
        copy.lastTimestamp = this.lastTimestamp;
        copy.lastTimestampFormatted = this.lastTimestampFormatted;
        return copy;
    }
}


