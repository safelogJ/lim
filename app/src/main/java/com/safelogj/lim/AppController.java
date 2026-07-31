package com.safelogj.lim;

import android.app.Activity;
import android.app.Application;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.os.Build;
import android.os.Bundle;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.MutableLiveData;
import androidx.work.BackoffPolicy;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.work.WorkRequest;

import com.safelogj.lim.model.Message;
import com.safelogj.lim.viewmodels.ResultCallback;

import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.SecureRandom;
import java.security.UnrecoverableEntryException;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.KeyAgreement;
import javax.crypto.KeyGenerator;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import okhttp3.Dispatcher;
import okhttp3.OkHttpClient;

public class AppController extends Application {
    public static final String LOG_TAG = "lim";
    public static final Constraints constraints = new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build();
    public static final Map<Long, Map<Long, Boolean>> onlineUsersChats = new ConcurrentHashMap<>();
    public static final String EMPTY_STRING = "";
    public static final int QUEUE_SIZE = 100;
    public static final int POOL_SIZE = 5;  // 0-2 отправка, 3 качает файлы, 4 получает сообщения
    public static final String NOTIFICATION_CHANNEL = "lim_messages";
    public static final String LIM_SYNC = "LimSync";
    private static final String USER_DATA = "userdata";
    private static final String USER_DATA_JSON = "userdata.txt";
    private static final String USER_ID = "userid";
    private static final String USER_NAME = "username";
    private static final String USER_PASS = "userpass";
    private static final String USER_DISPLAY_NAME = "userdisplayname";
    private static final String SERVER_CERT = "servercert";
    private static final String SERVER_CERT_NAME = "servercertname";
    private static final String SERVER_URL = "serverurl";
    private static final String SERVER_IP = "serverip";
    private static final String E2EE_PRIVATE_KEY = "e2eePrivateKey";
    private static final String E2EE_PUBLIC_KEY = "e2eePublicKey";
    private static final String KEY_ALIAS = "MikrotikRouterKeyAlias";
    private static final String ANDROID_KEYSTORE = "AndroidKeyStore";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int GCM_TAG_LENGTH = 16;
    private static final int AES_KEY_SIZE = 256;
    private static final String ENCRYPTED_DATA_KEY = "encryptedData";    // Константы для E2EE
    private static final String PBKDF2_ALGORITHM = "PBKDF2WithHmacSHA256";
    public static final int CHAT_COLOR_GREEN = 0;
    public static final int CHAT_COLOR_PINK = 1;
    public static final int CHAT_COLOR_YELLOW = 2;
    public static final int CHAT_COLOR_BLUE = 3;
    private static final ThreadLocal<MessageDigest> SHA256 =
            ThreadLocal.withInitial(() -> {
                try {
                    return MessageDigest.getInstance("SHA-256");
                } catch (NoSuchAlgorithmException e) {
                    throw new RuntimeException(e);
                }
            });
    private static final ThreadLocal<KeyAgreement> ECDH =
            ThreadLocal.withInitial(() -> {
                try {
                    return KeyAgreement.getInstance("ECDH");
                } catch (NoSuchAlgorithmException e) {
                    throw new RuntimeException(e);
                }
            });
    private static final KeyFactory EC_KEY_FACTORY;
    public final AtomicInteger activeDownloadsCount = new AtomicInteger(0);
    public final AtomicInteger startedActivities = new AtomicInteger(0);
    private final ExecutorService dbExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService userExecutor = Executors.newSingleThreadExecutor();
    private final ScheduledExecutorService syncExecutor = Executors.newSingleThreadScheduledExecutor();
    private final AtomicBoolean isNetworkActive = new AtomicBoolean(false);
    private final ExecutorService[] netStreams = new ExecutorService[POOL_SIZE];
    private final DateTimeFormatter timeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault());
    private final DateTimeFormatter dateFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy", Locale.getDefault());
    private final DateTimeFormatter dayMonthFormatter = DateTimeFormatter.ofPattern("dd MMM", Locale.getDefault());
    private final SecureRandom secureRandom = new SecureRandom();
    private final ConcurrentHashMap<String, SecretKey> sharedKeys = new ConcurrentHashMap<>();
    private NetworkService networkService;
    private ScheduledFuture<?> syncTask;
    private File mExternalFileDir;
    private OkHttpClient okHttpClient;
    private DatabaseHelper dbHelper;
    private boolean initAppError;
    private String initAppErrStr = EMPTY_STRING;

    @NonNull
    private volatile String e2eePrivateKey = EMPTY_STRING;
    @NonNull
    private volatile String e2eePublicKey = EMPTY_STRING;
    private volatile byte[] certBytes;
    @NonNull
    private volatile String certName = EMPTY_STRING;
    @NonNull
    private volatile String username = EMPTY_STRING;
    private volatile long userId;
    @NonNull
    private volatile String password = EMPTY_STRING;
    @NonNull
    private volatile String displayName = EMPTY_STRING;
    @NonNull
    private volatile String serverUrl = EMPTY_STRING;
    @NonNull
    private volatile String serverIp = EMPTY_STRING;


    static {
        try {
            EC_KEY_FACTORY = KeyFactory.getInstance("EC");
        } catch (NoSuchAlgorithmException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    public boolean isInitAppError() {
        return initAppError;
    }

    public void setInitAppError(boolean initAppError) {
        this.initAppError = initAppError;
    }

    public String getInitAppErrStr() {
        return initAppErrStr;
    }

    public OkHttpClient getOkHttpClient() {
        return okHttpClient;
    }

    public DatabaseHelper getDbHelper() {
        return dbHelper;
    }

    public ExecutorService getDbExecutor() {
        return dbExecutor;
    }

    @NonNull
    public String getDisplayName() {
        return displayName;
    }

    @NonNull
    public String getPassword() {
        return password;
    }

    public long getUserId() {
        return userId;
    }

    @NonNull
    public String getUsername() {
        return username;
    }

    @NonNull
    public String getServerUrl() {
        return serverUrl;
    }

    public void setCertBytes(byte[] certBytes) {
        this.certBytes = certBytes;
    }

    public void setCertName(@NonNull String certName) {
        this.certName = certName;
    }

    public void setUsername(@NonNull String username) {
        this.username = username;
    }

    public void setUserId(long userId) {
        this.userId = userId;
    }

    public void setPassword(@NonNull String password) {
        this.password = password;
    }

    public void setDisplayName(@NonNull String displayName) {
        this.displayName = displayName;
    }

    public void eraseUser() {
        userId = 0;
        username = EMPTY_STRING;
        password = EMPTY_STRING;
        displayName = EMPTY_STRING;
        e2eePrivateKey = EMPTY_STRING;
        e2eePublicKey = EMPTY_STRING;
    }

    public void setServerUrl(@NonNull String serverIp) {
        serverUrl = "https://" + serverIp + ":443";
    }

    @NonNull
    public String getCertName() {
        return certName;
    }

    @NonNull
    public String getServerIp() {
        return serverIp;
    }

    public void setServerIp(@NonNull String serverIp) {
        this.serverIp = serverIp;
    }

    public NetworkService getNetworkService() {
        return networkService;
    }

    public ExecutorService getUserExecutor() {
        return userExecutor;
    }

    public ExecutorService[] getNetStreams() {
        return netStreams;
    }

    @Nullable
    public File getExternalFileDir() {
        return mExternalFileDir;
    }

    @NonNull
    public String getE2eePublicKey() {
        return e2eePublicKey;
    }

    public void setE2eePublicKey(@NonNull String e2eePublicKey) {
        this.e2eePublicKey = e2eePublicKey;
    }

    public SecureRandom getSecureRandom() {
        return secureRandom;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        mExternalFileDir = getExternalFilesDir(null);
        regActivityListener();
        registerNetworkCallback();
        readRoutersListAndSettingsEncrypted();
        initOkHttpClient();
        dbHelper = new DatabaseHelper(this);
        networkService = new NetworkService(this);
        initStreams();
        dbHelper.initDatabase();
        if (userId > 0) {
            dbHelper.initOnlineStatuses();
        }
        createNotificationChannel();
        setupWorkManager();
    }

    public void writeSettingsToFile() {
        userExecutor.execute(this::writeRoutersListAndSettingsEncrypted);
    }

    public void startDownloadNewMsg() {
        dbHelper.getLastDbMessageId(new ResultCallback<>() {
            @Override
            public void onSuccess(Long lastServerId) {
                activeDownloadsCount.incrementAndGet();
                netStreams[POOL_SIZE - 1].execute(() -> networkService.getNewMessages(lastServerId, new ArrayList<>(onlineUsersChats.keySet())));
            }

            @Override
            public void onError(String msg) {
                Log.d(LOG_TAG, msg);
            }
        });
    }

    public void startSendingMsgList() {
        dbHelper.getPendingMessages(new ResultCallback<>() {
            @Override
            public void onSuccess(List<Message> messages) {
                for (Message msg : messages) {
                    if (msg.type.equals(Message.TYPE_TEXT)) {
                        netStreams[Math.abs((int) (msg.localChatId % (POOL_SIZE - 2)))].execute(() -> networkService.sendTextMessage(msg));
                    } else {
                        netStreams[Math.abs((int) (msg.localChatId % (POOL_SIZE - 2)))].execute(() -> networkService.sendMediaMessage(msg));
                    }
                }
            }

            @Override
            public void onError(String msg) {
                //
            }

        });
    }

    private void readRoutersListAndSettingsEncrypted() {
        File userDataDir = new File(getFilesDir(), USER_DATA);
        File userDataFile = new File(userDataDir, USER_DATA_JSON);
        StringBuilder fileContent = new StringBuilder();

        if (!userDataFile.exists()) {
            Log.d(LOG_TAG, "Encrypted settings file not found.");
            return;
        }
        // 1. Чтение содержимого файла-оболочки
        try (FileReader reader = new FileReader(userDataFile)) {
            char[] buffer = new char[1024];
            int length;
            while ((length = reader.read(buffer)) != -1) {
                fileContent.append(buffer, 0, length);
            }
        } catch (IOException e) {
            String msg = "Error reading encrypted settings file: " + e.getMessage();
            Log.d(LOG_TAG, msg);
            initAppError = true;
            initAppErrStr = msg;
            return;
        }
        // 2. Извлечение и дешифрование данных
        try {
            JSONObject fileWrapper = new JSONObject(fileContent.toString());
            String encryptedBase64 = fileWrapper.getString(ENCRYPTED_DATA_KEY);
            // Декодирование и дешифрование
            byte[] combinedBytes = Base64.decode(encryptedBase64, Base64.NO_WRAP);
            byte[] decryptedBytes = decrypt(combinedBytes);
            String rawJsonString = new String(decryptedBytes, StandardCharsets.UTF_8);
            // 3. Парсинг дешифрованного полного JSON
            JSONObject json = new JSONObject(rawJsonString);
            userId = json.optLong(USER_ID, 0);
            username = json.optString(USER_NAME, EMPTY_STRING);
            password = json.optString(USER_PASS, EMPTY_STRING);
            displayName = json.optString(USER_DISPLAY_NAME, EMPTY_STRING);
            serverUrl = json.optString(SERVER_URL, EMPTY_STRING);
            serverIp = json.optString(SERVER_IP, EMPTY_STRING);
            certName = json.optString(SERVER_CERT_NAME, EMPTY_STRING);
            e2eePrivateKey = json.optString(E2EE_PRIVATE_KEY, EMPTY_STRING);
            e2eePublicKey = json.optString(E2EE_PUBLIC_KEY, EMPTY_STRING);
            String cert = json.optString(SERVER_CERT, EMPTY_STRING);
            certBytes = cert.isEmpty() ? null : Base64.decode(cert, Base64.NO_WRAP);
        } catch (Exception e) {
            String msg = "Error reading or decrypting full JSON data: " + e.getMessage();
            Log.d(LOG_TAG, msg);
            initAppError = true;
            initAppErrStr = msg;
        }
    }

    private void writeRoutersListAndSettingsEncrypted() {
        File userDataDir = new File(getFilesDir(), USER_DATA);
        if (!userDataDir.exists() && !userDataDir.mkdirs()) {
            Log.d(LOG_TAG, "Failed to create directory.");
            return;
        }

        File routersListFile = new File(userDataDir, USER_DATA_JSON);

        JSONObject json = new JSONObject();
        try {
            json.put(USER_ID, userId);
            json.put(USER_NAME, username);
            json.put(USER_PASS, password);
            json.put(USER_DISPLAY_NAME, displayName);
            json.put(SERVER_URL, serverUrl);
            json.put(SERVER_IP, serverIp);
            json.put(SERVER_CERT_NAME, certName);
            json.put(E2EE_PRIVATE_KEY, e2eePrivateKey);
            json.put(E2EE_PUBLIC_KEY, e2eePublicKey);
            json.put(SERVER_CERT, certBytes != null ? Base64.encodeToString(certBytes, Base64.NO_WRAP) : EMPTY_STRING);
            String rawJsonString = json.toString();
            byte[] rawJsonBytes = rawJsonString.getBytes(StandardCharsets.UTF_8);
            byte[] encryptedCombinedBytes = encrypt(rawJsonBytes);
            String encryptedBase64 = Base64.encodeToString(encryptedCombinedBytes, Base64.NO_WRAP);
            JSONObject fileWrapper = new JSONObject();
            fileWrapper.put(ENCRYPTED_DATA_KEY, encryptedBase64);
            // 4. Запись JSON-оболочки в файл
            try (FileWriter file = new FileWriter(routersListFile)) {
                file.write(fileWrapper.toString(4));
            }

        } catch (Exception e) {
            Log.d(LOG_TAG, "Error writing encrypted JSON file or key management failure: ", e);
        }
    }

    private byte[] encrypt(byte[] dataBytes) throws KeyStoreException, IllegalArgumentException, IOException, NoSuchAlgorithmException,
            CertificateException, NullPointerException, UnrecoverableEntryException, NoSuchProviderException, InvalidAlgorithmParameterException,
            NoSuchPaddingException, UnsupportedOperationException, InvalidKeyException, IllegalBlockSizeException, IllegalStateException,
            BadPaddingException {

        SecretKey secretKey = getOrCreateSecretKey();
        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        cipher.init(Cipher.ENCRYPT_MODE, secretKey);

        byte[] iv = cipher.getIV();
        byte[] encryptedData = cipher.doFinal(dataBytes);
        byte[] combined = new byte[1 + iv.length + encryptedData.length];
        combined[0] = (byte) iv.length; // Сохраняем длину IV в первом байте
        System.arraycopy(iv, 0, combined, 1, iv.length); // Копируем IV начиная со второго байта
        System.arraycopy(encryptedData, 0, combined, 1 + iv.length, encryptedData.length); // Копируем данные
        return combined;
    }

    private byte[] decrypt(byte[] combinedBytes) throws KeyStoreException, IllegalArgumentException, IOException, NoSuchAlgorithmException,
            CertificateException, NullPointerException, UnrecoverableEntryException, NoSuchProviderException, InvalidAlgorithmParameterException,
            NoSuchPaddingException, UnsupportedOperationException, InvalidKeyException, IllegalBlockSizeException, IllegalStateException,
            BadPaddingException {

        // Минимальная длина: 1 байт (длина IV) + 1 байт (IV) + 16 байт (GCM Tag) = 18 байт
        if (combinedBytes.length < 1 + GCM_TAG_LENGTH) {
            throw new InvalidKeyException("Combined data too short to contain IV length and GCM Tag.");
        }

        int ivLength = combinedBytes[0] & 0xFF; // Получаем фактическую длину IV из первого байта
        // Проверяем, достаточно ли данных для IV и GCM Tag
        if (combinedBytes.length < 1 + ivLength + GCM_TAG_LENGTH) {
            throw new InvalidKeyException("IV length leads to combined data too short for GCM Tag.");
        }
        // Извлекаем IV
        byte[] iv = Arrays.copyOfRange(combinedBytes, 1, 1 + ivLength);
        // Извлекаем зашифрованные данные (начинаются после байта длины и IV)
        byte[] encryptedData = Arrays.copyOfRange(combinedBytes, 1 + ivLength, combinedBytes.length);

        SecretKey secretKey = getOrCreateSecretKey();
        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        // GCM_TAG_LENGTH * 8, так как длина тега указывается в битах (16 байт * 8 = 128 бит)
        GCMParameterSpec spec = new GCMParameterSpec(GCM_TAG_LENGTH * 8, iv);

        cipher.init(Cipher.DECRYPT_MODE, secretKey, spec);
        return cipher.doFinal(encryptedData);
    }

    private SecretKey getOrCreateSecretKey() throws KeyStoreException, IllegalArgumentException, IOException, NoSuchAlgorithmException,
            CertificateException, NullPointerException, UnrecoverableEntryException, NoSuchProviderException, InvalidAlgorithmParameterException {

        KeyStore keyStore = KeyStore.getInstance(ANDROID_KEYSTORE);
        keyStore.load(null);

        // Попытка получить существующий ключ
        if (keyStore.containsAlias(KEY_ALIAS)) {
            KeyStore.SecretKeyEntry entry = (KeyStore.SecretKeyEntry) keyStore.getEntry(KEY_ALIAS, null);
            return entry.getSecretKey();
        }

        // Если ключа нет, создаем новый (Требуется API 23+ для KeyGenParameterSpec)
        KeyGenerator keyGenerator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE);

        // Настройка параметров: AES/GCM/NoPadding
        keyGenerator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(AES_KEY_SIZE)
                .build());

        return keyGenerator.generateKey();
    }

    private void initOkHttpClient() {
        try {
            final TrustManager[] trustAllCerts = new TrustManager[]{
                    new X509TrustManager() {

                        @Override
                        public void checkClientTrusted(X509Certificate[] chain, String authType) {
                            //
                        }

                        @Override
                        public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                            checkTime(chain[0]);
                            if (certBytes == null) {
                                Log.w(AppController.LOG_TAG, "сертификат не импортирован проверена только дата: ");
                                return;
                            }
                            try {
                                checkCert(chain[0]);
                            } catch (CertificateException e) {
                                throw e;
                            } catch (Exception e) {
                                throw new CertificateException(getString(R.string.main_cert_error), e);
                            }
                        }

                        @Override
                        public X509Certificate[] getAcceptedIssuers() {
                            return new X509Certificate[0];
                        }

                        private void checkTime(X509Certificate chain) throws CertificateException {
                            Instant now = Instant.now();
                            if (now.isBefore(chain.getNotBefore().toInstant())) {
                                throw new CertificateException(getString(R.string.date_cert_before_error));
                            }
                            if (now.isAfter(chain.getNotAfter().toInstant())) {
                                throw new CertificateException(getString(R.string.date_cert_after_error) + " (" + chain.getNotAfter() + ")");
                            }
                        }

                        private void checkCert(X509Certificate chain) throws CertificateException {
                            CertificateFactory cf = CertificateFactory.getInstance("X.509");
                            X509Certificate savedCert = (X509Certificate) cf.generateCertificate(new ByteArrayInputStream(certBytes));
                            // 3. СРАВНЕНИЕ ПУБЛИЧНЫХ КЛЮЧЕЙ.
                            if (!chain.getPublicKey().equals(savedCert.getPublicKey())) {
                                throw new CertificateException(getString(R.string.public_key_cert_error));
                            } else {
                                Log.i(AppController.LOG_TAG, "сертификат публичный ключ совпал: ");
                            }
                            checkSign(chain, savedCert);
                        }

                        private void checkSign(X509Certificate chain, X509Certificate savedCert) throws CertificateException {
                            try {
                                chain.verify(savedCert.getPublicKey());
                            } catch (Exception e) {
                                throw new CertificateException(getString(R.string.sign_cert_error), e);
                            }
                            Log.i(AppController.LOG_TAG, "сертификат подпись проверена");
                        }
                    }
            };

            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, trustAllCerts, new java.security.SecureRandom());

            Dispatcher dispatcher = new Dispatcher();
            dispatcher.setMaxRequests(POOL_SIZE);
            dispatcher.setMaxRequestsPerHost(POOL_SIZE);

            okHttpClient = new OkHttpClient.Builder()
                    .dispatcher(dispatcher)
                    .sslSocketFactory(sslContext.getSocketFactory(), (X509TrustManager) trustAllCerts[0])
                    .hostnameVerifier((hostname, session) -> true)
                    .connectTimeout(60, TimeUnit.SECONDS) // Время на установку связи с роутером
                    .writeTimeout(15, TimeUnit.SECONDS)   // Время на отправку данных
                    .readTimeout(60, TimeUnit.SECONDS)    // Время на ожидание ответа от роутера
                    .callTimeout(190, TimeUnit.SECONDS) // Общее время на весь запрос с ответом, чтоб не переподключалось много раз
                    .retryOnConnectionFailure(true)
                    .build();
        } catch (Exception e) {
            String msg = "Error init OkHttpClient: " + e.getMessage();
            Log.d(LOG_TAG, msg);
            initAppError = true;
            initAppErrStr = msg;
        }
    }

    @SuppressWarnings("resource")
    private void initStreams() {
        for (int i = 0; i < POOL_SIZE; i++) {
            netStreams[i] = Executors.newSingleThreadExecutor();
        }
    }

    private void regActivityListener() {
        registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks() {
            @Override
            public void onActivityCreated(@NonNull Activity activity, @Nullable Bundle savedInstanceState) {
                //
            }

            @Override
            public void onActivityStarted(@NonNull Activity activity) {
                if (startedActivities.incrementAndGet() == 1) {
                    syncTask = syncExecutor.scheduleWithFixedDelay(() -> {
                        if (userId > 0 && isNetworkActive.get()) {
                            if (activeDownloadsCount.get() == 0) {
                                startDownloadNewMsg();
                            }
                            startSendingMsgList();
                        }
                    }, 4, 4, TimeUnit.SECONDS);
                }
            }

            @Override
            public void onActivityResumed(@NonNull Activity activity) {
                //
            }

            @Override
            public void onActivityPaused(@NonNull Activity activity) {
                //
            }

            @Override
            public void onActivityStopped(@NonNull Activity activity) {
                if (startedActivities.decrementAndGet() == 0 && syncTask != null && !syncTask.isCancelled()) {
                    syncTask.cancel(false);
                }
            }

            @Override
            public void onActivitySaveInstanceState(@NonNull Activity activity, @NonNull Bundle outState) {
                //
            }

            @Override
            public void onActivityDestroyed(@NonNull Activity activity) {
                //
            }
        });
    }

    public static String formatSmartTime(Context context, long timestamp) {
        if (timestamp <= 0) return EMPTY_STRING;
        AppController controller = (AppController) context.getApplicationContext();

        ZonedDateTime msgTime = Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault());
        LocalDate msgDate = msgTime.toLocalDate();
        LocalDate today = LocalDate.now(msgTime.getZone());

        if (msgDate.equals(today)) {
            return controller.timeFormatter.format(msgTime);
        }

        if (msgDate.equals(today.minusDays(1))) {
            return controller.getString(R.string.yesterday);
        }

        if (msgDate.getYear() == today.getYear()) {
            return controller.dayMonthFormatter.format(msgTime);
        }

        return controller.dateFormatter.format(msgTime);
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    NOTIFICATION_CHANNEL, getString(R.string.msg_notification_channel_name), NotificationManager.IMPORTANCE_DEFAULT);
            channel.enableVibration(false); // ОТКЛЮЧАЕМ вибрацию для канала
            channel.setVibrationPattern(new long[]{0}); // На всякий случай зануляем паттерн
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    private void registerNetworkCallback() {
        ConnectivityManager connectivityManager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (connectivityManager != null) {
            connectivityManager.registerDefaultNetworkCallback(new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(@NonNull Network network) {
                    isNetworkActive.set(true);
                }

                @Override
                public void onLost(@NonNull Network network) {
                    isNetworkActive.set(false);
                }
            });
        }
    }

    private void setupWorkManager() {
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(LIM_SYNC, ExistingPeriodicWorkPolicy.KEEP,
                new PeriodicWorkRequest.Builder(MessageWorker.class, 15, TimeUnit.MINUTES).setConstraints(constraints)
                        .setBackoffCriteria(BackoffPolicy.LINEAR, WorkRequest.MIN_BACKOFF_MILLIS, TimeUnit.MILLISECONDS).build());
    }

    /**
     * Создает пару ключей для E2EE.
     */
    public void createE2Keys() throws IllegalArgumentException, NoSuchAlgorithmException, NullPointerException,
            InvalidAlgorithmParameterException, UnsupportedOperationException, IllegalStateException {

        KeyPairGenerator kpg = KeyPairGenerator.getInstance("EC");
        kpg.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair kp = kpg.generateKeyPair();
        // Превращаем ключи в обычные строки (Base64)
        e2eePrivateKey = Base64.encodeToString(kp.getPrivate().getEncoded(), Base64.NO_WRAP);
        e2eePublicKey = Base64.encodeToString(kp.getPublic().getEncoded(), Base64.NO_WRAP);
    }

    public String getPrivateHash(String pass) throws IllegalArgumentException, NoSuchAlgorithmException,
            NullPointerException, UnsupportedOperationException, IllegalStateException, InvalidKeySpecException, NoSuchPaddingException,
            InvalidKeyException, BadPaddingException, IllegalBlockSizeException {

        byte[] salt = new byte[16];
        secureRandom.nextBytes(salt);
        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        cipher.init(Cipher.ENCRYPT_MODE, deriveKeyFromPassword(pass, salt));
        byte[] iv = cipher.getIV();
        byte[] encrypted = cipher.doFinal(e2eePrivateKey.getBytes(StandardCharsets.UTF_8));
        byte[] combined = new byte[salt.length + iv.length + encrypted.length];
        System.arraycopy(salt, 0, combined, 0, salt.length);
        System.arraycopy(iv, 0, combined, salt.length, iv.length);
        System.arraycopy(encrypted, 0, combined, salt.length + iv.length, encrypted.length);
        return Base64.encodeToString(combined, Base64.NO_WRAP);
    }

    public void unpackPrivateKey(@Nullable String encryptedBlob, String password) throws IllegalArgumentException, NoSuchAlgorithmException,
            NullPointerException, UnsupportedOperationException, IllegalStateException, InvalidKeySpecException, NoSuchPaddingException,
            InvalidKeyException, BadPaddingException, IllegalBlockSizeException, InvalidAlgorithmParameterException {

        if (encryptedBlob == null) return;
        byte[] combined = Base64.decode(encryptedBlob, Base64.NO_WRAP);
        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        cipher.init(Cipher.DECRYPT_MODE, deriveKeyFromPassword(password, Arrays.copyOfRange(combined, 0, 16)),
                new GCMParameterSpec(128, Arrays.copyOfRange(combined, 16, 28)));
        e2eePrivateKey = new String(cipher.doFinal(Arrays.copyOfRange(combined, 28, combined.length)), StandardCharsets.UTF_8);
    }

    private SecretKey deriveKeyFromPassword(String password, byte[] salt) throws IllegalArgumentException, NoSuchAlgorithmException,
            NullPointerException, UnsupportedOperationException, IllegalStateException, InvalidKeySpecException {
        return new SecretKeySpec(SecretKeyFactory.getInstance(PBKDF2_ALGORITHM)
                .generateSecret(new PBEKeySpec(password.toCharArray(), salt, 10000, AES_KEY_SIZE))
                .getEncoded(), "AES");
    }

    /**
     * Шифрует текст сообщения для конкретного получателя.
     */
    @Nullable
    public String encryptMessage(@NonNull String plainText, @NonNull String theirPublicKeyBase64) {
        try {
            // 2. Шифруем через AES-GCM
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, getSharedKey(theirPublicKeyBase64));
            byte[] iv = cipher.getIV();
            byte[] encryptedText = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));
            // 3. Упаковываем в строку: [IV_LENGTH(1b)] + [IV] + [DATA]
            byte[] packet = new byte[1 + iv.length + encryptedText.length];
            packet[0] = (byte) iv.length;
            System.arraycopy(iv, 0, packet, 1, iv.length);
            System.arraycopy(encryptedText, 0, packet, 1 + iv.length, encryptedText.length);
            return Base64.encodeToString(packet, Base64.NO_WRAP);
        } catch (Exception e) {
            Log.e(LOG_TAG, "Encryption error: " + e.getMessage());
            return null; // Если не смогли зашифровать - вернем как есть (или ошибку)
        }
    }

    /**
     * Расшифровывает входящее сообщение.
     */
    @Nullable
    public String decryptMessage(@NonNull String encryptedBase64, @NonNull String theirPublicKeyBase64) {
        try {
            byte[] packet = Base64.decode(encryptedBase64, Base64.NO_WRAP);
            // 1. Извлекаем IV и зашифрованные данные
            int ivLength = packet[0] & 0xFF;
            byte[] iv = Arrays.copyOfRange(packet, 1, 1 + ivLength);
            byte[] encryptedText = Arrays.copyOfRange(packet, 1 + ivLength, packet.length);
            // 3. Расшифровываем
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, getSharedKey(theirPublicKeyBase64), new GCMParameterSpec(GCM_TAG_LENGTH * 8, iv));
            byte[] decrypted = cipher.doFinal(encryptedText);
            return new String(decrypted, StandardCharsets.UTF_8);
        } catch (Exception e) {
            Log.w(LOG_TAG, "Decryption failed (maybe plain text?): " + e.getMessage());
            return null;
        }
    }

    private SecretKey getSharedKey(String theirPublicKeyBase64) throws IllegalArgumentException, NullPointerException,
            UnsupportedOperationException, IllegalStateException {
        return sharedKeys.computeIfAbsent(theirPublicKeyBase64, key -> {
            try {
                return calculateSharedKey(key);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    private SecretKey calculateSharedKey(String theirPublicKeyBase64) throws IllegalArgumentException, NullPointerException,
            UnsupportedOperationException, IllegalStateException, InvalidKeySpecException, InvalidKeyException {
        KeyAgreement ka = ECDH.get();
        ka.init(EC_KEY_FACTORY.generatePrivate(new PKCS8EncodedKeySpec(Base64.decode(e2eePrivateKey, Base64.NO_WRAP))));
        ka.doPhase(EC_KEY_FACTORY.generatePublic(new X509EncodedKeySpec(Base64.decode(theirPublicKeyBase64, Base64.NO_WRAP))), true);
        byte[] aesKeyBytes = SHA256.get().digest(ka.generateSecret());
        return new SecretKeySpec(aesKeyBytes, "AES");
    }

    /**
     * Создает Cipher для потокового шифрования файла.
     * Мы добавим IV в начало файла вручную в NetworkService.
     */
    public Cipher getFileEncryptCipher(String theirPublicKey, byte[] iv) throws IllegalArgumentException, NoSuchAlgorithmException,
            NullPointerException, UnsupportedOperationException, IllegalStateException, NoSuchPaddingException,
            InvalidKeyException, InvalidAlgorithmParameterException {
        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        cipher.init(Cipher.ENCRYPT_MODE, getSharedKey(theirPublicKey), new GCMParameterSpec(GCM_TAG_LENGTH * 8, iv));
        return cipher;
    }

    /**
     * Создает Cipher для потоковой расшифровки файла.
     */
    public Cipher getFileDecryptCipher(String theirPublicKey, byte[] iv) throws IllegalArgumentException, NoSuchAlgorithmException,
            NullPointerException, UnsupportedOperationException, IllegalStateException, NoSuchPaddingException,
            InvalidKeyException, InvalidAlgorithmParameterException {
        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        cipher.init(Cipher.DECRYPT_MODE, getSharedKey(theirPublicKey), new GCMParameterSpec(GCM_TAG_LENGTH * 8, iv));
        return cipher;
    }

    public static int getInterlocutorBackground(int color) {
        return switch (color) {
            case AppController.CHAT_COLOR_PINK -> R.drawable.interlocutor_background_pink;
            case AppController.CHAT_COLOR_YELLOW -> R.drawable.interlocutor_background_yellow;
            case AppController.CHAT_COLOR_BLUE -> R.drawable.interlocutor_background_blue;
            default -> R.drawable.interlocutor_background_green;
        };

    }
}
