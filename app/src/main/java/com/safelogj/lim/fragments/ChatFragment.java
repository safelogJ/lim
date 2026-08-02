package com.safelogj.lim.fragments;

import android.Manifest;
import android.app.Activity;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;

import androidx.activity.result.ActivityResult;
import androidx.activity.result.ActivityResultCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.documentfile.provider.DocumentFile;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.service.notification.StatusBarNotification;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import com.safelogj.lim.AppController;
import com.safelogj.lim.NetworkService;
import com.safelogj.lim.NotificationHelper;
import com.safelogj.lim.R;
import com.safelogj.lim.adapters.MsgAdapter;
import com.safelogj.lim.databinding.FragmentChatBinding;
import com.safelogj.lim.model.Chat;
import com.safelogj.lim.model.Message;
import com.safelogj.lim.viewmodels.ChatViewModel;
import com.safelogj.lim.viewmodels.ResultCallback;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class ChatFragment extends Fragment {

    private static final String ARG_CHAT_ID = "arg_chat_id";
    private static final String ARG_CHAT_LOCAL_ID = "arg_chat_local_id";
    private static final String ARG_CHAT_NAME = "arg_chat_name";
    private static final String ARG_CHAT_COLOR = "arg_chat_color";
    private final List<Message> messages = new ArrayList<>();
    private AppController controller;
    private FragmentChatBinding mBinding;
    private MsgAdapter adapter;
    private ChatViewModel chatViewModel;
    private final ActivityResultCallback<ActivityResult> callbackForGeneralPermitURI = result -> {
        if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
            Uri uri = result.getData().getData();
            if (uri != null && isSmallFile(uri)) {
                final int takeFlags = (Intent.FLAG_GRANT_READ_URI_PERMISSION);
                try {
                    controller.getContentResolver().takePersistableUriPermission(uri, takeFlags);
                    Log.d(AppController.LOG_TAG, "Разрешение на URI сохранено: " + uri);
                } catch (SecurityException e) {
                    Log.d(AppController.LOG_TAG, "Ошибка получения разрешений на URI: " + e.getMessage(), e);
                }
                DocumentFile documentFile = DocumentFile.fromSingleUri(controller, uri);
                if (documentFile.exists()) {
                    chatViewModel.selectFile(uri, documentFile.getName());
                }
            } else {
                Toast.makeText(controller, getString(R.string.big_file_error), Toast.LENGTH_SHORT).show();
            }
        }
    };
    private final ActivityResultLauncher<Intent> requestGeneralPermitURI =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), callbackForGeneralPermitURI);

    private final ActivityResultCallback<Boolean> callbackAskReadFilePermit = result -> {
        if (Boolean.TRUE == result) {
            requestGeneralPermitURI.launch(getIntentActionOpenDoc());
        }
    };
    private final ActivityResultLauncher<String> requestAskReadFilePermit =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), callbackAskReadFilePermit);

    private final ActivityResultCallback<Boolean> callbackRecordPermit = result -> {
        if (Boolean.TRUE == result) {
            startRecording();
        }
    };

    private final ActivityResultLauncher<String> requestRecordPermit =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), callbackRecordPermit);

    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final Runnable uiRunnable = new Runnable() {
        @Override
        public void run() {
            if (currentChatId != Chat.INVALID_ID) {
                chatViewModel.loadDbMessages(currentChatId, lastMsgListSize);
                controller.getDbHelper().markChatAsRead(currentChatId);
                updateOnlineStatusUI();
            }
            controller.getDbHelper().getUnreadChats(new ResultCallback<>() {
                @Override
                public void onSuccess(List<Chat> unreadChats) {
                    unreadChats.removeIf(chat -> chat.id == currentChatId);
                    if (!unreadChats.isEmpty()) {
                        NotificationHelper.showNotification(controller, unreadChats);
                    }
                }

                @Override
                public void onError(String msg) {
                    Log.w(AppController.LOG_TAG, msg);
                }
            });
            uiHandler.postDelayed(this, 4000);
        }

        private void updateOnlineStatusUI() {
            Log.d(AppController.LOG_TAG, "updateOnlineStatusUI");
            if (mBinding == null) return;
            Boolean isOnline = false;
            for (Map<Long, Boolean> userChats : AppController.getChatsStatuses()) {
                Log.d(AppController.LOG_TAG, "updateOnlineStatusUI пееребор");
                if (userChats.containsKey(currentChatId)) {
                    isOnline = userChats.get(currentChatId);
                    Log.d(AppController.LOG_TAG, "updateOnlineStatusUI нашли -1 " + currentChatId + " ono " + isOnline);
                    break;
                }
            }
            if (mBinding.onlineStatus.getBackground() != null) {
                mBinding.onlineStatus.getBackground().mutate().setTint(ContextCompat.getColor(
                        controller, (isOnline == null || !isOnline) ? R.color.light_gray_aaa : R.color.last_time));
            }
        }
    };

    private MediaRecorder mediaRecorder;
    private File audioFile;
    private long startTime = 0L;
    private AlertDialog recordingDialog;
    private final Handler recordingHandler = new Handler(Looper.getMainLooper());
    private final Runnable recordingTimerRunnable = new Runnable() {
        @Override
        public void run() {
            if (startTime > 0) {
                long seconds = (System.currentTimeMillis() - startTime) / 1000;
                if (recordingDialog != null) {
                    TextView tvTimer = recordingDialog.findViewById(R.id.tvRecordingTimer);
                    if (tvTimer != null) {
                        tvTimer.setText(String.format(Locale.US, "%02d:%02d", seconds / 60, seconds % 60));
                    }
                }
                recordingHandler.postDelayed(this, 1000);
            }
        }
    };

    private long currentChatId = Chat.INVALID_ID;
    private long currentChatLocalId = Chat.INVALID_ID;
    private int chatColor;
    private String currentChatName = AppController.EMPTY_STRING;
    private String interlocutorPublicKey = AppController.EMPTY_STRING;
    private String inputText = AppController.EMPTY_STRING;
    private int lastMsgListSize;
    private long lastMaxMsgId;

    public ChatFragment() {
        // Required empty public constructor
    }

    public static ChatFragment newInstance(long chatId, long chatLocalId, String chatName, int chatColor) {
        ChatFragment fragment = new ChatFragment();
        Bundle args = new Bundle();
        args.putLong(ARG_CHAT_ID, chatId);
        args.putLong(ARG_CHAT_LOCAL_ID, chatLocalId);
        args.putString(ARG_CHAT_NAME, chatName);
        args.putInt(ARG_CHAT_COLOR, chatColor);
        fragment.setArguments(args);
        return fragment;
    }


    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        controller = (AppController) requireActivity().getApplication();
        if (getArguments() != null) {
            currentChatId = getArguments().getLong(ARG_CHAT_ID, Chat.INVALID_ID);
            currentChatLocalId = getArguments().getLong(ARG_CHAT_LOCAL_ID, Chat.INVALID_ID);
            currentChatName = getArguments().getString(ARG_CHAT_NAME, AppController.EMPTY_STRING);
            chatColor = getArguments().getInt(ARG_CHAT_COLOR);
        }
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        mBinding = FragmentChatBinding.inflate(inflater, container, false);
        return mBinding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        adapter = new MsgAdapter(controller.getUserId(), chatColor);
        mBinding.messagesRecyclerView.setAdapter(adapter);

        setSendBtnListener();
        setAddFileBtnListener();
        mBinding.clearFileButton.setOnClickListener(v -> chatViewModel.clearFile());

        chatViewModel = new ViewModelProvider(this).get(ChatViewModel.class);

        setOnScrollListener();
        setObserveChat();
        setObservePublicKey();
        setObserveDbPublicKey();
        setObserveMsgList();
        setObserveSelectedFileUri();
        setObserveErrorStatus();
        setKeyboardPadding();
        updateBottomPanel();

        if (currentChatId == Chat.INVALID_ID) {
            addSystemMessageToList(getString(R.string.send_login_hint));
        } else {
            chatViewModel.getDbPublicKey(currentChatId);
        }
    }

    @Override
    public void onStart() {
        super.onStart();
        uiHandler.post(uiRunnable);
        clearNotificationIfMatch();
    }

    @Override
    public void onStop() {
        super.onStop();
        uiHandler.removeCallbacks(uiRunnable);
        if (adapter != null) {
            adapter.pausePlaying();
        }
    }

    private void setSendBtnListener() {
        mBinding.sendButton.setOnClickListener(v -> {
            inputText = Message.replaceEmoji(mBinding.messageEditText.getText().toString().trim());
            if (currentChatId == Chat.INVALID_ID && !inputText.isEmpty() && !inputText.equals(controller.getUsername())) { // РЕЖИМ ПОИСКА
                mBinding.messageEditText.setText(AppController.EMPTY_STRING);
                chatViewModel.checkChatInDb(inputText);
                Log.d(AppController.LOG_TAG, "поиск");
            } else if (currentChatId != Chat.INVALID_ID) { // РЕЖИМ ОТПРАВКИ
                Log.d(AppController.LOG_TAG, "отправка");
                if (interlocutorPublicKey.isEmpty()) {
                    Log.d(AppController.LOG_TAG, "отправка нет ключа для чата " + currentChatId);
                    chatViewModel.searchInterlocutorOnServer(null, currentChatId);
                } else {
                    Log.d(AppController.LOG_TAG, "отправка есть ключ");
                    sendMessage();
                }
            }
        });
    }

    private void setAddFileBtnListener() {
        mBinding.addFileButton.setOnClickListener(v -> {
            if (currentChatId != Chat.INVALID_ID) {
                if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2
                        && ContextCompat.checkSelfPermission(controller, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                    requestAskReadFilePermit.launch(Manifest.permission.READ_EXTERNAL_STORAGE);
                    return;
                }
                requestGeneralPermitURI.launch(getIntentActionOpenDoc());
            }
        });

        mBinding.addFileButton.setOnLongClickListener(v -> {
            if (currentChatId != Chat.INVALID_ID) {
                if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO)
                        != PackageManager.PERMISSION_GRANTED) {
                    requestRecordPermit.launch(Manifest.permission.RECORD_AUDIO);
                } else {
                    startRecording();
                }
            }
            return true;
        });
    }

    private void setObserveMsgList() {
        chatViewModel.getMsgList().observe(getViewLifecycleOwner(), msgList -> {
            if (msgList != null && !msgList.isEmpty() && mBinding != null) {
                renewChatName(msgList.get(msgList.size() - 1).chatName);
                messages.clear();
                msgList.sort((o1, o2) -> Long.compare(o1.localId, o2.localId));
                messages.addAll(msgList);
                adapter.submitList(new ArrayList<>(msgList), () -> {
                    long newMaxId = msgList.get(msgList.size() - 1).localId;
                    if (newMaxId > lastMaxMsgId) {
                        mBinding.messagesRecyclerView.scrollToPosition(adapter.getItemCount() - 1);
                    }
                    lastMsgListSize = msgList.size();
                    lastMaxMsgId = newMaxId;
                });
            }
        });
    }

    private void renewChatName(@Nullable String freshChatName) {
        if (freshChatName != null && !freshChatName.isEmpty()
                && !freshChatName.equals(currentChatName)) {
            currentChatName = freshChatName;
            mBinding.chatNameText.setText(currentChatName);
        }
    }

    private void setObserveSelectedFileUri() {
        chatViewModel.getSelectedFileUri().observe(getViewLifecycleOwner(), uri -> {
            if (mBinding == null) return;
            if (uri != null) { // Показываем панель с именем файла
                mBinding.attachmentPreview.setVisibility(View.VISIBLE);
                mBinding.fileNameText.setText(chatViewModel.getSelectedFileName());
            } else { // Скрываем панель, если файл удален
                mBinding.attachmentPreview.setVisibility(View.INVISIBLE);
            }
        });
    }

    private void setOnScrollListener() {
        mBinding.messagesRecyclerView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                super.onScrolled(recyclerView, dx, dy);
                if (dy < 0) { // dy < 0 означает, что пользователь скроллит ВВЕРХ
                    LinearLayoutManager layoutManager = (LinearLayoutManager) recyclerView.getLayoutManager();
                    if (layoutManager != null && layoutManager.findFirstVisibleItemPosition() <= 5) {
                        chatViewModel.loadMoreMessages(currentChatId);
                    }
                }
            }
        });
    }

    private void setObserveChat() {
        chatViewModel.getFoundChat().observe(getViewLifecycleOwner(), chat -> {
            if (chat != null && mBinding != null) {
                currentChatId = chat.id;
                currentChatName = chat.name;
                chatViewModel.getDbPublicKey(currentChatId);
                updateBottomPanel();
            }
        });
    }

    private void setObservePublicKey() {
        chatViewModel.getInterlocutorPublicKey().observe(getViewLifecycleOwner(), publicKey -> {
            if (publicKey != null) {
                Log.d(AppController.LOG_TAG, "взяли ключ от сервера: перед отправкой ответа начавшему чат ");
                interlocutorPublicKey = publicKey;
                sendMessage();
            }
        });
    }

    private void setObserveDbPublicKey() {
        chatViewModel.getDbPublicKey().observe(getViewLifecycleOwner(), chatKey -> {
            if (chatKey != null) {
                Log.d(AppController.LOG_TAG, "взяли ключ из БД: при входе в чат " + chatKey);
                interlocutorPublicKey = chatKey;
            }
        });
    }

    private void setObserveErrorStatus() {
        chatViewModel.getErrorStatus().observe(getViewLifecycleOwner(), error -> {
            if (error != null && mBinding != null) {
                addSystemMessageToList(error);
            }
        });
    }

    @Override
    public void onDestroyView() {
        if (recordingDialog != null && recordingDialog.isShowing()) {
            recordingDialog.dismiss();
        }
        if (adapter != null) {
            adapter.stopPlaying();
        }
        super.onDestroyView();
        mBinding = null;
    }

    private void sendMessage() {
        Uri fileUri = chatViewModel.getSelectedFileUri().getValue();
        if (inputText.isEmpty() && fileUri == null) {
            return;
        }

        mBinding.messageEditText.setText(AppController.EMPTY_STRING);
        chatViewModel.sendMessage(buildMessage(fileUri, chatViewModel.getSelectedFileName()), currentChatLocalId);
        chatViewModel.clearFile();
        inputText = AppController.EMPTY_STRING;
        chatViewModel.loadDbMessages(currentChatId, lastMsgListSize);
    }

    private Message buildMessage(@Nullable Uri fileUri, @Nullable String fileName) {
        Message msg = new Message();
        msg.chatId = currentChatId;
        msg.chatName = currentChatName;
        msg.senderId = controller.getUserId();
        msg.interlocutorPublicKey = interlocutorPublicKey;
        msg.text = inputText.isEmpty() ? fileName : inputText; // для синхронизации с другими устройствами
        msg.type = fileUri == null ? Message.TYPE_TEXT : getMessageType(fileUri);
        msg.filePath = fileUri == null ? null : fileUri.toString();
        msg.fileName = fileName;
        msg.timestamp = System.currentTimeMillis();
        msg.formattedTime = AppController.formatSmartTime(controller, msg.timestamp);
        Log.d(AppController.LOG_TAG, "Отправляем сообщение в чат: " + msg.chatId + " публичный ключ " + interlocutorPublicKey);
        return msg;
    }

    private void addSystemMessageToList(String text) {
        Message msg = new Message();
        msg.text = text;
        Log.d(AppController.LOG_TAG, "Добавляем системное сообщение: " + text);
        msg.senderId = Message.SYSTEM_SENDER_ID;
        messages.add(msg);
        messages.sort((o1, o2) -> Long.compare(o1.localId, o2.localId));
        adapter.submitList(new ArrayList<>(messages), () -> mBinding.messagesRecyclerView.scrollToPosition(adapter.getItemCount() - 1));
    }

    private void updateBottomPanel() {
        mBinding.chatNameText.setText(currentChatName);
        if (currentChatId == Chat.INVALID_ID) {
            // Режим ПОИСКА
            mBinding.addFileButton.setVisibility(View.INVISIBLE);
            mBinding.messageEditText.setHint(getString(R.string.send_login));
        } else {
            // Режим ПЕРЕПИСКИ
            mBinding.addFileButton.setVisibility(View.VISIBLE);
            mBinding.messageEditText.setHint(getString(R.string.send_msg));
        }
    }

    private void setKeyboardPadding() {
        ViewCompat.setOnApplyWindowInsetsListener(mBinding.inputContainer, (v, insets) -> {
            int imeHeight = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom;
            int systemBarsHeight = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom;
            int keyboardHeight = Math.max(0, imeHeight - systemBarsHeight);
            v.setTranslationY(-keyboardHeight);
            mBinding.messagesRecyclerView.setPadding(
                    mBinding.messagesRecyclerView.getPaddingLeft(),
                    mBinding.messagesRecyclerView.getPaddingTop(),
                    mBinding.messagesRecyclerView.getPaddingRight(),
                    keyboardHeight
            );
            return insets;
        });
    }

    private Intent getIntentActionOpenDoc() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.setType("*/*");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION |
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        return intent;
    }

    private boolean isSmallFile(Uri uri) {
        Cursor cursor = controller.getContentResolver().query(uri, null, null, null, null);
        if (cursor != null && cursor.moveToFirst()) {
            int sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE);
            if (!cursor.isNull(sizeIndex)) {
                long size = cursor.getLong(sizeIndex);
                cursor.close();
                return size < NetworkService.FILE_SIZE_LIMIT;
            }
            cursor.close();
        }
        return false;
    }

    private String getMessageType(Uri uri) {
        String mimeType = controller.getContentResolver().getType(uri);
        if (mimeType != null && mimeType.startsWith("image/")) {
            return Message.TYPE_IMAGE;
        }
        return Message.TYPE_FILE;
    }

    private void clearNotificationIfMatch() {
        NotificationManager manager = (NotificationManager) controller.getSystemService(Context.NOTIFICATION_SERVICE);
        for (StatusBarNotification sbn : manager.getActiveNotifications()) {
            if (sbn.getId() == NotificationHelper.NOTIFICATION_ID
                    && sbn.getNotification().extras.getLong(NotificationHelper.EXTRA_CHAT_ID, -1) == currentChatId) {

                manager.cancel(NotificationHelper.NOTIFICATION_ID);
            }
        }
    }

    private void startRecording() {
        try {
            // 1. Создаем файл для записи
            audioFile = new File(controller.getExternalFileDir(),"voice_" + System.currentTimeMillis() + ".m4a");
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                mediaRecorder = new android.media.MediaRecorder(controller);
            } else {
                @SuppressWarnings("deprecation")
                MediaRecorder legacyRecorder = new MediaRecorder();
                mediaRecorder = legacyRecorder;
            }
            // 2. Настраиваем MediaRecorder
            mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            mediaRecorder.setOutputFile(audioFile.getAbsolutePath());

            mediaRecorder.prepare();
            mediaRecorder.start();
            // 3. Показываем диалог
            View dialogView = getLayoutInflater().inflate(R.layout.dialog_recording, null);
            recordingDialog = new AlertDialog.Builder(requireContext())
                    .setView(dialogView)
                    .setOnDismissListener(dialog -> stopRecording()) // Остановка при клике мимо
                    .create();

            if (recordingDialog.getWindow() != null) {
                recordingDialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
            }
            recordingDialog.show();
            // 4. Запускаем таймер
            startTime = System.currentTimeMillis();
            recordingHandler.post(recordingTimerRunnable);

        } catch (Exception e) {
            Log.e(AppController.LOG_TAG, "Recording error: " + e.getMessage());
            Toast.makeText(requireContext(), "Error starting record", Toast.LENGTH_SHORT).show();
        }
    }

    private void stopRecording() {
        if (startTime == 0) return; // Уже остановлено

        try {
            startTime = 0;
            recordingHandler.removeCallbacks(recordingTimerRunnable);

            if (mediaRecorder != null) {
                mediaRecorder.stop();
                mediaRecorder.release();
                mediaRecorder = null;
            }
            // Если запись длилась больше 1 секунды — прикрепляем файл
            if (audioFile != null && audioFile.exists() && audioFile.length() > 100) {
                chatViewModel.selectFile(Uri.fromFile(audioFile), audioFile.getName());
            }

        } catch (Exception e) {
            Log.e(AppController.LOG_TAG, "Stop recording error: " + e.getMessage());
        }
    }

}