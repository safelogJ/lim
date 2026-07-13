package com.safelogj.lim.request;


public record GetMessagesRequest(String username, String password, Long lastMessageId) {

    public boolean isValidRequest() {
        return lastMessageId != null
                && username != null && !username.isEmpty()
                && password != null && !password.isEmpty();
    }
}
