package com.safelogj.lim.request;

public record AuthRequest(String username, String password) {

    public boolean isValidRequest() {
        return username != null && !username.isEmpty()
                && password != null && !password.isEmpty();
    }
}
