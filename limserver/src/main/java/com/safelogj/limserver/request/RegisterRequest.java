package com.safelogj.limserver.request;

public record RegisterRequest(String username, String password, String displayName) {

    public boolean isValidRequest() {
        return username != null && !username.isEmpty()
                && password != null && !password.isEmpty()
                && displayName != null && !displayName.isEmpty();
    }
}
