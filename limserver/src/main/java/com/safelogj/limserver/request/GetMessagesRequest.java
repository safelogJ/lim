package com.safelogj.limserver.request;


public record GetMessagesRequest(Long lastMessageId, String username, String password) {

    public boolean isValidRequest() {
        return lastMessageId != null
                && username != null && !username.isEmpty()
                && password != null && !password.isEmpty();
    }
}
