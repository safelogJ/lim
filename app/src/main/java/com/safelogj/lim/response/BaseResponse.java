package com.safelogj.lim.response;

import com.safelogj.lim.model.Chat;
import com.safelogj.lim.model.Message;

import java.util.List;

public record BaseResponse(String status, String message, Long messageId, Long userId,
                           String displayName, Long chatId, Long timestamp,
                           String text, String type, String filePath, String fileName,
                           String queryUsername, List<Message> messages, List<Chat> chats) {

    public static final String SUCCESS = "success";
    public static final String ERROR = "error";

    public boolean isValidRegResponse() {
        return userId != null && displayName != null;
    }

}
