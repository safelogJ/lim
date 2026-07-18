package com.safelogj.limserver.request;


public record SendMessageRequest(String username, String password, Long userId, Long chatId, String text, String type,
                                 String filePath, String fileName, String chatName) {

    public boolean isValidRequest() {
        return userId != null && userId > 0
                && chatId != null && chatId > 0
                && text != null && !text.isEmpty()
                && type != null && !type.isEmpty()
                && username != null && !username.isEmpty()
                && password != null && !password.isEmpty()
                && chatName != null && !chatName.isEmpty();
    }

    public static boolean isValidHeaders(String login, String password, String fileName) {
        return login != null && !login.isEmpty()
                && password != null && !password.isEmpty()
                && fileName != null && !fileName.isEmpty();
    }
}
