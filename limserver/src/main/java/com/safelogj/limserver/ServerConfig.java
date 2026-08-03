package com.safelogj.limserver;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Properties;

public class ServerConfig {
    private final String keystorePath;
    private final char[] keystorePassword;
    private final int serverPoolSize;
    private final int serverQueueSize;
    private final int dbPoolSize;
    private final long mediaQuota;
    private final long diskSafeMargin;

    private ServerConfig(String keystorePath, char[] keystorePassword, int serverPoolSize, int serverQueueSize,
                         int dbPoolSize, long mediaQuota, long diskSafeMargin) {
        this.keystorePath = keystorePath;
        this.keystorePassword = keystorePassword;
        this.serverPoolSize = serverPoolSize;
        this.serverQueueSize = serverQueueSize;
        this.dbPoolSize = dbPoolSize;
        this.mediaQuota = mediaQuota;
        this.diskSafeMargin = diskSafeMargin;
    }

    public String getKeystorePath() {
        return keystorePath;
    }

    public char[] getKeystorePassword() {
        return keystorePassword;
    }

    public int getServerPoolSize() {
        return serverPoolSize;
    }

    public int getServerQueueSize() {
        return serverQueueSize;
    }

    public int getDbPoolSize() {
        return dbPoolSize;
    }

    public long getMediaQuota() { return mediaQuota; }
    public long getDiskSafeMargin() { return diskSafeMargin; }


    public static ServerConfig load(String configPath) throws IllegalArgumentException, IOException {
        Properties props = new Properties();
        try (FileInputStream fis = new FileInputStream(configPath)) {
            props.load(fis);
        }

        String path = props.getProperty("keystore.path");
        if (path == null || path.trim().isEmpty()) {
            throw new IllegalArgumentException("Config error: 'keystore.path' is not specified.");
        }
        path = path.trim();
        if (!path.toLowerCase().endsWith(".p12")) {
            throw new IllegalArgumentException("Config error: The storage file must have the extension .p12. Specified: " + path);
        }

        File keystoreFile = new File(path);
        if (!keystoreFile.exists() || !keystoreFile.isFile()) {
            throw new IllegalArgumentException("Configuration error: Storage file not found at path: " + keystoreFile.getAbsolutePath());
        }

        String pass = props.getProperty("keystore.password");
        if (pass == null || pass.trim().isEmpty()) {
            throw new IllegalArgumentException("Configuration error: 'keystore.password' cannot be empty.");
        }
        if (pass.length() < 4) {
            throw new IllegalArgumentException("Config error: 'keystore.password' is too short (minimum 4 characters).");
        }
        return new ServerConfig(path, pass.toCharArray(),
                Integer.parseInt(props.getProperty("server.pool.size", "8")),
                Integer.parseInt(props.getProperty("server.queue.size", "2")),
                Integer.parseInt(props.getProperty("db.connect.size", "8")),
                Long.parseLong(props.getProperty("server.media.quota.mb", "50")) * 1024 * 1024,
                Long.parseLong(props.getProperty("server.disk.safe_margin.mb", "20")) * 1024 * 1024

        );
    }
}
