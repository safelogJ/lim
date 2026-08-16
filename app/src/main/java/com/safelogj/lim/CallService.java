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
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.safelogj.lim.model.Chat;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import io.github.jaredmdobson.concentus.OpusApplication;
import io.github.jaredmdobson.concentus.OpusDecoder;
import io.github.jaredmdobson.concentus.OpusEncoder;
import io.github.jaredmdobson.concentus.OpusException;
import io.github.jaredmdobson.concentus.OpusSignal;

public class CallService {
    private static final int BUFFER_SIZE = 2048;
    private static final int OPUS_BUFFER_SIZE = 1024;
    private static final int HEADER_SIZE = 16;
    private static final int SAMPLE_RATE = 48000;
    private static final int FRAME_SIZE = 960; // 20мс при 48кГц
    public final AtomicBoolean isTalking = new AtomicBoolean(false);
    public final AtomicBoolean lineBusy = new AtomicBoolean(false);
    private final AppController appController;
    private final DatabaseHelper dbHelper;
    private final AtomicBoolean isListeningUdp = new AtomicBoolean(false);
    private final AtomicBoolean isOutputCall = new AtomicBoolean(false);
    private final AtomicLong ringingStopLastTimeout = new AtomicLong(0L);
    private final AtomicLong callingEndLastTimeout = new AtomicLong(0L);
    private final Object socketLock = new Object();
    private final long userId;
    private final String serverIp;
    private final int udpPort;
    private volatile long interlocutorId = Chat.INVALID_ID;
    private final DatagramPacket packetOut;
    private final DatagramPacket packetIn = new DatagramPacket(new byte[BUFFER_SIZE], BUFFER_SIZE);
    private final Map<Long, Long> tempBlockedUsers = new ConcurrentHashMap<>();
    private final Handler callHandler = new Handler(Looper.getMainLooper());
    private final Runnable endCallRunnable = this::cancelCall;
    private final Runnable ringingStopRunnable = () -> {
        if (interlocutorId != Chat.INVALID_ID) {
            tempBlockedUsers.put(interlocutorId, System.currentTimeMillis() + 120000); // 2мин
        }
        interlocutorId = Chat.INVALID_ID;
        stopRinging();
        lineBusy.set(false);
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
    private final byte[] voiceOutBuffer = new byte[HEADER_SIZE + OPUS_BUFFER_SIZE];
    private final short[] pcmBuffer = new short[FRAME_SIZE];
    private final byte[] opusBuffer = new byte[OPUS_BUFFER_SIZE];
    private final ByteBuffer udpHeader = ByteBuffer.allocate(HEADER_SIZE);
    private DatagramSocket udpSocket;
    private AudioRecord audioRecord;
    private AudioTrack audioTrack;
    private OpusEncoder opusEncoder;
    private OpusDecoder opusDecoder;
    private Ringtone currentRingtone;
    private final AudioManager audioManager;

    public CallService(AppController appController) {
        this.appController = appController;
        audioManager = (AudioManager) appController.getSystemService(Context.AUDIO_SERVICE);
        dbHelper = appController.getDbHelper();
        userId = appController.getUserId();
        serverIp = appController.getServerIp();
        udpPort = appController.getUdpRelayPort();
        packetOut = buildKeepAlivePacket();

    }

    public void stopUdpTraffic() {
        isListeningUdp.set(false);
        killCall();
        closeUdpSocket(udpSocket);
        udpSocket = null;
    }

    public void sendKeepAlive() {
        if (!lineBusy.get() && !isTalking.get()) {
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
            });
        }
    }

    public void startListeningUdp() {
        if (!isListeningUdp.get()) {
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
            callingEndLastTimeout.set(System.currentTimeMillis());
            callStartTime = System.currentTimeMillis();
            callHandler.post(durationRunnable);
            Log.d(AppController.LOG_TAG, "Call started with target: " + interlocutorId);
            sendVoiceCycle(isTalking);
        });
    }

    public void startOutgoingCall(long targetUserId) {
        if (!lineBusy.get()) {
            lineBusy.set(true);
            appController.getNetStreams()[AppController.UDP_OUT].execute(() -> {
                isOutputCall.set(true);
                interlocutorId = targetUserId;
                tempBlockedUsers.remove(interlocutorId);
                Log.d(AppController.LOG_TAG, "Outgoing call started to: " + interlocutorId);
                sendVoiceCycle(lineBusy);
            });
        }
    }

    private void sendVoiceCycle(AtomicBoolean lineStatus) {
        try {
            initAudioPlayAndOpusDec();
            initAudioRecAndOpusEnc(); // вкл микрофон
            audioRecord.startRecording();
            DatagramPacket voiceOutPacket = new DatagramPacket(voiceOutBuffer, voiceOutBuffer.length, InetAddress.getByName(serverIp), udpPort);
            while (lineStatus.get() && audioRecord != null) {
                if (audioRecord.read(pcmBuffer, 0, FRAME_SIZE) > 0) {
                    int encodedLen = opusEncoder.encode(pcmBuffer, 0, FRAME_SIZE, opusBuffer, 0, OPUS_BUFFER_SIZE);
                    if (encodedLen > 0 && hasSocket()) {
                        udpHeader.clear();
                        udpHeader.putLong(userId);
                        udpHeader.putLong(interlocutorId);

                        System.arraycopy(udpHeader.array(), 0, voiceOutBuffer, 0, HEADER_SIZE);
                        System.arraycopy(opusBuffer, 0, voiceOutBuffer, HEADER_SIZE, encodedLen);

                        voiceOutPacket.setLength(HEADER_SIZE + encodedLen);
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
        }
    }

    public void rejectCall() {
        if (interlocutorId != Chat.INVALID_ID) {
            tempBlockedUsers.put(interlocutorId, System.currentTimeMillis() + 120000); // 2мин
        }
        interlocutorId = Chat.INVALID_ID;
        renewOrStopRingRunnable(0);
        stopRinging();
        lineBusy.set(false);
    }

    public void cancelCall() {
        isTalking.set(false);
        lineBusy.set(false);
        isOutputCall.set(false);
        interlocutorId = Chat.INVALID_ID;
        appController.notifyEndCallChanged(Chat.INVALID_ID);  // закрыть фрагмент
    }

    public void endCall() {
        toggleSpeakerphone(false); // Сбрасываем при выходе
        renewOrStopEndCallRunnable(0);
        cancelCall();
    }

    public void killCall() {
        if (isTalking.get()) {
            endCall();
        } else {
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
        if (audioManager != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) { // Современный способ (Android 12+)
                if (on) {
                    AudioDeviceInfo speakerDevice = null;
                    for (AudioDeviceInfo device : audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
                        if (device.getType() == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) {
                            speakerDevice = device;
                            break;
                        }
                    }
                    if (speakerDevice != null) {
                        audioManager.setCommunicationDevice(speakerDevice);
                    }
                } else {
                    audioManager.clearCommunicationDevice();
                }
            } else { // Классический способ (до Android 12)
                audioManager.setSpeakerphoneOn(on);
            }
            Log.d(AppController.LOG_TAG, "Speakerphone: " + on);
        }
    }

    private void handleIncomingPacket(DatagramPacket packet) {
        if (packet.getLength() >= 8) {
            long senderId = ByteBuffer.wrap(packet.getData(), 0, packet.getLength()).getLong(); // ID того, кто прислал данные
            if (!dbHelper.isInterlocutorBlocked(senderId)) {
                Long blockedUntil = tempBlockedUsers.get(senderId);
                if (blockedUntil != null) {
                    if (System.currentTimeMillis() < blockedUntil) {
                        return; // Скипаем пакет, время блокировки еще не вышло
                    } else {
                        tempBlockedUsers.remove(senderId); // Время вышло, чистим карту
                    }
                }
                routeInputPacket(senderId, packet);
            }
        }
    }

    private void routeInputPacket(long senderId, DatagramPacket packet) {
        if (!lineBusy.get() && interlocutorId == Chat.INVALID_ID) {  // ПЕРВЫЙ ПАКЕТ: Начало входящего вызова
            interlocutorId = senderId;
            lineBusy.set(true);
            isOutputCall.set(false);
            ringingStopLastTimeout.set(System.currentTimeMillis()); // входящий
            appController.notifyIncomingCallChanged(interlocutorId);
            callHandler.postDelayed(ringingStopRunnable, 5000);
            Log.d(AppController.LOG_TAG, "New incoming call from: " + senderId);
        } else if (senderId == interlocutorId) { // второй пакет при входящем или первый при исходящем
            if (isOutputCall.get()) {
                if (!isTalking.get()) {
                    isTalking.set(true);
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
        if (opusDecoder != null && audioTrack != null) {
            try {
                int decodedLen = opusDecoder.decode(packet.getData(), 8, packet.getLength() - 8,
                        decodeBuffer, 0, FRAME_SIZE, false);
                if (decodedLen > 0) {
                    audioTrack.write(decodeBuffer, 0, decodedLen);
                }
            } catch (Exception e) {
                Log.e(AppController.LOG_TAG, "Opus decode error: " + e.getMessage());
            }
        }
    }

    private void renewOrStopRingRunnable(long time) {
        synchronized (ringingStopLastTimeout) {
            if (time == 0) {
                ringingStopLastTimeout.set(time);
                callHandler.removeCallbacks(ringingStopRunnable);
            } else if (ringingStopLastTimeout.get() != 0 && time - ringingStopLastTimeout.get() > 2000) {
                ringingStopLastTimeout.set(time);
                callHandler.removeCallbacks(ringingStopRunnable);
                callHandler.postDelayed(ringingStopRunnable, 5000); // 5 сек тишины = сброс
            }
        }
    }

    private void renewOrStopEndCallRunnable(long time) {
        synchronized (callingEndLastTimeout) {
            if (time == 0) {
                callingEndLastTimeout.set(time);
                callHandler.removeCallbacks(endCallRunnable);
            } else if (callingEndLastTimeout.get() != 0 && time - callingEndLastTimeout.get() > 2000) {
                callingEndLastTimeout.set(time);
                callHandler.removeCallbacks(endCallRunnable);
                callHandler.postDelayed(endCallRunnable, 5000);
            }
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
                        .setBufferSizeInBytes(Math.max(AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT), FRAME_SIZE * 4))
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

            audioRecord = new AudioRecord(MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    Math.max(AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT), FRAME_SIZE * 2));

            if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                throw new SecurityException("AudioRecord initialization failed");
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
        ByteBuffer bb = ByteBuffer.allocate(16);
        bb.putLong(userId);
        bb.putLong(0L);
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
}

