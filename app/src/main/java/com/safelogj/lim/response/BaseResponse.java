package com.safelogj.lim.response;

import com.safelogj.lim.model.Message;

import java.util.List;
import java.util.Map;

public record BaseResponse(String status, String message, Long messageId, Integer userId, String userName,
                           String displayName, Integer chatId, Long timestamp, String text, String type, String filePath, String fileName,
                           String queryUsername, List<Message> messages, String publicKey, String privateHash,
                           String interlocutorPublicKey, Map<Integer, Boolean> onlineStatuses, Integer udpRelayPort) {

    public static final String SUCCESS = "success";
    public static final String ERROR = "error";
}
