package com.safelogj.limserver.request;


import java.util.List;

public record GetMessagesRequest(String username, String password, Long lastMessageId, List<Integer> interlocutorIds) {

    public boolean isValidRequest() {
        return lastMessageId != null
                && username != null && !username.isEmpty()
                && password != null && !password.isEmpty();
    }
}
