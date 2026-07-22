package com.safelogj.limserver.handler;

import com.safelogj.limserver.DatabaseManager;
import com.safelogj.limserver.LimController;
import com.safelogj.limserver.model.Message;
import com.safelogj.limserver.model.User;
import com.safelogj.limserver.request.SendMessageRequest;
import com.safelogj.limserver.response.BaseResponse;
import com.sun.net.httpserver.HttpExchange;

import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
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
        if (!isSmallFile(exchange.getRequestHeaders().getFirst("Content-Length"))) { // 50 MB
            response.status = BaseResponse.ERROR;
            response.message = "File is too large (max 50MB)";
            sendResponse(exchange, 413, response);
            return;
        }

        String username = exchange.getRequestHeaders().getFirst("X-Username");
        String password = exchange.getRequestHeaders().getFirst("X-Password");
        long senderId = parsePositiveLong(exchange.getRequestHeaders().getFirst("X-Sender-Id"));
        long chatId = parsePositiveLong(exchange.getRequestHeaders().getFirst("X-Chat-Id"));
        String text = decodeFromHeader(exchange.getRequestHeaders().getFirst("X-Message-Text"));
        String type = exchange.getRequestHeaders().getFirst("X-Message-Type");
        String fileName = decodeFromHeader(exchange.getRequestHeaders().getFirst("X-File-Name"));
        String chatName = decodeFromHeader(exchange.getRequestHeaders().getFirst("X-Chat-Name"));

        if (!SendMessageRequest.isValidHeaders(username, password, senderId, chatId, text, type, fileName, chatName)
                || !isUsernameValid(username)) {
            sendFieldMissingError(exchange, response);
            return;
        }

        User user = LimController.dbManager.authenticateUser(username, password);
        if (user == null) {
            sendUnauthorizedError(exchange, response);
            return;
        }
        // 2. Генерация уникального имени файла
        String extension = LimController.EMPTY_STRING;
        int dotIndex = fileName.lastIndexOf('.');
        if (dotIndex >= 0) extension = fileName.substring(dotIndex).toLowerCase(Locale.US);
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
            long messageId = LimController.dbManager.saveMessage(chatId, user.id, text,
                    type, chatName, serverFileName, fileName, timestamp);
            if (messageId != Message.INVALID_MSG_ID) {
                response.messageId = messageId;
                response.timestamp = timestamp;
                response.message = "file uploaded successfully: " + fileName;
                sendSuccess(exchange, response);
            } else {
                throw new IOException("Save message after file upload error " + fileName);
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

    private String decodeFromHeader(String text) {
        if (text == null || text.isEmpty()) return LimController.EMPTY_STRING;
        try {
            return URLDecoder.decode(text, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return LimController.EMPTY_STRING;
        }
    }

    private boolean isSmallFile(@Nullable String sizeStr) {
        if (sizeStr == null) return false;
        long size = parsePositiveLong(sizeStr);
        return size > 0 && size <= DatabaseManager.FILE_SIZE_LIMIT;
    }

    private long parsePositiveLong(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
