package com.safelogj.limserver.request;

public record HideChatRequest (String username, String password, Long chatId) {

    public boolean isValidRequest() {
        return chatId != null
                && username != null && !username.isEmpty()
                && password != null && !password.isEmpty();
    }
}
