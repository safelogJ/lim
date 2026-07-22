package com.safelogj.limserver.request;

public record MediaDownloadRequest(String username, String password, Long chatId, String filePath) {

    public boolean isValidRequest() {
        return username != null && !username.isEmpty()
                && password != null && !password.isEmpty()
                && chatId != null && chatId > 0
                && filePath != null && !filePath.isEmpty();
    }
}
