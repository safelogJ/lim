package com.safelogj.limserver.handler;

import com.safelogj.limserver.LimController;
import com.safelogj.limserver.model.User;
import com.safelogj.limserver.request.RegisterRequest;
import com.safelogj.limserver.response.BaseResponse;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

public class RegisterUserHandler extends BaseHandler {
    // Регулярное выражение: строго маленькая латиница и цифры, от 3 до 20 символов
    private static final Pattern USERNAME_PATTERN = Pattern.compile("^[a-z0-9]{3,20}$");

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        BaseResponse response = new BaseResponse();
        if (!POST.equalsIgnoreCase(exchange.getRequestMethod())) {
            sendMethodError(exchange, response);
            return;
        }

        try (InputStreamReader reader = new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8)) {
            RegisterRequest req = gson.fromJson(reader, RegisterRequest.class);
            if (req == null || !req.isValidRequest()) {
                sendFieldMissingError(exchange, response);
                return;
            }

            String username = req.username().trim();
            if (!USERNAME_PATTERN.matcher(username).matches()) {
                response.status = BaseResponse.ERROR;
                response.message = "invalid username format (3-20 chars, lowercase Latin and digits)";
                sendResponse(exchange, 400, response);
                return;
            }

            if (req.password().trim().length() != 64) {
                response.status = BaseResponse.ERROR;
                response.message = "invalid password hash format (SHA-256)";
                sendResponse(exchange, 400, response);
                return;
            }

            User user = LimController.dbManager.authenticateUser(username, req.password());
            if (user != null) {
                response.userId = user.id;
                response.displayName = user.displayName;
                response.publicKey = user.publicKey;
                response.privateHash = user.privateHash;
                response.udpRelayPort = LimController.getUdpRelayPort();
                response.message = "login successful: " + user.displayName;
                sendSuccess(exchange, response);
                LimController.log.info("User '{}' login successfully", user.displayName);
                return;
            }

            if (!isDisplayNameValid(req.displayName())) {
                response.status = BaseResponse.ERROR;
                response.message = "invalid display name format (3-20 chars)";
                sendResponse(exchange, 400, response);
                return;
            }
            user = LimController.dbManager.registerUser(username, req.password(), req.displayName(), req.publicKey(), req.privateHash());
            if (user != null) {
                response.userId = user.id;
                response.displayName = user.displayName;
                response.publicKey = req.publicKey();
                response.udpRelayPort = LimController.getUdpRelayPort();
                response.message = "registration successful: " + user.displayName;
                sendSuccess(exchange, response);
                LimController.log.info("User '{}' registered successfully", user.displayName);
            } else {
                response.status = BaseResponse.ERROR;
                response.message = "this username is already taken";
                sendResponse(exchange, 409, response);
            }

        } catch (Exception e) {
            LimController.log.error("RegisterUserHandler error: ", e);
            sendCatchError(exchange, response, e);
        }
    }

}
