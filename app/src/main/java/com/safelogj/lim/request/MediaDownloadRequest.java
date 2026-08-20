package com.safelogj.lim.request;

public record MediaDownloadRequest(String username, String password, Integer chatId, String filePath, Boolean isConfirmed) {

}
