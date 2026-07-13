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
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
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
            if (LimController.dbManager.authenticateUser(req.username(), req.password()) == null) {
                sendUnauthorizedError(exchange, response);
                return;
            }
            // 3. Ищем пользователя в базе данных
            User user = LimController.dbManager.searchUserByUsername(req.queryUsername().trim());
            if (user != null) {
                response.userId = user.id;
                response.displayName = user.displayName;
                response.message = "user found success: " + user.displayName;
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
