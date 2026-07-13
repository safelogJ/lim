package com.safelogj.limserver.request;

public record BlockChatRequest (String username, String password, Long chatId, Boolean isBlocked){

    public boolean isValidRequest() {
        return chatId != null
                && username != null && !username.isEmpty()
                && password != null && !password.isEmpty();
    }
}
