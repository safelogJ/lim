package com.safelogj.limserver;

import com.safelogj.limserver.handler.AuthUserHandler;
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
import java.security.KeyManagementException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.UnrecoverableKeyException;
import java.security.cert.CertificateException;
import java.util.concurrent.LinkedBlockingQueue;
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
    public static final DatabaseManager dbManager = new DatabaseManager(DB_PATH);
    public static final int ERROR = 1;
    public static final int DATA_ERR = 65;

    private static final ThreadPoolExecutor EXECUTOR_POOL = new ThreadPoolExecutor(2, 8, 30L,
            TimeUnit.MINUTES, new LinkedBlockingQueue<>(500), new ThreadPoolExecutor.CallerRunsPolicy());


    public static void main(String[] args) {
        try {
            System.setProperty("java.awt.headless", "true");
            File db = new File(DB_PATH);
            File media = new File(MEDIA_PATH);
            if ((!db.exists() && !db.mkdirs()) || (!media.exists() && !media.mkdirs())) {
                log.error("Критическая ошибка: Не удалось создать папки db и media");
                System.exit(DATA_ERR);
            }
            dbManager.initDatabase();
            HttpsServer server = initHttpsServer();
            closeAppListener(server);
            server.createContext("/register", new RegisterUserHandler());
            server.createContext("/auth", new AuthUserHandler());
            server.createContext("/user", new EditUserHandler());
            server.createContext("/chat/hide", new HideChatHandler());
            server.createContext("/chat/block", new BlockChatHandler());
            server.createContext("/chat/search", new SearchChatHandler());
            server.createContext("/user/search", new SearchUserHandler());
            server.createContext("/messages/send", new SendMessageHandler());
            server.createContext("/messages/get", new GetMessagesHandler());
            server.createContext("/media/upload", new MediaUploadHandler());
            server.createContext("/media/get", new MediaDownloadHandler());
            server.setExecutor(EXECUTOR_POOL);
            server.start();
        } catch (Exception e) {
            log.error("Критическая ошибка при создании сервера: ", e);
            System.exit(ERROR);
        }
        log.info("Сервер запустился");
    }

    private static HttpsServer initHttpsServer() throws KeyStoreException, NullPointerException, IOException, NoSuchAlgorithmException,
            CertificateException, UnrecoverableKeyException, KeyManagementException, IllegalArgumentException {

        ServerConfig prop = ServerConfig.load(System.getProperty(USER_DIR) +  "/server.properties");
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
                    LimController.log.error("Ошибка конфигурации параметров HTTPS: ", e);
                }
            }
        });
        return server;
    }

    private static void closeAppListener(HttpServer server) {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("📥 Получен сигнал на остановку контейнера. Начинаем плавное завершение...");
            server.stop(1);
            log.info("[-] HttpServer остановлен. Новые запросы не принимаются.");
            EXECUTOR_POOL.shutdown();
            log.info("⏳ Ожидаем завершения активных задач в пуле потоков...");
            try {
                if (!EXECUTOR_POOL.awaitTermination(5, TimeUnit.SECONDS)) {
                    log.warn("⚠️ Некоторые задачи не успели завершиться вовремя. Принудительная остановка пула.");
                    EXECUTOR_POOL.shutdownNow(); // Если не успели — режем жестко
                }
                log.info("[+] Пул потоков успешно остановлен.");
            } catch (InterruptedException e) {
                log.error("Ошибка при ожидании остановки пула: ", e);
                EXECUTOR_POOL.shutdownNow();
                Thread.currentThread().interrupt();
            }
            dbManager.close();
            log.info("🛑 Все ресурсы освобождены. Контейнер успешно остановлен. Пока!");
        }));
    }
}