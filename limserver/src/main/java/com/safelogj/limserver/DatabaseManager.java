package com.safelogj.limserver;

import com.safelogj.limserver.model.Chat;
import com.safelogj.limserver.model.Message;
import com.safelogj.limserver.model.User;
import com.safelogj.limserver.request.EditUserRequest;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Connection;
import java.sql.Statement;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

public class DatabaseManager {
    public static final long FILE_SIZE_LIMIT = 50_000_000L;
    private static final String DB_FILE = "lim.db";
    @NotNull
    private static final char[] HEX_ARRAY = "0123456789abcdef".toCharArray();
    private final HikariDataSource dataSource;
    private final HikariPoolMXBean poolProxy;
    private MessageDigest mDigest;
    @NotNull
    private final ReentrantLock digestLock = new ReentrantLock();

    DatabaseManager(String dbFolderPath) {
        HikariConfig config = new HikariConfig();
        // Путь к базе
        config.setJdbcUrl("jdbc:sqlite:" + dbFolderPath + "/" + DB_FILE);
        // Настройки пула
        config.setMaximumPoolSize(4); // 4 соединений будут всегда "под рукой"
        config.setMinimumIdle(2);      // Минимум 2 всегда открыты
        config.setConnectionTimeout(5000); // Ждать соединение из пула не более 5 сек
        config.setIdleTimeout(1800000);    // Закрывать лишние через 30 мин простоя
        // Оптимизации SQLite прямо в URL или через свойства
        config.addDataSourceProperty("journal_mode", "WAL");
        config.addDataSourceProperty("busy_timeout", "5000");
        config.addDataSourceProperty("synchronous", "NORMAL");
        config.addDataSourceProperty("cache_size", "-16000");
        dataSource = new HikariDataSource(config);
        poolProxy = dataSource.getHikariPoolMXBean();
    }

    // Метод для получения живого соединения с базой
    private Connection getConnection() throws SQLException {
        if (poolProxy != null && poolProxy.getThreadsAwaitingConnection() > 0) {
            LimController.log.warn("ВНИМАНИЕ: Появилась очередь к базе данных! Ждут: {}", poolProxy.getThreadsAwaitingConnection());
        }
        return dataSource.getConnection();
    }

    public void close() {
        if (dataSource != null) {
            dataSource.close();
        }
    }

    void initDatabase() {
        String createUsersTable = "CREATE TABLE IF NOT EXISTS users (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "username TEXT NOT NULL UNIQUE, " + // Уникальный логин (например, "ivan")
                "password_hash TEXT NOT NULL, " +   // Хэш пароля (для безопасности)
                "display_name TEXT NOT NULL, " +    // Имя в чате (например, "Иван Иванович")
                "created_at INTEGER NOT NULL, " +     // Дата регистрации
                "is_deleted INTEGER NOT NULL DEFAULT 0" + // 0 = активен, 1 = удален
                ")";

        String createChatsTable = "CREATE TABLE IF NOT EXISTS chats (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "name TEXT, " +             // Название группы (null для личных чатов двоих)
                "is_group INTEGER NOT NULL DEFAULT 0, " + // 1 = групповой чат, 0 = чат один-на-один
                "created_at INTEGER NOT NULL" +
                ")";

        // Индексы на chat_id и user_id создаются автоматически, если сделать их составным первичным ключом
        String createChatMembersTable = "CREATE TABLE IF NOT EXISTS chat_members (" +
                "chat_id INTEGER NOT NULL, " +
                "user_id INTEGER NOT NULL, " +
                "joined_at INTEGER NOT NULL, " +
                "is_hidden INTEGER NOT NULL DEFAULT 0, " + // 0 = visible, 1 = hidden
                "is_blocked INTEGER NOT NULL DEFAULT 0, " + // 1 = blocked, 0 = not blocked
                "PRIMARY KEY (chat_id, user_id)" + // Юзер не может вступить в один чат дважды
                ")";

        // Используем AUTOINCREMENT для ID, чтобы сообщения шли строго по порядку.
        String createMessagesTable = "CREATE TABLE IF NOT EXISTS messages (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "chat_id INTEGER NOT NULL, " +
                "sender_id INTEGER NOT NULL, " +
                "text TEXT NOT NULL, " +
                "type TEXT NOT NULL, " +        // TEXT, IMAGE, FILE, SYSTEM
                "file_path TEXT, " +            // Путь к файлу на диске роутера
                "file_name TEXT, " +            // Оригинальное имя файла
                "chat_name TEXT NOT NULL, " +   // Название чата, если личное
                "timestamp INTEGER NOT NULL" +  // Время в миллисекундах (System.currentTimeMillis())
                ")";

        try (Connection conn = getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute(createUsersTable);
            stmt.execute(createChatsTable);
            stmt.execute(createChatMembersTable);
            stmt.execute(createMessagesTable);
            mDigest = MessageDigest.getInstance("SHA-256");
            LimController.log.info("База данных SQLite успешно инициализирована. Таблицы проверены.");
        } catch (Exception e) {
            LimController.log.error("Критическая ошибка при инициализации базы данных: ", e);
            System.exit(LimController.ERROR);
        }
    }

    @Nullable
    public User registerUser(@NotNull String username, @NotNull String clientPasswordHash, @NotNull String displayName) throws SQLException {
        String serverPasswordHash = hashPassword(clientPasswordHash);
        if (serverPasswordHash.isEmpty()) {
            throw new SQLException("critical error: password hashing failed");
        }

        // Открываем соединение из пула
        try (Connection conn = getConnection()) {
            int oldIsolation = conn.getTransactionIsolation();

            try {
                // Для регистрации SERIALIZABLE идеален, чтобы два юзера не забили один логин одновременно
                conn.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
                conn.setAutoCommit(false);
                // 1. Проверяем занятость логина
                try (PreparedStatement checkStmt = conn.prepareStatement("SELECT 1 FROM users WHERE username = ? LIMIT 1")) {
                    checkStmt.setString(1, username);
                    try (ResultSet rs = checkStmt.executeQuery()) {
                        if (rs.next()) {
                            LimController.log.warn("Попытка регистрации: логин '{}' уже занят", username);
                            // Просто выходим. Блок finally сам корректно закроет пустую транзакцию
                            return null;
                        }
                    }
                }
                long userId = -1;
                // 2. Если свободно — вставляем
                try (PreparedStatement insertStmt = conn.prepareStatement(
                        "INSERT INTO users (username, password_hash, display_name, created_at) VALUES (?, ?, ?, ?)", Statement.RETURN_GENERATED_KEYS)) {
                    insertStmt.setString(1, username);
                    insertStmt.setString(2, serverPasswordHash);
                    insertStmt.setString(3, displayName);
                    insertStmt.setLong(4, System.currentTimeMillis());
                    insertStmt.executeUpdate();

                    try (ResultSet rs = insertStmt.getGeneratedKeys()) {
                        if (rs.next()) {
                            userId = rs.getLong(1);
                        }
                    }
                }

                if (userId == -1) {
                    throw new SQLException("failed to retrieve new user id");
                }

                // Если дошли досюда — фиксируем изменения
                conn.commit();
                LimController.log.info("Пользователь '{}' успешно зарегистрирован", username);
                User user = new User();
                user.id = userId;
                user.displayName = displayName;
                return user;

            } catch (SQLException e) {
                // Откатываем ТОЛЬКО если реально была ошибка при записи
                try {
                    conn.rollback();
                } catch (SQLException ex) {
                    LimController.log.error("Не удалось откатить транзакцию: ", ex);
                }
                throw e; // Пробрасываем наверх для логирования в главном catch
            } finally {
                // Возвращаем коннект в пул в исходном состоянии
                try {
                    conn.setAutoCommit(true);
                    conn.setTransactionIsolation(oldIsolation);
                } catch (SQLException ex) {
                    LimController.log.error("Ошибка при восстановлении настроек соединения: ", ex);
                }
            }

        } catch (SQLException e) {
            LimController.log.error("Критическая ошибка БД при регистрации: ", e);
            return null;
        }
    }

    /**
     * Проверяет учетные данные пользователя по логину и клиентскому хэшу пароля.
     *
     * @return id пользователя, если успешно; -1 если не найден или пароль неверен
     */
    @Nullable
    public User authenticateUser(@NotNull String username, @NotNull String password) {
        String serverPasswordHash = hashPassword(password);
        if (!serverPasswordHash.isEmpty()) {
            try (Connection conn = getConnection();
                 PreparedStatement stmt = conn.prepareStatement("SELECT id, display_name, password_hash FROM users WHERE username = ? AND is_deleted = 0")) {
                stmt.setString(1, username);
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next() && rs.getString("password_hash").equals(serverPasswordHash)) {
                        User user = new User();
                        user.id = rs.getLong("id");
                        user.displayName = rs.getString("display_name");
                        return user;
                    } else {
                        LimController.log.warn("Неверный пароль для пользователя '{}'", username);
                    }
                }
            } catch (Exception e) {
                LimController.log.error("Ошибка при аутентификации пользователя во время запроса: ", e);
            }
        } else {
            LimController.log.error("Ошибка при аутентификации пользователя, не получен хэш из пароля ");
        }
        return null;
    }

    /**
     * Мягкое удаление пользователя. Занимает логин навсегда,
     * сбрасывает пароль и переименовывает аккаунт, сохраняя историю в чатах.
     */
    public boolean deleteUser(long userId) {
        try (Connection conn = getConnection(); PreparedStatement stmt = conn.prepareStatement("UPDATE users SET is_deleted = 1, password_hash = '', display_name = 'Deleted account' WHERE id = ? AND is_deleted = 0")) {
            stmt.setLong(1, userId);
            if (stmt.executeUpdate() > 0) {
                LimController.log.info("Пользователь id={} успешно переведен в статус 'Удален'. Логин заблокирован.", userId);
                return true;
            } else {
                LimController.log.warn("Не удалось удалить пользователя id={}. Возможно, он уже удален или не существовал.", userId);
            }
        } catch (Exception e) {
            LimController.log.error("Ошибка при мягком удалении пользователя id={}", userId, e);
        }
        return false;
    }

    /**
     * Возвращает ID существующего личного чата между двумя пользователями или создает новый.
     * Сбрасывает флаг скрытия (is_hidden = 0) для обоих участников, если чат уже был.
     */
    @Nullable
    public Chat getOrCreatePersonalChat(long senderId, long receiverId) {
        if (senderId == receiverId) {
            throw new IllegalArgumentException("can't create a chat with yourself");
        }

        try (Connection conn = getConnection()) {
            int oldIsolation = conn.getTransactionIsolation();
            conn.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
            conn.setAutoCommit(false);

            try {
                long chatId = -1;
                try (PreparedStatement findStmt = conn.prepareStatement(
                        "SELECT cm1.chat_id FROM chat_members cm1 " +
                                "JOIN chat_members cm2 ON cm1.chat_id = cm2.chat_id " +
                                "JOIN chats c ON cm1.chat_id = c.id " +
                                "WHERE c.is_group = 0 AND cm1.user_id = ? AND cm2.user_id = ? LIMIT 1")) {
                    findStmt.setLong(1, senderId);
                    findStmt.setLong(2, receiverId);
                    try (ResultSet rs = findStmt.executeQuery()) {
                        if (rs.next()) {
                            chatId = rs.getLong("chat_id");
                        }
                    }
                }

                if (chatId != -1) { // --- СЦЕНАРИЙ А: ЧАТ НАЙДЕН ---
                    try (PreparedStatement unhideStmt = conn.prepareStatement(
                            "UPDATE chat_members SET is_hidden = 0 WHERE chat_id = ?");
                         PreparedStatement unblockStmt = conn.prepareStatement(
                                 "UPDATE chat_members SET is_blocked = 0 WHERE chat_id = ? AND user_id = ?")) {
                        unhideStmt.setLong(1, chatId);
                        unhideStmt.executeUpdate();
                        unblockStmt.setLong(1, chatId);
                        unblockStmt.setLong(2, senderId);
                        unblockStmt.executeUpdate();
                    }
                } else { // --- СЦЕНАРИЙ Б: СОЗДАЕМ НОВЫЙ ЧАТ ---
                    long now = System.currentTimeMillis();
                    // Вставляем запись в таблицу chats
                    try (PreparedStatement createChatStmt = conn.prepareStatement(
                            "INSERT INTO chats (name, is_group, created_at) VALUES (NULL, 0, ?)", Statement.RETURN_GENERATED_KEYS)) {
                        createChatStmt.setLong(1, now);
                        createChatStmt.executeUpdate();
                        try (ResultSet keys = createChatStmt.getGeneratedKeys()) {
                            if (keys.next()) {
                                chatId = keys.getLong(1);
                            } else {
                                throw new SQLException("Не удалось получить ID нового чата.");
                            }
                        }
                    }
                    // Добавляем участников (используем Batch для эффективности)
                    try (PreparedStatement memberStmt = conn.prepareStatement(
                            "INSERT INTO chat_members (chat_id, user_id, joined_at, is_hidden) VALUES (?, ?, ?, 0)")) {
                        // Участник 1
                        memberStmt.setLong(1, chatId);
                        memberStmt.setLong(2, senderId);
                        memberStmt.setLong(3, now);
                        memberStmt.addBatch();

                        // Участник 2
                        memberStmt.setLong(1, chatId);
                        memberStmt.setLong(2, receiverId);
                        memberStmt.setLong(3, now);
                        memberStmt.addBatch();
                        memberStmt.executeBatch();
                    }
                }
                conn.commit();
                Chat chat = new Chat();
                chat.id = chatId;
                return chat;
            } catch (SQLException e) {
                try {
                    conn.rollback();
                } catch (SQLException ex) { /* игнорим */ }
                throw e;
            } finally {
                conn.setAutoCommit(true);
                conn.setTransactionIsolation(oldIsolation);
            }
        } catch (SQLException e) {
            LimController.log.error("critical error while creating/retrieving chat: ", e);
            return null;
        }
    }

    public boolean hideChat(long chatId, long userId) {
        try (Connection conn = getConnection(); PreparedStatement stmt = conn.prepareStatement(
                "UPDATE chat_members SET is_hidden = 1 WHERE chat_id = ? AND user_id = ?")) {
            stmt.setLong(1, chatId);
            stmt.setLong(2, userId);
            return stmt.executeUpdate() > 0;
        } catch (SQLException e) {
            LimController.log.error("error hiding chat {} for user {}: ", chatId, userId, e);
            return false;
        }
    }

    public boolean setChatBlockedState(long chatId, long userId) {
        try (Connection conn = getConnection(); PreparedStatement stmt = conn.prepareStatement(
                "UPDATE chat_members SET is_blocked = ? WHERE chat_id = ? AND user_id = ?")) {
            stmt.setLong(1, 1);
            stmt.setLong(2, chatId);
            stmt.setLong(3, userId);
            return stmt.executeUpdate() > 0;
        } catch (SQLException e) {
            LimController.log.error("error hiding chat {} for user {}: ", chatId, userId, e);
            return false;
        }
    }

    public List<Chat> getActiveChats(long userId) {
        List<Chat> activeChats = new ArrayList<>();
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "SELECT c.id, c.name, c.is_group, cm.is_hidden, " +
                             "(SELECT text FROM messages WHERE chat_id = c.id ORDER BY id DESC LIMIT 1) as last_text, " +
                             "(SELECT timestamp FROM messages WHERE chat_id = c.id ORDER BY id DESC LIMIT 1) as last_time " +
                             "FROM chats c " +
                             "JOIN chat_members cm ON c.id = cm.chat_id " +
                             "WHERE cm.user_id = ? " +
                             "ORDER BY last_time DESC")) {

            stmt.setLong(1, userId);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    Chat chat = new Chat();
                    chat.id = rs.getLong("id");
                    chat.name = rs.getString("name");
                    chat.isGroup = rs.getLong("is_group") == 1;
                    chat.lastMessage = rs.getString("last_text");
                    chat.lastTimestamp = rs.getLong("last_time");
                    chat.isHidden = rs.getLong("is_hidden") == 1;
                    // Для личных чатов вытягиваем инфо о собеседнике
                    if (!chat.isGroup) {
                        User interlocutor = getInterlocutorInfo(conn, chat.id, userId);
                        if (interlocutor != null) {
                            chat.name = interlocutor.displayName;
                            chat.interlocutorId = interlocutor.id;
                        }
                    }
                    activeChats.add(chat);
                }
            }
        } catch (SQLException e) {
            LimController.log.error("Ошибка при получении активных чатов для юзера {}: ", userId, e);
        }
        return activeChats;
    }

    private User getInterlocutorInfo(Connection conn, long chatId, long myUserId) throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement(
                "SELECT u.id, u.display_name FROM users u " +
                        "JOIN chat_members cm ON u.id = cm.user_id " +
                        "WHERE cm.chat_id = ? AND cm.user_id != ? LIMIT 1")) {
            stmt.setLong(1, chatId);
            stmt.setLong(2, myUserId);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    User user = new User();
                    user.id = rs.getLong("id");
                    user.displayName = rs.getString("display_name");
                    return user;
                }
            }
        }
        return null;
    }

    /**
     * Обновляет отображаемое имя и/или пароль пользователя.
     * Принимает объект запроса, где поля уже проверены на валидность в хэндлере.
     */
    @Nullable
    public User updateUser(long userId, @NotNull EditUserRequest req, boolean isNewDisplayName, boolean isNewPassword) {
        String newDisplayName = req.newDisplayName();
        String newClientPasswordHash = req.newPassword();
        String newServerPasswordHash = null;
        // 2. Хэшируем пароль заранее
        if (isNewPassword) {
            newServerPasswordHash = hashPassword(newClientPasswordHash);
            if (newServerPasswordHash.isEmpty()) {
                LimController.log.error("Ошибка при генерации хэша для обновления id={}", userId);
                return null;
            }
        }

        // 3. Динамически собираем SQL
        StringBuilder sql = new StringBuilder("UPDATE users SET ");
        if (isNewDisplayName) {
            sql.append("display_name = ?");
        }
        if (isNewDisplayName && isNewPassword) sql.append(", ");
        if (isNewPassword) sql.append("password_hash = ?");
        sql.append(" WHERE id = ? AND is_deleted = 0");

        // 4. Работаем с БД через пул
        try (Connection conn = getConnection(); PreparedStatement stmt = conn.prepareStatement(sql.toString())) {
            int paramIndex = 1;
            if (isNewDisplayName) {
                stmt.setString(paramIndex++, newDisplayName);
            }
            if (isNewPassword) {
                stmt.setString(paramIndex++, newServerPasswordHash);
            }
            stmt.setLong(paramIndex, userId);
            if (stmt.executeUpdate() > 0) {
                LimController.log.info("Данные пользователя id={} успешно обновлены.", userId);
                return new User();
            } else {
                LimController.log.warn("Обновление не удалось: пользователь id={} не найден или удален.", userId);
            }
        } catch (SQLException e) {
            LimController.log.error("Ошибка БД при обновлении пользователя id={}: ", userId, e);
        }
        return null;
    }

    /**
     * Ищет пользователя по его точному логину (username).
     * Возвращает объект EditUserRequest (используем его как удобный контейнер для ID и имени)
     * или null, если пользователь не найден.
     */
    @Nullable
    public User searchUserByUsername(@NotNull String targetUsername) {
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement("SELECT id, display_name FROM users WHERE username = ? AND is_deleted = 0")) {
            stmt.setString(1, targetUsername);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    User user = new User();
                    user.id = rs.getLong("id");
                    user.displayName = rs.getString("display_name");
                    return user;
                }
            }
        } catch (SQLException e) {
            LimController.log.error("Ошибка при поиске пользователя '{}'", targetUsername, e);
        }
        return null;
    }

    public long saveMessage(long chatId, long senderId, String text, String type, long timestamp,
                            String filePath, String fileName, String chatName) {
        try (Connection conn = getConnection()) {
            conn.setAutoCommit(false);

            try (PreparedStatement insertStmt = conn.prepareStatement(
                    "INSERT INTO messages (chat_id, sender_id, text, type, timestamp, file_path, file_name) VALUES (?, ?, ?, ?, ?, ?, ?)", Statement.RETURN_GENERATED_KEYS);
                 PreparedStatement updateStmt = conn.prepareStatement(
                         "UPDATE chat_members SET is_hidden = 0 WHERE chat_id = ?");
                 PreparedStatement updateBlock = conn.prepareStatement(
                         "UPDATE chat_members SET is_blocked = 0 WHERE chat_id = ? AND user_id = ?")) {

                insertStmt.setLong(1, chatId);
                insertStmt.setLong(2, senderId);
                insertStmt.setString(3, text);
                insertStmt.setString(4, type);
                insertStmt.setLong(5, timestamp);
                insertStmt.setString(6, filePath);
                insertStmt.setString(7, fileName);
                insertStmt.setString(8, chatName);


                if (insertStmt.executeUpdate() == 0) {
                    conn.rollback();
                    return Message.INVALID_MSG_ID;
                }
                long serverId = Message.INVALID_MSG_ID;
                try (ResultSet rs = insertStmt.getGeneratedKeys()) {
                    if (rs.next()) serverId = rs.getLong(1);
                }
                updateStmt.setLong(1, chatId);
                updateStmt.executeUpdate();
                updateBlock.setLong(1, chatId);
                updateBlock.setLong(2, senderId);
                updateBlock.executeUpdate();
                conn.commit();
                return serverId;
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            LimController.log.error("Критическая ошибка при сохранении сообщения и обновлении участников: ", e);
            return Message.INVALID_MSG_ID;
        }
    }

    @Nullable
    public List<Message> getNewMessages(long userId, long lastMessageId) {
        String sql;
//        boolean isInitialSync = (lastMessageId == 0);
//
//        if (isInitialSync) {
//            LimController.log.info("новый чат ищем все сообщения: ");
            // Для нового устройства: всё, что есть в чатах пользователя
            sql = "SELECT m.* FROM messages m " +
                    "JOIN chat_members cm ON m.chat_id = cm.chat_id " +
                    "WHERE cm.user_id = ? AND m.id > ? " +
                    "ORDER BY m.id ASC";
//        } else {
//            LimController.log.info("Старый чат ищем новые сообщения: ");
//            // Для обычного опроса: только чужие сообщения
//            sql = "SELECT m.* FROM messages m " +
//                    "JOIN chat_members cm ON m.chat_id = cm.chat_id " +
//                    "WHERE cm.user_id = ? AND m.id > ? AND m.sender_id != ? " +
//                    "ORDER BY m.id ASC";
//        }

        try (Connection conn = getConnection(); PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, userId);
            stmt.setLong(2, lastMessageId);

//            if (!isInitialSync) {
//                stmt.setLong(3, userId); // Фильтруем себя только при обычном опросе
//            }

            try (ResultSet rs = stmt.executeQuery()) {
                List<Message> messages = new ArrayList<>();
                while (rs.next()) {
                    Message msg = new Message();
                    msg.id = rs.getLong("id");
                    LimController.log.info("считано новое сообщение msg.id = {}", msg.id);
                    msg.chatId = rs.getLong("chat_id");
                    msg.chatName = rs.getString("chat_name");
                    msg.senderId = rs.getLong("sender_id");
                    msg.text = rs.getString("text");
                    msg.type = rs.getString("type");
                    msg.timestamp = rs.getLong("timestamp");
                    msg.filePath = rs.getString("file_path");
                    msg.fileName = rs.getString("file_name");
                    messages.add(msg);
                }
                return messages;
            }
        } catch (SQLException e) {
            LimController.log.error("Ошибка при получении новых сообщений для юзера {}: ", userId, e);
        }
        return null;
    }

    @NotNull
    private String hashPassword(@NotNull String clientPasswordHash) {
        byte[] hash;
        digestLock.lock();
        try {
            mDigest.reset();
            hash = mDigest.digest(clientPasswordHash.getBytes(StandardCharsets.UTF_8));
        } finally {
            digestLock.unlock();
        }
        if (hash == null || hash.length == 0) {
            LimController.log.error("Сбой при вычислении хэша пароля MD5/SHA:");
            return LimController.EMPTY_STRING;
        }
        return bytesToHex(hash);
    }

    @NotNull
    private String bytesToHex(byte[] bytes) {
        char[] hexChars = new char[bytes.length * 2];
        for (int j = 0; j < bytes.length; j++) {
            int v = bytes[j] & 0xFF;
            hexChars[j * 2] = HEX_ARRAY[v >>> 4];
            hexChars[j * 2 + 1] = HEX_ARRAY[v & 0x0F];
        }
        return new String(hexChars);
    }

    private byte[] hexToBytes(String s) {
        int len = s.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(s.charAt(i), 16) << 4)
                    + Character.digit(s.charAt(i + 1), 16));
        }
        return data;
    }
}
