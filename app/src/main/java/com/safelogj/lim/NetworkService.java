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
import com.safelogj.lim.request.EditUserRequest;
import com.safelogj.lim.request.HideChatRequest;
import com.safelogj.lim.request.RegisterRequest;
import com.safelogj.lim.request.SearchUserRequest;
import com.safelogj.lim.request.SendMessageRequest;
import com.safelogj.lim.response.BaseResponse;
import com.safelogj.lim.viewmodels.ResultCallback;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.locks.ReentrantLock;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okio.BufferedSink;


public class NetworkService {

    public static final String TEXT = "TEXT";
    public static final String IMAGE = "IMAGE";
    public static final String FILE = "FILE";
    public static final String SYSTEM = "SYSTEM";
    public static final long FILE_SIZE_LIMIT = 50_000_000L;
    private static final char[] HEX_ARRAY = "0123456789abcdef".toCharArray();
    private static final String SERVER_RETURNED_ERROR = "server returned error: ";
    private static final String NETWORK_SERVICE_ERROR = "network service error: ";
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
        try {
            RequestBody body = RequestBody.create(
                    gson.toJson(new RegisterRequest(username, hashPassword(password), displayName)),
                    MediaType.parse(MEDIA_TYPE_JSON));
            Request request = new Request.Builder().url(controller.getServerUrl() + "/register").post(body).build();

            client.newCall(request).enqueue(new Callback() {
                @Override
                public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                    if (response.isSuccessful()) {
                        BaseResponse res = gson.fromJson(response.body().string(), BaseResponse.class);
                        if (BaseResponse.SUCCESS.equals(res.status()) && res.isValidRegResponse()) {
                            User user = new User();
                            user.id = res.userId();
                            user.username = username;
                            user.displayName = displayName;

                            controller.setUserId(user.id);
                            controller.setUsername(username);
                            controller.setPassword(password);
                            controller.setDisplayName(displayName);
                            dbHelper.saveUser(user, callback, res.message(), res.chats());
                            controller.writeSettingsToFile();
                            Log.i(AppController.LOG_TAG, res.message());
                        } else {
                            sendError(callback, SERVER_RETURNED_ERROR + res.message());
                        }
                    } else {
                        sendError(callback, SERVER_RETURNED_ERROR + response.message());
                    }
                }

                @Override
                public void onFailure(@NonNull Call call, @NonNull IOException e) {
                    sendError(callback, NETWORK_SERVICE_ERROR + e.getMessage());
                }
            });

        } catch (Exception e) {
            sendError(callback, NETWORK_SERVICE_ERROR + e.getMessage());
        }
    }

    public void deleteAccount(String username, String password, ResultCallback<String> callback) {
        try {
            RequestBody body = RequestBody.create(
                    gson.toJson(new EditUserRequest(username, hashPassword(password), null, null)),
                    MediaType.parse(MEDIA_TYPE_JSON));
            Request request = new Request.Builder().url(controller.getServerUrl() + "/user").delete(body).build();

            client.newCall(request).enqueue(new Callback() {
                @Override
                public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                    if (response.isSuccessful()) {
                        BaseResponse res = gson.fromJson(response.body().string(), BaseResponse.class);
                        if (BaseResponse.SUCCESS.equals(res.status())) {
                            controller.setUserId(0);
                            controller.setUsername(AppController.EMPTY_STRING);
                            controller.setPassword(AppController.EMPTY_STRING);
                            controller.setDisplayName(AppController.EMPTY_STRING);
                            dbHelper.wipeAllData(callback, res.message(), res.message());
                            controller.writeSettingsToFile();
                        } else {
                            sendError(callback, SERVER_RETURNED_ERROR + res.message());
                        }
                    } else {
                        sendError(callback, SERVER_RETURNED_ERROR + response.message());
                    }
                }

                @Override
                public void onFailure(@NonNull Call call, @NonNull IOException e) {
                    sendError(callback, NETWORK_SERVICE_ERROR + e.getMessage());
                }
            });

        } catch (Exception e) {
            sendError(callback, NETWORK_SERVICE_ERROR + e.getMessage());
        }
    }

    public void editUser(String username, String password, @Nullable String dName, @Nullable String newPass, ResultCallback<String> callback) {
        try {
            RequestBody body = RequestBody.create(
                    gson.toJson(new EditUserRequest(username, hashPassword(password), dName, (newPass == null ? null : hashPassword(newPass)))),
                    MediaType.parse(MEDIA_TYPE_JSON));
            Request request = new Request.Builder()
                    .url(controller.getServerUrl() + "/user").post(body).build();

            client.newCall(request).enqueue(new Callback() {
                @Override
                public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                    if (response.isSuccessful()) {
                        BaseResponse res = gson.fromJson(response.body().string(), BaseResponse.class);
                        if (BaseResponse.SUCCESS.equals(res.status())) {
                            if (dName != null) {
                                controller.setDisplayName(dName);
                                dbHelper.updateUserDisplayName(controller.getUserId(), dName);
                            }
                            if (newPass != null) {
                                controller.setPassword(newPass);
                            }
                            sendSuccess(callback, res.message(), res.message());
                            controller.writeSettingsToFile();
                        } else {
                            sendError(callback, SERVER_RETURNED_ERROR + res.message());
                        }
                    } else {
                        sendError(callback, SERVER_RETURNED_ERROR + response.message());
                    }
                }

                @Override
                public void onFailure(@NonNull Call call, @NonNull IOException e) {
                    sendError(callback, NETWORK_SERVICE_ERROR + e.getMessage());
                }
            });

        } catch (Exception e) {
            sendError(callback, NETWORK_SERVICE_ERROR + e.getMessage());
        }
    }

    public void searchUser(String username, String password, String queryUsername, ResultCallback<User> callback) {
        try {
            RequestBody body = RequestBody.create(
                    gson.toJson(new SearchUserRequest(username, hashPassword(password), queryUsername)), MediaType.parse(MEDIA_TYPE_JSON));
            Request request = new Request.Builder()
                    .url(controller.getServerUrl() + "/user/search").post(body).build();

            client.newCall(request).enqueue(new Callback() {
                @Override
                public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                    if (response.isSuccessful()) {
                        BaseResponse res = gson.fromJson(response.body().string(), BaseResponse.class);
                        if (BaseResponse.SUCCESS.equals(res.status())) {
                            User user = new User();
                            user.id = res.userId();
                            user.username = queryUsername;
                            user.displayName = res.displayName();
                            dbHelper.saveUser(user, callback, user, null);
                            Log.i(AppController.LOG_TAG, res.message());
                        } else {
                            sendError(callback, SERVER_RETURNED_ERROR + res.message());
                        }
                    } else {
                        sendError(callback, SERVER_RETURNED_ERROR + response.message());
                    }
                }

                @Override
                public void onFailure(@NonNull Call call, @NonNull IOException e) {
                    sendError(callback, NETWORK_SERVICE_ERROR + e.getMessage());
                }
            });
        } catch (Exception e) {
            sendError(callback, NETWORK_SERVICE_ERROR + e.getMessage());
        }
    }

    public void searchChat(User queryUser, ResultCallback<Chat> callback) {
        try {
            RequestBody body = RequestBody.create(
                    gson.toJson(new SendMessageRequest(queryUser.id, queryUser.displayName, TEXT, controller.getUsername(),
                            hashPassword(controller.getPassword()), null, null)), MediaType.parse(MEDIA_TYPE_JSON));
            Request request = new Request.Builder()
                    .url(controller.getServerUrl() + "/messages/send").post(body).build();

            client.newCall(request).enqueue(new Callback() {
                @Override
                public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                    if (response.isSuccessful()) {
                        BaseResponse res = gson.fromJson(response.body().string(), BaseResponse.class);
                        if (BaseResponse.SUCCESS.equals(res.status())) {
                            Chat chat = new Chat();
                            chat.id = res.chatId();
                            chat.name = queryUser.displayName;
                            chat.isGroup = false;
                            chat.interlocutorId = queryUser.id;
                            chat.lastMessage = queryUser.displayName;
                            chat.lastTimestamp = res.timestamp();
                            dbHelper.saveChat(chat);

                            Message msg = new Message();
                            msg.serverId = res.messageId();
                            msg.chatId = res.chatId();
                            msg.senderId = controller.getUserId();
                            msg.text = queryUser.displayName;
                            msg.type = TEXT;
                            msg.timestamp = res.timestamp();

                            dbHelper.saveMessage(msg);
                            dbHelper.confirmMessageSent(msg, callback, chat);
                            Log.i(AppController.LOG_TAG, res.message());
                        } else {
                            sendError(callback, SERVER_RETURNED_ERROR + res.message());
                        }
                    } else {
                        sendError(callback, SERVER_RETURNED_ERROR + response.message());
                    }
                }

                @Override
                public void onFailure(@NonNull Call call, @NonNull IOException e) {
                    sendError(callback, NETWORK_SERVICE_ERROR + e.getMessage());
                }
            });

        } catch (Exception e) {
            sendError(callback, NETWORK_SERVICE_ERROR + e.getMessage());
        }
    }

    public void hideChat(long chatId) {
        try {
            RequestBody body = RequestBody.create(gson.toJson(new HideChatRequest(chatId, controller.getUsername(),
                    hashPassword(controller.getPassword()))), MediaType.parse(MEDIA_TYPE_JSON));
            Request request = new Request.Builder()
                    .url(controller.getServerUrl() + "/chat").post(body).build();

            client.newCall(request).enqueue(new Callback() {

                @Override
                public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                    Log.i(AppController.LOG_TAG, response.message());
                }

                @Override
                public void onFailure(@NonNull Call call, @NonNull IOException e) {
                    Log.i(AppController.LOG_TAG, e.getMessage());
                }
            });

        } catch (Exception e) {
            Log.d(AppController.LOG_TAG, NETWORK_SERVICE_ERROR + e.getMessage());
        }
    }

    public void sendTextMessage(Message msg, ResultCallback<Long> callback) {
        try {
            RequestBody body = RequestBody.create(gson.toJson(new SendMessageRequest(msg.receiverId, msg.text, msg.type,
                    controller.getUsername(), hashPassword(controller.getPassword()), msg.filePath, msg.fileName)), MediaType.parse(MEDIA_TYPE_JSON));
            Request request = new Request.Builder()
                    .url(controller.getServerUrl() + "/messages/send").post(body).build();

            client.newCall(request).enqueue(new Callback() {
                @Override
                public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                    if (response.isSuccessful()) {
                        BaseResponse res = gson.fromJson(response.body().string(), BaseResponse.class);
                        if (BaseResponse.SUCCESS.equals(res.status())) {
                            msg.chatId = res.chatId(); // проверить
                            msg.serverId = res.messageId();
                            msg.timestamp = res.timestamp();
                            dbHelper.confirmMessageSent(msg, callback, msg.localId);
                            Log.i(AppController.LOG_TAG, res.message());
                        }
                    } else {
                        sendError(callback, SERVER_RETURNED_ERROR + response.message());
                    }
                }

                @Override
                public void onFailure(@NonNull Call call, @NonNull IOException e) {
                    sendError(callback, NETWORK_SERVICE_ERROR + e.getMessage());
                }
            });
        } catch (Exception e) {
            sendError(callback, NETWORK_SERVICE_ERROR + e.getMessage());
        }
    }


    // Метод для отправки текстового сообщения
    public void sendMediaMessage(Message msg, ResultCallback<Long> callback) {
        if (msg.filePath == null) {
            callback.onError(controller.getResources().getString(R.string.select_file_error));
            return;
        }
        Uri uri = Uri.parse(msg.filePath);
        long fileSize = getFileSize(uri);
        if (fileSize >= FILE_SIZE_LIMIT) {
            callback.onError(controller.getResources().getString(R.string.big_file_error));
            return;
        }

        try {
            // 1. Открываем поток для чтения файла
            InputStream inputStream = controller.getContentResolver().openInputStream(uri);

            // 2. Создаем RequestBody, который читает из стрима
            RequestBody requestBody = createRequestBodyFromStream(inputStream, fileSize);

            // 3. Собираем запрос с заголовками, как ждет ваш сервер
            Request request = new Request.Builder()
                    .url(controller.getServerUrl() + "/media/upload")
                    .header("X-Username", controller.getUsername())
                    .header("X-Password", hashPassword(controller.getPassword()))
                    .header("X-Sender-Id", String.valueOf(msg.senderId))
                    .header("X-Chat-Id", String.valueOf(msg.chatId))
                    .header("X-Message-Text", encodeToHeader(msg.text)) // Чтобы не было проблем с русским
                    .header("X-File-Name", encodeToHeader(msg.fileName))
                    .header("X-Message-Type", msg.type)
                    .post(requestBody)
                    .build();

            client.newCall(request).enqueue(new Callback() {
                @Override
                public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                    if (response.isSuccessful()) {
                        BaseResponse res = gson.fromJson(response.body().string(), BaseResponse.class);
                        if (BaseResponse.SUCCESS.equals(res.status())) {
                            msg.chatId = res.chatId(); // проверить
                            msg.serverId = res.messageId();
                            msg.timestamp = res.timestamp();
                            dbHelper.confirmMessageSent(msg, callback, msg.localId);
                            Log.i(AppController.LOG_TAG, res.message());
                        }
                    } else {
                        Log.d(AppController.LOG_TAG, "Сообщение не отправлено, ответ сервера :" + response.message());
                    }
                }

                @Override
                public void onFailure(@NonNull Call call, @NonNull IOException e) {
                    Log.d(AppController.LOG_TAG, "Сообщение не отправлено :" + e);
                }
            });

        } catch (Exception e) {
            callback.onError("Ошибка при чтении файла: " + e.getMessage());
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

    private RequestBody createRequestBodyFromStream(final InputStream inputStream, final long size) {
        return new RequestBody() {
            @Nullable
            @Override
            public MediaType contentType() {
                return MediaType.parse("application/octet-stream");
            }

            @Override
            public long contentLength() {
                return size;
            }

            @Override
            public void writeTo(@NonNull BufferedSink bufferedSink) throws IOException {
                try (inputStream) {
                    byte[] buffer = new byte[4096];
                    int read;
                    while ((read = inputStream.read(buffer)) != -1) {
                        bufferedSink.write(buffer, 0, read);
                    }
                }
            }
        };
    }

    private String encodeToHeader(String text) {
        if (text == null || text.isEmpty()) return AppController.EMPTY_STRING;
        try {
            return URLEncoder.encode(text, "UTF-8");
        } catch (Exception e) {
            return AppController.EMPTY_STRING;
        }
    }
}
