package com.safelogj.lim;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.media.ToneGenerator;
import android.media.audiofx.AcousticEchoCanceler;
import android.media.audiofx.NoiseSuppressor;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.safelogj.lim.model.Mute;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

import io.github.jaredmdobson.concentus.OpusApplication;
import io.github.jaredmdobson.concentus.OpusDecoder;
import io.github.jaredmdobson.concentus.OpusEncoder;
import io.github.jaredmdobson.concentus.OpusException;
import io.github.jaredmdobson.concentus.OpusSignal;

public class CallService {
    public static final Integer LINE_FREE = 0;
    public static final int INVALID_ID = -1;
    private static final int BUFFER_SIZE = 2048;
    private static final int OPUS_BUFFER_SIZE = 1024;
    private static final int HEADER_SIZE = 16;
    private static final int SAMPLE_RATE = 48000;
    private static final int FRAME_SIZE = 960; // 20мс при 48кГц
    public final AtomicBoolean isTalking = new AtomicBoolean(false);
    public final AtomicBoolean lineBusy = new AtomicBoolean(false);
    private AppController appController;
    private final DatabaseHelper dbHelper;
    private final AtomicBoolean isListeningUdp = new AtomicBoolean(false);
    private final AtomicBoolean isOutputCall = new AtomicBoolean(false);
    private final AtomicLong ringingStopLastTimeout = new AtomicLong(0L);
    private final AtomicLong callingEndLastTimeout = new AtomicLong(0L);
    private final Object socketLock = new Object();
    private final int userId;
    private final String serverIp;
    private final int udpPort;
    private volatile int interlocutorId = INVALID_ID;
    private final DatagramPacket packetOut;
    private final DatagramPacket packetIn = new DatagramPacket(new byte[BUFFER_SIZE], BUFFER_SIZE);
    private final Object muteLock = new Object();
    private volatile Mute[] mutedUsers = new Mute[20];
    private final Handler callHandler = new Handler(Looper.getMainLooper());
    private final Runnable endCallRunnable = () -> {
        toggleSpeakerphone(false); // Сбрасываем при выходе
        cancelCall();
    };
    private final Runnable ringingStopRunnable = () -> {
        interlocutorId = INVALID_ID;
        stopRinging();
        lineBusy.set(false);
        appController.notifyIncomingCallChanged(CallService.INVALID_ID);
    };
    private long callStartTime = 0;
    private final Runnable durationRunnable = new Runnable() {
        @Override
        public void run() {
            if (isTalking.get()) {
                long seconds = (System.currentTimeMillis() - callStartTime) / 1000;
                String duration = String.format(Locale.getDefault(), "%02d:%02d", seconds / 60, seconds % 60);
                appController.notifyCallDurationChanged(duration);
                callHandler.postDelayed(this, 1000);
            } else {
                appController.notifyCallDurationChanged(null);
            }
        }
    };
    private final short[] decodeBuffer = new short[FRAME_SIZE];
    private final byte[] voiceOutBuffer = new byte[HEADER_SIZE + 12 + OPUS_BUFFER_SIZE + 16];
    private final byte[] voiceInIv = new byte[12];
    private final byte[] voiceInDecryptBuffer = new byte[OPUS_BUFFER_SIZE + 16];
    private final byte[] voiceOutIv = new byte[12];
    private final short[] pcmBuffer = new short[FRAME_SIZE];
    private final byte[] opusBuffer = new byte[OPUS_BUFFER_SIZE];
    private final ByteBuffer udpHeader = ByteBuffer.allocate(HEADER_SIZE);
    private final ToneGenerator toneGenerator = new ToneGenerator(AudioManager.STREAM_VOICE_CALL, 80); // 80 - громкость
    private final SecureRandom secureRandom;
    private final AudioManager audioManager;
    private DatagramSocket udpSocket;
    private AudioRecord audioRecord;
    private AudioTrack audioTrack;
    private OpusEncoder opusEncoder;
    private OpusDecoder opusDecoder;
    private SecretKey currentCallKey;
    private Cipher encryptCipher;
    private Cipher decryptCipher;
    private Ringtone currentRingtone;
    private long currentCallToken;

    public CallService(AppController appController) {
        this.appController = appController;
        audioManager = (AudioManager) appController.getSystemService(Context.AUDIO_SERVICE);
        dbHelper = appController.getDbHelper();
        userId = appController.userId();
        serverIp = appController.getServerIp();
        udpPort = appController.getUdpRelayPort();
        packetOut = buildKeepAlivePacket();
        secureRandom = appController.getSecureRandom();

    }

    public void releaseToneGenerator() {
        toneGenerator.release();
    }

    public void stopUdpTraffic() {
        if (!isTalking.get()) {
            isListeningUdp.set(false);
            killCall();
            closeUdpSocket(udpSocket);
            udpSocket = null;
        }
    }

    public void sendKeepAlive() {
        if (!lineBusy.get() && !isTalking.get() && appController.hasMic() && appController.hasAudioOut()) {
            appController.getNetStreams()[AppController.UDP_OUT].execute(() -> {
                DatagramSocket socket = null;
                try {
                    if (packetOut != null && hasSocket()) {
                        socket = udpSocket;
                        socket.send(packetOut);
                    }
                } catch (Exception e) {
                    closeUdpSocket(socket);
                    Log.e(AppController.LOG_TAG, "Keep-alive error: " + e.getMessage());
                }
                ensureMuted();
            });
        }
    }

    public void startListeningUdp() {
        if (!isListeningUdp.get() && appController.hasMic() && appController.hasAudioOut()) {
            isListeningUdp.set(true);
            appController.getNetStreams()[AppController.UDP_IN].execute(() -> {
                while (isListeningUdp.get() && hasSocket()) {
                    DatagramSocket socket = udpSocket;
                    try {
                        socket.receive(packetIn);
                        handleIncomingPacket(packetIn);
                    } catch (Exception e) {
                        closeUdpSocket(socket);
                        if (isListeningUdp.get()) {
                            Log.e(AppController.LOG_TAG, "Receive error: " + e.getMessage());
                        }
                    }
                }
                Log.d(AppController.LOG_TAG, "UDP Listener stopped");
            });
        }
    }

    public void acceptCall() {
        appController.getNetStreams()[AppController.UDP_OUT].execute(() -> {
            renewOrStopRingRunnable(0);
            stopRinging();
            isTalking.set(true);
            callStartTime = System.currentTimeMillis();
            callingEndLastTimeout.set(callStartTime);
            callHandler.post(durationRunnable);
            Log.d(AppController.LOG_TAG, "Call started with target: " + interlocutorId);
            sendVoiceCycle(isTalking);
        });
    }

    public void startOutgoingCall(int targetUserId) {
        if (!lineBusy.get()) {
            lineBusy.set(true);
            appController.getNetStreams()[AppController.UDP_OUT].execute(() -> {
                isOutputCall.set(true);
                interlocutorId = targetUserId;
                muteInterlocutor(3_100);
                Log.d(AppController.LOG_TAG, "Outgoing call started to: " + interlocutorId);
                currentCallToken = Math.max(secureRandom.nextLong(), 1L);
                sendVoiceCycle(lineBusy);
            });
        }
    }

    private void sendVoiceCycle(AtomicBoolean lineStatus) {
        try {
            audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
            initAudioPlayAndOpusDec();
            if (isOutputCall.get()) {
                toneGenerator.startTone(ToneGenerator.TONE_SUP_RINGTONE);
            }
            initAudioRecAndOpusEnc(); // вкл микрофон
            audioRecord.startRecording();
            DatagramPacket voiceOutPacket = new DatagramPacket(voiceOutBuffer, voiceOutBuffer.length, InetAddress.getByName(serverIp), udpPort);
            while (lineStatus.get() && audioRecord != null) {
                if (audioRecord.read(pcmBuffer, 0, FRAME_SIZE) > 0) {
                    int encodedLen = opusEncoder.encode(pcmBuffer, 0, FRAME_SIZE, opusBuffer, 0, OPUS_BUFFER_SIZE);
                    if (encodedLen > 0 && hasSocket()) {
                        udpHeader.clear();
                        udpHeader.putInt(userId);
                        udpHeader.putLong(currentCallToken);
                        udpHeader.putInt(interlocutorId);

                        secureRandom.nextBytes(voiceOutIv);
                        encryptCipher.init(Cipher.ENCRYPT_MODE, currentCallKey, new GCMParameterSpec(128, voiceOutIv));
                        int encryptedLen = encryptCipher.doFinal(opusBuffer, 0, encodedLen, voiceOutBuffer, HEADER_SIZE + 12);

                        System.arraycopy(udpHeader.array(), 0, voiceOutBuffer, 0, HEADER_SIZE);
                        System.arraycopy(voiceOutIv, 0, voiceOutBuffer, HEADER_SIZE, 12);

                        voiceOutPacket.setLength(HEADER_SIZE + 12 + encryptedLen);
                        udpSocket.send(voiceOutPacket);
                    }
                }
            }
        } catch (Exception e) {
            Log.e(AppController.LOG_TAG, "sendVoice error: " + e.getMessage());
            appController.notifyEndCallChanged(interlocutorId);  // toast
            endCall();
        } finally {
            stopAudioRecOpusEnc();
            stopAudioPlayOpusDec();
            audioManager.setMode(AudioManager.MODE_NORMAL);
        }
    }

    public void rejectCall() {
        muteInterlocutor(120_000);
        interlocutorId = INVALID_ID;
        renewOrStopRingRunnable(0);
        stopRinging();
        lineBusy.set(false);
    }

    public void cancelCall() {
        toneGenerator.stopTone();
        interlocutorId = INVALID_ID;
        isTalking.set(false);
        lineBusy.set(false);
        isOutputCall.set(false);
        appController.notifyEndCallChanged(CallService.INVALID_ID);  // закрыть фрагмент
        if (appController.startedActivities.get() == 0) {
            closeUdpSocket(udpSocket);
            udpSocket = null;
        }
    }

    public void endCall() {
        muteInterlocutor(4_000);
        toggleSpeakerphone(false); // Сбрасываем при выходе
        renewOrStopEndCallRunnable(0);
        cancelCall();
    }

    public void killCall() {
        if (isTalking.get()) {
            endCall();
        } else if (lineBusy.get()) {
            cancelCall();
        }
    }

    public boolean isSpeakerphoneOn() {
        if (audioManager == null) return false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            AudioDeviceInfo device = audioManager.getCommunicationDevice();
            return device != null && device.getType() == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER;
        } else {
            return audioManager.isSpeakerphoneOn();
        }
    }

    public void toggleSpeakerphone(boolean on) {
        if (audioManager == null) return;
        // 1. Гарантируем правильный режим
        if (audioManager.getMode() != AudioManager.MODE_IN_COMMUNICATION) {
            audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // (Android 12+)
            if (on) {
                AudioDeviceInfo speakerDevice = null;
                List<AudioDeviceInfo> devices = audioManager.getAvailableCommunicationDevices();
                for (AudioDeviceInfo device : devices) {
                    if (device.getType() == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) {
                        speakerDevice = device;
                        break;
                    }
                }
                if (speakerDevice != null) {
                    boolean result = audioManager.setCommunicationDevice(speakerDevice);
                    Log.d(AppController.LOG_TAG, "Set speakerphone result: " + result);
                }
            } else {
                audioManager.clearCommunicationDevice();
                Log.d(AppController.LOG_TAG, "Cleared communication device (back to earpiece)");
            }
        } else {
            // (до Android 12)
            audioManager.setSpeakerphoneOn(on);
        }
        Log.d(AppController.LOG_TAG, "Loudspeaker is now: " + on);
    }

    private void handleIncomingPacket(DatagramPacket packet) {
        if (packet.getLength() >= HEADER_SIZE + 12) { // 16 (header) + 12 (iv)
            ByteBuffer bb = ByteBuffer.wrap(packet.getData(), 0, packet.getLength());
            int senderId = bb.getInt();
            long tokenFromPacket = bb.getLong();
            if (!stillMuted(senderId) && bb.getInt() == userId) {
                routeInputPacket(senderId, tokenFromPacket, packet);
            }
        }
    }

    private void routeInputPacket(int senderId, long token, DatagramPacket packet) {
        if (!lineBusy.get() && interlocutorId == INVALID_ID) {  // ПЕРВЫЙ ПАКЕТ: Начало входящего вызова
            if (dbHelper.isInterlocutorBlocked(senderId) || !initCallEncryption(senderId)) return;
            interlocutorId = senderId;
            lineBusy.set(true);
            isOutputCall.set(false);
            currentCallToken = token;
            ringingStopLastTimeout.set(System.currentTimeMillis()); // входящий
            appController.notifyIncomingCallChanged(interlocutorId);
            callHandler.postDelayed(ringingStopRunnable, 3000);
            Log.d(AppController.LOG_TAG, "New incoming call from: " + senderId);
        } else if (senderId == interlocutorId) { // второй пакет при входящем или первый при исходящем
            if (isOutputCall.get()) {
                if (!isTalking.get()) {
                    isTalking.set(true);
                    toneGenerator.stopTone();
                    callingEndLastTimeout.set(System.currentTimeMillis()); // исходящий
                    appController.notifyStartCallChanged(senderId); // Пинаем фрагмент
                    callStartTime = System.currentTimeMillis();
                    callHandler.post(durationRunnable);
                }
                playVoice(packet); // исходящий
                renewOrStopEndCallRunnable(System.currentTimeMillis());
            } else {
                if (isTalking.get()) {
                    playVoice(packet); // входящий
                    renewOrStopEndCallRunnable(System.currentTimeMillis());
                } else {
                    renewOrStopRingRunnable(System.currentTimeMillis()); // отложенная остановка не принятого
                }
            }
        }
    }

    private void playVoice(DatagramPacket packet) {
        if (opusDecoder != null && audioTrack != null && decryptCipher != null) {
            try {
                byte[] data = packet.getData();
                // Читаем IV (начинается после 16 байт заголовка)
                System.arraycopy(data, HEADER_SIZE, voiceInIv, 0, 12);
                decryptCipher.init(Cipher.DECRYPT_MODE, currentCallKey, new GCMParameterSpec(128, voiceInIv));
                // Дешифруем данные (начинаются с 28 байта)
                int decryptedLen = decryptCipher.doFinal(data, 28, packet.getLength() - 28, voiceInDecryptBuffer, 0);
                int decodedLen = opusDecoder.decode(voiceInDecryptBuffer, 0, decryptedLen,
                        decodeBuffer, 0, FRAME_SIZE, false);
                if (decodedLen > 0) {
                    audioTrack.write(decodeBuffer, 0, decodedLen);
                }
            } catch (Exception e) {
                Log.e(AppController.LOG_TAG, "Voice decrypt/decode error: " + e.getMessage());
            }
        }
    }

    public boolean initCallEncryption(int interlocutorId) {
        currentCallKey = appController.getChatSecretKey(interlocutorId);
        if (currentCallKey == null) {
            Log.e(AppController.LOG_TAG, "Cannot start call: Secret key not found");
            return false;
        }
        try {
            encryptCipher = Cipher.getInstance("AES/GCM/NoPadding");
            decryptCipher = Cipher.getInstance("AES/GCM/NoPadding");
            return true;
        } catch (Exception e) {
            Log.e(AppController.LOG_TAG, "Error initializing ciphers: " + e.getMessage());
            return false;
        }
    }

    private void renewOrStopRingRunnable(long time) {
        synchronized (ringingStopLastTimeout) {
            renewOrStop(ringingStopLastTimeout, time, ringingStopRunnable);
        }
    }

    private void renewOrStopEndCallRunnable(long time) {
        synchronized (callingEndLastTimeout) {
            renewOrStop(callingEndLastTimeout, time, endCallRunnable);
        }
    }

    private void renewOrStop(AtomicLong timeout, long time, Runnable runnable) {
        if (time == 0) {
            timeout.set(time);
            callHandler.removeCallbacks(runnable);
        } else if (timeout.get() != 0 && time - timeout.get() > 2000) {
            timeout.set(time);
            callHandler.removeCallbacks(runnable);
            callHandler.postDelayed(runnable, 3000);
        }
    }

    private void initAudioPlayAndOpusDec() {
        if (audioTrack == null) {
            try {
                if (opusDecoder == null) {
                    opusDecoder = new OpusDecoder(SAMPLE_RATE, 1);
                }
                audioTrack = new AudioTrack.Builder()
                        .setAudioAttributes(new AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                .build())
                        .setAudioFormat(new AudioFormat.Builder()
                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                .setSampleRate(SAMPLE_RATE)
                                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                                .build())
                        .setBufferSizeInBytes(Math.max(AudioTrack.getMinBufferSize(
                                SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT), FRAME_SIZE * 4))
                        .setTransferMode(AudioTrack.MODE_STREAM)
                        .build();
                audioTrack.play();
            } catch (Exception e) {
                Log.e(AppController.LOG_TAG, "AudioTrack init error: " + e.getMessage());
            }
        }
    }

    private void stopAudioPlayOpusDec() {
        if (audioTrack != null) {
            try {
                audioTrack.stop();
                audioTrack.release();
            } catch (Exception ignored) {
                Log.d(AppController.LOG_TAG, "AudioTrack release error");
            }
            audioTrack = null;
        }
        opusDecoder = null;
    }

    private void initAudioRecAndOpusEnc() throws SecurityException, OpusException {
        if (audioRecord == null) {
            if (ContextCompat.checkSelfPermission(appController, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                throw new SecurityException("No microphone permission");
            }

            if (opusEncoder == null) {
                opusEncoder = new OpusEncoder(SAMPLE_RATE, 1, OpusApplication.OPUS_APPLICATION_VOIP);
                opusEncoder.setBitrate(32000);
                opusEncoder.setSignalType(OpusSignal.OPUS_SIGNAL_VOICE);
            }

            audioRecord = new AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    Math.max(AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT), FRAME_SIZE * 2));

            if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                throw new SecurityException("AudioRecord initialization failed");
            }

            if (AcousticEchoCanceler.isAvailable()) {
                AcousticEchoCanceler aec = AcousticEchoCanceler.create(audioRecord.getAudioSessionId());
                if (aec != null) aec.setEnabled(true);
            }
            if (NoiseSuppressor.isAvailable()) {
                NoiseSuppressor ns = NoiseSuppressor.create(audioRecord.getAudioSessionId());
                if (ns != null) ns.setEnabled(true);
            }
        }
    }

    private void stopAudioRecOpusEnc() {
        if (audioRecord != null) {
            try {
                audioRecord.stop();
                audioRecord.release();
            } catch (Exception ignored) {
                Log.d(AppController.LOG_TAG, "AudioRecord release error");
            }
            audioRecord = null;
        }
        opusEncoder = null;
    }

    private void closeUdpSocket(DatagramSocket socket) {
        synchronized (socketLock) {
            if (socket != null && !socket.isClosed()) {
                socket.close();
            }
        }
    }

    private boolean hasSocket() {
        if (udpSocket == null || udpSocket.isClosed()) {
            synchronized (socketLock) {
                if (udpSocket == null || udpSocket.isClosed()) {
                    try {
                        udpSocket = new DatagramSocket();
                        Log.d(AppController.LOG_TAG, "UDP Socket (re)opened on port: " + udpSocket.getLocalPort());
                    } catch (Exception e) {
                        Log.e(AppController.LOG_TAG, "Error creating UDP socket: " + e.getMessage());
                    }
                }
            }
        }
        return udpSocket != null && !udpSocket.isClosed();
    }

    @Nullable
    private DatagramPacket buildKeepAlivePacket() {
        ByteBuffer bb = ByteBuffer.allocate(4);
        bb.putInt(userId);
        byte[] data = bb.array();
        try {
            return new DatagramPacket(data, data.length, InetAddress.getByName(serverIp), udpPort);
        } catch (Exception e) {
            Log.e(AppController.LOG_TAG, "Keep-alive error: " + e.getMessage());
        }
        return null;
    }

    public void startRinging() {
        if (currentRingtone == null) {
            currentRingtone = RingtoneManager.getRingtone(appController, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE));
            if (currentRingtone != null) {
                currentRingtone.setLooping(true);
                currentRingtone.play();
            }
        }
    }

    private void stopRinging() {
        if (currentRingtone != null) {
            if (currentRingtone.isPlaying()) {
                currentRingtone.stop();
            }
            currentRingtone = null;
        }
    }

    private boolean stillMuted(int interlocutorId) {
        Mute[] localRef = mutedUsers;
        for (Mute m : localRef) {
            if (m == null) break;
            if (m.userId == interlocutorId) {
                return m.muteTime > System.currentTimeMillis();
            }
        }
        return false;
    }

    private void ensureMuted() {
        Mute[] temp = mutedUsers;
        if (temp[temp.length - 1] != null) {
            synchronized (muteLock) {
                mutedUsers = Arrays.copyOf(temp, temp.length + 10);
            }
        }
    }

    private void muteInterlocutor(long millis) {
        if (interlocutorId != INVALID_ID) {
            synchronized (muteLock) {
                Mute[] localRef = mutedUsers;
                int oldLen = localRef.length;
                for (int i = 0; i < oldLen; i++) {
                    if (localRef[i] == null) {
                        localRef[i] = new Mute(interlocutorId, System.currentTimeMillis() + millis);
                        mutedUsers = localRef;
                        return;
                    } else if (localRef[i].userId == interlocutorId) {
                        localRef[i].muteTime = (System.currentTimeMillis() + millis);
                        mutedUsers = localRef;
                        return;
                    }
                }
                Mute[] newArr = Arrays.copyOf(localRef, localRef.length + 10);
                newArr[oldLen] = new Mute(interlocutorId, System.currentTimeMillis() + millis);
                mutedUsers = newArr;
            }
        }
    }
}


