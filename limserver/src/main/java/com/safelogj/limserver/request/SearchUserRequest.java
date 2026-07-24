package com.safelogj.limserver.request;

import org.jetbrains.annotations.Nullable;

public record SearchUserRequest(String username, String password, @Nullable String queryUsername, @Nullable Long chatId) {

    public boolean isValidRequest() {
        return username != null && !username.isEmpty()
                && password != null && !password.isEmpty()
                && ((queryUsername != null && !queryUsername.isEmpty()) || (chatId != null && chatId > 0));
    }
}
