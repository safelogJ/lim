package com.safelogj.limserver.request;

public record RegisterRequest(String username, String password, String displayName, String publicKey, String privateHash) {

    public boolean isValidRequest() {
        return username != null && !username.isEmpty()
                && password != null && !password.isEmpty()
                && displayName != null && !displayName.isEmpty()
                && publicKey != null && !publicKey.isEmpty()
                && privateHash != null && !privateHash.isEmpty();
    }
}
