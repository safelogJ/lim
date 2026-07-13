package com.safelogj.limserver.handler;

import com.safelogj.limserver.LimController;
import com.safelogj.limserver.model.User;
import com.safelogj.limserver.request.HideChatRequest;
import com.safelogj.limserver.response.BaseResponse;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

public class HideChatHandler extends BaseHandler {
    @Override
    public void handle(HttpExchange exchange) throws IOException {
        BaseResponse response = new BaseResponse();
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendMethodError(exchange, response);
            return;
        }
        // 2. Читаем входящий JSON от Андроида
        try (InputStreamReader reader = new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8)) {
            HideChatRequest req = gson.fromJson(reader, HideChatRequest.class);
            if (req == null || !req.isValidRequest() || !isUsernameValid(req.username())) {
                sendFieldMissingError(exchange, response);
                return;
            }
            User user = LimController.dbManager.authenticateUser(req.username(), req.password());
            if (user == null) {
                sendUnauthorizedError(exchange, response);
                return;
            }

            if (LimController.dbManager.hideChat(req.chatId(), user.id)) {
                response.message = "chat hidden successfully: " + req.chatId();
                sendSuccess(exchange, response);
            } else {
                sendInternalServerError(exchange, response);
            }

        } catch (Exception e) {
            LimController.log.error("SendMessageHandler error: ", e);
            sendCatchError(exchange, response, e);
        }
    }
}

