package com.safelogj.limserver.handler;

import com.safelogj.limserver.LimController;
import com.safelogj.limserver.model.User;
import com.safelogj.limserver.request.EditUserRequest;
import com.safelogj.limserver.response.BaseResponse;
import com.sun.net.httpserver.HttpExchange;

import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

public class EditUserHandler extends BaseHandler {

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        BaseResponse response = new BaseResponse();

        if (!"POST".equalsIgnoreCase(method) && !"DELETE".equalsIgnoreCase(method)) {
            sendMethodError(exchange, response);
            return;
        }

        try (InputStreamReader reader = new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8)) {
            EditUserRequest req = gson.fromJson(reader, EditUserRequest.class);
            if (req == null || !req.isValidRequest() || !isUsernameValid(req.username())) {
                sendFieldMissingError(exchange, response);
                return;
            }
            User user = LimController.dbManager.authenticateUser(req.username(), req.password());
            if (user == null) {
                sendUnauthorizedError(exchange, response);
                return;
            }

            if ("DELETE".equalsIgnoreCase(method)) {
                deleteUser(exchange, user, response);
            } else {
                updateUser(exchange, req, user.id, response);
            }

        } catch (Exception e) {
            LimController.log.error("EditUserHandler error: ", e);
            sendCatchError(exchange, response, e);
        }
    }

    private void deleteUser(HttpExchange exchange, User user, BaseResponse response) throws IOException {
        if (LimController.dbManager.deleteUser(user.id)) {
            response.message = "the user has been deleted: " + user.displayName;
            sendSuccess(exchange, response);
        } else {
            sendInternalServerError(exchange, response);
        }
    }

    private void updateUser(HttpExchange exchange, @NotNull EditUserRequest req, Long userId, BaseResponse response) throws IOException {
        boolean isNewDisplayName = req.newDisplayName() != null && !req.newDisplayName().isEmpty();
        boolean isNewPassword = req.newPassword() != null && !req.newPassword().isEmpty();

        if ((isNewDisplayName || isNewPassword)
                && (LimController.dbManager.updateUser(userId, req, isNewDisplayName, isNewPassword)) != null) {
            response.message = "edit success: " + (isNewDisplayName ? req.newDisplayName() : LimController.EMPTY_STRING);
            sendSuccess(exchange, response);
            return;
        }
        sendFieldMissingError(exchange, response);
    }
}
