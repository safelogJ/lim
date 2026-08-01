package com.safelogj.limserver;

import com.safelogj.limserver.handler.BlockChatHandler;
import com.safelogj.limserver.handler.GetMessagesHandler;
import com.safelogj.limserver.handler.HideChatHandler;
import com.safelogj.limserver.handler.MediaDownloadHandler;
import com.safelogj.limserver.handler.MediaUploadHandler;
import com.safelogj.limserver.handler.RegisterUserHandler;
import com.safelogj.limserver.handler.SearchChatHandler;
import com.safelogj.limserver.handler.SearchUserHandler;
import com.safelogj.limserver.handler.SendMessageHandler;
import com.safelogj.limserver.handler.EditUserHandler;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsParameters;
import com.sun.net.httpserver.HttpsServer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.security.KeyManagementException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.UnrecoverableKeyException;
import java.security.cert.CertificateException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;

public class LimController {
    public static final Logger log = LoggerFactory.getLogger(LimController.class);
    public static final String EMPTY_STRING = "";
    private static final String USER_DIR = "user.dir";
    private static final String DB_PATH = System.getProperty(USER_DIR) + "/db";
    public static final String MEDIA_PATH = System.getProperty(USER_DIR) + "/media";
    public static final int ERROR = 1;
    public static final int DATA_ERR = 65;
    public static final long MEDIA_DOWNLOAD_LIFETIME = TimeUnit.DAYS.toMillis(30);
    private static final long MEDIA_DELETE_LIFETIME = TimeUnit.DAYS.toMillis(31);
    private static final Map<Long, Long> onlineUsers = new ConcurrentHashMap<>();
    public static DatabaseManager dbManager;
    private static ThreadPoolExecutor EXECUTOR_POOL;
    private static final ScheduledExecutorService CLEANUP_SCHEDULER =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "cleanup-thread");
                t.setDaemon(true);
                return t;
            });

    public static void main(String[] args) {
        try {
            System.setProperty("java.awt.headless", "true");
            File db = new File(DB_PATH);
            File media = new File(MEDIA_PATH);
            if (!db.exists() || !media.exists()) {
                log.error("folders db and media not found");
                System.exit(DATA_ERR);
            }
            HttpsServer server = initDbAndHttpsServer();
            closeAppListener(server);
            server.createContext("/register", new RegisterUserHandler());
            server.createContext("/user", new EditUserHandler());
            server.createContext("/chat/hide", new HideChatHandler());
            server.createContext("/chat/block", new BlockChatHandler());
            server.createContext("/chat/search", new SearchChatHandler());
            server.createContext("/user/search", new SearchUserHandler());
            server.createContext("/messages/send", new SendMessageHandler());
            server.createContext("/messages/get", new GetMessagesHandler());
            server.createContext("/media/upload", new MediaUploadHandler());
            server.createContext("/media/get", new MediaDownloadHandler());
            server.start();
            CLEANUP_SCHEDULER.scheduleWithFixedDelay(LimController::removeOldMedia, 24, 24, TimeUnit.HOURS);
            CLEANUP_SCHEDULER.scheduleWithFixedDelay(LimController::removeOfflineUsers, 10, 10, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.error("critical error while creating server: ", e);
            System.exit(ERROR);
        }
        log.info("LimServer run");
    }

    public static void setOnline(long userId) {
        onlineUsers.put(userId, System.currentTimeMillis());
    }

    public static boolean getOnlineStatus(Long id) {
       return onlineUsers.containsKey(id);
    }

    private static HttpsServer initDbAndHttpsServer() throws KeyStoreException, NullPointerException, IOException, NoSuchAlgorithmException,
            CertificateException, UnrecoverableKeyException, KeyManagementException, IllegalArgumentException {

        ServerConfig prop = ServerConfig.load(DB_PATH + "/server.properties");
        dbManager = new DatabaseManager(DB_PATH, prop.getDbPoolSize());
        dbManager.initDatabase();
        // 2. Загружаем Keystore в память Java
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (FileInputStream fis = new FileInputStream(prop.getKeystorePath())) {
            ks.load(fis, prop.getKeystorePassword());
        }
        // 3. Инициализируем менеджер ключей
        KeyManagerFactory kmf = KeyManagerFactory.getInstance("SunX509");
        kmf.init(ks, prop.getKeystorePassword());

        // 4. Создаем и настраиваем SSL-контекст (протокол TLS)
        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(kmf.getKeyManagers(), null, null);

        HttpsServer server = HttpsServer.create(new InetSocketAddress(443), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(sslContext) {
            @Override
            public void configure(HttpsParameters params) {
                try {
                    SSLContext context = getSSLContext();
                    params.setSSLParameters(context.getDefaultSSLParameters());
                } catch (Exception e) {
                    LimController.log.error("HTTPS settings configuration error: ", e);
                }
            }
        });
        EXECUTOR_POOL = ServerThreadPool.createPool(prop.getServerPoolSize(), prop.getServerQueueSize());
        server.setExecutor(EXECUTOR_POOL);
        return server;
    }

    private static void closeAppListener(HttpServer server) {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("📥 The signal to stop the container has been received. We are beginning a smooth shutdown....");
            server.stop(1);
            log.info("HttpServer has stopped. New requests are not accepted..");
            EXECUTOR_POOL.shutdown();
            CLEANUP_SCHEDULER.shutdown();
            log.info("⏳ Waiting for active tasks in the thread pool to complete...");
            try {
                if (!EXECUTOR_POOL.awaitTermination(5, TimeUnit.SECONDS)) {
                    log.warn("⚠️ Some tasks did not complete on time. Forced pool stop.");
                    EXECUTOR_POOL.shutdownNow(); // Если не успели — режем жестко
                }
                log.info("The thread pool was stopped successfully..");
            } catch (InterruptedException e) {
                log.error("Error waiting for pool to stop: ", e);
                EXECUTOR_POOL.shutdownNow();
                Thread.currentThread().interrupt();
            }
            try {
                if (!CLEANUP_SCHEDULER.awaitTermination(5, TimeUnit.SECONDS)) {
                    log.info("Cleanup scheduler not complete on time...");
                    CLEANUP_SCHEDULER.shutdownNow();
                }
                log.info("Cleanup scheduler stopped successfully..");
            } catch (InterruptedException e) {
                log.error("Error waiting for Cleanup scheduler to stop: ", e);
                CLEANUP_SCHEDULER.shutdownNow();
                Thread.currentThread().interrupt();
            }
            dbManager.close();
            log.info("🛑 All resources have been released. The container has been stopped successfully. Bye!");
        }));
    }

    private static void removeOldMedia() {
        File mediaDir = new File(MEDIA_PATH);
        long now = System.currentTimeMillis();
        try {
            File[] files = mediaDir.listFiles();
            if (files != null) {
                for (File file : files) {
                    if (file.isFile() && (now - file.lastModified() > MEDIA_DELETE_LIFETIME) && Files.deleteIfExists(file.toPath())) {
                        log.info("File {} older than 31 days deleted successfully", file.getName());
                    }
                }
            } else {
                log.error("⚠️ Error getting file list in {}", mediaDir.getName());
            }
        } catch (Exception e) {
            log.error("⚠️ Error deleting files in {}  {}", mediaDir.getName(), e.getMessage());
        }
    }

    private static void removeOfflineUsers() {
        long now = System.currentTimeMillis();
        onlineUsers.entrySet().removeIf(entry -> (now - entry.getValue() > 12000));
    }
}