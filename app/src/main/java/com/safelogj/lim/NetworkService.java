package com.safelogj.lim;

import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.gson.Gson;
import com.safelogj.lim.model.Chat;
import com.safelogj.lim.model.MediaLatch;
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
                Log.e(AppController.LOG_TAG, res.privateHash());
                Log.e(AppController.LOG_TAG, "публичный автор,рега " + res.publicKey());

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
                dbHelper.wipeAllData(callback, res.message());
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
                Log.i(AppController.LOG_TAG, res.message());
                callback.onSuccess(res.message());
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
                dbHelper.saveChat(chat, callback);
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

    public void blockChat(long chatId) {
        Request request;
        try {
            RequestBody body = RequestBody.create(gson.toJson(new BlockChatRequest(
                    controller.getUsername(), hashPassword(controller.getPassword()), chatId)), MediaType.parse(MEDIA_TYPE_JSON));
            request = new Request.Builder().url(controller.getServerUrl() + "/chat/block").post(body).build();
        } catch (Exception e) {
            Log.e(AppController.LOG_TAG, REQUEST_BUILD_ERROR + e.getMessage());
            return;
        }
        try (Response response = client.newCall(request).execute()) {
            BaseResponse res = gson.fromJson(response.body().string(), BaseResponse.class);
            if (response.isSuccessful() && BaseResponse.SUCCESS.equals(res.status())) {
                dbHelper.setChatBlockedState(chatId);
            } else {
                Log.e(AppController.LOG_TAG, SERVER_RETURNED_ERROR + res.message());
            }
        } catch (Exception e) {
            Log.e(AppController.LOG_TAG, NETWORK_SERVICE_ERROR + e.getMessage());
        }
    }

    public void sendTextMessage(Message msg, @Nullable ResultCallback<String> liveChat) {
        if (msg.interlocutorPublicKey == null || msg.interlocutorPublicKey.isEmpty()) {
            Log.e(AppController.LOG_TAG, "Send Text Cannot encrypt: recipient public key not found or empty");
            dbHelper.notConfirmMessageSent(msg);
            return;
        }
        Request request;
        try {
            String encryptText = controller.encryptMessage(msg.text, msg.interlocutorPublicKey);
            if (encryptText == null) {
                dbHelper.notConfirmMessageSent(msg);
                Log.w(AppController.LOG_TAG, "ошибка при шифровании текста");
                return;
            }
            RequestBody body = RequestBody.create(gson.toJson(new SendMessageRequest(controller.getUsername(),
                    hashPassword(controller.getPassword()), msg.senderId, msg.chatId, encryptText,
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
                if (response.code() == 203 && liveChat != null) {
                      liveChat.onError(res.message());
                }
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

    public void sendMediaMessage(Message msg, @Nullable ResultCallback<String> liveChat) {
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

        if (msg.interlocutorPublicKey == null || msg.interlocutorPublicKey.isEmpty()) {
            Log.e(AppController.LOG_TAG, "Send Media Cannot encrypt: recipient public key not found or empty");
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
            if (encText == null || encFileName == null) {
                dbHelper.notConfirmMessageSent(msg);
                Log.e(AppController.LOG_TAG, "ошибка при шифровании текста или имени файла в медиа сообщении");
                return;
            }

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
                        byte[] inBuffer = new byte[65536];
                        byte[] outBuffer = new byte[cipher.getOutputSize(inBuffer.length)];
                        int read;
                        while ((read = inputStream.read(inBuffer)) != -1) {
                            int outWritten = cipher.update(inBuffer, 0, read, outBuffer, 0);
                            sink.write(outBuffer, 0, outWritten);
                            sink.emit();
                        }
                        sink.write(cipher.doFinal());
                    } catch (Exception e) {
                        Log.w(AppController.LOG_TAG, "Encryption error " + e.getMessage());
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
                    if (response.code() == 203 && liveChat != null) {
                        liveChat.onError(res.message());
                    }
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

    public void getNewMessages(long lastMessageId, @Nullable List<Long> interlocutorIds, @NonNull MediaLatch mediaLatch) {
        Request request;
        try {
            RequestBody body = RequestBody.create(gson.toJson(new GetMessagesRequest(controller.getUsername(),
                    hashPassword(controller.getPassword()), lastMessageId, interlocutorIds)), MediaType.parse(MEDIA_TYPE_JSON));
            request = new Request.Builder().url(controller.getServerUrl() + "/messages/get").post(body).build();
        } catch (Exception e) {
            Log.d(AppController.LOG_TAG, REQUEST_BUILD_ERROR + e.getMessage());
            controller.activeDownloadsCount.decrementAndGet();
            mediaLatch.countDown();
            return;
        }
        try (Response response = client.newCall(request).execute()) {
            BaseResponse res = gson.fromJson(response.body().string(), BaseResponse.class);
            if (response.isSuccessful() && BaseResponse.SUCCESS.equals(res.status())) {
                List<Message> decryptMessages = new ArrayList<>();
                for (Message msg : res.messages()) {
                    Message decryptMsg = decryptMessage(msg);
                    if (decryptMsg != null) {
                        decryptMessages.add(decryptMsg);
                    }
                }
                dbHelper.saveIncomingMsgList(decryptMessages, mediaLatch);
                fillChatStatus(res.onlineStatuses());
                controller.offlineHandler.removeCallbacks(controller.resetStatusesRunnable);
            } else {
                Log.w(AppController.LOG_TAG, SERVER_RETURNED_ERROR + res.message());
                controller.offlineHandler.postDelayed(controller.resetStatusesRunnable, 15000);
                mediaLatch.countDown();
            }
        } catch (Exception e) {
            Log.e(AppController.LOG_TAG, NETWORK_SERVICE_ERROR + e.getMessage());
            controller.offlineHandler.postDelayed(controller.resetStatusesRunnable, 15000);
            mediaLatch.countDown();
        } finally {
            controller.activeDownloadsCount.decrementAndGet();
        }
        checkMediaThread(mediaLatch);
    }
    @Nullable
    private Message decryptMessage(Message msg) {
        Log.e(AppController.LOG_TAG, "расшифровываю id: " + msg.id + " key " + msg.interlocutorPublicKey);
        msg.text = controller.decryptMessage(msg.text, msg.interlocutorPublicKey);
        if (msg.text == null) {
            Log.i(AppController.LOG_TAG, "расшифровка сообщения не удалась " + msg.id);
            return null;
        }
        if (msg.fileName != null && !msg.fileName.isEmpty()) {
            msg.fileName = controller.decryptMessage(msg.fileName, msg.interlocutorPublicKey);
            if (msg.fileName == null) {
                Log.i(AppController.LOG_TAG, "расшифровка имени файла не удалась " + msg.id);
                return null;
            }
        }
        return msg;
    }

    private void fillChatStatus(@Nullable Map<Long, Boolean> onlineStatuses) {
        if (onlineStatuses == null) return;
        for (Map.Entry<Long, Boolean> userStatus : onlineStatuses.entrySet()) {
            Map<Long, Boolean> chat = controller.getChatStatuses(userStatus.getKey());
            if (chat == null) continue;
            chat.replaceAll((id, oldStatus) -> userStatus.getValue());
        }
        controller.notifyOnlineMapChanged();
    }

    private void checkMediaThread(@NonNull MediaLatch mediaLatch) {
        waitMediaAndNotification(mediaLatch);
        if (mediaLatch.isWorker()) {
            workerShowNotification();
            for (Message msg : dbHelper.getMediaList()) {
                Log.d(AppController.LOG_TAG, "пнули загрузку в воркере ");
                downloadMedia(msg);
            }
        } else {
            for (Message msg : dbHelper.getMediaList()) {
                Log.d(AppController.LOG_TAG, "пнули загрузку в нити 4 сообщение " + msg.id);
                controller.getNetStreams()[AppController.POOL_SIZE - 2].execute(() -> downloadMedia(msg));
            }
        }
    }

    private void downloadMedia(Message msg) {
        if (msg.interlocutorPublicKey == null) {
            Log.d(AppController.LOG_TAG, "download media null: " + msg.fileName);
            dbHelper.setMediaStatus(msg, Message.MEDIA_STATUS_PENDING);
            return;
        }
        Log.d(AppController.LOG_TAG, "download media : id " + msg.id);
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
                byte[] iv = new byte[12];
                if (is.read(iv) != 12) throw new IOException("Missing IV header");
                Cipher cipher = controller.getFileCipherByMode(msg.interlocutorPublicKey, iv, Cipher.DECRYPT_MODE);
                File localFile = getUniquePath(msg);
                try (FileOutputStream fos = new FileOutputStream(localFile)) {
                    byte[] inBuffer = new byte[65536];
                    byte[] outBuffer = new byte[cipher.getOutputSize(inBuffer.length)];
                    int read;
                    while ((read = is.read(inBuffer)) != -1) {
                        int outWritten = cipher.update(inBuffer, 0, read, outBuffer, 0);
                        if (outWritten > 0) {
                            fos.write(outBuffer, 0, outWritten);
                        }
                    }
                    fos.write(cipher.doFinal());
                    fos.flush();
                }
                dbHelper.updateFilePath(msg, Uri.fromFile(localFile).toString());
                Log.d(AppController.LOG_TAG, "download file success: " + msg.fileName);
                confirmMediaDownload(msg);
            } else {
                dbHelper.setMediaStatus(msg, response.code() == 429 ? Message.MEDIA_STATUS_PENDING : Message.MEDIA_STATUS_ERROR);
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

    private void waitMediaAndNotification(@NonNull CountDownLatch latch) {
        try {
            if (!latch.await(30, TimeUnit.SECONDS)) {
                Log.w(AppController.LOG_TAG, "MessageWorker timed out waiting for DB save");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void workerShowNotification() {
        Log.d(AppController.LOG_TAG, "workerShowNotification");
        List<Chat> allUnread = controller.getDbHelper().getUnreadChats();
        if (!allUnread.isEmpty()) {
            long maxTimestamp = 0;
            for (Chat chat : allUnread) {
                if (chat.lastTimestamp > maxTimestamp) {
                    maxTimestamp = chat.lastTimestamp;
                }
            }
            if (controller.startedActivities.get() == 0 && maxTimestamp > controller.lastNotifiedTimestamp.get()) {
                controller.lastNotifiedTimestamp.accumulateAndGet(maxTimestamp, Math::max);
                NotificationHelper.showNotification(controller, allUnread);
            }
        }
    }
}
