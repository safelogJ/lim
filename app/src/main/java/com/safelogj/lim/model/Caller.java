package com.safelogj.lim.model;

public class Caller {

    private final int userId;
    private final String publicKey;
    private boolean isBlocked;

    public Caller(int userId, String publicKey) {
        this.userId = userId;
        this.publicKey = publicKey;
    }

    public int getUserId() {
        return userId;
    }

    public boolean isBlocked() {
        return isBlocked;
    }

    public void setBlocked(boolean blocked) {
        isBlocked = blocked;
    }

    public String getPublicKey() {
        return publicKey;
    }
}
