package com.safelogj.limserver.handler;

import com.safelogj.limserver.DatabaseManager;
import com.safelogj.limserver.LimController;
import com.safelogj.limserver.model.Message;
import com.safelogj.limserver.request.SendMessageRequest;
import com.safelogj.limserver.response.BaseResponse;
import com.sun.net.httpserver.HttpExchange;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.file.Files;
import java.util.Locale;
import java.util.UUID;

public class MediaUploadHandler extends BaseHandler {

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        BaseResponse response = new BaseResponse();
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendMethodError(exchange, response);
            return;
        }
        String contentLengthHeader = exchange.getRequestHeaders().getFirst("Content-Length");
        if (contentLengthHeader != null) {
            long fileSize = Long.parseLong(contentLengthHeader);
            if (fileSize > DatabaseManager.FILE_SIZE_LIMIT) { // 50 MB
                response.status = BaseResponse.ERROR;
                response.message = "File is too large (max 50MB)";
                sendResponse(exchange, 413, response);
                return;
            }
        } else {
            response.status = BaseResponse.ERROR;
            response.message = "Content-Length header is missing";
            sendResponse(exchange, 400, response);
            return;
        }

        String username = exchange.getRequestHeaders().getFirst("X-Username");
        String password = exchange.getRequestHeaders().getFirst("X-Password");
        String chatIdStr = exchange.getRequestHeaders().getFirst("X-Chat-Id");
        String senderId = exchange.getRequestHeaders().getFirst("X-Sender-Id");
        String messageText = encodeToHeader(exchange.getRequestHeaders().getFirst("X-Message-Text"));
        String originalName = encodeToHeader(exchange.getRequestHeaders().getFirst("X-File-Name"));
        String chatName = encodeToHeader(exchange.getRequestHeaders().getFirst("X-Chat-Name"));
        String messageType = exchange.getRequestHeaders().getFirst("X-Message-Type");

        if (!SendMessageRequest.isValidHeaders(username, password, originalName)  || !isUsernameValid(username)) {
            sendFieldMissingError(exchange, response);
            return;
        }

        if (LimController.dbManager.authenticateUser(username, password) == null) {
            sendUnauthorizedError(exchange, response);
            return;
        }
        // 2. Генерация уникального имени файла
        String extension = LimController.EMPTY_STRING;
        int dotIndex = originalName.lastIndexOf('.');
        if (dotIndex >= 0) extension = originalName.substring(dotIndex).toLowerCase(Locale.US);
        long timestamp = System.currentTimeMillis();
        String serverFileName = timestamp + "_" + UUID.randomUUID().toString().substring(0, 8) + extension;
        File targetFile = new File(LimController.MEDIA_PATH, serverFileName);

        // 3. Стриминг файла из сети на диск
        try (InputStream is = exchange.getRequestBody(); FileOutputStream fos = new FileOutputStream(targetFile)) {
            byte[] buffer = new byte[8192]; // 8KB
            int bytesRead;
            long totalRead = 0L;
            while ((bytesRead = is.read(buffer)) != -1) {
                totalRead += bytesRead;
                if (totalRead > DatabaseManager.FILE_SIZE_LIMIT) {
                    throw new IOException("File size limit exceeded during upload");
                }
                fos.write(buffer, 0, bytesRead);
            }
            // 4. Возвращаем клиенту имя файла на сервере
            long chatId = Long.parseLong(chatIdStr);
            long messageId = LimController.dbManager.saveMessage(chatId, Long.parseLong(senderId), messageText,
                    messageType, timestamp, serverFileName, originalName, chatName);
            if (messageId != Message.INVALID_MSG_ID) {
                response.timestamp = timestamp;
                response.messageId = messageId;
                response.chatId = chatId;
                response.message = "file uploaded successfully: " + originalName;
                sendSuccess(exchange, response);
            } else {
                throw new IOException("Save message after file upload error " + originalName);
            }

        } catch (Exception e) {
            if (Files.deleteIfExists(targetFile.toPath())) {
                LimController.log.error("Deleted temporary file: {}", targetFile.getAbsolutePath());
            } else {
                LimController.log.error("Failed to delete temporary file: {}", targetFile.getAbsolutePath());
            }
            LimController.log.error("File upload error: ", e);
            sendCatchError(exchange, response, e);
        }
    }

    private String encodeToHeader(String text) {
        if (text == null || text.isEmpty()) return LimController.EMPTY_STRING;
        try {
            return URLEncoder.encode(text, "UTF-8");
        } catch (Exception e) {
            return LimController.EMPTY_STRING;
        }
    }
}
