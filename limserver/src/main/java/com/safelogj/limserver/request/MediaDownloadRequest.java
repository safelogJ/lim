package com.safelogj.limserver.request;

public record MediaDownloadRequest(String username, String password, Integer chatId, String filePath, Boolean isConfirmed) {

    public boolean isValidRequest() {
        return username != null && !username.isEmpty()
                && password != null && !password.isEmpty()
                && chatId != null && chatId > 0
                && filePath != null && !filePath.isEmpty();
    }
}
