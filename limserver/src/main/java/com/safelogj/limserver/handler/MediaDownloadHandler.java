package com.safelogj.limserver.handler;

import com.safelogj.limserver.LimController;
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
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendMethodError(exchange, response);
            return;
        }
        File file;
        try (InputStreamReader reader = new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8)) {
            MediaDownloadRequest req = gson.fromJson(reader, MediaDownloadRequest.class);
            if (req == null || !req.isValidRequest() || !isUsernameValid(req.username())) {
                sendFieldMissingError(exchange, response);
                return;
            }
            // 3. Авторизация
            if (LimController.dbManager.authenticateUser(req.username(), req.password()) == null) {
                sendUnauthorizedError(exchange, response);
                return;
            }
            // 4. Проверка существования файла
            file = new File(LimController.MEDIA_PATH, req.filePath());
            if (!file.exists() || file.isDirectory()) {
                response.status = BaseResponse.ERROR;
                response.message = "File not found or already deleted";
                sendResponse(exchange, 404, response);
                return;
            }

        } catch (Exception e) {
            LimController.log.error("MediaDownloadHandler error: ", e);
            sendCatchError(exchange, response, e);
            return;
        }

        exchange.getResponseHeaders().set("Content-Type", "application/octet-stream");
        exchange.sendResponseHeaders(200, file.length());
        try (FileInputStream fis = new FileInputStream(file); OutputStream os = exchange.getResponseBody()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = fis.read(buffer)) != -1) {
                os.write(buffer, 0, count);
            }
        } catch (Exception e) {
            LimController.log.error("MediaDownloadHandler error: ", e);
            return;
        }
        Files.deleteIfExists(file.toPath());
    }
}
