package com.safelogj.limserver.request;

public record SearchChatRequest (String username, String password, Integer queryUserId) {

    public boolean isValidRequest() {
        return username != null && !username.isEmpty()
                && password != null && !password.isEmpty()
                && queryUserId != null;
    }
}
