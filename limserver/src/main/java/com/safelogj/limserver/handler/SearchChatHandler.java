package com.safelogj.limserver.handler;

import com.safelogj.limserver.LimController;
import com.safelogj.limserver.model.Chat;
import com.safelogj.limserver.model.User;
import com.safelogj.limserver.request.SearchChatRequest;
import com.safelogj.limserver.response.BaseResponse;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

public class SearchChatHandler extends BaseHandler {

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        BaseResponse response = new BaseResponse();
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendMethodError(exchange, response);
            return;
        }
        try (InputStreamReader reader = new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8)) {
            SearchChatRequest req = gson.fromJson(reader, SearchChatRequest.class);
            if (req == null || !req.isValidRequest() || !isUsernameValid(req.username())) {
                sendFieldMissingError(exchange, response);
                return;
            }
            User user = LimController.dbManager.authenticateUser(req.username(), req.password());
            if (user == null) {
                sendUnauthorizedError(exchange, response);
                return;
            }

            Chat chat = LimController.dbManager.getOrCreatePersonalChat(user.id, req.queryUserId());
            if (chat != null) {
                response.chatId = chat.id;
                response.message = "chat found";
                sendSuccess(exchange, response);
            } else {
                sendChatNotFoundError(exchange, response);
            }

        } catch (Exception e) {
            LimController.log.error("SearchChatHandler error: ", e);
            sendCatchError(exchange, response, e);
        }
    }
}
