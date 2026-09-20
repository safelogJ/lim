package com.safelogj.limserver.handler;

import com.safelogj.limserver.LimController;
import com.safelogj.limserver.model.Chat;
import com.safelogj.limserver.model.Message;
import com.safelogj.limserver.model.User;
import com.safelogj.limserver.request.ScriptRequest;
import com.safelogj.limserver.request.SendMessageRequest;
import com.safelogj.limserver.response.BaseResponse;
import com.safelogj.limserver.response.ScriptResponse;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

public class ScriptHandler extends BaseHandler {

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        ScriptResponse response = new ScriptResponse();
        if (!POST.equalsIgnoreCase(exchange.getRequestMethod())) {
            sendScriptError(exchange, response, "Method not allowed");
            LimController.log.error("sendMethodError ");
            return;
        }

        try (InputStreamReader reader = new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8)) {
            ScriptRequest req = gson.fromJson(reader, ScriptRequest.class);
            if (req == null || !req.isValidRequest() || !isUsernameValid(req.username())) {
                LimController.log.error("sendFieldMissingError ");
                sendScriptError(exchange, response, "missing required fields");
                return;
            }
            User bot = LimController.dbManager.authenticateUserAndBot(req.username(), req.password(), true);
            if (bot == null) {
                bot = LimController.dbManager.registerUserAndBot(req.username(), req.password(), req.displayName(), User.BOT, User.BOT, true);
            }

            if (bot == null) {
                LimController.log.error("sendUnauthorizedError ");
                sendScriptError(exchange, response, "Unauthorized");
                return;
            }

            response.status = BaseResponse.SUCCESS;
            response.results = getResult(req, bot.id);
            response.message = "Sending messages completed";
            sendSuccess(exchange, response);
        } catch (Exception e) {
            LimController.log.error("ScriptHandler error: ", e);
            sendScriptError(exchange, response, e.getMessage());
        }
    }

    private Map<String, String> getResult(ScriptRequest req, int botId) {
        Map<String, String> results = new HashMap<>();
        long timestamp = System.currentTimeMillis();

        for (String login : req.loginList()) {
            User interlocutor = LimController.dbManager.searchUserByUsername(login);
            if (interlocutor != null) {
                Chat chat = LimController.dbManager.getOrCreatePersonalChat(botId, interlocutor.id);
                if (chat != null && LimController.dbManager.isMemberOfChat(botId, chat.id)) {
                    long msgId = LimController.dbManager.saveMessage(new SendMessageRequest(null, null, null,
                            chat.id, req.text(), "TEXT",null, null, req.displayName()), botId, timestamp);
                    if (msgId != Message.INVALID_MSG_ID) {
                        results.put(login, "success");
                    } else {
                        results.put(login, "db_error");
                    }
                } else {
                    results.put(login, "chat_error");
                }
            } else {
                results.put(login, "user_not_found");
            }
        }
        return results;
    }
}
