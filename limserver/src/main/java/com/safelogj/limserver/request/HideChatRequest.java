package com.safelogj.limserver.request;

public record HideChatRequest (Long chatId, String username, String password) {

    public boolean isValidRequest() {
        return chatId != null
                && username != null && !username.isEmpty()
                && password != null && !password.isEmpty();
    }
}
