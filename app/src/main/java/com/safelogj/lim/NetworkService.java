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
import java.util.concurrent.locks.ReentrantLock;

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
    @NonNull
    private final ReentrantLock digestLock = new ReentrantLock();
    private final OkHttpClient client;
    private final Gson gson = new Gson();
    private final AppController controller;
    private final DatabaseHelper dbHelper;
    private MessageDigest mDigest;

    public NetworkService(AppController controller) {
        this.controller = controller;
        client = controller.getOkHttpClient();
        dbHelper = controller.getDbHelper();
        try {
            mDigest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            controller.setInitAppError(true);
            Log.w(AppController.LOG_TAG, "Ошибка инициализации MessageDigest");
        }
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

                controller.setUserId(user.id);
                controller.setUsername(username);
                controller.setPassword(password);
                controller.setDisplayName(displayName);
                controller.setE2eePublicKey(user.publicKey);
                controller.unpackPrivateKey(res.privateHash(), password);
                dbHelper.saveUser(user, callback, res.message(), res.chats());
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
                dbHelper.saveUser(user, callback, user, null);
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
            Log.e(AppController.LOG_TAG, "Cannot encrypt: recipient public key not found");
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
            return;
        }
        try (Response response = client.newCall(request).execute()) {
            BaseResponse res = gson.fromJson(response.body().string(), BaseResponse.class);
            if (response.isSuccessful() && BaseResponse.SUCCESS.equals(res.status())) {
                    msg.id = res.messageId();
                    msg.timestamp = res.timestamp();
                    if (msg.localId == Chat.INVALID_ID) {
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
            if (fileSize > FILE_SIZE_LIMIT) {
                Log.w(AppController.LOG_TAG, controller.getResources().getString(R.string.big_file_error));
                return;
            }
        } catch (Exception e) {
            Log.w(AppController.LOG_TAG, REQUEST_BUILD_ERROR + e.getMessage());
            return;
        }
        // 1. Получаем публичный ключ собеседника (он должен быть подгружен в ChatFragment)
        // Если его нет, мы не сможем зашифровать.
        // ВАЖНО: Тебе нужно добавить interlocutorPublicKey в объект Message или передавать отдельно.
        // Предположим, мы берем его из базы или кэша.
        if (msg.interlocutorPublicKey == null) {
            Log.e(AppController.LOG_TAG, "Cannot encrypt: recipient public key not found");
            dbHelper.notConfirmMessageSent(msg);
            return;
        }

        try (InputStream inputStream = controller.getContentResolver().openInputStream(uri)) {
            if (inputStream == null) return;

            // 2. Генерируем IV и шифруем заголовки
            byte[] iv = new byte[12];
            controller.getSecureRandom().nextBytes(iv);

            String encText = controller.encryptMessage(msg.text, msg.interlocutorPublicKey);
            String encFileName = controller.encryptMessage(msg.fileName, msg.interlocutorPublicKey);

            // 3. Создаем RequestBody, который пишет IV, а потом шифрованные данные
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
                        Cipher cipher = controller.getFileEncryptCipher(msg.interlocutorPublicKey, iv);
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
            Log.w(AppController.LOG_TAG, "Media send error: " + e.getMessage());
        }
        dbHelper.notConfirmMessageSent(msg);
    }

    public void getNewMessages(long lastMessageId) {
        Request request;
        try {
            RequestBody body = RequestBody.create(gson.toJson(new GetMessagesRequest(controller.getUsername(),
                    hashPassword(controller.getPassword()), lastMessageId)), MediaType.parse(MEDIA_TYPE_JSON));
            request = new Request.Builder().url(controller.getServerUrl() + "/messages/get").post(body).build();
        } catch (Exception e) {
            Log.d(AppController.LOG_TAG, REQUEST_BUILD_ERROR + e.getMessage());
            controller.activeDownloadsCount.decrementAndGet();
            return;
        }
        Log.d(AppController.LOG_TAG, "ищем новые сообщения после id " + lastMessageId);
        try (Response response = client.newCall(request).execute()) {
            BaseResponse res = gson.fromJson(response.body().string(), BaseResponse.class);
            if (response.isSuccessful() && BaseResponse.SUCCESS.equals(res.status())) {
                    for (Message msg : res.messages()) {
                        msg.text = controller.decryptMessage(msg.text, msg.interlocutorPublicKey);
                        if (msg.fileName != null && !msg.fileName.isEmpty()) {
                            msg.fileName = controller.decryptMessage(msg.fileName, msg.interlocutorPublicKey);
                        }
                    }
                    dbHelper.saveIncomingMsgList(res.messages());
                    Log.i(AppController.LOG_TAG, res.message());
                    return;

            } else {
                Log.w(AppController.LOG_TAG, SERVER_RETURNED_ERROR + res.message());
            }
        } catch (Exception e) {
            Log.d(AppController.LOG_TAG, NETWORK_SERVICE_ERROR + e.getMessage());
        }
        controller.activeDownloadsCount.decrementAndGet();
    }

    public void downloadMedia(Message msg) {
        if (msg.interlocutorPublicKey == null) {
            Log.d(AppController.LOG_TAG, "download media null: " + msg.fileName);
            controller.activeDownloadsCount.decrementAndGet();
            return;
        }
        Log.d(AppController.LOG_TAG, "download media : " + msg.fileName);
        Request request;
        try {
            RequestBody body = RequestBody.create(gson.toJson(new MediaDownloadRequest(controller.getUsername(),
                    hashPassword(controller.getPassword()), msg.chatId, msg.filePath)), MediaType.parse(MEDIA_TYPE_JSON));
            request = new Request.Builder().url(controller.getServerUrl() + "/media/get").post(body).build();
        } catch (Exception e) {
            controller.activeDownloadsCount.decrementAndGet();
            return;
        }
        try (Response response = client.newCall(request).execute()) {
            if (response.isSuccessful()) {
                InputStream is = response.body().byteStream();
                // 1. Читаем IV из начала потока
                byte[] iv = new byte[12];
                if (is.read(iv) != 12) throw new IOException("Missing IV header");
                // 2. Настраиваем CipherInputStream
                CipherInputStream cis = new CipherInputStream(is, controller.getFileDecryptCipher(msg.interlocutorPublicKey, iv));
                File localFile = new File(controller.getExternalFileDir(), msg.fileName);
                try (FileOutputStream fos = new FileOutputStream(localFile)) {
                    byte[] buffer = new byte[8192];
                    int read;
                    while ((read = cis.read(buffer)) != -1) {
                        fos.write(buffer, 0, read);
                    }
                }
                dbHelper.updateFilePath(msg, Uri.fromFile(localFile).toString());
                Log.d(AppController.LOG_TAG, "download file success: " + msg.fileName);
            } else {
                dbHelper.setMediaStatusError(msg);
                Log.d(AppController.LOG_TAG, SERVER_RETURNED_ERROR + response.message());
            }
        } catch (Exception e) {
            Log.e(AppController.LOG_TAG, "Download error: " + e.getMessage());
        }
        controller.activeDownloadsCount.decrementAndGet();
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
        byte[] hash;
        digestLock.lock();
        try {
            mDigest.reset();
            hash = mDigest.digest(clientPasswordHash.getBytes(StandardCharsets.UTF_8));
        } finally {
            digestLock.unlock();
        }
        if (hash.length == 0) {
            Log.d(AppController.LOG_TAG, "Сбой при вычислении хэша пароля MD5/SHA:");
            return AppController.EMPTY_STRING;
        }
        return bytesToHex(hash);
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

    public static String encodeToHeader(String text) {
        if (text == null || text.isEmpty()) return AppController.EMPTY_STRING;
        try {
            return URLEncoder.encode(text, "UTF-8");
        } catch (Exception e) {
            return AppController.EMPTY_STRING;
        }
    }
}
