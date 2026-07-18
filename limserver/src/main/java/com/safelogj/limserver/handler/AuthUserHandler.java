package com.safelogj.limserver.handler;

import com.safelogj.limserver.LimController;
import com.safelogj.limserver.model.User;
import com.safelogj.limserver.request.AuthRequest;
import com.safelogj.limserver.response.BaseResponse;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

public class AuthUserHandler extends BaseHandler {

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        BaseResponse response = new BaseResponse();
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendMethodError(exchange, response);
            return;
        }

        try (InputStreamReader reader = new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8)) {
            AuthRequest req = gson.fromJson(reader, AuthRequest.class);
            if (req == null || !req.isValidRequest()) {
                sendFieldMissingError(exchange, response);
                return;
            }

            String username = req.username().toLowerCase(Locale.US).trim();
            User user = LimController.dbManager.authenticateUser(username, req.password());
            if (user != null) {
                LimController.log.info("User '{}' authenticated successfully", username);
                response.userId = user.id;
                response.displayName = user.displayName;
                response.message = "user authenticated successfully";
                sendSuccess(exchange, response);
            } else {
                LimController.log.info("User login '{}' password '{}' nulleded", username, req.password());
                sendUnauthorizedError(exchange, response);
            }

        } catch (Exception e) {
            LimController.log.error("AuthUserHandler error: ", e);
            sendCatchError(exchange, response, e);
        }
    }
}
