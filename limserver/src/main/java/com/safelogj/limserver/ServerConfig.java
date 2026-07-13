package com.safelogj.limserver;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Properties;

public class ServerConfig {
    private final String keystorePath;
    private final char[] keystorePassword;

    private ServerConfig(String keystorePath, char[] keystorePassword) {
        this.keystorePath = keystorePath;
        this.keystorePassword = keystorePassword;
    }

    public String getKeystorePath() {
        return keystorePath;
    }

    public char[] getKeystorePassword() {
        return keystorePassword;
    }

    public static ServerConfig load(String configPath) throws IllegalArgumentException, IOException {
        Properties props = new Properties();
        try (FileInputStream fis = new FileInputStream(configPath)) {
            props.load(fis);
        }

        // 1. Валидация ПУТИ К КЕЙСТОРУ
        String path = props.getProperty("keystore.path");
        if (path == null || path.trim().isEmpty()) {
            throw new IllegalArgumentException("Ошибка конфига: 'keystore.path' не указан.");
        }
        path = path.trim();
        if (!path.toLowerCase().endsWith(".p12")) {
            throw new IllegalArgumentException("Ошибка конфига: файл хранилища должен иметь расширение .p12. Указано: " + path);
        }

        // Дополнительно проверяем, существует ли этот файл физически на роутере
        File keystoreFile = new File(path);
        if (!keystoreFile.exists() || !keystoreFile.isFile()) {
            throw new IllegalArgumentException("Ошибка конфига: файл хранилища не найден по пути: " + keystoreFile.getAbsolutePath());
        }

        // 2. Валидация ПАРОЛЯ
        String pass = props.getProperty("keystore.password");
        if (pass == null || pass.trim().isEmpty()) {
            throw new IllegalArgumentException("Ошибка конфига: 'keystore.password' не может быть пустым.");
        }
        if (pass.length() < 4) {
            throw new IllegalArgumentException("Ошибка конфига: 'keystore.password' слишком короткий (минимум 4 символа).");
        }

        // Если всё прошло идеально — возвращаем чистый, валидный объект
        return new ServerConfig(path, pass.toCharArray());
    }
}
