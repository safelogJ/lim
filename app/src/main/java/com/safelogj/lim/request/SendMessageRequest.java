package com.safelogj.lim.request;


public record SendMessageRequest(Long receiverId, String text, String type,
                                 String username, String password, String filePath, String fileName) {

    public boolean isValidRequest() {
        return receiverId != null && receiverId > 0
                && ((text != null && !text.isEmpty()) || (filePath != null && !filePath.isEmpty() && fileName != null && !fileName.isEmpty()))
                && type != null && !type.isEmpty()
                && username != null && !username.isEmpty()
                && password != null && !password.isEmpty();
    }

    public static boolean isValidHeaders(String login, String password, String fileName) {
        return login != null && !login.isEmpty()
                && password != null && !password.isEmpty()
                && fileName != null && !fileName.isEmpty();
    }
}
