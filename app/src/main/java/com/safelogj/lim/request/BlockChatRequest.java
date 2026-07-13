package com.safelogj.lim.request;

public record BlockChatRequest (String username, String password, Long chatId, Boolean isBlocked){
}
