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
import com.safelogj.lim.model.MediaLatch;
import com.safelogj.lim.model.Message;
import com.safelogj.lim.model.User;
import com.safelogj.lim.viewmodels.ResultCallback;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicLong;

public class DatabaseHelper extends SQLiteOpenHelper {
    private static final String DB_NAME = "lim.db";
    private static final String ID = "id";
    private static final String NAME = "name";
    private static final String USERNAME = "username";
    private static final String DISPLAY_NAME = "display_name";
    private static final String PUBLIC_KEY = "public_key";
    private static final String IS_GROUP = "is_group";
    private static final String IS_HIDDEN = "is_hidden";
    private static final String COLOR = "color";
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
    // --- SQL CONSTANTS ---
    private static final String GET_CHATS_FOR_ONLINE_INIT_SQL = "SELECT interlocutor_id, id, color FROM chats WHERE is_hidden = 0";
    private static final String GET_INTERLOCUTOR_KEY_SQL = "SELECT u.public_key FROM users u JOIN chats c ON u.id = c.interlocutor_id WHERE c.id = ? LIMIT 1";
    private static final String GET_CHAT_BY_USERNAME_SQL = "SELECT c.local_id, c.id, c.name, c.is_group, c.interlocutor_id, c.last_message, c.last_send_status, c.color, c.is_blocked, c.has_new_msg, c.last_timestamp FROM chats c JOIN users u ON c.interlocutor_id = u.id WHERE u.username = ? LIMIT 1";
    private static final String GET_CHAT_BY_ID_SQL = "SELECT local_id, id, name, is_group, interlocutor_id, last_message, last_send_status, is_blocked, color, has_new_msg, last_timestamp FROM chats WHERE id = ? LIMIT 1";
    private static final String GET_CHAT_LIST_SQL = "SELECT local_id, id, name, is_group, interlocutor_id, last_message, last_send_status, is_blocked, color, has_new_msg, last_timestamp FROM chats WHERE is_hidden = 0 ORDER BY has_new_msg DESC, last_timestamp DESC";
    private static final String GET_UNREAD_CHATS_SQL = "SELECT local_id, id, name FROM chats WHERE has_new_msg = 1";
    private static final String GET_LOCAL_CHAT_ID_SQL = "SELECT local_id FROM chats WHERE id = ? LIMIT 1";
    private static final String BASE_SELECT_MESSAGES = "SELECT local_id, id, chat_id, chat_name, sender_id, text, type, file_path, file_name, timestamp, send_status FROM messages";
    private static final String LOAD_CHAT_MESSAGES_SQL = BASE_SELECT_MESSAGES + " WHERE chat_id = ? ORDER BY local_id DESC LIMIT ";
    private static final String LOAD_MORE_MESSAGES_SQL = BASE_SELECT_MESSAGES + " WHERE chat_id = ? AND local_id < ? ORDER BY local_id DESC LIMIT 50";
    private static final String CHECK_MESSAGE_EXISTS_SQL = "SELECT 1 FROM messages WHERE id = ? LIMIT 1";
    private static final String GET_LAST_MSG_ID_SQL = "SELECT MAX(id) FROM messages";
    private static final String GET_PENDING_MESSAGES_SQL = "SELECT m.local_id, m.chat_id, m.chat_name, m.sender_id, m.text, m.type, m.file_path, m.file_name, m.timestamp, c.local_id, u.public_key FROM messages m JOIN chats c ON m.chat_id = c.id JOIN users u ON u.id = c.interlocutor_id WHERE m.send_status = 1 AND m.sender_id = ? ORDER BY m.timestamp ASC LIMIT ";
    private static final String GET_MEDIA_DOWNLOAD_LIST_SQL = "SELECT m.id, m.chat_id, m.file_path, m.file_name, u.public_key FROM messages m JOIN chats c ON c.id = m.chat_id JOIN users u ON u.id = c.interlocutor_id WHERE m.media_status = 1";
    private static final String GET_CHAT_NAME_BY_INTERLOCUTOR_SQL = "SELECT name, color FROM chats WHERE interlocutor_id = ? LIMIT 1";
    private static final String GET_BLOCKED_USERS_SQL = "SELECT interlocutor_id FROM chats WHERE is_blocked = 1";
    private static final String GET_ALL_USER_KEYS_SQL = "SELECT id, public_key FROM users";

    private final AtomicLong cachedLastMsgServerId = new AtomicLong(0);
    private final Map<Long, Chat> unreadChatsCache = new HashMap<>();
    private final Map<Long, Message> pendingMessagesCache = new HashMap<>();
    private final Map<Long, Message> pendingMediaCache = new HashMap<>();
    private final Map<Long, List<Message>> chatMessagesCache = new HashMap<>(); // только БД нить
    private final List<Chat> cachedChatList = new ArrayList<>(); // только БД нить
    private final Set<Long> blockedUserIds = ConcurrentHashMap.newKeySet();
    private final Map<Long, String> userKeysCache = new ConcurrentHashMap<>();
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
                    "color INTEGER DEFAULT 0, " + // 0 green
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
            initLastMsgIdCache();
            initUnreadChatCache();
            initPendingMessagesCache();
            initPendingMediaCache();
            initChatCache();
            initBlockedUsersCache();
            initUserKeysCache();
        } catch (SQLiteException e) {
            controller.setInitAppError(true);
            Log.d(AppController.LOG_TAG, "Критическая ошибка при инициализации базы данных: ", e);
        }
    }

    public void initOnlineStatuses() {
        dbExecutor.execute(() -> {
            try (Cursor cursor = database.rawQuery(GET_CHATS_FOR_ONLINE_INIT_SQL, null)) {
                while (cursor.moveToNext()) {
                    long interlocutorId = cursor.getLong(0);
                    long chatId = cursor.getLong(1);
                    int color = cursor.getInt(2);
                    if (interlocutorId != controller.getUserId()) {
                        controller.updateOnlineStatus(interlocutorId, chatId, false);
                        AppController.updateChatColor(chatId, color);
                    }
                }
            } catch (Exception e) {
                Log.e(AppController.LOG_TAG, "Ошибка инициализации карты статусов: ", e);
            }
        });
    }

    public void wipeAllData(ResultCallback<String> callback, String result) {
        dbExecutor.execute(() -> {
            database.beginTransaction();
            try {
                database.delete(MESSAGES, null, null);
                database.delete(CHATS, null, null);
                database.delete(USERS, null, null);
                database.setTransactionSuccessful();
                controller.clearOnlineStatuses();
                userKeysCache.clear();
                AppController.clearChatColors();
                cachedLastMsgServerId.set(0);
                unreadChatsSynchronizedTask(unreadChatsCache::clear);
                controller.notifyUnreadChatChanged(new ArrayList<>(unreadChatsCache.values()));
                NotificationHelper.clearNotification(controller);
                synchronized (pendingMessagesCache) {
                    pendingMessagesCache.clear();
                }
                synchronized (pendingMediaCache) {
                    pendingMediaCache.clear();
                }
                chatMessagesCache.clear();
                controller.notifyMessagesChanged(Chat.INVALID_ID);
                cachedChatList.clear();
                notifyChatsList();
                callback.onSuccess(result);
                Log.i(AppController.LOG_TAG, result);
            } catch (Exception e) {
                callback.onError("error clearing database");
                Log.i(AppController.LOG_TAG, "error clearing database");
            } finally {
                database.endTransaction();
            }
        });
    }

    public <T> void saveUser(User user, ResultCallback<T> callback, T result, @Nullable Long chatId) {
        dbExecutor.execute(() -> {
            database.beginTransaction();
            try {  // Сохраняем пользователя
                ContentValues userValues = new ContentValues();
                userValues.put(ID, user.id);
                userValues.put(USERNAME, user.username);
                userValues.put(DISPLAY_NAME, user.displayName);
                userValues.put(PUBLIC_KEY, user.publicKey);
                database.insertWithOnConflict(USERS, null, userValues, SQLiteDatabase.CONFLICT_REPLACE);
                userKeysCache.put(user.id, user.publicKey);
                // Если известен чат — привязываем к нему собеседника
                if (chatId != null) {
                    ContentValues chatValues = new ContentValues();
                    chatValues.put(INTERLOCUTOR_ID, user.id);
                    if (database.update(CHATS, chatValues, ID_ANCHOR, new String[]{String.valueOf(chatId)}) == 0) {
                        Log.w(AppController.LOG_TAG, "Chat " + chatId + " not found while linking user " + user.id);
                    } else {
                        Chat cached = findChatInCache(chatId);
                        if (cached != null) {
                            cached.interlocutorId = user.id;
                        }
                        controller.updateOnlineStatus(user.id, chatId, false);
                        Log.d(AppController.LOG_TAG, "Saved user id=" + user.id + " username=" + user.username + " display=" + user.displayName);
                    }
                }
                database.setTransactionSuccessful();
                callback.onSuccess(result);
            } catch (Exception e) {
                Log.e(AppController.LOG_TAG, "error while saving user", e);
                callback.onError("error saving user: " + user.displayName);
            } finally {
                database.endTransaction();
            }
        });
    }

    public void updateUserDisplayName(String newName) {
        dbExecutor.execute(() -> {
            try {
                ContentValues values = new ContentValues();
                values.put(DISPLAY_NAME, newName);
                if (database.update(USERS, values, ID_ANCHOR, new String[]{String.valueOf(controller.getUserId())}) > 0) {
                    Log.d(AppController.LOG_TAG, "change name " + newName + " success");
                } else {
                    Log.d(AppController.LOG_TAG, "error change name");
                }
            } catch (Exception e) {
                Log.e(AppController.LOG_TAG, "error change name: ", e);
            }
        });
    }

    public void getInterlocutorPublicKey(long chatId, ResultCallback<String> callback) {
        dbExecutor.execute(() -> {
            try (Cursor cursor = database.rawQuery(GET_INTERLOCUTOR_KEY_SQL, new String[]{String.valueOf(chatId)})) {
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
                try (Cursor cursor = database.rawQuery(GET_CHAT_BY_USERNAME_SQL, new String[]{interlocutorUsername})) {
                    if (cursor.moveToFirst()) {
                        foundChat = new Chat();
                        foundChat.localId = cursor.getLong(0);
                        foundChat.id = cursor.getLong(1);
                        foundChat.name = cursor.getString(2);
                        foundChat.isGroup = cursor.getInt(3) == 1;
                        foundChat.interlocutorId = cursor.getLong(4);
                        foundChat.lastMessage = cursor.getString(5);
                        foundChat.lastSendStatus = cursor.getLong(6);
                        foundChat.color = cursor.getInt(7);
                        foundChat.isBlocked = cursor.getInt(8) == 1;
                        foundChat.hasNewMsg = cursor.getInt(9) == 1;
                        foundChat.lastTimestamp = cursor.getLong(10);
                    }
                }
                if (foundChat != null) {
                    ContentValues values = new ContentValues();
                    values.put(IS_HIDDEN, 0);
                    if (database.update(CHATS, values, ID_ANCHOR, new String[]{String.valueOf(foundChat.id)}) > 0) {
                        Chat cached = findChatInCache(foundChat.id);
                        if (cached == null) {
                            cachedChatList.add(foundChat);
                        } else {
                            for (int i = 0; i < cachedChatList.size(); i++) {
                                if (cachedChatList.get(i).id == foundChat.id) {
                                    Chat updated = cachedChatList.get(i).copy();
                                    updated.isHidden = false;
                                    cachedChatList.set(i, updated);
                                    break;
                                }
                            }
                        }
                        sortCachedChatList();
                        notifyChatsList();
                    }
                    database.setTransactionSuccessful();
                    controller.updateOnlineStatus(foundChat.interlocutorId, foundChat.id, false);
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

    public void saveChat(Chat chat, ResultCallback<Chat> callback) {
        dbExecutor.execute(() -> {
            try {
                ContentValues v = new ContentValues();
                v.put(ID, chat.id);
                v.put(NAME, chat.name);
                v.put(INTERLOCUTOR_ID, chat.interlocutorId);
                long chatLocalId = database.insertWithOnConflict(CHATS, null, v, SQLiteDatabase.CONFLICT_REPLACE);
                if (chatLocalId != -1) {
                    Chat c = new Chat();
                    c.localId = chatLocalId;
                    c.id = chat.id;
                    c.name = chat.name;
                    c.interlocutorId = chat.interlocutorId;
                    c.lastSendStatus = Message.STATUS_SENDING_OR_RECEIVE;

                    Chat cached = findChatInCache(chat.id);
                    if (cached == null) {
                        cachedChatList.add(c);
                        callback.onSuccess(c);
                    } else {
                        callback.onSuccess(cached);
                    }
                    notifyChatsList();
                    controller.updateOnlineStatus(chat.interlocutorId, chat.id, false);
                    return;
                }
            } catch (Exception e) {
                Log.d(AppController.LOG_TAG, "error save new chat");
            }
            callback.onError("error save new chat");
        });
    }

    public void renameChat(long chatId, String newName) {
        dbExecutor.execute(() -> {
            database.beginTransaction();
            try {
                // 1. Обновляем имя в самой таблице чатов
                ContentValues chatValues = new ContentValues();
                chatValues.put(NAME, newName);
                if (database.update(CHATS, chatValues, ID_ANCHOR, new String[]{String.valueOf(chatId)}) > 0) {
                    for (int i = 0; i < cachedChatList.size(); i++) {
                        Chat c = cachedChatList.get(i);
                        if (c.id == chatId) {
                            Chat updated = c.copy();
                            updated.name = newName;
                            cachedChatList.set(i, updated);
                            break;
                        }
                    }
                    notifyChatsList();
                    // 2. Обновляем chat_name во всех сообщениях этого чата.
                    // Это нужно, чтобы при входе в чат синхронизация подхватила новое имя.
                    ContentValues msgValues = new ContentValues();
                    msgValues.put(CHAT_NAME, newName);
                    if (database.update(MESSAGES, msgValues, "chat_id = ?", new String[]{String.valueOf(chatId)}) > 0) {
                        List<Message> cacheList = chatMessagesCache.get(chatId);
                        if (cacheList != null) {
                            for (int i = 0; i < cacheList.size(); i++) {
                                Message updatedMsg = cacheList.get(i).copy();
                                updatedMsg.chatName = newName;
                                cacheList.set(i, updatedMsg);
                            }
                            controller.notifyMessagesChanged(chatId);
                        }
                    }
                    database.setTransactionSuccessful();
                    Log.d(AppController.LOG_TAG, "success renaming chat " + chatId + " to " + newName);
                } else {
                    Log.d(AppController.LOG_TAG, "error renaming chat: not found");
                }
            } catch (Exception e) {
                Log.e(AppController.LOG_TAG, "database error during rename", e);
            } finally {
                database.endTransaction();
            }
        });
    }

    public void hideChatLocally(Chat chat) {
        dbExecutor.execute(() -> {
            try {
                ContentValues values = new ContentValues();
                values.put(IS_HIDDEN, 1); // 1 - "Hidden"
                if (database.update(CHATS, values, ID_ANCHOR, new String[]{String.valueOf(chat.id)}) > 0) {
                    cachedChatList.removeIf(c -> c.id == chat.id);
                    notifyChatsList();
                    unreadChatsSynchronizedTask(() -> unreadChatsCache.remove(chat.id));
                    chatMessagesCache.remove(chat.id);
                    controller.clearInterlocutorOnlineStatus(chat.interlocutorId);
                } else {
                    Log.d(AppController.LOG_TAG, "chat hiding error");
                }
            } catch (Exception e) {
                Log.d(AppController.LOG_TAG, "chat hiding error " + e);
            }
        });
    }

    public void setChatBlockedState(long chatId) {
        dbExecutor.execute(() -> {
            try {
                ContentValues v = new ContentValues();
                v.put(IS_BLOCKED, 1);
                if (database.update(CHATS, v, ID_ANCHOR, new String[]{String.valueOf(chatId)}) > 0) {
                    for (int i = 0; i < cachedChatList.size(); i++) {
                        Chat c = cachedChatList.get(i);
                        if (c.id == chatId) {
                            Chat updated = c.copy();
                            updated.isBlocked = true;
                            cachedChatList.set(i, updated);
                            blockedUserIds.add(updated.interlocutorId);
                            break;
                        }
                    }
                    notifyChatsList();
                } else {
                    Log.d(AppController.LOG_TAG, "chat blocking error");
                }
            } catch (Exception e) {
                Log.d(AppController.LOG_TAG, "chat blocking error " + e);
            }
        });
    }

    public boolean isInterlocutorBlocked(long interlocutorId) {
        return blockedUserIds.contains(interlocutorId);
    }

    public void setChatColor(long chatId, int color) {
        dbExecutor.execute(() -> {
            try {
                ContentValues v = new ContentValues();
                v.put(COLOR, color);
                if (database.update(CHATS, v, ID_ANCHOR, new String[]{String.valueOf(chatId)}) > 0) {

                    for (int i = 0; i < cachedChatList.size(); i++) {
                        Chat c = cachedChatList.get(i);
                        if (c.id == chatId) {
                            Chat updated = c.copy();
                            updated.color = color;
                            cachedChatList.set(i, updated);
                            break;
                        }
                    }

                    notifyChatsList();
                    Log.d(AppController.LOG_TAG, "set color success " + color);
                } else {
                    Log.d(AppController.LOG_TAG, "error set color " + color);
                }
            } catch (Exception e) {
                Log.d(AppController.LOG_TAG, "error set color " + color, e);
            }
        });
    }

    public void getChatName(long interlocutorId, ResultCallback<Chat> callback) {
        dbExecutor.execute(() -> {
            try (Cursor cursor = database.rawQuery(GET_CHAT_NAME_BY_INTERLOCUTOR_SQL, new String[]{String.valueOf(interlocutorId)})) {
                if (cursor.moveToFirst()) {
                    Chat chat = new Chat();
                    chat.name = cursor.getString(0);
                    chat.color = cursor.getInt(1);
                    callback.onSuccess(chat);
                } else {
                    callback.onError("Chat not found for interlocutor ID: " + interlocutorId);
                }
            } catch (Exception e) {
                Log.e(AppController.LOG_TAG, "Error getting chat name from DB", e);
                callback.onError("Database error");
            }
        });
    }

    public List<Chat> getUnreadChats() {
        return new ArrayList<>(unreadChatsCache.values());
    }

    public void loadChatMessages(long chatId, int lastMsgListSize, ResultCallback<List<Message>> callback) {
        dbExecutor.execute(() -> {
            List<Message> cachedList = chatMessagesCache.get(chatId);
            if (cachedList != null) {
                callback.onSuccess(new ArrayList<>(cachedList));
            } else {
                List<Message> messages = new ArrayList<>();
                try (Cursor cursor = database.rawQuery(LOAD_CHAT_MESSAGES_SQL + Math.max(50, lastMsgListSize), new String[]{String.valueOf(chatId)})) {
                    while (cursor.moveToNext()) {
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
                        messages.add(msg);
                    }
                    chatMessagesCache.put(chatId, new LinkedList<>(messages));
                    callback.onSuccess(messages);
                } catch (Exception e) {
                    Log.d(AppController.LOG_TAG, "error loading message history " + chatId + ": ", e);
                    callback.onError("error loading message history");
                }
            }
        });
    }

    public void loadMoreMessages(long chatId, long lastLoadedLocalId, ResultCallback<List<Message>> callback) {
        dbExecutor.execute(() -> {
            List<Message> messages = new ArrayList<>();
            try (Cursor cursor = database.rawQuery(LOAD_MORE_MESSAGES_SQL, new String[]{String.valueOf(chatId), String.valueOf(lastLoadedLocalId)})) {
                while (cursor.moveToNext()) {
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
                    messages.add(msg);
                }
                // Обновляем кэш
                List<Message> cachedList = chatMessagesCache.get(chatId);
                if (cachedList != null) {
                    cachedList.addAll(messages);
                    callback.onSuccess(new ArrayList<>(cachedList));
                } else {
                    callback.onSuccess(messages);
                }
            } catch (Exception e) {
                Log.e(AppController.LOG_TAG, "error loading more messages for chat " + chatId, e);
                callback.onError("error loading more messages");
            }
        });
    }

    public void markChatAsRead(long chatId) {
        Chat unreadChat;
        synchronized (unreadChatsCache) {
            unreadChat = unreadChatsCache.remove(chatId);
        }
        if (unreadChat != null) {
            controller.notifyUnreadChatChanged(new ArrayList<>(unreadChatsCache.values()));
            dbExecutor.execute(() -> {
                try {
                    ContentValues values = new ContentValues();
                    values.put(HAS_NEW_MSG, 0);
                    if (database.update(CHATS, values, ID_ANCHOR, new String[]{String.valueOf(chatId)}) > 0) {
                        for (int i = 0; i < cachedChatList.size(); i++) {
                            Chat c = cachedChatList.get(i);
                            if (c.id == chatId) {
                                Chat updated = c.copy();
                                updated.hasNewMsg = false;
                                cachedChatList.set(i, updated);
                                break;
                            }
                        }
                        sortCachedChatList();
                        notifyChatsList();
                    } else {
                        Log.d(AppController.LOG_TAG, "error mark chat as read " + chatId);
                    }
                } catch (Exception e) {
                    Log.d(AppController.LOG_TAG, "error mark chat as read " + chatId + ": ", e);
                }
            });
        }
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
                    synchronized (pendingMessagesCache) {
                        pendingMessagesCache.put(msg.localId, msg);
                    }
                    List<Message> cacheList = chatMessagesCache.get(msg.chatId);
                    if (cacheList != null) {
                        cacheList.add(0, msg);
                        controller.notifyMessagesChanged(msg.chatId);
                    }
                    ContentValues chatValues = new ContentValues();
                    chatValues.put(LAST_MESSAGE, msg.text);
                    chatValues.put(LAST_TIMESTAMP, msg.timestamp);
                    chatValues.put(IS_HIDDEN, 0);
                    chatValues.put(LAST_SEND_STATUS, Message.STATUS_SENDING_OR_RECEIVE);
                    if (database.update(CHATS, chatValues, ID_ANCHOR, new String[]{String.valueOf(msg.chatId)}) > 0) {
                        Chat cached = findChatInCache(msg.chatId);
                        if (cached != null) {
                            cached.lastMessage = msg.text;
                            cached.lastTimestamp = msg.timestamp;
                            cached.isHidden = false;
                            cached.lastSendStatus = Message.STATUS_SENDING_OR_RECEIVE;
                            sortCachedChatList();
                            notifyChatsList();
                        }
                    }
                }
            } catch (Exception e) {
                Log.d(AppController.LOG_TAG, "error save msg before sending " + msg.chatId, e);
            }
        });
    }

    public void saveIncomingMsgList(List<Message> messages, @NonNull MediaLatch mediaLatch) {
        if (messages != null && !messages.isEmpty()) {
            dbExecutor.execute(() -> {
                database.beginTransaction();
                try {
                    Set<Long> newMsgChatIds = new HashSet<>();
                    for (Message msg : messages) {
                        cachedLastMsgServerId.accumulateAndGet(msg.id, Math::max);
                        try (Cursor c = database.rawQuery(CHECK_MESSAGE_EXISTS_SQL, new String[]{String.valueOf(msg.id)})) {
                            if (c.moveToFirst() || isSendingMessage(msg)) continue;
                        }
                        msg.localId = database.insertWithOnConflict(MESSAGES, null, getMsgValues(msg), SQLiteDatabase.CONFLICT_REPLACE);
                        List<Message> cacheList = chatMessagesCache.get(msg.chatId);
                        if (cacheList != null) {
                            cacheList.add(0, msg);
                            newMsgChatIds.add(msg.chatId);
                        }
                        ContentValues chatValues = new ContentValues();
                        chatValues.put(LAST_MESSAGE, msg.text);
                        chatValues.put(LAST_TIMESTAMP, msg.timestamp);
                        chatValues.put(IS_HIDDEN, 0);

                        long localChatId = Chat.INVALID_ID;
                        if (msg.senderId != controller.getUserId()) {
                            chatValues.put(INTERLOCUTOR_ID, msg.senderId);
                            chatValues.put(HAS_NEW_MSG, 1);
                            chatValues.put(LAST_SEND_STATUS, Message.STATUS_SENDING_OR_RECEIVE);
                            try (Cursor c = database.rawQuery(GET_LOCAL_CHAT_ID_SQL, new String[]{String.valueOf(msg.chatId)})) {
                                if (c.moveToFirst()) {
                                    localChatId = c.getLong(0);
                                }
                            }
                        } else {
                            chatValues.put(INTERLOCUTOR_ID, msg.receiverId);
                            chatValues.put(NAME, msg.chatName);
                            chatValues.put(LAST_SEND_STATUS, Message.STATUS_SENT);
                        }

                        if (database.update(CHATS, chatValues, ID_ANCHOR, new String[]{String.valueOf(msg.chatId)}) == 0) {
                            Chat newChat = new Chat();
                            chatValues.put(ID, msg.chatId);
                            chatValues.put(NAME, msg.chatName);
                            localChatId = database.insert(CHATS, null, chatValues);
                            newChat.localId = localChatId;
                            newChat.id = msg.chatId;
                            newChat.name = msg.chatName;
                            newChat.lastMessage = msg.text;
                            newChat.lastTimestamp = msg.timestamp;
                            if (msg.senderId != controller.getUserId()) {
                                newChat.interlocutorId = msg.senderId;
                                newChat.hasNewMsg = true;
                                newChat.lastSendStatus = Message.STATUS_SENDING_OR_RECEIVE;
                            } else {
                                newChat.interlocutorId = msg.receiverId;
                                newChat.lastSendStatus = Message.STATUS_SENT;
                            }
                            cachedChatList.add(newChat);
                            Log.d(AppController.LOG_TAG, "Создан новый чат при синхронизации: " + localChatId);
                            addInterlocutorToUsers(msg);
                        } else {
                            fillOldChat(msg);
                        }
                        if (msg.senderId != controller.getUserId()) {
                            Chat c = new Chat();
                            c.localId = localChatId;
                            c.id = msg.chatId;
                            c.name = msg.chatName;
                            c.lastTimestamp = msg.timestamp;
                            unreadChatsSynchronizedTask(() -> unreadChatsCache.put(c.id, c));
                            Log.d(AppController.LOG_TAG, "в кэш непрочитанных чатов добавлено сообщение" + msg.chatName);
                        }
                    }
                    newMsgChatIds.forEach(controller::notifyMessagesChanged);
                    controller.notifyUnreadChatChanged(new ArrayList<>(unreadChatsCache.values()));
                    sortCachedChatList();
                    notifyChatsList();
                    database.setTransactionSuccessful();
                } catch (Exception e) {
                    Log.d(AppController.LOG_TAG, "Error syncing messages: " + e.getMessage());
                } finally {
                    database.endTransaction();
                    mediaLatch.countDown();

                }
            });
        } else {
            mediaLatch.countDown();
        }
    }

    @NonNull
    private ContentValues getMsgValues(Message msg) {
        ContentValues values = new ContentValues();
        values.put(ID, msg.id); // Серверный ID
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
                msg.mediaStatus = Message.MEDIA_STATUS_PENDING;
                synchronized (pendingMediaCache) {
                    pendingMediaCache.put(msg.id, msg);
                }
            }
        }
        Log.w(AppController.LOG_TAG, "сохранено сообщение: serverId " + msg.id + " в чат id " + msg.chatId);
        return values;
    }

    private void fillOldChat(Message msg) {
        Chat cached = findChatInCache(msg.chatId);
        if (cached == null) { // Чат был скрыт - читаем его из БД целиком
            cached = getFullChatFromDb(msg.chatId);
            if (cached != null) {
                cachedChatList.add(cached);
                controller.updateOnlineStatus(cached.interlocutorId, cached.id, false);
            }
        } else {
            for (int i = 0; i < cachedChatList.size(); i++) {
                if (cachedChatList.get(i).id == msg.chatId) {
                    Chat updated = cachedChatList.get(i).copy();
                    updated.lastMessage = msg.text;
                    updated.lastTimestamp = msg.timestamp;
                    updated.isHidden = false;
                    if (msg.senderId != controller.getUserId()) {
                        updated.interlocutorId = msg.senderId;
                        updated.hasNewMsg = true;
                        updated.lastSendStatus = Message.STATUS_SENDING_OR_RECEIVE;
                    } else {
                        updated.interlocutorId = msg.receiverId;
                        updated.name = msg.chatName;
                        updated.lastSendStatus = Message.STATUS_SENT;
                    }
                    cachedChatList.set(i, updated);
                    break;
                }
            }
        }
    }

    private boolean isSendingMessage(Message msg) {
        synchronized (pendingMessagesCache) {
            if (msg.senderId == controller.getUserId() && !pendingMessagesCache.isEmpty()) {
                for (Message pending : pendingMessagesCache.values()) {
                    if (msg.chatId == pending.chatId
                            && Objects.equals(msg.type, pending.type)
                            && Objects.equals(msg.fileName, pending.fileName)
                            && Objects.equals(msg.text, pending.text)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    public void updateFilePath(Message msg, String filePath) {
        dbExecutor.execute(() -> {
            try {
                ContentValues v = new ContentValues();
                v.put(FILE_PATH, filePath);
                v.put(MEDIA_STATUS, Message.MEDIA_STATUS_DOWNLOADED);
                if (database.update(MESSAGES, v, LOCAL_ID_ANCHOR, new String[]{String.valueOf(msg.localId)}) > 0) {
                    synchronized (pendingMediaCache) {
                        pendingMediaCache.remove(msg.id);
                    }
                    List<Message> cacheList = chatMessagesCache.get(msg.chatId);
                    if (cacheList != null) {
                        for (int i = 0; i < cacheList.size(); i++) {
                            Message m = cacheList.get(i);
                            if (m.localId == msg.localId) {
                                Message updatedMsg = m.copy();
                                updatedMsg.filePath = filePath;
                                updatedMsg.mediaStatus = Message.MEDIA_STATUS_DOWNLOADED;
                                cacheList.set(i, updatedMsg);
                                controller.notifyMessagesChanged(msg.chatId);
                                break;
                            }
                        }
                    }
                    Log.d(AppController.LOG_TAG, "update file path success: " + filePath);
                } else {
                    msg.mediaStatus = Message.MEDIA_STATUS_PENDING;
                    Log.d(AppController.LOG_TAG, "error update file path " + filePath);
                }
            } catch (Exception e) {
                msg.mediaStatus = Message.MEDIA_STATUS_PENDING;
                Log.d(AppController.LOG_TAG, "error update file path " + filePath, e);
            }
        });
    }

    public void setMediaStatus(Message msg, int status) {
        synchronized (pendingMediaCache) {
            if (status == Message.MEDIA_STATUS_PENDING) {
                Message cached = pendingMediaCache.get(msg.id);
                if (cached != null)
                    cached.mediaStatus = Message.MEDIA_STATUS_PENDING; // Просто возвращаем в очередь в памяти
            } else {  // Message.MEDIA_STATUS_ERROR
                msg.mediaStatus = Message.MEDIA_STATUS_ERROR;
                pendingMediaCache.remove(msg.id);
                dbExecutor.execute(() -> {
                    try {
                        ContentValues v = new ContentValues();
                        v.put(MEDIA_STATUS, Message.MEDIA_STATUS_ERROR);
                        if (database.update(MESSAGES, v, LOCAL_ID_ANCHOR, new String[]{String.valueOf(msg.localId)}) == 0) {
                            Log.d(AppController.LOG_TAG, "set error status media error");
                        }
                    } catch (Exception e) {
                        Log.d(AppController.LOG_TAG, "set error status media error ", e);
                    }
                });
            }
        }
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
                    synchronized (pendingMessagesCache) {
                        pendingMessagesCache.remove(msg.localId);
                    }
                    updateMessageInCache(msg);
                    updateChatInCache(msg);
                    notifyChatsList();
                } else {
                    Log.d(AppController.LOG_TAG, "error confirm msg send" + msg.id);
                }
            } catch (Exception e) {
                Log.d(AppController.LOG_TAG, "error confirm msg send ", e);
            }
        });
    }

    private void updateMessageInCache(Message msg) {
        List<Message> cacheList = chatMessagesCache.get(msg.chatId);
        if (cacheList != null) {
            for (int i = 0; i < cacheList.size(); i++) {
                Message m = cacheList.get(i);
                if (m.localId == msg.localId) {
                    Message updatedMsg = m.copy();
                    updatedMsg.id = msg.id;
                    updatedMsg.timestamp = msg.timestamp;
                    updatedMsg.sendStatus = Message.STATUS_SENT;
                    cacheList.set(i, updatedMsg);
                    controller.notifyMessagesChanged(msg.chatId);
                    break;
                }
            }
        }
    }

    private void updateChatInCache(Message msg) {
        Chat cached = findChatInCache(msg.chatId);
        if (cached == null) {
            cached = getFullChatFromDb(msg.chatId);
            if (cached != null) {
                cached.lastSendStatus = Message.STATUS_SENT;
                cached.isBlocked = false;
                cachedChatList.add(cached);
                blockedUserIds.remove(cached.interlocutorId);
                controller.updateOnlineStatus(cached.interlocutorId, cached.id, false);
            }
        } else {
            for (int i = 0; i < cachedChatList.size(); i++) {
                if (cachedChatList.get(i).id == msg.chatId) {
                    Chat updated = cachedChatList.get(i).copy();
                    updated.lastSendStatus = Message.STATUS_SENT;
                    updated.isBlocked = false;
                    cachedChatList.set(i, updated);
                    blockedUserIds.remove(updated.interlocutorId);
                    break;
                }
            }
        }
    }

    public void notConfirmMessageSent(Message msg) {
        synchronized (pendingMessagesCache) {
            Message cached = pendingMessagesCache.get(msg.localId);
            if (cached != null) {
                cached.sendStatus = Message.STATUS_WAITING;
            }
        }
    }

    public long getLastDbMessageId() {
        return cachedLastMsgServerId.get();
    }

    public List<Message> getPendingMessages() {
        List<Message> toSend = new ArrayList<>();
        synchronized (pendingMessagesCache) {
            for (Message msg : pendingMessagesCache.values()) {
                if (msg.sendStatus == Message.STATUS_WAITING) {
                    msg.sendStatus = Message.STATUS_SENDING_OR_RECEIVE; // Метим как "в работе"
                    toSend.add(msg);
                    if (toSend.size() >= AppController.QUEUE_SIZE) break;
                }
            }
        }
        return toSend;
    }

    public List<Message> getMediaList() {
        List<Message> toDownload = new ArrayList<>();
        synchronized (pendingMediaCache) {
            for (Message msg : pendingMediaCache.values()) {
                Log.d(AppController.LOG_TAG, "файл на загрузку: " + msg.mediaStatus + " serv id " + msg.id);
                if (msg.mediaStatus == Message.MEDIA_STATUS_PENDING) {
                    msg.mediaStatus = Message.MEDIA_STATUS_LOADING;
                    toDownload.add(msg);
                }
            }
        }
        return toDownload;
    }

    private void addInterlocutorToUsers(Message msg) {
        controller.getNetStreams()[AppController.GET_MSG].execute(() ->
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

    private void initLastMsgIdCache() {
        try (Cursor cursor = database.rawQuery(GET_LAST_MSG_ID_SQL, null)) {
            if (cursor.moveToFirst()) {
                cachedLastMsgServerId.set(cursor.getLong(0));
            }
        }
    }

    private void initUnreadChatCache() {
        try (Cursor cursor = database.rawQuery(GET_UNREAD_CHATS_SQL, null)) {
            while (cursor.moveToNext()) {
                Chat chat = new Chat();
                chat.localId = cursor.getLong(0);
                chat.id = cursor.getLong(1);
                chat.name = cursor.getString(2);
                unreadChatsSynchronizedTask(() -> unreadChatsCache.put(chat.id, chat));
            }
            controller.notifyUnreadChatChanged(new ArrayList<>(unreadChatsCache.values()));
        }
    }

    private void initPendingMessagesCache() {
        try (Cursor cursor = database.rawQuery(GET_PENDING_MESSAGES_SQL + AppController.QUEUE_SIZE, new String[]{String.valueOf(controller.getUserId())})) {
            while (cursor.moveToNext()) {
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
                msg.sendStatus = Message.STATUS_WAITING;
                pendingMessagesCache.put(msg.localId, msg);
            }
        }
    }

    private void initPendingMediaCache() {
        try (Cursor cursor = database.rawQuery(GET_MEDIA_DOWNLOAD_LIST_SQL, null)) {
            while (cursor.moveToNext()) {
                Message msg = new Message();
                msg.id = cursor.getLong(0);
                msg.chatId = cursor.getLong(1);
                msg.filePath = cursor.getString(2);
                msg.fileName = cursor.getString(3);
                msg.interlocutorPublicKey = cursor.getString(4);
                msg.mediaStatus = Message.MEDIA_STATUS_PENDING;
                pendingMediaCache.put(msg.id, msg);
            }
        }
    }

    private void initChatCache() {
        try (Cursor cursor = database.rawQuery(GET_CHAT_LIST_SQL, null)) {
            while (cursor.moveToNext()) {
                Chat chat = new Chat();
                chat.localId = cursor.getLong(0);
                chat.id = cursor.getLong(1);
                chat.name = cursor.getString(2);
                chat.isGroup = cursor.getInt(3) == 1;
                chat.interlocutorId = cursor.getLong(4);
                chat.lastMessage = cursor.getString(5);
                chat.lastSendStatus = cursor.getLong(6);
                chat.isBlocked = cursor.getInt(7) == 1;
                chat.color = cursor.getInt(8);
                chat.hasNewMsg = cursor.getInt(9) == 1;
                chat.lastTimestamp = cursor.getLong(10);
                cachedChatList.add(chat);
            }
            dbExecutor.execute(this::notifyChatsList);
        }
    }

    private void initBlockedUsersCache() {
        try (Cursor cursor = database.rawQuery(GET_BLOCKED_USERS_SQL, null)) {
            while (cursor.moveToNext()) {
                blockedUserIds.add(cursor.getLong(0));
            }
        }
    }

    private Chat getFullChatFromDb(long chatId) {
        try (Cursor cursor = database.rawQuery(GET_CHAT_BY_ID_SQL, new String[]{String.valueOf(chatId)})) {
            if (cursor.moveToFirst()) {
                Chat chat = new Chat();
                chat.localId = cursor.getLong(0);
                chat.id = cursor.getLong(1);
                chat.name = cursor.getString(2);
                chat.isGroup = cursor.getInt(3) == 1;
                chat.interlocutorId = cursor.getLong(4);
                chat.lastMessage = cursor.getString(5);
                chat.lastSendStatus = cursor.getLong(6);
                chat.isBlocked = cursor.getInt(7) == 1;
                chat.color = cursor.getInt(8);
                chat.hasNewMsg = cursor.getInt(9) == 1;
                chat.lastTimestamp = cursor.getLong(10);
                return chat;
            }
        } catch (Exception e) {
            Log.e(AppController.LOG_TAG, "Error reading full chat from DB", e);
        }
        return null;
    }

    private void notifyChatsList() {
        List<Chat> uiList = new ArrayList<>();
        uiList.add(Chat.createNewChatAction(controller.getString(R.string.new_chat), controller.getString(R.string.find_user)));
        uiList.addAll(cachedChatList);
        controller.notifyChatListChanged(uiList);
    }

    private void sortCachedChatList() {
        cachedChatList.sort((c1, c2) -> {
            if (c1.hasNewMsg != c2.hasNewMsg) return c2.hasNewMsg ? 1 : -1;
            return Long.compare(c2.lastTimestamp, c1.lastTimestamp);
        });
    }

    private Chat findChatInCache(long chatId) {
        for (Chat c : cachedChatList) if (c.id == chatId) return c;
        return null;
    }

    private void initUserKeysCache() {
        try (Cursor cursor = database.rawQuery(GET_ALL_USER_KEYS_SQL, null)) {
            while (cursor.moveToNext()) {
                userKeysCache.put(cursor.getLong(0), cursor.getString(1));
            }
        } catch (Exception e) {
            Log.e(AppController.LOG_TAG, "Error initializing user keys cache", e);
        }
    }

    @Nullable
    public String getUserPublicKey(long userId) {
        return userKeysCache.get(userId);
    }

    private void unreadChatsSynchronizedTask(Runnable unreadChatTask) {
        synchronized (unreadChatsCache) {
            unreadChatTask.run();
        }
    }
}
