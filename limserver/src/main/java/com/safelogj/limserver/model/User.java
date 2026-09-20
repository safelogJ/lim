package com.safelogj.limserver.model;

public class User {

    public static final String BOT = "bot";
    public int id;
    public String username;
    public String displayName;
    public String publicKey;
    public String privateHash;
    public boolean isDeleted;

    public boolean isBot() {
        return BOT.equals(publicKey) && BOT.equals(privateHash);
    }
}
