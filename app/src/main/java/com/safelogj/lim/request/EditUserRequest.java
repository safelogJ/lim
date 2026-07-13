package com.safelogj.lim.request;

public record EditUserRequest(String username, String password, String newDisplayName,
                              String newPassword) {

}
