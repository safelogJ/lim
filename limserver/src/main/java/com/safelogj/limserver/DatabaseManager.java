package com.safelogj.limserver;

import com.safelogj.limserver.model.Chat;
import com.safelogj.limserver.model.Message;
import com.safelogj.limserver.model.User;
import com.safelogj.limserver.request.EditUserRequest;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
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
import java.util.Objects;

public class DatabaseManager {

    public static final long FILE_SIZE_LIMIT = 50_000_000L;
    private static final String DB_FILE = "lim.db";
    private static final ThreadLocal<MessageDigest> DIGEST =
            ThreadLocal.withInitial(() -> {
                try {
                    return MessageDigest.getInstance("SHA-256"); // или MD5
                } catch (NoSuchAlgorithmException e) {
                    throw new IllegalStateException(e);
                }
            });
    @NotNull
    private static final char[] HEX_ARRAY = "0123456789abcdef".toCharArray();
    private final HikariDataSource dataSource;
    private final HikariPoolMXBean poolProxy;

    DatabaseManager(String dbFolderPath, int poolSize) {
        HikariConfig config = getHikariConfig(dbFolderPath, poolSize);
        // Оптимизации SQLite прямо в URL или через свойства
        config.addDataSourceProperty("journal_mode", "WAL");
        config.addDataSourceProperty("busy_timeout", "5000");
        config.addDataSourceProperty("synchronous", "NORMAL");
        config.addDataSourceProperty("cache_size", "-16000");
        dataSource = new HikariDataSource(config);
        poolProxy = dataSource.getHikariPoolMXBean();
    }

    @NotNull
    private HikariConfig getHikariConfig(String dbFolderPath, int poolSize) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:sqlite:" + dbFolderPath + "/" + DB_FILE);
        config.setMaximumPoolSize(poolSize);
        config.setMinimumIdle(2); // Минимум 2 всегда открыты
        config.setConnectionTimeout(5000); // Ждать соединение из пула не более 5 сек
        config.setIdleTimeout(1200000);    // Закрывать лишние через 20 мин простоя
        config.setMaxLifetime(3600000);     // Макс. время жизни соединения
        return config;
    }

    // Метод для получения живого соединения с базой
    private Connection getConnection() throws SQLException {
        if (poolProxy != null && poolProxy.getThreadsAwaitingConnection() > 0) {
            LimController.log.warn("ATTENTION: There is a queue for the database! Waiting: {}", poolProxy.getThreadsAwaitingConnection());
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
                "username TEXT NOT NULL UNIQUE, " +
                "password_hash TEXT NOT NULL, " +
                "public_key TEXT NOT NULL, " +   // Публичный ключ
                "private_hash TEXT NOT NULL, " +   // Хэш приватного ключа
                "display_name TEXT NOT NULL, " +
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
                "timestamp INTEGER NOT NULL" +  // Время в миллисекундах
                ")";

        try (Connection conn = getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute(createUsersTable);
            stmt.execute(createChatsTable);
            stmt.execute(createChatMembersTable);
            stmt.execute(createMessagesTable);
            LimController.log.info("The SQLite database has been successfully initialized. Tables have been verified..");
        } catch (Exception e) {
            LimController.log.error("critical error while initializing database: ", e);
            System.exit(LimController.ERROR);
        }
    }

    @Nullable
    public User registerUser(@NotNull String username, @NotNull String clientPasswordHash, @NotNull String displayName,
                             @NotNull String publicKey, @NotNull String privateHash) throws SQLException {
        String serverPasswordHash = hashPassword(clientPasswordHash);
        if (serverPasswordHash.isEmpty()) {
            throw new SQLException("critical error: password hashing failed");
        }

        try (Connection conn = getConnection()) {
            int oldIsolation = conn.getTransactionIsolation();

            try {
                conn.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
                conn.setAutoCommit(false);
                try (PreparedStatement checkStmt = conn.prepareStatement("SELECT 1 FROM users WHERE username = ? LIMIT 1")) {
                    checkStmt.setString(1, username);
                    try (ResultSet rs = checkStmt.executeQuery()) {
                        if (rs.next()) {
                            LimController.log.warn("registration attempt: login '{}' is already taken", username);
                            return null;
                        }
                    }
                }
                long userId = -1;
                try (PreparedStatement insertStmt = conn.prepareStatement(
                        "INSERT INTO users (username, password_hash, public_key, private_hash, display_name, created_at) VALUES (?, ?, ?, ?, ?, ?)", Statement.RETURN_GENERATED_KEYS)) {
                    insertStmt.setString(1, username);
                    insertStmt.setString(2, serverPasswordHash);
                    insertStmt.setString(3, publicKey);
                    insertStmt.setString(4, privateHash);
                    insertStmt.setString(5, displayName);
                    insertStmt.setLong(6, System.currentTimeMillis());
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
                conn.commit();
                User user = new User();
                user.id = userId;
                user.displayName = displayName;
                return user;

            } catch (SQLException e) {
                // Откатываем ТОЛЬКО если реально была ошибка при записи
                try {
                    conn.rollback();
                } catch (SQLException ex) {
                    LimController.log.error("failed to roll back transaction: ", ex);
                }
                throw e; // Пробрасываем наверх для логирования в главном catch
            } finally {
                // Возвращаем коннект в пул в исходном состоянии
                try {
                    conn.setAutoCommit(true);
                    conn.setTransactionIsolation(oldIsolation);
                } catch (SQLException ex) {
                    LimController.log.error("error restoring connection settings: ", ex);
                }
            }

        } catch (SQLException e) {
            LimController.log.error("critical database error during registration: ", e);
            return null;
        }
    }

    @Nullable
    public User authenticateUser(@NotNull String username, @NotNull String password) {
        String serverPasswordHash = hashPassword(password);
        if (!serverPasswordHash.isEmpty()) {
            try (Connection conn = getConnection();
                 PreparedStatement stmt = conn.prepareStatement(
                         "SELECT id, display_name, password_hash, public_key, private_hash FROM users WHERE username = ? AND is_deleted = 0")) {
                stmt.setString(1, username);
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next() && rs.getString(3).equals(serverPasswordHash)) {
                        User user = new User();
                        user.id = rs.getLong(1);
                        user.displayName = rs.getString(2);
                        user.publicKey = rs.getString(4);
                        user.privateHash = rs.getString(5);
                        return user;
                    } else {
                        LimController.log.warn("incorrect password for user '{}'", username);
                    }
                }
            } catch (Exception e) {
                LimController.log.error("an error occurred while authenticating the user during the request.: ", e);
            }
        } else {
            LimController.log.error("error while authenticating user, password hash not retrieved ");
        }
        return null;
    }

    public boolean deleteUser(long userId) {
        try (Connection conn = getConnection(); PreparedStatement stmt = conn.prepareStatement(
                "UPDATE users SET is_deleted = 1, password_hash = '', public_key = '', private_hash = '', "
                        + "display_name = 'Deleted account' WHERE id = ? AND is_deleted = 0")) {
            stmt.setLong(1, userId);
            if (stmt.executeUpdate() > 0) {
                LimController.log.info("User id={} has been successfully moved to 'Deleted' status. Login blocked.", userId);
                return true;
            } else {
                LimController.log.warn("failed to delete user id={}. It may have already been deleted or may not have existed.", userId);
            }
        } catch (Exception e) {
            LimController.log.error("error while soft deleting user id={}", userId, e);
        }
        return false;
    }

    public boolean isUserDeleted(@NotNull String username) {
        try (Connection conn = getConnection(); PreparedStatement stmt = conn.prepareStatement(
                "SELECT 1 FROM users WHERE username = ? AND is_deleted = 1 LIMIT 1")) {
            stmt.setString(1, username);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            LimController.log.error("Error checking deleted status for user '{}': ", username, e);
            return false;
        }
    }

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
                            chatId = rs.getLong(1);
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
                                throw new SQLException("failed to get new chat ID.");
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

    public boolean blockChat(long chatId, long userId) {
        try (Connection conn = getConnection(); PreparedStatement stmt = conn.prepareStatement(
                "UPDATE chat_members SET is_blocked = 1 WHERE chat_id = ? AND user_id = ?")) {
            stmt.setLong(1, chatId);
            stmt.setLong(2, userId);
            return stmt.executeUpdate() > 0;
        } catch (SQLException e) {
            LimController.log.error("error hiding chat {} for user {}: ", chatId, userId, e);
            return false;
        }
    }

    @Nullable
    public User updateUser(long userId, @NotNull EditUserRequest req, boolean isNewDisplayName, boolean isNewPassword) {
        String newDisplayName = req.newDisplayName();
        String newClientPasswordHash = req.newPassword();
        String newServerPasswordHash = null;
        // 2. Хэшируем пароль заранее
        if (isNewPassword) {
            newServerPasswordHash = hashPassword(newClientPasswordHash);
            if (newServerPasswordHash.isEmpty()) {
                LimController.log.error("error generating hash for update id={}", userId);
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
                LimController.log.info("user data id={} has been updated successfully.", userId);
                return new User();
            } else {
                LimController.log.warn("update failed: user id={} not found or deleted.", userId);
            }
        } catch (SQLException e) {
            LimController.log.error("database error while updating user id={}: ", userId, e);
        }
        return null;
    }

    @Nullable
    public User searchUserByUsername(@NotNull String targetUsername) {
        try (Connection conn = getConnection(); PreparedStatement stmt = conn.prepareStatement(
                "SELECT id, display_name, public_key FROM users WHERE username = ? AND is_deleted = 0")) {
            stmt.setString(1, targetUsername);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    User user = new User();
                    user.id = rs.getLong(1);
                    user.username = targetUsername;
                    user.displayName = rs.getString(2);
                    user.publicKey = rs.getString(3);
                    return user;
                }
            }
        } catch (SQLException e) {
            LimController.log.error("error searching for user '{}'", targetUsername, e);
        }
        return null;
    }

    @Nullable
    public User searchUserByChatId(@NotNull Long userId, @NotNull Long chatId) {
        try (Connection conn = getConnection(); PreparedStatement stmt = conn.prepareStatement(
                     "SELECT u.id, u.username, u.display_name, u.public_key FROM users u " +
                     "JOIN chat_members cm ON u.id = cm.user_id " +
                     "WHERE cm.chat_id = ? AND cm.user_id != ? LIMIT 1")) {
            stmt.setLong(1, chatId);
            stmt.setLong(2, userId);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    User user = new User();
                    user.id = rs.getLong(1);
                    user.username = rs.getString(2);
                    user.displayName = rs.getString(3);
                    user.publicKey = rs.getString(4);
                    return user;
                }
            }
        } catch (SQLException e) {
            LimController.log.error("error searching for user in chat '{}'", chatId, e);
        }
        return null;
    }

    public long saveMessage(long chatId, long senderId, String text, String type, String chatName,
                            String filePath, String fileName, long timestamp) {
        try (Connection conn = getConnection()) {
            conn.setAutoCommit(false);

            try (PreparedStatement insertStmt = conn.prepareStatement(
                    "INSERT INTO messages (chat_id, sender_id, text, type, chat_name, file_path, file_name, timestamp) VALUES (?, ?, ?, ?, ?, ?, ?, ?)", Statement.RETURN_GENERATED_KEYS);
                 PreparedStatement updateStmt = conn.prepareStatement(
                         "UPDATE chat_members SET is_hidden = 0 WHERE chat_id = ?");
                 PreparedStatement updateBlock = conn.prepareStatement(
                         "UPDATE chat_members SET is_blocked = 0 WHERE chat_id = ? AND user_id = ?")) {

                insertStmt.setLong(1, chatId);
                insertStmt.setLong(2, senderId);
                insertStmt.setString(3, text);
                insertStmt.setString(4, type);
                insertStmt.setString(5, chatName);
                insertStmt.setString(6, filePath);
                insertStmt.setString(7, fileName);
                insertStmt.setLong(8, timestamp);

                if (insertStmt.executeUpdate() == 0) {
                    conn.rollback();
                    return Message.INVALID_MSG_ID;
                }
                long serverId = Message.INVALID_MSG_ID;
                try (ResultSet rs = insertStmt.getGeneratedKeys()) {
                    if (rs.next()) serverId = rs.getLong(1);
                }
                LimController.log.info("+++ save: msg_id={}, in chat={}, from id={} +++", serverId, chatId, senderId);
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
            LimController.log.error("error while saving message and updating participants: ", e);
            return Message.INVALID_MSG_ID;
        }
    }

    @Nullable
    public List<Message> getNewMessages(long userId, long lastMessageId) {
        // Мы добавляем JOIN с пользователями, чтобы достать их Display Name
        // И используем CASE для выбора имени:
        // если я отправитель - оставляем мое название чата (для синхронизации),
        // если нет - берем имя отправителя (для идентификации).

        try (Connection conn = getConnection(); PreparedStatement stmt = conn.prepareStatement(
                "SELECT m.id, m.chat_id, m.sender_id, m.text, m.type, m.file_path, m.file_name, " +
                        "CASE WHEN m.sender_id = ? THEN m.chat_name ELSE u_sender.display_name END, " +
                        "m.timestamp, u_interlocutor.public_key " + // <--- КЛЮЧ СОБЕСЕДНИКА
                        "FROM messages m " +
                        "JOIN chat_members cm_me ON m.chat_id = cm_me.chat_id AND cm_me.user_id = ? " +
                        "JOIN chat_members cm_other ON m.chat_id = cm_other.chat_id AND cm_other.user_id != ? " +
                        "JOIN users u_interlocutor ON cm_other.user_id = u_interlocutor.id " +
                        "JOIN users u_sender ON m.sender_id = u_sender.id " +
                        "WHERE m.id > ? " +
                        "ORDER BY m.id ASC")) {
            stmt.setLong(1, userId);
            stmt.setLong(2, userId);
            stmt.setLong(3, userId);
            stmt.setLong(4, lastMessageId);

            try (ResultSet rs = stmt.executeQuery()) {
                List<Message> messages = new ArrayList<>();
                while (rs.next()) {
                    Message msg = new Message();
                    msg.id = rs.getLong(1);
                    msg.chatId = rs.getLong(2);
                    msg.senderId = rs.getLong(3);
                    msg.text = rs.getString(4);
                    msg.type = rs.getString(5);
                    msg.filePath = rs.getString(6);
                    msg.fileName = rs.getString(7);
                    msg.chatName = rs.getString(8);
                    msg.timestamp = rs.getLong(9);
                    msg.interlocutorPublicKey = rs.getString(10);
                    messages.add(msg);
                }
                return messages;
            }
        } catch (SQLException e) {
            LimController.log.error("error receiving messages: ", e);
        }
        return null;
    }

    public boolean isFileAccessible(long userId, long chatId, String serverFileName) {
        try (Connection conn = getConnection(); PreparedStatement stmt = conn.prepareStatement(
                "SELECT 1 FROM messages m " +
                "JOIN chat_members cm ON m.chat_id = cm.chat_id " +
                "WHERE m.chat_id = ? AND m.file_path = ? AND cm.user_id = ? LIMIT 1")) {
            stmt.setLong(1, chatId);
            stmt.setString(2, serverFileName);
            stmt.setLong(3, userId);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            LimController.log.error("Error checking file accessibility: ", e);
            return false;
        }
    }


    public boolean isMemberOfChat(long userId, long chatId) {
        try (Connection conn = getConnection(); PreparedStatement stmt = conn.prepareStatement(
                "SELECT 1 FROM chat_members WHERE chat_id = ? AND user_id = ? LIMIT 1")) {
            stmt.setLong(1, chatId);
            stmt.setLong(2, userId);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            LimController.log.error("Security check error: ", e);
            return false;
        }
    }

    @NotNull
    private String hashPassword(@NotNull String clientPasswordHash) {
        return bytesToHex(Objects.requireNonNull(DIGEST.get()).digest(clientPasswordHash.getBytes(StandardCharsets.UTF_8)));
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

}