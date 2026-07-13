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
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.documentfile.provider.DocumentFile;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import android.provider.OpenableColumns;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import com.safelogj.lim.AppController;
import com.safelogj.lim.adapters.MessageAdapter;
import com.safelogj.lim.NetworkService;
import com.safelogj.lim.R;
import com.safelogj.lim.databinding.FragmentChatBinding;
import com.safelogj.lim.model.Chat;
import com.safelogj.lim.model.Message;
import com.safelogj.lim.viewmodels.ChatViewModel;

import java.util.ArrayList;
import java.util.List;

public class ChatFragment extends Fragment {

    private static final String ARG_CHAT_ID = "arg_chat_id";
    private final List<Message> messages = new ArrayList<>();
    private AppController controller;
    private FragmentChatBinding mBinding;
    private MessageAdapter adapter;
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
    // Текущий ID чата. Если -1, значит мы только ищем собеседника
    private long currentChatId = Chat.INVALID_ID;

    public ChatFragment() {
        // Required empty public constructor
    }

    public static ChatFragment newInstance(long chatId) {
        ChatFragment fragment = new ChatFragment();
        Bundle args = new Bundle();
        args.putLong(ARG_CHAT_ID, chatId);
        fragment.setArguments(args);
        return fragment;
    }


    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        controller = (AppController) requireActivity().getApplication();
        if (getArguments() != null) {
            currentChatId = getArguments().getLong(ARG_CHAT_ID, Chat.INVALID_ID);
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

        adapter = new MessageAdapter(messages, controller.getUserId());
        mBinding.messagesRecyclerView.setAdapter(adapter);

        setSendBtnListener();
        setAddFileBtnListener();
        mBinding.clearFileButton.setOnClickListener(v -> chatViewModel.clearFile());

        chatViewModel = new ViewModelProvider(this).get(ChatViewModel.class);
        setObserveUser();
        setObserveChat();
        setObserveSendMsgLocalId();
        setObserveMsgList();
        setObserveSelectedFileUri();
        setObserveErrorStatus();

        updateChatUI();
        setKeyboardPadding();
        // Если чат уже существует, загружаем историю
        if (currentChatId != Chat.INVALID_ID) {
            loadChatHistory();
        } else {
            addSystemMessageToList("Введите логин пользователя, чтобы начать чат");
        }
    }

    private void setSendBtnListener() {
        mBinding.sendButton.setOnClickListener(v -> {
            String text = mBinding.messageEditText.getText().toString().trim();
            if (currentChatId == Chat.INVALID_ID && !text.isEmpty() && !text.equals(controller.getUsername())) { // РЕЖИМ ПОИСКА
                performUserSearch(text);
            } else if (currentChatId != Chat.INVALID_ID) { // РЕЖИМ ОТПРАВКИ
                Uri fileUri = chatViewModel.getSelectedFileUri().getValue();
                if (text.isEmpty() && fileUri == null) {
                    return;
                }
                Message msg = buildMessage(text.isEmpty() ? null : text, // text
                        fileUri == null ? NetworkService.TEXT : getMessageType(fileUri),  // type
                        fileUri,  // uri
                        chatViewModel.getSelectedFileName()); // name
                messages.add(msg);
                adapter.notifyItemInserted(messages.size() - 1);
                mBinding.messagesRecyclerView.scrollToPosition(messages.size() - 1);
                mBinding.messageEditText.setText(AppController.EMPTY_STRING);
                chatViewModel.sendMessage(msg);
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

    private void setObserveUser() {
        chatViewModel.getFoundUser().observe(getViewLifecycleOwner(), user -> {
            if (user != null) {
                chatViewModel.searchChat(user);
            //    updateChatUI();
             //   addSystemMessageToList("Начинаем чат с " + user.displayName);
            }
        });
    }

    private void setObserveChat() {
        chatViewModel.getFoundChat().observe(getViewLifecycleOwner(), chat -> {
            if (chat != null) {  // Ура! Чат найден.
                currentChatId = chat.id;
                updateChatUI();
                loadChatHistory();
            }
        });
    }

    private void setObserveSendMsgLocalId() {
        chatViewModel.getSusseccSendMsgLocalId().observe(getViewLifecycleOwner(), localId -> {
            if (localId != null) {
                for (int i = 0; i < messages.size(); i++) {
                    if (messages.get(i).localId == localId) {
                        messages.get(i).sendStatus = Message.STATUS_SENT;
                        adapter.notifyItemChanged(i);
                        break;
                    }
                }
            }
        });
    }

    private void setObserveMsgList() {
        chatViewModel.getMsgList().observe(getViewLifecycleOwner(), msgList -> {
            if (msgList != null) {
                messages.clear();
                messages.addAll(msgList);
                adapter.notifyDataSetChanged();
            }
        });
    }

    private void setObserveSelectedFileUri() {
        chatViewModel.getSelectedFileUri().observe(getViewLifecycleOwner(), uri -> {
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
            if (error != null) {
                addSystemMessageToList(error);
            }
        });
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        mBinding = null;
    }

    private void performUserSearch(String login) {
        addSystemMessageToList("Поиск пользователя '" + login + "'...");
        mBinding.messageEditText.setText(AppController.EMPTY_STRING);
        chatViewModel.searchUser(login);
    }

    private Message buildMessage(@Nullable String text, @NonNull String type, @Nullable Uri fileUri, @Nullable String fileName) {
        Message msg = new Message();
        msg.chatId = currentChatId;
        msg.senderId = controller.getUserId();
        msg.text = text;
        msg.type = type;
        msg.timestamp = System.currentTimeMillis();
        msg.formattedTime = AppController.formatSmartTime(controller, msg.timestamp);
        msg.filePath = fileUri == null ? null : fileUri.toString();
        msg.fileName = fileName;
        return msg;
    }

    private void addSystemMessageToList(String text) {
        Message msg = new Message();
        msg.text = text;
        msg.senderId = Message.SYSTEM_SENDER_ID;
        messages.add(msg);
        adapter.notifyItemInserted(messages.size() - 1);
        mBinding.messagesRecyclerView.scrollToPosition(messages.size() - 1);
    }

    private void loadChatHistory() {
        chatViewModel.loadMessages(currentChatId);
    }

    private void updateChatUI() {
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

//    private void setKeyboardPadding() {
//        ViewCompat.setOnApplyWindowInsetsListener(mBinding.inputContainer, (v, insets) -> {
//            // Узнаем, на сколько пикселей клавиатура (ime) вылезла снизу экрана
//            int keyboardHeight = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom;
//            // Сдвигаем контейнер ввода вверх ровно на эту высоту
//            v.setTranslationY(-keyboardHeight);
//            // А чтобы RecyclerView тоже не перекрывался клавиатурой,
//            // добавим ему нижний отступ (padding) на ту же высоту
//            mBinding.messagesRecyclerView.setPadding(
//                    mBinding.messagesRecyclerView.getPaddingLeft(),
//                    mBinding.messagesRecyclerView.getPaddingTop(),
//                    mBinding.messagesRecyclerView.getPaddingRight(),
//                    keyboardHeight
//            );
//            return insets;
//        });
//    }

    private void setKeyboardPadding() {
        // Слушаем отступы на САМОМ ВЕРХНЕМ контейнере фрагмента
        ViewCompat.setOnApplyWindowInsetsListener(mBinding.chatOuter, (v, insets) -> {
            Insets ime = insets.getInsets(WindowInsetsCompat.Type.ime());
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());

            // Вычисляем чистую высоту клавиатуры за вычетом системных баров
            // (чтобы не прыгало дважды, если есть навигационная панель)
            int bottomPadding = Math.max(0, ime.bottom - systemBars.bottom);

            // 1. Вместо трансляции просто меняем нижний отступ у всего контейнера ввода
            // Это поднимет его над клавиатурой естественным образом
            ConstraintLayout.LayoutParams lp = (ConstraintLayout.LayoutParams) mBinding.inputContainer.getLayoutParams();
            lp.bottomMargin = bottomPadding + (int)(4 * getResources().getDisplayMetrics().density); // ваши 4dp из XML
            mBinding.inputContainer.setLayoutParams(lp);

            // 2. Добавляем отступ списку сообщений, чтобы последние сообщения были видны
            mBinding.messagesRecyclerView.setPadding(
                    mBinding.messagesRecyclerView.getPaddingLeft(),
                    mBinding.messagesRecyclerView.getPaddingTop(),
                    mBinding.messagesRecyclerView.getPaddingRight(),
                    bottomPadding
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