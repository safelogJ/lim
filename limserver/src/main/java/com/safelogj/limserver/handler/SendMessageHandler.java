package com.safelogj.limserver.handler;

import com.safelogj.limserver.LimController;
import com.safelogj.limserver.model.Chat;
import com.safelogj.limserver.model.Message;
import com.safelogj.limserver.model.User;
import com.safelogj.limserver.request.SendMessageRequest;
import com.safelogj.limserver.response.BaseResponse;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

public class SendMessageHandler extends BaseHandler {

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        BaseResponse response = new BaseResponse();
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendMethodError(exchange, response);
            return;
        }
        // 2. Читаем входящий JSON от Андроида
        try (InputStreamReader reader = new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8)) {
            SendMessageRequest req = gson.fromJson(reader, SendMessageRequest.class);
            if (req == null || !req.isValidRequest() || !isUsernameValid(req.username())) {
                sendFieldMissingError(exchange, response);
                return;
            }
            User user = LimController.dbManager.authenticateUser(req.username(), req.password());
            if (user == null) {
                sendUnauthorizedError(exchange, response);
                return;
            }

            // 5. Получение или создание вечного чата
            // Передаем senderId (который достали при авторизации) и receiverId из запроса
            Chat chat = LimController.dbManager.getOrCreatePersonalChat(user.id, req.receiverId());
            if (chat == null) {
                sendUserNotFoundError(exchange, response);
                return;
            }
            // 6. Сохранение сообщения в созданный/найденный чат
            long timestamp = System.currentTimeMillis();
            long messageId = LimController.dbManager.saveMessage(
                    chat.id, user.id, req.text(), req.type(), timestamp, req.filePath(), req.fileName());
            if (messageId != Message.INVALID_MSG_ID) {
                response.chatId = chat.id;
                response.messageId = messageId;
                response.message = "message saved on server";
                response.timestamp = timestamp;
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
