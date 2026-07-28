package com.safelogj.limserver.handler;

import com.safelogj.limserver.LimController;
import com.safelogj.limserver.model.User;
import com.safelogj.limserver.request.SearchUserRequest;
import com.safelogj.limserver.response.BaseResponse;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

public class SearchUserHandler extends BaseHandler {

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        BaseResponse response = new BaseResponse();
        if (!POST.equalsIgnoreCase(exchange.getRequestMethod())) {
            sendMethodError(exchange, response);
            return;
        }

        try (InputStreamReader reader = new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8)) {
            SearchUserRequest req = gson.fromJson(reader, SearchUserRequest.class);
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
            User interlocutor = req.queryUsername() == null ? LimController.dbManager.searchUserByChatId(user.id, req.chatId()) :
                    LimController.dbManager.searchUserByUsername(req.queryUsername().trim());
            if (interlocutor != null) {
                response.userId = interlocutor.id;
                response.userName = interlocutor.username;
                response.displayName = interlocutor.displayName;
                response.publicKey = interlocutor.publicKey;
                response.message = "user found success: " + interlocutor.displayName + " ключ " + interlocutor.publicKey;
                sendSuccess(exchange, response);
            } else {
                sendUserNotFoundError(exchange, response);
            }

        } catch (Exception e) {
            LimController.log.error("SearchUserHandler error: ", e);
            sendCatchError(exchange, response, e);
        }
    }
}
