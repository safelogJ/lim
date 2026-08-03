package com.safelogj.lim;

import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.gson.Gson;
import com.safelogj.lim.model.Chat;
import com.safelogj.lim.model.Message;
import com.safelogj.lim.model.User;
import com.safelogj.lim.request.BlockChatRequest;
import com.safelogj.lim.request.EditUserRequest;
import com.safelogj.lim.request.GetMessagesRequest;
import com.safelogj.lim.request.HideChatRequest;
import com.safelogj.lim.request.MediaDownloadRequest;
import com.safelogj.lim.request.RegisterRequest;
import com.safelogj.lim.request.SearchChatRequest;
import com.safelogj.lim.request.SearchUserRequest;
import com.safelogj.lim.request.SendMessageRequest;
import com.safelogj.lim.response.BaseResponse;
import com.safelogj.lim.viewmodels.ResultCallback;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javax.crypto.Cipher;
import javax.crypto.CipherInputStream;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okio.BufferedSink;


public class NetworkService {

    public static final long FILE_SIZE_LIMIT = 50_000_000L;
    private static final char[] HEX_ARRAY = "0123456789abcdef".toCharArray();
    private static final String SERVER_RETURNED_ERROR = "server returned error: ";
    private static final String NETWORK_SERVICE_ERROR = "network service error: ";
    private static final String REQUEST_BUILD_ERROR = "request build error: ";
    private static final String MEDIA_TYPE_JSON = "application/json; charset=utf-8";
    private final OkHttpClient client;
    private final Gson gson = new Gson();
    private final AppController controller;
    private final DatabaseHelper dbHelper;
    private static final ThreadLocal<MessageDigest> DIGEST =
            ThreadLocal.withInitial(() -> {
                try {
                    return MessageDigest.getInstance("SHA-256"); // или MD5
                } catch (NoSuchAlgorithmException e) {
                    throw new IllegalStateException(e);
                }
            });

    public NetworkService(AppController controller) {
        this.controller = controller;
        client = controller.getOkHttpClient();
        dbHelper = controller.getDbHelper();
    }

    public void register(String username, String password, String displayName, ResultCallback<String> callback) {
        String publicKey;
        String privateHash;
        Request request;
        try {
            controller.createE2Keys();
            publicKey = controller.getE2eePublicKey();
            privateHash = controller.getPrivateHash(password);
            if (publicKey.isEmpty() || privateHash.isEmpty()) {
                throw new SecurityException("error creating encryption keys");
            }

            RequestBody body = RequestBody.create(gson.toJson(new RegisterRequest(username, hashPassword(password),
                    displayName, publicKey, privateHash)), MediaType.parse(MEDIA_TYPE_JSON));
            request = new Request.Builder().url(controller.getServerUrl() + "/register").post(body).build();
        } catch (Exception e) {
            sendError(callback, REQUEST_BUILD_ERROR + e.getMessage());
            return;
        }
        try (Response response = client.newCall(request).execute()) {
            BaseResponse res = gson.fromJson(response.body().string(), BaseResponse.class);
            if (response.isSuccessful() && BaseResponse.SUCCESS.equals(res.status())) {
                User user = new User();
                user.id = res.userId();
                user.username = username;
                user.displayName = displayName;
                user.publicKey = res.publicKey();

                controller.setUsername(username);
                controller.setPassword(password);
                controller.setDisplayName(displayName);
                controller.setE2eePublicKey(res.publicKey());
                controller.unpackPrivateKey(res.privateHash(), password);
                dbHelper.saveUser(user, callback, res.message(), null);
                controller.setUserId(user.id);
                controller.writeSettingsToFile();
                Log.i(AppController.LOG_TAG, res.message());

            } else {
                sendError(callback, SERVER_RETURNED_ERROR + res.message());
            }
        } catch (Exception e) {
            sendError(callback, NETWORK_SERVICE_ERROR + e.getMessage());
        }
    }

    public void deleteAccount(String username, String password, ResultCallback<String> callback) {
        Request request;
        try {
            RequestBody body = RequestBody.create(gson.toJson(new EditUserRequest(username, hashPassword(password), null, null)),
                    MediaType.parse(MEDIA_TYPE_JSON));
            request = new Request.Builder().url(controller.getServerUrl() + "/user").delete(body).build();
        } catch (Exception e) {
            sendError(callback, REQUEST_BUILD_ERROR + e.getMessage());
            return;
        }
        try (Response response = client.newCall(request).execute()) {
            BaseResponse res = gson.fromJson(response.body().string(), BaseResponse.class);
            if (response.isSuccessful() && BaseResponse.SUCCESS.equals(res.status())) {
                controller.eraseUser();
                dbHelper.wipeAllData(callback, res.message(), res.message());
                controller.writeSettingsToFile();
            } else {
                sendError(callback, SERVER_RETURNED_ERROR + res.message());
            }

        } catch (Exception e) {
            sendError(callback, NETWORK_SERVICE_ERROR + e.getMessage());
        }
    }

    public void editUser(String username, String password, @Nullable String dName, @Nullable String newPass, ResultCallback<String> callback) {
        Request request;
        try {
            RequestBody body = RequestBody.create(gson.toJson(new EditUserRequest(username, hashPassword(password),
                    dName, (newPass == null ? null : hashPassword(newPass)))), MediaType.parse(MEDIA_TYPE_JSON));
            request = new Request.Builder().url(controller.getServerUrl() + "/user").post(body).build();
        } catch (Exception e) {
            sendError(callback, REQUEST_BUILD_ERROR + e.getMessage());
            return;
        }
        try (Response response = client.newCall(request).execute()) {
            BaseResponse res = gson.fromJson(response.body().string(), BaseResponse.class);
            if (response.isSuccessful() && BaseResponse.SUCCESS.equals(res.status())) {
                if (dName != null) {
                    controller.setDisplayName(dName);
                    dbHelper.updateUserDisplayName(dName);
                }
                if (newPass != null) {
                    controller.setPassword(newPass);
                }
                sendSuccess(callback, res.message(), res.message());
                controller.writeSettingsToFile();

            } else {
                sendError(callback, SERVER_RETURNED_ERROR + res.message());
            }
        } catch (Exception e) {
            sendError(callback, NETWORK_SERVICE_ERROR + e.getMessage());
        }
    }

    public void searchInterlocutor(String username, String password, @Nullable String queryUsername,
                                   @Nullable Long chatId, ResultCallback<User> callback) {
        Request request;
        try {
            RequestBody body = RequestBody.create(
                    gson.toJson(new SearchUserRequest(username, hashPassword(password), queryUsername, chatId)), MediaType.parse(MEDIA_TYPE_JSON));
            request = new Request.Builder().url(controller.getServerUrl() + "/user/search").post(body).build();
        } catch (Exception e) {
            sendError(callback, REQUEST_BUILD_ERROR + e.getMessage());
            return;
        }
        try (Response response = client.newCall(request).execute()) {
            BaseResponse res = gson.fromJson(response.body().string(), BaseResponse.class);
            if (response.isSuccessful() && BaseResponse.SUCCESS.equals(res.status())) {
                User user = new User();
                user.id = res.userId();
                user.username = res.userName();
                user.displayName = res.displayName();
                user.publicKey = res.publicKey();
                dbHelper.saveUser(user, callback, user, chatId);
                Log.i(AppController.LOG_TAG, res.message());

            } else {
                sendError(callback, SERVER_RETURNED_ERROR + res.message());
            }

        } catch (Exception e) {
            sendError(callback, NETWORK_SERVICE_ERROR + e.getMessage());
        }
    }

    public void searchNewChat(User queryUser, ResultCallback<Chat> callback) {
        Request request;
        try {
            RequestBody body = RequestBody.create(gson.toJson(new SearchChatRequest(controller.getUsername(),
                    hashPassword(controller.getPassword()), queryUser.id)), MediaType.parse(MEDIA_TYPE_JSON));
            request = new Request.Builder().url(controller.getServerUrl() + "/chat/search").post(body).build();
        } catch (Exception e) {
            sendError(callback, REQUEST_BUILD_ERROR + e.getMessage());
            return;
        }
        try (Response response = client.newCall(request).execute()) {
            BaseResponse res = gson.fromJson(response.body().string(), BaseResponse.class);
            if (response.isSuccessful() && BaseResponse.SUCCESS.equals(res.status())) {
                Chat chat = new Chat();
                chat.id = res.chatId();
                chat.name = queryUser.displayName;
                chat.interlocutorId = queryUser.id;
                dbHelper.saveChat(chat, callback, chat);
                Log.i(AppController.LOG_TAG, res.message());

            } else {
                sendError(callback, SERVER_RETURNED_ERROR + res.message());
            }
        } catch (Exception e) {
            sendError(callback, NETWORK_SERVICE_ERROR + e.getMessage());
        }
    }

    public void hideChat(long chatId) {
        Request request;
        try {
            RequestBody body = RequestBody.create(gson.toJson(new HideChatRequest(controller.getUsername(),
                    hashPassword(controller.getPassword()), chatId)), MediaType.parse(MEDIA_TYPE_JSON));
            request = new Request.Builder().url(controller.getServerUrl() + "/chat/hide").post(body).build();
        } catch (Exception e) {
            Log.d(AppController.LOG_TAG, REQUEST_BUILD_ERROR + e.getMessage());
            return;
        }
        try (Response response = client.newCall(request).execute()) {
            Log.i(AppController.LOG_TAG, response.message());
        } catch (Exception e) {
            Log.d(AppController.LOG_TAG, NETWORK_SERVICE_ERROR + e.getMessage());
        }
    }

    public void blockChat(long chatId, ResultCallback<Boolean> callback) {
        Request request;
        try {
            RequestBody body = RequestBody.create(gson.toJson(new BlockChatRequest(
                    controller.getUsername(), hashPassword(controller.getPassword()), chatId)), MediaType.parse(MEDIA_TYPE_JSON));
            request = new Request.Builder().url(controller.getServerUrl() + "/chat/block").post(body).build();
        } catch (Exception e) {
            sendError(callback, REQUEST_BUILD_ERROR + e.getMessage());
            return;
        }
        try (Response response = client.newCall(request).execute()) {
            BaseResponse res = gson.fromJson(response.body().string(), BaseResponse.class);
            if (response.isSuccessful() && BaseResponse.SUCCESS.equals(res.status())) {
                dbHelper.setChatBlockedState(chatId, callback);
            } else {
                sendError(callback, SERVER_RETURNED_ERROR + res.message());
            }
        } catch (Exception e) {
            sendError(callback, NETWORK_SERVICE_ERROR + e.getMessage());
        }
    }

    public void sendTextMessage(Message msg) {
        if (msg.interlocutorPublicKey == null) {
            Log.e(AppController.LOG_TAG, "Send Text Cannot encrypt: recipient public key not found");
            dbHelper.notConfirmMessageSent(msg);
            return;
        }
        Request request;
        try {
            RequestBody body = RequestBody.create(gson.toJson(new SendMessageRequest(controller.getUsername(),
                    hashPassword(controller.getPassword()), msg.senderId, msg.chatId, controller.encryptMessage(msg.text, msg.interlocutorPublicKey),
                    msg.type, msg.filePath, msg.fileName, msg.chatName)), MediaType.parse(MEDIA_TYPE_JSON));
            request = new Request.Builder().url(controller.getServerUrl() + "/messages/send").post(body).build();
        } catch (Exception e) {
            Log.w(AppController.LOG_TAG, REQUEST_BUILD_ERROR + e.getMessage());
            dbHelper.notConfirmMessageSent(msg);
            return;
        }
        try (Response response = client.newCall(request).execute()) {
            BaseResponse res = gson.fromJson(response.body().string(), BaseResponse.class);
            if (response.isSuccessful() && BaseResponse.SUCCESS.equals(res.status())) {
                msg.id = res.messageId();
                msg.timestamp = res.timestamp();
                if (msg.localId == Chat.INVALID_ID) {
                    Log.e(AppController.LOG_TAG, "сообщение отправлено, но локал id -1, снова сохраняем его локально");
                    dbHelper.saveMsgBeforeSending(msg);
                }
                dbHelper.confirmMessageSent(msg);
                Log.i(AppController.LOG_TAG, res.message());
                return;

            } else {
                Log.w(AppController.LOG_TAG, SERVER_RETURNED_ERROR + res.message());
            }
        } catch (Exception e) {
            Log.w(AppController.LOG_TAG, NETWORK_SERVICE_ERROR + e.getMessage());
        }
        dbHelper.notConfirmMessageSent(msg);
    }

    public void sendMediaMessage(Message msg) {
        Uri uri;
        long fileSize;
        try {
            uri = Uri.parse(msg.filePath);
            fileSize = getFileSize(uri);
            if (fileSize >= FILE_SIZE_LIMIT) {
                Log.w(AppController.LOG_TAG, controller.getResources().getString(R.string.big_file_error));
                dbHelper.notConfirmMessageSent(msg);
                return;
            }
        } catch (Exception e) {
            Log.w(AppController.LOG_TAG, REQUEST_BUILD_ERROR + e.getMessage());
            dbHelper.notConfirmMessageSent(msg);
            return;
        }

        if (msg.interlocutorPublicKey == null) {
            Log.e(AppController.LOG_TAG, "Send Media Cannot encrypt: recipient public key not found");
            dbHelper.notConfirmMessageSent(msg);
            return;
        }

        try (InputStream inputStream = controller.getContentResolver().openInputStream(uri)) {
            if (inputStream == null) {
                dbHelper.notConfirmMessageSent(msg);
                return;
            }

            byte[] iv = new byte[12];
            controller.getSecureRandom().nextBytes(iv);

            String encText = controller.encryptMessage(msg.text, msg.interlocutorPublicKey);
            String encFileName = controller.encryptMessage(msg.fileName, msg.interlocutorPublicKey);

            RequestBody requestBody = new RequestBody() {
                @Override
                public MediaType contentType() {
                    return MediaType.parse("application/octet-stream");
                }

                @Override
                public long contentLength() {
                    return 12 + fileSize + 16;
                } // IV + Данные

                @Override
                public void writeTo(@NonNull BufferedSink sink) throws IOException {
                    try {
                        sink.write(iv); // Пишем IV в начало
                        Cipher cipher = controller.getFileCipherByMode(msg.interlocutorPublicKey, iv, Cipher.ENCRYPT_MODE);
                        byte[] buffer = new byte[8192];
                        int read;
                        while ((read = inputStream.read(buffer)) != -1) {
                            sink.write(cipher.update(buffer, 0, read));
                        }
                        sink.write(cipher.doFinal());
                    } catch (Exception e) {
                        throw new IOException("Encryption error during upload", e);
                    }
                }
            };

            Request request = new Request.Builder()
                    .url(controller.getServerUrl() + "/media/upload")
                    .header("X-Username", controller.getUsername())
                    .header("X-Password", hashPassword(controller.getPassword()))
                    .header("X-Sender-Id", String.valueOf(msg.senderId))
                    .header("X-Chat-Id", String.valueOf(msg.chatId))
                    .header("X-Message-Text", encodeToHeader(encText))
                    .header("X-Message-Type", msg.type)
                    .header("X-File-Name", encodeToHeader(encFileName))
                    .header("X-Chat-Name", encodeToHeader(msg.chatName)) // Его можно оставить открытым для синхронизации
                    .post(requestBody)
                    .build();

            try (Response response = client.newCall(request).execute()) {
                BaseResponse res = gson.fromJson(response.body().string(), BaseResponse.class);
                if (response.isSuccessful() && BaseResponse.SUCCESS.equals(res.status())) {
                    msg.id = res.messageId();
                    msg.timestamp = res.timestamp();
                    if (msg.localId == Chat.INVALID_ID) {
                        Log.e(AppController.LOG_TAG, "сообщение отправлено, но локал id -1, снова сохраняем его локально");
                        dbHelper.saveMsgBeforeSending(msg);
                    }
                    dbHelper.confirmMessageSent(msg);
                    Log.i(AppController.LOG_TAG, "Media message sent: " + res.message());
                    return;

                } else {
                    Log.w(AppController.LOG_TAG, SERVER_RETURNED_ERROR + res.message());
                }

            }
        } catch (Exception e) {
            Log.e(AppController.LOG_TAG, "Media send error: " + e.getMessage());
        }
        dbHelper.notConfirmMessageSent(msg);
    }

    public void getNewMessages(long lastMessageId, @Nullable List<Long> interlocutorIds) {
        Request request;
        try {
            RequestBody body = RequestBody.create(gson.toJson(new GetMessagesRequest(controller.getUsername(),
                    hashPassword(controller.getPassword()), lastMessageId, interlocutorIds)), MediaType.parse(MEDIA_TYPE_JSON));
            request = new Request.Builder().url(controller.getServerUrl() + "/messages/get").post(body).build();
        } catch (Exception e) {
            Log.d(AppController.LOG_TAG, REQUEST_BUILD_ERROR + e.getMessage());
            controller.activeDownloadsCount.decrementAndGet();
            return;
        }
        try (Response response = client.newCall(request).execute()) {
            BaseResponse res = gson.fromJson(response.body().string(), BaseResponse.class);
            if (response.isSuccessful() && BaseResponse.SUCCESS.equals(res.status())) {
                for (Message msg : res.messages()) {
                    Log.d(AppController.LOG_TAG, "ключ собеседника: " + msg.interlocutorPublicKey);
                    msg.text = controller.decryptMessage(msg.text, msg.interlocutorPublicKey);
                    if (msg.fileName != null && !msg.fileName.isEmpty()) {
                        msg.fileName = controller.decryptMessage(msg.fileName, msg.interlocutorPublicKey);
                    }
                }
                dbHelper.saveIncomingMsgList(res.messages());
                fillChatStatus(res.onlineStatuses());
                controller.offlineHandler.removeCallbacks(controller.resetStatusesRunnable);
            } else {
                Log.w(AppController.LOG_TAG, SERVER_RETURNED_ERROR + res.message());
                controller.offlineHandler.postDelayed(controller.resetStatusesRunnable, 15000);
            }
        } catch (Exception e) {
            Log.e(AppController.LOG_TAG, NETWORK_SERVICE_ERROR + e.getMessage());
            controller.offlineHandler.postDelayed(controller.resetStatusesRunnable, 15000);
        } finally {
            controller.activeDownloadsCount.decrementAndGet();
        }
        checkMediaThread();
    }

    private void fillChatStatus(@Nullable Map<Long, Boolean> onlineStatuses) {
        if (onlineStatuses == null) return;
        for (Map.Entry<Long, Boolean> userStatus : onlineStatuses.entrySet()) {
            Map<Long, Boolean> chat = AppController.getChatStatuses(userStatus.getKey());
            if (chat == null) continue;
            chat.replaceAll((id, oldStatus) -> userStatus.getValue());
        }
    }

    private void checkMediaThread() {
        if (controller.startedActivities.get() > 0) {
            dbHelper.getMediaList(new ResultCallback<>() {
                @Override
                public void onSuccess(List<Message> mediaList) {
                    for (Message msg : mediaList) {
                        Log.d(AppController.LOG_TAG, "пнули загрузку в нити " + controller.getNetStreams()[AppController.POOL_SIZE - 2].toString());
                        controller.getNetStreams()[AppController.POOL_SIZE - 2].execute(() -> downloadMedia(msg));
                    }
                }

                @Override
                public void onError(String errorMsg) {
                    Log.d(AppController.LOG_TAG, errorMsg);
                }
            });
        } else {
            List<Message> list = new ArrayList<>();
            CountDownLatch latch = new CountDownLatch(1);
            dbHelper.getMediaList(new ResultCallback<>() {
                @Override
                public void onSuccess(List<Message> mediaList) {
                    list.addAll(mediaList);
                    latch.countDown();
                }

                @Override
                public void onError(String errorMsg) {
                    latch.countDown();
                    Log.d(AppController.LOG_TAG, errorMsg);
                }
            });

            try {
                if (latch.await(10, TimeUnit.SECONDS)) {
                    for (Message msg : list) {
                        Log.d(AppController.LOG_TAG, "пнули загрузку для в воркере " + Thread.currentThread().getName());
                        downloadMedia(msg);

                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void downloadMedia(Message msg) {
        if (msg.interlocutorPublicKey == null) {
            Log.d(AppController.LOG_TAG, "download media null: " + msg.fileName);
            dbHelper.setMediaStatus(msg, Message.MEDIA_STATUS_PENDING);
            return;
        }
        Log.d(AppController.LOG_TAG, "download media : " + msg.fileName);
        Request request;
        try {
            RequestBody body = RequestBody.create(gson.toJson(new MediaDownloadRequest(controller.getUsername(),
                    hashPassword(controller.getPassword()), msg.chatId, msg.filePath, false)), MediaType.parse(MEDIA_TYPE_JSON));
            request = new Request.Builder().url(controller.getServerUrl() + "/media/get").post(body).build();
        } catch (Exception e) {
            dbHelper.setMediaStatus(msg, Message.MEDIA_STATUS_PENDING);
            return;
        }
        try (Response response = client.newCall(request).execute()) {
            if (response.isSuccessful()) {
                InputStream is = response.body().byteStream();
                // 1. Читаем IV из начала потока
                byte[] iv = new byte[12];
                if (is.read(iv) != 12) throw new IOException("Missing IV header");
                // 2. Настраиваем CipherInputStream
                CipherInputStream cis = new CipherInputStream(is, controller.getFileCipherByMode(msg.interlocutorPublicKey, iv, Cipher.DECRYPT_MODE));
                File localFile = getUniquePath(msg);
                try (FileOutputStream fos = new FileOutputStream(localFile)) {
                    byte[] buffer = new byte[8192];
                    int read;
                    while ((read = cis.read(buffer)) != -1) {
                        fos.write(buffer, 0, read);
                    }
                }
                dbHelper.updateFilePath(msg, Uri.fromFile(localFile).toString());
                Log.d(AppController.LOG_TAG, "download file success: " + msg.fileName);
                confirmMediaDownload(msg);
            } else if (response.code() == 429) {
                dbHelper.setMediaStatus(msg, Message.MEDIA_STATUS_PENDING);
                Log.d(AppController.LOG_TAG, SERVER_RETURNED_ERROR + response.code() + " " + response.message());
            } else {
                dbHelper.setMediaStatus(msg, Message.MEDIA_STATUS_ERROR);
                Log.d(AppController.LOG_TAG, SERVER_RETURNED_ERROR + response.code() + " " + response.message());
            }
        } catch (Exception e) {
            dbHelper.setMediaStatus(msg, Message.MEDIA_STATUS_PENDING);
            Log.e(AppController.LOG_TAG, NETWORK_SERVICE_ERROR + e.getMessage());
        }
    }

    private void confirmMediaDownload(Message msg) {
        Request request;
        try {
            RequestBody body = RequestBody.create(gson.toJson(new MediaDownloadRequest(controller.getUsername(), hashPassword(controller.getPassword()),
                    msg.chatId, msg.filePath, true)), MediaType.parse(MEDIA_TYPE_JSON));
            request = new Request.Builder().url(controller.getServerUrl() + "/media/get").post(body).build();
        } catch (Exception e) {
            Log.d(AppController.LOG_TAG, REQUEST_BUILD_ERROR + e.getMessage());
            return;
        }
        try (Response response = client.newCall(request).execute()) {
            if (response.isSuccessful()) {
                Log.i(AppController.LOG_TAG, response.message() + msg.fileName);
            } else {
                Log.w(AppController.LOG_TAG, SERVER_RETURNED_ERROR + response.code());
            }
        } catch (Exception e) {
            Log.w(AppController.LOG_TAG, NETWORK_SERVICE_ERROR + e.getMessage());
        }

    }

    private <T> void sendSuccess(ResultCallback<T> callback, String log, T result) {
        Log.i(AppController.LOG_TAG, log);
        callback.onSuccess(result);
    }

    private <T> void sendError(ResultCallback<T> callback, String errorMsg) {
        Log.w(AppController.LOG_TAG, errorMsg);
        callback.onError(errorMsg);
    }


    @NonNull
    private String hashPassword(@NonNull String clientPasswordHash) {
        return bytesToHex(Objects.requireNonNull(DIGEST.get()).digest(clientPasswordHash.getBytes(StandardCharsets.UTF_8)));
    }

    @NonNull
    private String bytesToHex(byte[] bytes) {
        char[] hexChars = new char[bytes.length * 2];
        for (int j = 0; j < bytes.length; j++) {
            int v = bytes[j] & 0xFF;
            hexChars[j * 2] = HEX_ARRAY[v >>> 4];
            hexChars[j * 2 + 1] = HEX_ARRAY[v & 0x0F];
        }
        return new String(hexChars);
    }

    private long getFileSize(Uri uri) {
        if ("file".equals(uri.getScheme())) {
            String path = uri.getPath();
            if (path != null) {
                return new File(path).length();
            }
        }

        Cursor cursor = controller.getContentResolver().query(uri, null, null, null, null);
        if (cursor != null && cursor.moveToFirst()) {
            int sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE);
            if (!cursor.isNull(sizeIndex)) {
                long size = cursor.getLong(sizeIndex);
                cursor.close();
                return size;
            }
            cursor.close();
        }
        return FILE_SIZE_LIMIT;
    }

    private static String encodeToHeader(String text) {
        if (text == null || text.isEmpty()) return AppController.EMPTY_STRING;
        try {
            return URLEncoder.encode(text, "UTF-8");
        } catch (Exception e) {
            return AppController.EMPTY_STRING;
        }
    }

    private File getUniquePath(Message msg) {
        String originalName = msg.fileName;
        String serverName = new File(msg.filePath).getName();
        int underscore = serverName.lastIndexOf('_');
        String suffix = underscore >= 0 ? serverName.substring(underscore + 1) : "";

        int dot = originalName.lastIndexOf('.');
        String localName;
        if (dot > 0) {
            localName = originalName.substring(0, dot) + "_" + suffix + originalName.substring(dot);
        } else {
            localName = originalName + "_" + suffix;
        }
        return new File(controller.getExternalFileDir(), localName);
    }
}
