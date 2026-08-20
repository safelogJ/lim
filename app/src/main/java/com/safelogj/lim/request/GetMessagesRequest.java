package com.safelogj.lim.request;

import java.util.List;

public record GetMessagesRequest(String username, String password, Long lastMessageId, List<Integer> interlocutorIds) {
}
