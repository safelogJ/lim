package com.safelogj.lim.viewmodels;

import android.app.Application;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.safelogj.lim.AppController;
import com.safelogj.lim.NetworkService;
import com.safelogj.lim.model.Chat;
import com.safelogj.lim.model.Message;
import com.safelogj.lim.model.User;

import java.util.List;

public class ChatViewModel extends AndroidViewModel {

    private final MutableLiveData<Long> sendMsgLocalId = new MutableLiveData<>();
    private final MutableLiveData<String> errorStatus = new MutableLiveData<>();
    private final MutableLiveData<User> foundUser = new MutableLiveData<>();
    private final MutableLiveData<Chat> foundChat = new MutableLiveData<>();
    private final MutableLiveData<List<Message>> msgList = new MutableLiveData<>();
    private final MutableLiveData<Uri> selectedFileUri = new MutableLiveData<>();
    private final AppController controller;
    @Nullable
    private String selectedFileName;

    public ChatViewModel(@NonNull Application application) {
        super(application);
        controller = (AppController) application;
    }

    public LiveData<User> getFoundUser() {
        return foundUser;
    }

    public LiveData<Chat> getFoundChat() {
        return foundChat;
    }

    public LiveData<String> getErrorStatus() {
        return errorStatus;
    }

    public LiveData<Long> getSusseccSendMsgLocalId() {
        return sendMsgLocalId;
    }

    public LiveData<List<Message>> getMsgList() {
        return msgList;
    }

    public LiveData<Uri> getSelectedFileUri() {
        return selectedFileUri;
    }

    @Nullable
    public String getSelectedFileName() {
        return selectedFileName;
    }


    public void selectFile(Uri uri, String name) {
        selectedFileUri.postValue(uri);
        this.selectedFileName = name;
    }

    public void clearFile() {
        selectedFileUri.postValue(null);
        selectedFileName = null;
    }


    public void searchUser(String login) {
        controller.getDbExecutor().execute(() -> controller.getNetworkService().searchUser(
                controller.getUsername(), controller.getPassword(), login, new ResultCallback<>() {

                    @Override
                    public void onSuccess(User user) {
                        foundUser.postValue(user);
                    }

                    @Override
                    public void onError(String errorMsg) {
                        errorStatus.postValue(errorMsg);
                        foundUser.postValue(null); // Очищаем старый результат
                    }
                }));
    }

    public void searchChat(User user) {
        controller.getDbExecutor().execute(() -> controller.getNetworkService().searchChat(user, new ResultCallback<>() {

            @Override
            public void onSuccess(Chat chat) {
                foundChat.postValue(chat);
            }

            @Override
            public void onError(String errorMsg) {
                errorStatus.postValue(errorMsg);
                foundChat.postValue(null);

            }
        }));

    }

    // Здесь же можно добавить метод для отправки сообщения в будущем
    public void sendMessage(Message msg) {
        controller.getDbHelper().saveMessage(msg);

        if (NetworkService.TEXT.equals(msg.type)) {
            // Шлем обычный текст
            controller.getNetworkService().sendTextMessage(msg, new ResultCallback<>() {
                @Override
                public void onSuccess(Long localId) {
                    sendMsgLocalId.postValue(localId);
                }

                @Override
                public void onError(String msg) { /* логика ошибки */ }
            });
        } else {
            // Шлем медиа (картинку или файл)
            controller.getNetworkService().sendMediaMessage(msg, new ResultCallback<>() {
                @Override
                public void onSuccess(Long localId) {
                    sendMsgLocalId.postValue(localId);
                }

                @Override
                public void onError(String msg) { /* логика ошибки */ }
            });
        }
    }

    public void loadMessages(long chatId) {
        controller.getDbHelper().loadMessages(chatId, new ResultCallback<>() {

            @Override
            public void onSuccess(List<Message> list) {
                msgList.postValue(list);
            }

            @Override
            public void onError(String errorMsg) {
                errorStatus.postValue(errorMsg);
            }

        });
    }
}
