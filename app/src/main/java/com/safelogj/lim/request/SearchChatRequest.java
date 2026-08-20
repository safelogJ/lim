package com.safelogj.lim.request;

public record SearchChatRequest (String username, String password, Integer queryUserId) {
}
