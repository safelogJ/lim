package com.safelogj.limserver.request;

import java.util.List;

public record ScriptRequest(String displayName, String username, String password, String text, List<String> loginList) {

    public boolean isValidRequest() {
        return displayName != null && !displayName.isEmpty()
                && username != null && !username.isEmpty()
                && password != null && !password.isEmpty()
                && text != null && !text.isEmpty()
                && loginList != null && !loginList.isEmpty();
    }
}
