package com.safelogj.limserver.handler;

import com.safelogj.limserver.LimController;
import com.safelogj.limserver.model.Message;
import com.safelogj.limserver.model.User;
import com.safelogj.limserver.request.GetMessagesRequest;
import com.safelogj.limserver.response.BaseResponse;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

public class GetMessagesHandler extends BaseHandler {
    @Override
    public void handle(HttpExchange exchange) throws IOException {
        BaseResponse response = new BaseResponse();
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendMethodError(exchange, response);
            return;
        }

        try (InputStreamReader reader = new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8)) {
            GetMessagesRequest req = gson.fromJson(reader, GetMessagesRequest.class);
            if (req == null || !req.isValidRequest() || !isUsernameValid(req.username())) {
                sendFieldMissingError(exchange, response);
                return;
            }
            // 2. Встроенная авторизация на лету
            User user = LimController.dbManager.authenticateUser(req.username(), req.password());
            if (user == null) {
                sendUnauthorizedError(exchange, response);
                return;
            }
            // 3. Ищем пользователя в базе данных
            List<Message> messages = LimController.dbManager.getNewMessages(user.id, req.lastMessageId());
            if (messages != null) {
                response.userId = user.id;
                response.displayName = user.displayName;
                response.messages = messages;
                sendSuccess(exchange, response);
            } else {
                sendUserNotFoundError(exchange, response);
            }

        } catch (Exception e) {
            LimController.log.error("GetMessagesHandler error: ", e);
            sendCatchError(exchange, response, e);
        }
    }
}
