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
    private static final String PUBLIC_KEY = "public_key";
    private static final String IS_GROUP = "is_group";
    private static final String IS_HIDDEN = "is_hidden";
    private static final String IS_BLOCKED = "is_blocked";
    private static final String HAS_NEW_MSG = "has_new_msg";
    private static final String INTERLOCUTOR_ID = "interlocutor_id";
    private static final String LAST_MESSAGE = "last_message";
    private static final String LAST_TIMESTAMP = "last_timestamp";
    private static final String TIMESTAMP = "timestamp";
    private static final String CHAT_ID = "chat_id";
    private static final String CHAT_NAME = "chat_name";
    private static final String CHATS = "chats";
    private static final String SENDER_ID = "sender_id";
    private static final String TEXT = "text";
    private static final String TYPE = "type";
    private static final String FILE_PATH = "file_path";
    private static final String FILE_NAME = "file_name";
    private static final String SEND_STATUS = "send_status";
    private static final String MEDIA_STATUS = "media_status";
    private static final String LAST_SEND_STATUS = "last_send_status";
    private static final String MESSAGES = "messages";
    private static final String USERS = "users";
    private static final String ID_ANCHOR = "id = ?";
    private static final String LOCAL_ID_ANCHOR = "local_id = ?";
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
                    "public_key TEXT NOT NULL, " +
                    "display_name TEXT NOT NULL)");

            db.execSQL("CREATE TABLE chats (" +
                    "local_id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    "id INTEGER UNIQUE NOT NULL, " +
                    "name TEXT NOT NULL, " +
                    "is_group INTEGER NOT NULL DEFAULT 0, " +
                    "interlocutor_id INTEGER NOT NULL, " + // для поиска чата локально
                    "last_message TEXT, " +
                    "last_send_status INTEGER DEFAULT 1, " + // 1 - "Sending", 2 - "Sent"
                    "is_hidden INTEGER DEFAULT 0, " + // 0 = visible, 1 = hidden
                    "is_blocked INTEGER DEFAULT 0, " + // 0 = not blocked, 1 = blocked
                    "has_new_msg INTEGER DEFAULT 0, " + // 0 = no, 1 = yes
                    "last_timestamp INTEGER DEFAULT 0)");

            db.execSQL("CREATE TABLE messages (" +
                    "local_id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    "id INTEGER , " + // Серверный ID
                    "chat_id INTEGER NOT NULL, " +
                    "chat_name TEXT NOT NULL, " +
                    "sender_id INTEGER NOT NULL, " +
                    "text TEXT NOT NULL, " +
                    "type TEXT NOT NULL, " +
                    "file_path TEXT, " +
                    "file_name TEXT, " +
                    "media_status INTEGER DEFAULT 0, " +
                    "timestamp INTEGER NOT NULL, " +
                    "send_status INTEGER DEFAULT 1)"); // 1 - "Sending", 2 - "Sent" , 3 = "Waiting"
        } catch (SQLException e) {
            controller.setInitAppError(true);
            Log.d(AppController.LOG_TAG, "Ошибка при инициализации таблиц БД: ", e);
        }
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        //
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
                userValues.put(PUBLIC_KEY, user.publicKey);
                database.insertWithOnConflict(USERS, null, userValues, SQLiteDatabase.CONFLICT_REPLACE);
                fillChats(chats);
                database.setTransactionSuccessful();
                callback.onSuccess(result);
                Log.d(AppController.LOG_TAG, "user data and " + (chats != null ? chats.size() : 0) + " chats successfully saved.");
            } catch (Exception e) {
                Log.e(AppController.LOG_TAG, "error while batch saving data: ", e);
                callback.onError("error saving user: " + user.displayName);
            } finally {
                database.endTransaction();
            }
        });
    }

    private void fillChats(List<Chat> chats) {
        if (chats != null) {
            for (Chat chat : chats) {
                ContentValues chatValues = new ContentValues();
                chatValues.put(ID, chat.id);
                chatValues.put(NAME, chat.name);
                chatValues.put(IS_GROUP, chat.isGroup ? 1 : 0);
                chatValues.put(INTERLOCUTOR_ID, chat.interlocutorId);
                //  chatValues.put(LAST_MESSAGE, chat.lastMessage);
                //   chatValues.put(LAST_MESSAGE, AppController.EMPTY_STRING);
                chatValues.put(IS_HIDDEN, chat.isHidden ? 1 : 0); // Сохраняем статус видимости
                chatValues.put(IS_BLOCKED, chat.isBlocked ? 1 : 0); // Сохраняем статус блокировки
                chatValues.put(LAST_TIMESTAMP, chat.lastTimestamp);
                database.insertWithOnConflict(CHATS, null, chatValues, SQLiteDatabase.CONFLICT_REPLACE);
            }
        } else {
            Log.d(AppController.LOG_TAG, "fillChats = null ");
        }
    }

    public void updateUserDisplayName(String newName) {
        dbExecutor.execute(() -> {
            try {
                ContentValues values = new ContentValues();
                values.put(DISPLAY_NAME, newName);
               if (database.update(USERS, values, ID_ANCHOR, new String[]{String.valueOf(controller.getUserId())}) > 0) {
                   Log.d(AppController.LOG_TAG, "change name " + newName + " success");
                   return;
               }
            } catch (Exception e) {
                Log.e(AppController.LOG_TAG, "error change name: ", e);
            }
            Log.e(AppController.LOG_TAG, "error change name");
        });
    }

    public void getInterlocutorPublicKey(long chatId, ResultCallback<String> callback) {
        dbExecutor.execute(() -> {
            try (Cursor cursor = database.rawQuery(
                    "SELECT u.public_key FROM users u JOIN chats c ON u.id = c.interlocutor_id WHERE c.id = ? LIMIT 1",
                    new String[]{String.valueOf(chatId)})) {
                if (cursor.moveToFirst()) {
                    callback.onSuccess(cursor.getString(0));
                } else {
                    callback.onError("chat or interlocutor not found locally for id: " + chatId);
                }
            } catch (Exception e) {
                Log.e(AppController.LOG_TAG, "Error getting public key from DB", e);
                callback.onError("database error: " + e.getMessage());
            }
        });
    }

    public void getChatIdByUsername(String interlocutorUsername, ResultCallback<Chat> callback) {
        dbExecutor.execute(() -> {
            database.beginTransaction();
            try {
                Chat foundChat = null;
                // 1. В SELECT добавляем через запятую нужные поля: c.id и c.name
                try (Cursor cursor = database.rawQuery(
                        "SELECT c.local_id, c.id, c.name, c.is_group, c.interlocutor_id, " +
                                "c.last_message, c.last_send_status, c.is_hidden, c.is_blocked, c.has_new_msg, c.last_timestamp " +
                                "FROM chats c JOIN users u ON c.interlocutor_id = u.id WHERE u.username = ? LIMIT 1",
                        new String[]{interlocutorUsername})) {
                    if (cursor.moveToFirst()) {
                        foundChat = new Chat();
                        foundChat.localId = cursor.getLong(0);
                        foundChat.id = cursor.getLong(1);
                        foundChat.name = cursor.getString(2);
                        foundChat.isGroup = cursor.getInt(3) == 1;
                        foundChat.interlocutorId = cursor.getLong(4);
                        foundChat.lastMessage = cursor.getString(5);
                        foundChat.lastSendStatus = cursor.getLong(6);
                        foundChat.isHidden = cursor.getInt(7) == 1;
                        foundChat.isBlocked = cursor.getInt(8) == 1;
                        foundChat.hasNewMsg = cursor.getInt(9) == 1;
                        foundChat.lastTimestamp = cursor.getLong(10);
                        foundChat.lastTimestampFormatted = AppController.formatSmartTime(controller, foundChat.lastTimestamp);
                    }
                }
                if (foundChat != null) {
                    ContentValues values = new ContentValues();
                    values.put(IS_HIDDEN, 0);
                    database.update(CHATS, values, ID_ANCHOR, new String[]{String.valueOf(foundChat.id)});
                    database.setTransactionSuccessful();
                    callback.onSuccess(foundChat);
                } else {
                    database.setTransactionSuccessful();
                    callback.onError(interlocutorUsername);
                }
            } catch (Exception e) {
                Log.d(AppController.LOG_TAG, "error retrieving/updating chat for " + interlocutorUsername, e);
                callback.onError(interlocutorUsername);
            } finally {
                database.endTransaction();
            }
        });
    }

    public <T> void saveChat(Chat chat, ResultCallback<T> callback, T result) {
        dbExecutor.execute(() -> {
            try {
                ContentValues v = new ContentValues();
                v.put(ID, chat.id);
                v.put(NAME, chat.name);
                v.put(INTERLOCUTOR_ID, chat.interlocutorId);
                if (database.insertWithOnConflict(CHATS, null, v, SQLiteDatabase.CONFLICT_REPLACE) != -1) {
                    callback.onSuccess(result);
                    return;
                }
            } catch (Exception e) {
                Log.d(AppController.LOG_TAG, "error save new chat");
            }
            callback.onError("error save new chat");
        });
    }

    public void renameChat(long chatId, String newName, ResultCallback<String> callback) {
        dbExecutor.execute(() -> {
            database.beginTransaction();
            try {
                // 1. Обновляем имя в самой таблице чатов
                ContentValues chatValues = new ContentValues();
                chatValues.put(NAME, newName);
                int updatedChats = database.update(CHATS, chatValues, ID_ANCHOR, new String[]{String.valueOf(chatId)});
                if (updatedChats > 0) {
                    // 2. Обновляем chat_name во всех сообщениях этого чата.
                    // Это нужно, чтобы при входе в чат синхронизация подхватила новое имя.
                    ContentValues msgValues = new ContentValues();
                    msgValues.put(CHAT_NAME, newName);
                    database.update(MESSAGES, msgValues, "chat_id = ?", new String[]{String.valueOf(chatId)});
                    database.setTransactionSuccessful();
                    callback.onSuccess(newName);
                    Log.d(AppController.LOG_TAG, "Чат " + chatId + " переименован в: " + newName);
                } else {
                    callback.onError("error renaming chat: not found");
                }
            } catch (Exception e) {
                Log.e(AppController.LOG_TAG, "Ошибка при транзакции переименования", e);
                callback.onError("database error during rename");
            } finally {
                database.endTransaction();
            }
        });
    }

    public void hideChatLocally(long chatId, ResultCallback<Boolean> callback) {
        dbExecutor.execute(() -> {
            try {
                ContentValues values = new ContentValues();
                values.put(IS_HIDDEN, 1); // 1 - "Hidden"
                if (database.update(CHATS, values, ID_ANCHOR, new String[]{String.valueOf(chatId)}) > 0) {
                    callback.onSuccess(true);
                    return;
                }
            } catch (Exception e) {
                callback.onError("chat hiding error " + e);
            }
            callback.onError("chat hiding error");
        });
    }

    public void setChatBlockedState(long chatId, ResultCallback<Boolean> callback) {
        dbExecutor.execute(() -> {
            try {
                ContentValues v = new ContentValues();
                v.put(IS_BLOCKED, 1);
                if (database.update(CHATS, v, ID_ANCHOR, new String[]{String.valueOf(chatId)}) > 0) {
                    callback.onSuccess(true);
                    return;
                }
            } catch (Exception e) {
                callback.onError("chat blocking error " + e);
            }
            callback.onError("chat blocking error");
        });
    }

    public void getChatList(ResultCallback<List<Chat>> callback) {
        dbExecutor.execute(() -> {
            List<Chat> chats = new ArrayList<>();
            try (Cursor cursor = database.rawQuery(
                    "SELECT local_id, id, name, is_group, interlocutor_id, " +
                            "last_message, last_send_status, is_blocked, has_new_msg, last_timestamp " +
                            "FROM chats WHERE is_hidden = 0 ORDER BY has_new_msg DESC, last_timestamp DESC", null)) {
                if (cursor.moveToFirst()) {
                    do {
                        Chat chat = new Chat();
                        chat.localId = cursor.getLong(0);
                        chat.id = cursor.getLong(1);
                        chat.name = cursor.getString(2);
                        chat.isGroup = cursor.getInt(3) == 1;
                        chat.interlocutorId = cursor.getLong(4);
                        chat.lastMessage = cursor.getString(5);
                        chat.lastSendStatus = cursor.getLong(6);
                        chat.isBlocked = cursor.getInt(7) == 1;
                        chat.hasNewMsg = cursor.getInt(8) == 1;
                        chat.lastTimestamp = cursor.getLong(9);
                        chat.lastTimestampFormatted = AppController.formatSmartTime(controller, chat.lastTimestamp);
                        chats.add(chat);
                    } while (cursor.moveToNext());
                }
                callback.onSuccess(chats);
            } catch (Exception e) {
                Log.e(AppController.LOG_TAG, "Ошибка при загрузке списка чатов: ", e);
                callback.onError("Ошибка загрузки чатов из базы");
            }
        });
    }

    public void getUnreadChats(ResultCallback<List<Chat>> callback) {
        dbExecutor.execute(() -> {
            List<Chat> unreadChats = new ArrayList<>();
            try (Cursor cursor = database.rawQuery("SELECT * FROM chats WHERE has_new_msg = 1", null)) {
                if (cursor.moveToFirst()) {
                    do {
                        Chat chat = new Chat();
                        chat.id = cursor.getLong(0);
                        chat.localId = cursor.getLong(1);
                        chat.name = cursor.getString(2);
                        unreadChats.add(chat);
                    } while (cursor.moveToNext());
                }
                callback.onSuccess(unreadChats);
            } catch (Exception e) {
                callback.onError("Error getting unread chats: " + e.getMessage());
            }
        });
    }

    public void loadChatMessages(long chatId, ResultCallback<List<Message>> callback) {
        dbExecutor.execute(() -> {
            List<Message> messages = new ArrayList<>();
            try (Cursor cursor = database.rawQuery(
                    "SELECT local_id, id, chat_id, chat_name, sender_id, " +
                            "text, type, file_path, file_name, timestamp, send_status " +
                            "FROM messages WHERE chat_id = ? ORDER BY local_id ASC", new String[]{String.valueOf(chatId)})) {
                if (cursor.moveToFirst()) {
                    do {
                        Message msg = new Message();
                        msg.localId = cursor.getLong(0);
                        msg.id = cursor.getLong(1);
                        msg.chatId = cursor.getLong(2);
                        msg.chatName = cursor.getString(3);
                        msg.senderId = cursor.getLong(4);
                        msg.text = cursor.getString(5);
                        msg.type = cursor.getString(6);
                        msg.filePath = cursor.getString(7);
                        msg.fileName = cursor.getString(8);
                        msg.timestamp = cursor.getLong(9);
                        msg.sendStatus = cursor.getLong(10);
                        msg.formattedTime = AppController.formatSmartTime(controller, msg.timestamp);
                        messages.add(msg);
                    } while (cursor.moveToNext());
                }
                callback.onSuccess(messages);
            } catch (Exception e) {
                Log.d(AppController.LOG_TAG, "error loading message history " + chatId + ": ", e);
                callback.onError("error loading message history");
            }
        });

    }

    public void markChatAsRead(long chatId) {
        dbExecutor.execute(() -> {
            try {
                ContentValues values = new ContentValues();
                values.put(HAS_NEW_MSG, 0);
                if (database.update(CHATS, values, ID_ANCHOR, new String[]{String.valueOf(chatId)}) > 0) {
                    Log.d(AppController.LOG_TAG, "chat mark as read " + chatId);
                    return;
                }
            } catch (Exception e) {
                Log.d(AppController.LOG_TAG, "error mark chat as read " + chatId + ": ", e);
            }
            Log.d(AppController.LOG_TAG, "error mark chat as read " + chatId);
        });
    }

    public void saveMsgBeforeSending(Message msg) {
        dbExecutor.execute(() -> {
            try {
                ContentValues values = new ContentValues();
                values.put(CHAT_ID, msg.chatId);
                values.put(CHAT_NAME, msg.chatName);
                values.put(SENDER_ID, msg.senderId);
                values.put(TEXT, msg.text);
                values.put(TYPE, msg.type);
                values.put(FILE_PATH, msg.filePath);
                values.put(FILE_NAME, msg.fileName);
                values.put(TIMESTAMP, msg.timestamp);
                msg.localId = database.insert(MESSAGES, null, values);
                Log.d(AppController.LOG_TAG, "Сохранено сообщение c id: " + msg.id + " для чата "
                        + msg.chatId + " c локальным id: " + msg.localId);
                // 2. СРАЗУ ОБНОВЛЯЕМ ЧАТ
                // Если сообщение сохранилось успешно (msg.localId != -1)
                if (msg.localId != Chat.INVALID_ID) {
                    ContentValues chatValues = new ContentValues();
                    chatValues.put(LAST_MESSAGE, msg.text);
                    chatValues.put(LAST_TIMESTAMP, msg.timestamp);
                    chatValues.put(IS_HIDDEN, 0);
                    chatValues.put(LAST_SEND_STATUS, Message.STATUS_SENDING_OR_RECEIVE);
                    database.update(CHATS, chatValues, ID_ANCHOR, new String[]{String.valueOf(msg.chatId)});
                }
            } catch (Exception e) {
                Log.d(AppController.LOG_TAG, "error save msg before sending " + msg.chatId, e);
            }
        });
    }

    public void saveIncomingMsgList(List<Message> messages) {
        dbExecutor.execute(() -> {
            if (messages != null && !messages.isEmpty()) {
                database.beginTransaction();
                try {
                    for (Message msg : messages) {
                        try (Cursor c = database.rawQuery("SELECT 1 FROM messages WHERE id = ? LIMIT 1", new String[]{String.valueOf(msg.id)})) {
                            if (c.moveToFirst()) continue;
                        }
                        msg.localId = database.insertWithOnConflict(MESSAGES, null, getMsgValues(msg), SQLiteDatabase.CONFLICT_REPLACE);
                        ContentValues chatValues = new ContentValues();
                        chatValues.put(LAST_MESSAGE, msg.text);
                        chatValues.put(LAST_TIMESTAMP, msg.timestamp);
                        chatValues.put(IS_HIDDEN, 0);

                        if (msg.senderId != controller.getUserId()) {
                            chatValues.put(INTERLOCUTOR_ID, msg.senderId);
                            chatValues.put(HAS_NEW_MSG, 1);
                        } else {
                            chatValues.put(INTERLOCUTOR_ID, msg.receiverId);
                            chatValues.put(NAME, msg.chatName); // Синхронизируем имя чата из сообщения
                            chatValues.put(LAST_SEND_STATUS, Message.STATUS_SENT);
                        }
                        // 4. Пытаемся обновить существующий чат
                        if (database.update(CHATS, chatValues, ID_ANCHOR, new String[]{String.valueOf(msg.chatId)}) == 0) {
                            chatValues.put(ID, msg.chatId);
                            chatValues.put(NAME, msg.chatName); // Получаем имя чата из сообщения
                            // 5. Если чат не найден создаем его
                            database.insert(CHATS, null, chatValues);
                            Log.d(AppController.LOG_TAG, "Создан новый чат при синхронизации: " + msg.chatName);
                            addInterlocutorToUsers(msg);
                        }
                    }
                    database.setTransactionSuccessful();
                } catch (Exception e) {
                    Log.d(AppController.LOG_TAG, "Error syncing messages: " + e.getMessage());
                } finally {
                    database.endTransaction();
                }
            }
            loadMedia();
        });
    }

    @NonNull
    private ContentValues getMsgValues(Message msg) {
        ContentValues values = new ContentValues();
        values.put(ID, msg.id);             // Серверный ID
        values.put(CHAT_ID, msg.chatId);
        values.put(CHAT_NAME, msg.chatName);
        values.put(SENDER_ID, msg.senderId);
        values.put(TEXT, msg.text);
        values.put(TYPE, msg.type);
        values.put(FILE_PATH, msg.filePath);
        values.put(FILE_NAME, msg.fileName);
        values.put(TIMESTAMP, msg.timestamp);
        if (msg.senderId == controller.getUserId()) {
            values.put(SEND_STATUS, Message.STATUS_SENT);
        } else {
            if (msg.hasServerPath()) {
                Log.d(AppController.LOG_TAG, "в msg есть путь к серверному файлу, метим для загрузки " + msg.filePath);
                values.put(MEDIA_STATUS, Message.MEDIA_STATUS_PENDING);
            }
        }
        Log.w(AppController.LOG_TAG, "сохранено сообщение: serverId " + msg.id);
        return values;
    }

    public void updateFilePath(Message msg, String filePath) {
        dbExecutor.execute(() -> {
            try {
                ContentValues v = new ContentValues();
                v.put(FILE_PATH, filePath);
                v.put(MEDIA_STATUS, Message.MEDIA_STATUS_DOWNLOADED);
                if (database.update(MESSAGES, v, LOCAL_ID_ANCHOR, new String[]{String.valueOf(msg.localId)}) > 0) {
                    Log.d(AppController.LOG_TAG, "update file path success: " + filePath);
                    return;
                }
            } catch (Exception e) {
                Log.d(AppController.LOG_TAG, "error update file path " + filePath, e);
            }
            Log.d(AppController.LOG_TAG, "error update file path " + filePath);
        });
    }

    public void setMediaStatusError(Message msg) {
        dbExecutor.execute(() -> {
            try {
                ContentValues v = new ContentValues();
                v.put(MEDIA_STATUS, Message.MEDIA_STATUS_ERROR);
                if (database.update(MESSAGES, v, LOCAL_ID_ANCHOR, new String[]{String.valueOf(msg.localId)}) > 0) {
                    return;
                }
            } catch (Exception e) {
                Log.d(AppController.LOG_TAG, "set error status media error ", e);
            }
            Log.d(AppController.LOG_TAG, "set error status media error");
        });
    }

    public void confirmMessageSent(Message msg) {
        dbExecutor.execute(() -> {
            try {
                ContentValues v = new ContentValues();
                v.put(ID, msg.id); // Теперь у сообщения есть серверный ID
                v.put(TIMESTAMP, msg.timestamp); // Используем время сервера
                v.put(SEND_STATUS, Message.STATUS_SENT);

                ContentValues chatValues = new ContentValues();
                chatValues.put(LAST_SEND_STATUS, Message.STATUS_SENT);
                chatValues.put(IS_BLOCKED, 0);
                if (database.update(MESSAGES, v, LOCAL_ID_ANCHOR, new String[]{String.valueOf(msg.localId)}) > 0
                        && database.update(CHATS, chatValues, ID_ANCHOR, new String[]{String.valueOf(msg.chatId)}) > 0) {
                    Log.d(AppController.LOG_TAG, "confirm msg send, id " + msg.id + " chat id " + msg.chatId);
                    return;
                }
            } catch (Exception e) {
                Log.d(AppController.LOG_TAG, "error confirm msg send ", e);
            }
            Log.d(AppController.LOG_TAG, "error confirm msg send" + msg.id);
        });
    }

    public void notConfirmMessageSent(Message msg) {
        dbExecutor.execute(() -> {
            try {
                ContentValues v = new ContentValues();
                v.put(SEND_STATUS, Message.STATUS_WAITING);
                if (database.update(MESSAGES, v, LOCAL_ID_ANCHOR, new String[]{String.valueOf(msg.localId)}) > 0) {
                    Log.d(AppController.LOG_TAG, "not confirm msg send, local id " + msg.localId);
                    return;
                }
            } catch (Exception e) {
                Log.d(AppController.LOG_TAG, "error not confirm msg send, local id " + msg.localId, e);
            }
            Log.d(AppController.LOG_TAG, "error not confirm msg send, local id " + msg.localId);
        });
    }

    public void getLastDbMessageId(ResultCallback<Long> callback) {
        dbExecutor.execute(() -> {
            long lastServerId = 0;
            try (Cursor cursor = database.rawQuery("SELECT MAX(id) FROM messages", null)) {
                if (cursor.moveToFirst()) {
                    lastServerId = cursor.getLong(0);
                 //   Log.w(AppController.LOG_TAG, "последнее полученное имеет id: " + lastServerId);
                } else {
                    Log.w(AppController.LOG_TAG, "не найдено последнего полученного сообщения");
                }
                callback.onSuccess(lastServerId);
            } catch (Exception e) {
                callback.onError("error getting last incoming timestamp");
            }
            Log.w(AppController.LOG_TAG, "last msg id in db: " + lastServerId);
        });
    }

    public void getPendingMessages(ResultCallback<List<Message>> callback) {
        dbExecutor.execute(() -> {
            List<Message> messages = new ArrayList<>();
            List<Long> idsToUpdate = new ArrayList<>();
            database.beginTransaction();
            try {
                try (Cursor cursor = database.rawQuery(
                        "SELECT m.local_id, m.chat_id, m.chat_name, m.sender_id, " +
                                "m.text, m.type, m.file_path, m.file_name, " +
                                "m.timestamp, c.local_id, u.public_key " +
                                "FROM messages m " +
                                "JOIN chats c ON m.chat_id = c.id " +
                                "JOIN users u ON u.id = c.interlocutor_id " +
                                "WHERE m.send_status = 3 " +
                                "ORDER BY m.timestamp ASC LIMIT " + AppController.QUEUE_SIZE, null)) {
                    if (cursor.moveToFirst()) {
                        do {
                            Message msg = new Message();
                            msg.localId = cursor.getLong(0);
                            msg.chatId = cursor.getLong(1);
                            msg.chatName = cursor.getString(2);
                            msg.senderId = cursor.getLong(3);
                            msg.text = cursor.getString(4);
                            msg.type = cursor.getString(5);
                            msg.filePath = cursor.getString(6);
                            msg.fileName = cursor.getString(7);
                            msg.timestamp = cursor.getLong(8);
                            msg.localChatId = cursor.getLong(9);
                            msg.interlocutorPublicKey = cursor.getString(10);
                            messages.add(msg);
                            idsToUpdate.add(msg.localId);
                        } while (cursor.moveToNext());
                    }
                }
                if (!idsToUpdate.isEmpty()) {
                    ContentValues v = new ContentValues();
                    v.put(SEND_STATUS, Message.STATUS_SENDING_OR_RECEIVE);

                    for (Long lid : idsToUpdate) {
                        Log.w(AppController.LOG_TAG, "сообщение id статус 3 отправлено на до отправку " + lid);
                        database.update(MESSAGES, v, LOCAL_ID_ANCHOR, new String[]{String.valueOf(lid)});
                    }
                }
                database.setTransactionSuccessful();
            } catch (Exception e) {
                Log.e(AppController.LOG_TAG, "Error in getPendingMessages transaction", e);
            } finally {
                database.endTransaction();
            }
            callback.onSuccess(messages);
        });
    }

    private void loadMedia() {
        Log.d(AppController.LOG_TAG, "зашли в loadmedia, задачи загрузок " + controller.activeDownloadsCount.get());
        try (Cursor cursor = database.rawQuery(
                "SELECT m.local_id, m.chat_id, m.file_path, m.file_name, u.public_key " +
                        "FROM messages m " +
                        "JOIN chats c ON c.id = m.chat_id " +
                        "JOIN users u ON u.id = c.interlocutor_id " +
                        "WHERE m.media_status = 1", null)) {
            if (cursor.moveToFirst()) {
                do {
                    Message msg = new Message();
                    msg.localId = cursor.getLong(0);
                    msg.chatId = cursor.getLong(1);
                    msg.filePath = cursor.getString(2);
                    msg.fileName = cursor.getString(3);
                    msg.interlocutorPublicKey = cursor.getString(4);
                    controller.activeDownloadsCount.incrementAndGet();
                    controller.getNetStreams()[AppController.POOL_SIZE - 2].execute(() ->
                            controller.getNetworkService().downloadMedia(msg)); // Пнули загрузку!
                    Log.d(AppController.LOG_TAG, "пнули загрузку для файла " + msg.filePath);
                } while (cursor.moveToNext());
            }
        } catch (Exception e) {
            Log.d(AppController.LOG_TAG, "error loading media message for download");
        }
        controller.activeDownloadsCount.decrementAndGet(); // конец задачи загрузки новых сообщений
    }

    private void addInterlocutorToUsers(Message msg) {
        controller.getNetStreams()[AppController.POOL_SIZE - 1].execute(() ->
                controller.getNetworkService().searchInterlocutor(controller.getUsername(), controller.getPassword(), null,
                        msg.chatId, new ResultCallback<>() {
                            @Override
                            public void onSuccess(User result) {
                                Log.d(AppController.LOG_TAG, "The interlocutor's data has been added to the user table.");
                            }

                            @Override
                            public void onError(String errorMsg) {
                                Log.d(AppController.LOG_TAG, "An error occurred while adding the interlocutor's data to the users table.");
                            }
                        }));
    }

}
