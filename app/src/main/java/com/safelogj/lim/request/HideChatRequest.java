package com.safelogj.lim.request;

public record HideChatRequest (String username, String password, Long chatId) {
}
