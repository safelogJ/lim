package com.safelogj.limserver.request;

public record MediaDownloadRequest(String username, String password, String filePath) {

    public boolean isValidRequest() {
        return username != null && !username.isEmpty()
                && password != null && !password.isEmpty()
                && filePath != null && !filePath.isEmpty();
    }
}
