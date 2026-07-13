package com.safelogj.limserver.response;

import com.safelogj.limserver.model.Chat;
import com.safelogj.limserver.model.Message;

import java.util.List;

public class BaseResponse {

    public static final String SUCCESS = "success";
    public static final String ERROR = "error";

    public String status;
    public String message;
    public Long messageId;
    public Long userId;
    public String displayName;
    public Long chatId;
    public Long timestamp;
    public String text;
    public String type;
    public String filePath;
    public String fileName;
    public List<Message> messages;
    public List<Chat> chats;
}
