package com.safelogj.limserver.handler;

import com.google.gson.Gson;
import com.safelogj.limserver.LimController;
import com.safelogj.limserver.response.BaseResponse;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

public abstract class BaseHandler implements HttpHandler {

    private static final Pattern USERNAME_PATTERN = Pattern.compile("^[a-z0-9]{3,20}$");
    protected final Gson gson = new Gson();

    protected void sendResponse(HttpExchange exchange, int statusCode, Object responseObj) throws IOException {
        byte[] bytes = gson.toJson(responseObj).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    protected void sendSuccess(HttpExchange exchange, BaseResponse response) throws IOException {
        response.status = BaseResponse.SUCCESS;
        sendResponse(exchange, 200, response);
    }

    protected void sendMethodError(HttpExchange exchange, BaseResponse response) throws IOException {
        response.status = BaseResponse.ERROR;
        response.message = "method not allowed";
        sendResponse(exchange, 405, response);
    }

    protected void sendFieldMissingError(HttpExchange exchange, BaseResponse response) throws IOException {
        response.status = BaseResponse.ERROR;
        response.message = "missing required fields";
        sendResponse(exchange, 400, response);
    }

    protected void sendInternalServerError(HttpExchange exchange, BaseResponse response) throws IOException {
        response.status = BaseResponse.ERROR;
        response.message = "internal server error";
        sendResponse(exchange, 500, response);
    }

    protected void sendCatchError(HttpExchange exchange, BaseResponse response, Exception e) throws IOException {
        response.status = BaseResponse.ERROR;
        response.message = e.getMessage();
        sendResponse(exchange, 500, response);
    }

    protected void sendUnauthorizedError(HttpExchange exchange, BaseResponse response) throws IOException {
        response.status = BaseResponse.ERROR;
        response.message = "unauthorized";
        sendResponse(exchange, 401, response);
    }

    protected void sendUserNotFoundError(HttpExchange exchange, BaseResponse response) throws IOException {
        response.status = BaseResponse.ERROR;
        response.message = "user not found";
        sendResponse(exchange, 404, response);
    }

    protected void sendChatNotFoundError(HttpExchange exchange, BaseResponse response) throws IOException {
        response.status = BaseResponse.ERROR;
        response.message = "chat not found";
        sendResponse(exchange, 404, response);
    }

    @NotNull
    private String sanitizeDisplayName(@NotNull String displayName) {
        String clean = displayName.replaceAll("\\p{Cc}", LimController.EMPTY_STRING);
        clean = clean.replaceAll("\\s+", " ");
        return clean.trim();
    }

    protected boolean isDisplayNameValid(@NotNull String displayName) {
        String cleanName = sanitizeDisplayName(displayName);
        return cleanName.length() >= 3 && cleanName.length() <= 20;
    }

    protected boolean isUsernameValid(@NotNull String username) {
        return (username.length() >= 3 && username.length() <= 20) && USERNAME_PATTERN.matcher(username).matches();
    }
}

