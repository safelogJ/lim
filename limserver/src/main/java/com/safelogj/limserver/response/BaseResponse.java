package com.safelogj.limserver.response;

import com.safelogj.limserver.model.Message;

import java.util.List;
import java.util.Map;

public class BaseResponse {

    public static final String SUCCESS = "success";
    public static final String ERROR = "error";

    public String status;
    public String message;
    public Long messageId;
    public Integer userId;
    public String userName;
    public String displayName;
    public Integer chatId;
    public Long timestamp;
    public String text;
    public String type;
    public String filePath;
    public String fileName;
    public String publicKey;
    public String interlocutorPublicKey;
    public String privateHash;
    public List<Message> messages;
    public Map<Integer, Boolean> onlineStatuses;
    public Integer udpRelayPort;
}
