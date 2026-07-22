package com.safelogj.lim.request;

public record MediaDownloadRequest(String username, String password, Long chatId, String filePath) {

}
