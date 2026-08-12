package com.safelogj.limserver.handler;

import com.safelogj.limserver.LimController;
import com.safelogj.limserver.model.Message;
import com.safelogj.limserver.model.User;
import com.safelogj.limserver.request.SendMessageRequest;
import com.safelogj.limserver.response.BaseResponse;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

public class SendMessageHandler extends BaseHandler {

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        BaseResponse response = new BaseResponse();
        if (!POST.equalsIgnoreCase(exchange.getRequestMethod())) {
            sendMethodError(exchange, response);
            LimController.log.error("sendMethodError ");
            return;
        }
        try (InputStreamReader reader = new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8)) {
            SendMessageRequest req = gson.fromJson(reader, SendMessageRequest.class);
            if (req == null || !req.isValidRequest() || !isUsernameValid(req.username())) {
                LimController.log.error("sendFieldMissingError ");
                sendFieldMissingError(exchange, response);
                return;
            }
            User user = LimController.dbManager.authenticateUser(req.username(), req.password());
            if (user == null || !LimController.dbManager.isMemberOfChat(user.id, req.chatId())) {
                LimController.log.error("sendUnauthorizedError ");
                sendUnauthorizedError(exchange, response);
                return;
            }
            long timestamp = System.currentTimeMillis();
            long messageId = LimController.dbManager.saveMessage(req, user.id, timestamp);
            if (messageId != Message.INVALID_MSG_ID) {
                response.messageId = messageId;
                response.timestamp = timestamp;
                response.status = BaseResponse.SUCCESS;
                if (LimController.dbManager.isLiveChat(user.id, req.chatId())) {
                    response.message = "message saved on server";
                    sendResponse(exchange, 200, response);
                } else {
                    response.message = "CH_ERR_NO_INTERLOCUTOR";
                    sendResponse(exchange, 203, response);
                }
            } else {
                sendInternalServerError(exchange, response);
                LimController.log.error("sendInternalServerError ");
            }

        } catch (Exception e) {
            LimController.log.error("SendMessageHandler error: ", e);
            sendCatchError(exchange, response, e);
        }
    }
}
