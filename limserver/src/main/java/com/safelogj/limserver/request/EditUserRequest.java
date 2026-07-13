package com.safelogj.limserver.request;

public record EditUserRequest(String username, String password, String newDisplayName,
                              String newPassword) {

    public boolean isValidRequest() {
        return username != null && !username.isEmpty()
                && password != null && !password.isEmpty();
    }
}
