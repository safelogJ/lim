package com.safelogj.lim;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.SQLException;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;
import android.database.sqlite.SQLiteOpenHelper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.safelogj.lim.model.Chat;
import com.safelogj.lim.model.Message;
import com.safelogj.lim.model.User;
import com.safelogj.lim.viewmodels.ResultCallback;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;

public class DatabaseHelper extends SQLiteOpenHelper {
    private static final String DB_NAME = "lim.db";
    private static final String ID = "id";
    private static final String NAME = "name";
    private static final String USERNAME = "username";
    private static final String DISPLAY_NAME = "display_name";
    private static final String IS_GROUP = "is_group";
    private static final String IS_HIDDEN = "is_hidden";
    private static final String INTERLOCUTOR_ID = "interlocutor_id";
    private static final String LAST_MESSAGE = "last_message";
    private static final String LAST_TIMESTAMP = "last_timestamp";
    private static final String TIMESTAMP = "timestamp";
    private static final String LOCAL_ID = "local_id";
    private static final String SERVER_ID = "server_id";
    private static final String CHAT_ID = "chat_id";
    private static final String CHATS = "chats";
    private static final String SENDER_ID = "sender_id";
    private static final String TEXT = "text";
    private static final String TYPE = "type";
    private static final String FILE_PATH = "file_path";
    private static final String FILE_NAME = "file_name";
    private static final String SEND_STATUS = "send_status";
    private static final String LAST_SEND_STATUS = "last_send_status";
    private static final String MESSAGES = "messages";
    private static final String USERS = "users";
    private static final int DB_VERSION = 1;
    private SQLiteDatabase database;
    private final AppController controller;
    private final ExecutorService dbExecutor;

    public DatabaseHelper(AppController controller) {
        super(controller, DB_NAME, null, DB_VERSION);
        this.controller = controller;
        dbExecutor = controller.getDbExecutor();
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        try {
            db.execSQL("CREATE TABLE users (" +
                    "id INTEGER PRIMARY KEY, " +
                    "username TEXT NOT NULL, " +
                    "display_name TEXT NOT NULL)");

            db.execSQL("CREATE TABLE chats (" +
                    "id INTEGER PRIMARY KEY, " +
                    "name TEXT, " +
                    "is_group INTEGER, " +
                    "interlocutor_id INTEGER, " +
                    "last_message TEXT, " +
                    "last_send_status INTEGER DEFAULT 1, " + // 1 - "Sending", 2 - "Sent"
                    "is_hidden INTEGER NOT NULL DEFAULT 0, " + // 0 = visible, 1 = hidden
                    "last_timestamp INTEGER NOT NULL)");

            db.execSQL("CREATE TABLE messages (" +
                    "local_id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    "server_id INTEGER , " + // Серверный ID
                    "chat_id INTEGER NOT NULL, " +
                    "sender_id INTEGER NOT NULL, " +
                    "text TEXT, " +
                    "type TEXT, " +
                    "file_path TEXT, " +
                    "file_name TEXT, " +
                    "timestamp INTEGER, " +
                    "send_status INTEGER DEFAULT 1)"); // 1 - "Sending", 2 - "Sent"
        } catch (SQLException e) {
            controller.setInitAppError(true);
            Log.d(AppController.LOG_TAG, "Ошибка при инициализации таблиц БД: ", e);
        }
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {

    }

    public void initDatabase() {
        try {
            database = getWritableDatabase();
            Log.d(AppController.LOG_TAG, "База данных SQLite успешно инициализирована. Таблицы проверены.");
        } catch (SQLiteException e) {
            controller.setInitAppError(true);
            Log.d(AppController.LOG_TAG, "Критическая ошибка при инициализации базы данных: ", e);
        }
    }

    public <T> void wipeAllData(ResultCallback<T> callback, T result, String log) {
        dbExecutor.execute(() -> {
            database.beginTransaction();
            try {
                database.delete(MESSAGES, null, null);
                database.delete(CHATS, null, null);
                database.delete(USERS, null, null);
                database.setTransactionSuccessful();
                callback.onSuccess(result);
                Log.i(AppController.LOG_TAG, log);
            } catch (Exception e) {
                callback.onError("error clearing database");
                Log.i(AppController.LOG_TAG, "error clearing database");
            } finally {
                database.endTransaction();
            }
        });
    }

    public <T> void saveUser(User user, ResultCallback<T> callback, T result, @Nullable List<Chat> chats) {
        dbExecutor.execute(() -> { // Начинаем транзакцию для максимальной скорости и надежности
            database.beginTransaction();
            try { // 1. Сохраняем (или обновляем) данные пользователя
                ContentValues userValues = new ContentValues();
                userValues.put(ID, user.id);
                userValues.put(USERNAME, user.username);
                userValues.put(DISPLAY_NAME, user.displayName);
                database.insertWithOnConflict(USERS, null, userValues, SQLiteDatabase.CONFLICT_REPLACE);
                fillChats(chats);
                database.setTransactionSuccessful();
                callback.onSuccess(result);
                Log.d(AppController.LOG_TAG, "user data and" + (chats != null ? chats.size() : 0) + " chats successfully saved.");
            } catch (Exception e) {
                Log.e(AppController.LOG_TAG, "error while batch saving data: ", e);
                callback.onError("error saving user: " + user.displayName);
            } finally {
                database.endTransaction();
            }
        });
    }

    private void fillChats(List<Chat> chats) {
        if (chats != null && !chats.isEmpty()) {
            for (Chat chat : chats) {
                ContentValues chatValues = new ContentValues();
                chatValues.put(ID, chat.id);
                chatValues.put(NAME, chat.name);
                chatValues.put(IS_GROUP, chat.isGroup ? 1 : 0);
                chatValues.put(INTERLOCUTOR_ID, chat.interlocutorId);
                chatValues.put(LAST_MESSAGE, chat.lastMessage);
                chatValues.put(LAST_TIMESTAMP, chat.lastTimestamp);
                chatValues.put(IS_HIDDEN, chat.isHidden ? 1 : 0); // Сохраняем статус видимости
                chatValues.put(LAST_SEND_STATUS, Message.STATUS_SENT);
                database.insertWithOnConflict(CHATS, null, chatValues, SQLiteDatabase.CONFLICT_REPLACE);
            }
        }
    }

    public void updateUserDisplayName(long userId, String newName) {
        dbExecutor.execute(() -> {
            try {
                ContentValues values = new ContentValues();
                values.put(DISPLAY_NAME, newName);
                database.update(USERS, values, "id = ?", new String[]{String.valueOf(userId)});

                if (userId != controller.getUserId()) {
                    ContentValues chatValues = new ContentValues();
                    chatValues.put(NAME, newName);
                    database.update(CHATS, chatValues, "interlocutor_id = ?", new String[]{String.valueOf(userId)});
                }
                Log.d(AppController.LOG_TAG, "Имя пользователя " + newName + " обновлено.");
            } catch (Exception e) {
                Log.e(AppController.LOG_TAG, "Ошибка обновления имени: ", e);
            }
        });
    }

    public void saveChat(Chat chat) {
        dbExecutor.execute(() -> {
            ContentValues v = new ContentValues();
            v.put(ID, chat.id);
            v.put(NAME, chat.name);
            v.put(IS_GROUP, 0); // Пока работаем только с личными чатами
            v.put(INTERLOCUTOR_ID, chat.interlocutorId);
            v.put(LAST_MESSAGE, chat.lastMessage);
            v.put(LAST_TIMESTAMP, chat.lastTimestamp);
            v.put(IS_HIDDEN, 0);
            database.insertWithOnConflict(CHATS, null, v, SQLiteDatabase.CONFLICT_REPLACE);
        });
    }

    public void hideChatLocally(long chatId, ResultCallback<Boolean> callback) {
        dbExecutor.execute(() -> {
            ContentValues values = new ContentValues();
            values.put(IS_HIDDEN, 1); // 1 - "Hidden"
            if (database.update(CHATS, values, "id = ?", new String[]{String.valueOf(chatId)}) > 0) {
                callback.onSuccess(true);
            } else {
                callback.onError("chat hiding error");
            }
        });
    }

    public void getChatList(ResultCallback<List<Chat>> callback) {
        dbExecutor.execute(() -> {
            List<Chat> chats = new ArrayList<>();
            // Сортируем по времени последнего сообщения: самые новые сверху
            try (Cursor cursor = database.rawQuery("SELECT * FROM chats WHERE is_hidden = 0 ORDER BY last_timestamp DESC", null)) {
                if (cursor.moveToFirst()) {
                    do {
                        long lastTimestamp = cursor.getLong(cursor.getColumnIndexOrThrow(LAST_TIMESTAMP));
                        Chat chat = new Chat();
                        chat.id = cursor.getLong(cursor.getColumnIndexOrThrow(ID));
                        chat.name = cursor.getString(cursor.getColumnIndexOrThrow(NAME));
                        chat.interlocutorId = cursor.getLong(cursor.getColumnIndexOrThrow(INTERLOCUTOR_ID));
                        chat.lastMessage = cursor.getString(cursor.getColumnIndexOrThrow(LAST_MESSAGE));
                        chat.lastTimestamp = lastTimestamp; // Используем для времени последнего сообщения
                        chat.lastTimestampFormatted = AppController.formatSmartTime(controller, lastTimestamp);
                        chat.isGroup = cursor.getInt(cursor.getColumnIndexOrThrow(IS_GROUP)) == 1;
                        chats.add(chat);
                    } while (cursor.moveToNext());
                }
                Log.e(AppController.LOG_TAG, "Длина списка чатов: " + chats.size());
                callback.onSuccess(chats);
            } catch (Exception e) {
                Log.e(AppController.LOG_TAG, "Ошибка при загрузке списка чатов: ", e);
                callback.onError("Ошибка загрузки чатов из базы");
            }
        });
    }

    public void loadMessages(long chatId, ResultCallback<List<Message>> callback) {
        dbExecutor.execute(() -> {
            List<Message> messages = new ArrayList<>();
            String query = "SELECT * FROM messages WHERE chat_id = ? ORDER BY timestamp ASC";
            try (Cursor cursor = database.rawQuery(query, new String[]{String.valueOf(chatId)})) {
                if (cursor.moveToFirst()) {
                    do {
                        Message msg = new Message();
                        msg.localId = cursor.getLong(cursor.getColumnIndexOrThrow(LOCAL_ID));
                        msg.chatId = cursor.getLong(cursor.getColumnIndexOrThrow(CHAT_ID));
                        msg.senderId = cursor.getLong(cursor.getColumnIndexOrThrow(SENDER_ID));
                        msg.text = cursor.getString(cursor.getColumnIndexOrThrow(TEXT));
                        msg.timestamp = cursor.getLong(cursor.getColumnIndexOrThrow(TIMESTAMP));
                        msg.type = cursor.getString(cursor.getColumnIndexOrThrow(TYPE));
                        msg.filePath = cursor.getString(cursor.getColumnIndexOrThrow(FILE_PATH));
                        msg.fileName = cursor.getString(cursor.getColumnIndexOrThrow(FILE_NAME));
                        msg.sendStatus = cursor.getLong(cursor.getColumnIndexOrThrow(SEND_STATUS));
                        msg.formattedTime = AppController.formatSmartTime(controller, msg.timestamp);
                        messages.add(msg);
                    } while (cursor.moveToNext());
                }
                callback.onSuccess(messages);
            } catch (Exception e) {
                Log.e(AppController.LOG_TAG, "Ошибка загрузки сообщений для чата " + chatId + ": ", e);
                callback.onError("Ошибка загрузки истории сообщений");
            }
        });

    }

    public void saveMessage(Message msg) {
        dbExecutor.execute(() -> {
            ContentValues values = new ContentValues();
            values.put(CHAT_ID, msg.chatId);
            values.put(SENDER_ID, msg.senderId);
            values.put(TEXT, msg.text);
            values.put(TYPE, msg.type);
            values.put(FILE_PATH, msg.filePath);
            values.put(FILE_NAME, msg.fileName);
            values.put(TIMESTAMP, msg.timestamp);
            msg.localId = database.insert(MESSAGES, null, values);
            // 2. СРАЗУ ОБНОВЛЯЕМ ЧАТ
            // Если сообщение сохранилось успешно (msg.localId != -1)
            if (msg.localId != -1) {
                ContentValues chatValues = new ContentValues();
                chatValues.put(LAST_MESSAGE, (msg.text != null && !msg.text.isEmpty()) ? msg.text : msg.fileName);
                chatValues.put(LAST_TIMESTAMP, msg.timestamp);
                chatValues.put(IS_HIDDEN, 0);
                // Обновляем чат, у которого совпадает ID
                database.update(CHATS, chatValues, "id = ?", new String[]{String.valueOf(msg.chatId)});
            }
        });
    }

    public void saveMessages(List<Message> messages) {
        dbExecutor.execute(() -> {
            if (messages == null || messages.isEmpty()) return;
            database.beginTransaction(); // Начинаем транзакцию для скорости
            try {
                for (Message msg : messages) {
                    database.insertWithOnConflict(MESSAGES, null, getValues(msg), SQLiteDatabase.CONFLICT_REPLACE);
                    ContentValues chatValues = new ContentValues();
                    chatValues.put(LAST_MESSAGE, (msg.text != null && !msg.text.isEmpty()) ? msg.text : msg.fileName);
                    chatValues.put(LAST_TIMESTAMP, msg.timestamp);
                    chatValues.put(IS_HIDDEN, 0);
                    database.update(CHATS, chatValues, "id = ?", new String[]{String.valueOf(msg.chatId)});
                }
                database.setTransactionSuccessful(); // Фиксируем изменения
            } catch (Exception e) {
                Log.e(AppController.LOG_TAG, "Ошибка сохранения сообщений в БД: ", e);
            } finally {
                database.endTransaction();
            }
        });
    }

    @NonNull
    private ContentValues getValues(Message msg) {
        ContentValues values = new ContentValues();
        values.put(LOCAL_ID, msg.localId);
        values.put(SERVER_ID, msg.serverId);             // Серверный ID
        values.put(CHAT_ID, msg.chatId);
        values.put(SENDER_ID, msg.senderId);
        values.put(TEXT, msg.text);
        values.put(TYPE, msg.type);
        values.put(FILE_PATH, msg.filePath);
        values.put(FILE_NAME, msg.fileName);
        values.put(TIMESTAMP, msg.timestamp);
        values.put(SEND_STATUS, Message.STATUS_SENT); // Все, что пришло с сервера — уже отправлено (OK)
        return values;
    }

    public <T> void confirmMessageSent(Message msg, ResultCallback<T> callback, T result) {
        dbExecutor.execute(() -> {
            ContentValues v = new ContentValues();
            v.put(SERVER_ID, msg.serverId); // Теперь у сообщения есть серверный ID
            v.put(TIMESTAMP, msg.timestamp); // Используем время сервера
            v.put(SEND_STATUS, Message.STATUS_SENT); // 2 - "Sent"
            database.update(MESSAGES, v, "local_id = ?", new String[]{String.valueOf(msg.localId)});

            ContentValues chatValues = new ContentValues();
            chatValues.put(LAST_SEND_STATUS, Message.STATUS_SENT);
            database.update(CHATS, chatValues, "id = ?", new String[]{String.valueOf(msg.chatId)});

            callback.onSuccess(result);
        });
    }

    public void updateMessageStatus(long localId, int newStatus) {
        dbExecutor.execute(() -> {
            ContentValues values = new android.content.ContentValues();
            values.put(SEND_STATUS, newStatus);
            database.update(MESSAGES, values, "local_id = ?", new String[]{String.valueOf(localId)});
            // Здесь позже можно добавить сигнал UI: "Эй, обнови иконку сообщения!"
            Log.d(AppController.LOG_TAG, "Status for message " + localId + " updated to " + newStatus);
        });
    }

    public void updateChatLastMessageStatus(long chatId, String text, long timestamp, int status) {
        dbExecutor.execute(() -> {
            ContentValues v = new ContentValues();
            v.put(LAST_MESSAGE, text);
            v.put(LAST_TIMESTAMP, timestamp);
            v.put(LAST_SEND_STATUS, status);
            v.put(IS_HIDDEN, 0);
            // Обновляем чат, у которого совпадает ID
            database.update(CHATS, v, "id = ?", new String[]{String.valueOf(chatId)});
        });
    }

}
