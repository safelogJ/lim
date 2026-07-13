package com.safelogj.limserver.request;

public record SearchUserRequest(String username, String password, String queryUsername) {

    public boolean isValidRequest() {
        return username != null && !username.isEmpty()
                && password != null && !password.isEmpty()
                && queryUsername != null && !queryUsername.isEmpty();
    }
}
