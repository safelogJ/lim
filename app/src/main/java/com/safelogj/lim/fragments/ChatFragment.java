package com.safelogj.lim.fragments;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;

import androidx.activity.result.ActivityResult;
import androidx.activity.result.ActivityResultCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.documentfile.provider.DocumentFile;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import com.safelogj.lim.AppController;
import com.safelogj.lim.NetworkService;
import com.safelogj.lim.R;
import com.safelogj.lim.adapters.MsgAdapter;
import com.safelogj.lim.databinding.FragmentChatBinding;
import com.safelogj.lim.model.Chat;
import com.safelogj.lim.model.Message;
import com.safelogj.lim.viewmodels.ChatViewModel;

import java.util.ArrayList;
import java.util.List;

public class ChatFragment extends Fragment {

    private static final String ARG_CHAT_ID = "arg_chat_id";
    private static final String ARG_CHAT_LOCAL_ID = "arg_chat_local_id";
    private static final String ARG_CHAT_NAME = "arg_chat_name";
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
                DocumentFile documentFile = DocumentFile.fromSingleUri(requireContext(), uri);
                if (documentFile.exists()) {
                    chatViewModel.selectFile(uri, documentFile.getName());
                }
            } else {
                Toast.makeText(requireContext(), getString(R.string.big_file_error), Toast.LENGTH_SHORT).show();
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

    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final Runnable uiRunnable = new Runnable() {
        @Override
        public void run() {
            if (currentChatId != Chat.INVALID_ID) {
                chatViewModel.loadDbMessages(currentChatId);
                controller.getDbHelper().markChatAsRead(currentChatId);
            }
            uiHandler.postDelayed(this, 4000);
        }
    };

    private long currentChatId = Chat.INVALID_ID;
    private long currentChatLocalId = Chat.INVALID_ID;
    private String currentChatName = AppController.EMPTY_STRING;
    private int lastMessageCount = 0;

    public ChatFragment() {
        // Required empty public constructor
    }

    public static ChatFragment newInstance(long chatId, long chatLocalId, String chatName) {
        ChatFragment fragment = new ChatFragment();
        Bundle args = new Bundle();
        args.putLong(ARG_CHAT_ID, chatId);
        args.putLong(ARG_CHAT_LOCAL_ID, chatLocalId);
        args.putString(ARG_CHAT_NAME, chatName);
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

        adapter = new MsgAdapter(controller.getUserId());
        mBinding.messagesRecyclerView.setAdapter(adapter);

        setSendBtnListener();
        setAddFileBtnListener();
        mBinding.clearFileButton.setOnClickListener(v -> chatViewModel.clearFile());

        chatViewModel = new ViewModelProvider(this).get(ChatViewModel.class);

        setObserveChat();
        setObserveMsgList();
        setObserveSelectedFileUri();
        setObserveErrorStatus();

        updateBottomPanel();
        setKeyboardPadding();

        if (currentChatId == Chat.INVALID_ID) {
            addSystemMessageToList(getString(R.string.send_login_hint));
        }
    }

    @Override
    public void onStart() {
        super.onStart();
        uiHandler.post(uiRunnable);
    }

    @Override
    public void onStop() {
        super.onStop();
        uiHandler.removeCallbacks(uiRunnable);
    }

    private void setSendBtnListener() {
        mBinding.sendButton.setOnClickListener(v -> {
            String userText = mBinding.messageEditText.getText().toString().trim();
            if (currentChatId == Chat.INVALID_ID && !userText.isEmpty() && !userText.equals(controller.getUsername())) { // РЕЖИМ ПОИСКА
              searchChat(userText);
            } else if (currentChatId != Chat.INVALID_ID) { // РЕЖИМ ОТПРАВКИ
                Uri fileUri = chatViewModel.getSelectedFileUri().getValue();
                if (userText.isEmpty() && fileUri == null) {
                    return;
                }
                String fileName = chatViewModel.getSelectedFileName();
                Message msg = buildMessage((userText.isEmpty() && fileName != null) ? fileName : userText, // text
                        fileUri == null ? NetworkService.TEXT : getMessageType(fileUri),  // type
                        fileUri, fileName);
                mBinding.messageEditText.setText(AppController.EMPTY_STRING);
                Log.d(AppController.LOG_TAG, "Отправляем сообщение в чат: " + msg.chatId);
                chatViewModel.sendMessage(msg, currentChatLocalId);
                chatViewModel.loadDbMessages(currentChatId);
            }
        });
    }

    private void setAddFileBtnListener() {
        mBinding.addFileButton.setOnClickListener(v -> {
            if (currentChatId != Chat.INVALID_ID) {
                if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2
                        && ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                    requestAskReadFilePermit.launch(Manifest.permission.READ_EXTERNAL_STORAGE);
                    return;
                }
                requestGeneralPermitURI.launch(getIntentActionOpenDoc());
            }
        });
    }

    private void setObserveMsgList() {
        chatViewModel.getMsgList().observe(getViewLifecycleOwner(), msgList -> {
            if (msgList != null && mBinding != null) {
              //  boolean isNewMessageAdded = msgList.size() > lastMessageCount;
                //  lastMessageCount = msgList.size();
                messages.clear();
                messages.addAll(msgList);
                adapter.submitList(new ArrayList<>(msgList), () -> {
                    if (msgList.size() > lastMessageCount) {
                        lastMessageCount = msgList.size();
                        mBinding.messagesRecyclerView.scrollToPosition(adapter.getItemCount() - 1);
                    }
                });
            }
        });
    }

    private void setObserveSelectedFileUri() {
        chatViewModel.getSelectedFileUri().observe(getViewLifecycleOwner(), uri -> {
            if (mBinding == null) return;
            if (uri != null) {
                // Показываем панель с именем файла
                mBinding.attachmentPreview.setVisibility(View.VISIBLE);
                mBinding.fileNameText.setText(chatViewModel.getSelectedFileName());
            } else {
                // Скрываем панель, если файл удален
                mBinding.attachmentPreview.setVisibility(View.GONE);
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

    private void setObserveChat() {
        chatViewModel.getFoundChat().observe(getViewLifecycleOwner(), chat -> {
            if (chat != null && mBinding != null) {
                currentChatId = chat.id;
                currentChatName = chat.name;
                updateBottomPanel();
            }
        });
    }

    public void searchChat(String userText) {
        chatViewModel.checkChatInDb(userText);
        mBinding.messageEditText.setText(AppController.EMPTY_STRING);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        mBinding = null;
    }

    private Message buildMessage(@NonNull String text, @NonNull String type, @Nullable Uri fileUri, @Nullable String fileName) {
        Message msg = new Message();
        msg.chatId = currentChatId;
        msg.chatName = currentChatName;
        msg.senderId = controller.getUserId();
        msg.text = text;
        msg.type = type;
        msg.filePath = fileUri == null ? null : fileUri.toString();
        msg.fileName = fileName;
        msg.timestamp = System.currentTimeMillis();
        msg.formattedTime = AppController.formatSmartTime(controller, msg.timestamp);
        return msg;
    }

    private void addSystemMessageToList(String text) {
        Message msg = new Message();
        msg.text = text;
        Log.d(AppController.LOG_TAG, "Добавляем системное сообщение: " + text);
        msg.senderId = Message.SYSTEM_SENDER_ID;
        messages.add(msg);
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
        String mimeType = requireContext().getContentResolver().getType(uri);
        if (mimeType != null && mimeType.startsWith("image/")) {
            return NetworkService.IMAGE;
        }
        return NetworkService.FILE;
    }

}