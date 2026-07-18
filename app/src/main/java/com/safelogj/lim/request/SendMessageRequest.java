package com.safelogj.lim.request;


public record SendMessageRequest(String username, String password, Long userId, Long chatId, String text, String type,
                                 String filePath, String fileName, String chatName) {

}
