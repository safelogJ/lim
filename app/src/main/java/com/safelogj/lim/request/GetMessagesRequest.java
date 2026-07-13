package com.safelogj.lim.request;


public record GetMessagesRequest(Integer lastMessageId, String username,
                                 String password) {

    public boolean isValidRequest() {
        return lastMessageId != null
                && username != null && !username.isEmpty()
                && password != null && !password.isEmpty();
    }
}
