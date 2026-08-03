package com.safelogj.limserver.handler;

import com.safelogj.limserver.FileCacheUtils;
import com.safelogj.limserver.LimController;
import com.safelogj.limserver.model.User;
import com.safelogj.limserver.request.MediaDownloadRequest;
import com.safelogj.limserver.response.BaseResponse;
import com.sun.net.httpserver.HttpExchange;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

public class MediaDownloadHandler extends BaseHandler {
    @Override
    public void handle(HttpExchange exchange) throws IOException {
        BaseResponse response = new BaseResponse();
        if (!POST.equalsIgnoreCase(exchange.getRequestMethod())) {
            sendMethodError(exchange, response);
            return;
        }

        try (InputStreamReader reader = new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8)) {
            MediaDownloadRequest req = gson.fromJson(reader, MediaDownloadRequest.class);
            if (req == null || !req.isValidRequest() || !isUsernameValid(req.username())) {
                sendFieldMissingError(exchange, response);
                return;
            }
            // 3. Авторизация
            User user = LimController.dbManager.authenticateUser(req.username(), req.password());
            if (user == null) {
                sendUnauthorizedError(exchange, response);
                return;
            }
            // 4. Проверка существования файла
           File file = new File(LimController.MEDIA_PATH, req.filePath());
            if (!file.exists() || file.isDirectory() || System.currentTimeMillis() - file.lastModified() > LimController.MEDIA_DOWNLOAD_LIFETIME) {
                response.status = BaseResponse.ERROR;
                response.message = "File is no longer available";
                sendResponse(exchange, 404, response);
                return;
            }

            if (!LimController.dbManager.isFileAccessible(user.id, req.chatId(), req.filePath())) {
                sendUnauthorizedError(exchange, response);
                return;
            }
            String path = file.getAbsolutePath();
            if (Boolean.TRUE.equals(req.isConfirmed())) {
                if (LimController.ACTIVE_DOWNLOADS.putIfAbsent(user.id, path) == null) {
                    try {
                        if (Files.deleteIfExists(file.toPath())) {
                            LimController.log.info("File confirmed and deleted: {}", file.getName());
                        }
                    } finally {
                        LimController.ACTIVE_DOWNLOADS.remove(user.id);
                    }
                }
                response.message = "file deletion request";
                sendSuccess(exchange, response);
                return;
            }

            if (LimController.ACTIVE_DOWNLOADS.putIfAbsent(user.id, path) != null) {
                response.status = BaseResponse.ERROR;
                response.message = "File is already being downloaded by another device";
                sendResponse(exchange, 429, response); // Too Many Requests
                return;
            }
            sendMediaFile(exchange, file, user);
        } catch (Exception e) {
            LimController.log.error("MediaDownloadHandler error: ", e);
            sendCatchError(exchange, response, e);
        }
    }

    private void sendMediaFile(HttpExchange exchange, File file, User user) {
        try {
            exchange.getResponseHeaders().set("Content-Type", "application/octet-stream");
            exchange.sendResponseHeaders(200, file.length());
            try (FileInputStream fis = new FileInputStream(file); OutputStream os = exchange.getResponseBody()) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = fis.read(buffer)) != -1) {
                    os.write(buffer, 0, count);
                }
                os.flush();
            }
        } catch (Exception e) {
            LimController.log.error("MediaDownloadHandler error: ", e);
        } finally {
            LimController.ACTIVE_DOWNLOADS.remove(user.id);
            FileCacheUtils.dropFileFromCache(file);
        }
    }
}
