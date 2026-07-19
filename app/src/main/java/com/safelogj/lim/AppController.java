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
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.UnrecoverableEntryException;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
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
import javax.crypto.KeyGenerator;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import okhttp3.Dispatcher;
import okhttp3.OkHttpClient;

public class AppController extends Application {
    public static final String LOG_TAG = "lim";
    public static final String EMPTY_STRING = "";
    public static final int QUEUE_SIZE = 100;
    public static final int POOL_SIZE = 5;
    public static final String NOTIFICATION_CHANNEL = "lim_messages";
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
    private static final String KEY_ALIAS = "MikrotikRouterKeyAlias";
    private static final String ANDROID_KEYSTORE = "AndroidKeyStore";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int GCM_TAG_LENGTH = 16;
    private static final int AES_KEY_SIZE = 256;
    private static final String ENCRYPTED_DATA_KEY = "encryptedData";
    private static final String LIM_SYNC = "LimSync";
    private final ExecutorService dbExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService userExecutor = Executors.newSingleThreadExecutor();
    private final ScheduledExecutorService syncExecutor = Executors.newSingleThreadScheduledExecutor();
    private ScheduledFuture<?> syncTask;
    public final AtomicBoolean isDownloadInProgress = new AtomicBoolean(false);
    public final AtomicInteger startedActivities = new AtomicInteger(0);
    private final AtomicBoolean isNetworkActive = new AtomicBoolean(false);
    private final ExecutorService[] netStreams = new ExecutorService[POOL_SIZE];
    private final DateTimeFormatter timeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault());
    private final DateTimeFormatter dateFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy", Locale.getDefault());
    private final DateTimeFormatter dayMonthFormatter = DateTimeFormatter.ofPattern("dd MMM", Locale.getDefault());
    private NetworkService networkService;

    private byte[] certBytes;
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
    private Cipher mCipher;
    private OkHttpClient okHttpClient;
    private DatabaseHelper dbHelper;
    private boolean initAppError;
    private String initAppErrStr = EMPTY_STRING;

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

    @Override
    public void onCreate() {
        super.onCreate();
        regActivityListener();
        registerNetworkCallback();
        readRoutersListAndSettingsEncrypted();
        initOkHttpClient();
        dbHelper = new DatabaseHelper(this);
        networkService = new NetworkService(this);
        initStreams();
        dbHelper.initDatabase();
        createNotificationChannel();
        setupWorkManager();
    }

    public void writeSettingsToFile() {
        dbExecutor.execute(this::writeRoutersListAndSettingsEncrypted);
    }

    public void startDownloadNewMsg() {
        dbHelper.getLastDbMessageId(userId, new ResultCallback<>() {
            @Override
            public void onSuccess(Long lastServerId) {
                isDownloadInProgress.set(true);
                netStreams[POOL_SIZE - 1].execute(() -> networkService.getNewMessages(lastServerId, () -> isDownloadInProgress.set(false)));
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
                    if (msg.type.equals(NetworkService.TEXT)) {
                        netStreams[Math.abs((int) (msg.localChatId % (POOL_SIZE - 1)))].execute(() -> networkService.sendTextMessage(msg));
                    } else {
                        //    netStreams[Math.abs((int) (msg.localChatId % (POOL_SIZE - 1)))].execute(() -> networkService.sendFileMessage(msg));
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
        if (mCipher == null) {
            mCipher = Cipher.getInstance(TRANSFORMATION);
        }
        mCipher.init(Cipher.ENCRYPT_MODE, secretKey);

        byte[] iv = mCipher.getIV();
        byte[] encryptedData = mCipher.doFinal(dataBytes);
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
        mCipher = Cipher.getInstance(TRANSFORMATION);
        // GCM_TAG_LENGTH * 8, так как длина тега указывается в битах (16 байт * 8 = 128 бит)
        GCMParameterSpec spec = new GCMParameterSpec(GCM_TAG_LENGTH * 8, iv);

        mCipher.init(Cipher.DECRYPT_MODE, secretKey, spec);
        return mCipher.doFinal(encryptedData);
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
                    .callTimeout(70, TimeUnit.SECONDS) // Общее время на весь запрос с ответом, чтоб не переподключалось много раз
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
            public void onActivityCreated(@androidx.annotation.NonNull Activity activity, @Nullable Bundle savedInstanceState) {
                //
            }

            @Override
            public void onActivityStarted(@androidx.annotation.NonNull Activity activity) {
                if (startedActivities.incrementAndGet() == 1) {
                    syncTask = syncExecutor.scheduleWithFixedDelay(() -> {
                        if (userId > 0 && isNetworkActive.get()) {
                            if (!isDownloadInProgress.get()) {
                                startDownloadNewMsg();
                            }
                            startSendingMsgList();
                        }
                    }, 4000, 4000, TimeUnit.MILLISECONDS);
                }
            }

            @Override
            public void onActivityResumed(@androidx.annotation.NonNull Activity activity) {
                //
            }

            @Override
            public void onActivityPaused(@androidx.annotation.NonNull Activity activity) {
                //
            }

            @Override
            public void onActivityStopped(@androidx.annotation.NonNull Activity activity) {
                if (startedActivities.decrementAndGet() == 0 && syncTask != null && !syncTask.isCancelled()) {
                    syncTask.cancel(false);
                }
            }

            @Override
            public void onActivitySaveInstanceState(@androidx.annotation.NonNull Activity activity, @androidx.annotation.NonNull Bundle outState) {
                //
            }

            @Override
            public void onActivityDestroyed(@androidx.annotation.NonNull Activity activity) {
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
        Constraints constraints = new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build();
        PeriodicWorkRequest workRequest =
                new PeriodicWorkRequest.Builder(MessageWorker.class, 15, TimeUnit.MINUTES)
                        .setConstraints(constraints)
                        .setBackoffCriteria(BackoffPolicy.LINEAR, WorkRequest.MIN_BACKOFF_MILLIS, TimeUnit.MILLISECONDS)
                        .build();
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(LIM_SYNC, ExistingPeriodicWorkPolicy.KEEP, workRequest);
    }
}
