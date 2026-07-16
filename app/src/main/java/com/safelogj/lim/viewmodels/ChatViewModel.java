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
    private final MutableLiveData<Chat> foundChat = new MutableLiveData<>();
    private final MutableLiveData<String> errorStatus = new MutableLiveData<>();
   // private final MutableLiveData<Chat> chatServer = new MutableLiveData<>();
    private final MutableLiveData<List<Message>> msgList = new MutableLiveData<>();
    private final MutableLiveData<Uri> selectedFileUri = new MutableLiveData<>();
    private final AppController controller;
    @Nullable
    private String selectedFileName;

    public ChatViewModel(@NonNull Application application) {
        super(application);
        controller = (AppController) application;
    }

//    public LiveData<Chat> getChatServer() {
//        return chatServer;
//    }

    public LiveData<String> getErrorStatus() {
        return errorStatus;
    }

    public LiveData<Long> getSendMsgLocalId() {
        return sendMsgLocalId;
    }

    public LiveData<List<Message>> getMsgList() {
        return msgList;
    }

    public LiveData<Uri> getSelectedFileUri() {
        return selectedFileUri;
    }

    public LiveData<Chat> getFoundChat() {
        return foundChat;
    }

    @Nullable
    public String getSelectedFileName() {
        return selectedFileName;
    }



    public void selectFile(Uri uri, String name) {
        selectedFileUri.postValue(uri);
        selectedFileName = name;
    }

    public void clearFile() {
        selectedFileUri.postValue(null);
        selectedFileName = null;
    }

    // Здесь же можно добавить метод для отправки сообщения в будущем
    public void sendMessage(Message msg, long localChatId) {
        controller.getDbHelper().saveMessage(msg);

        if (NetworkService.TEXT.equals(msg.type)) {
            controller.getNetStreams()[Math.abs((int) (localChatId % (AppController.POOL_SIZE - 1)))].execute(()->
                    controller.getNetworkService().sendTextMessage(msg, new ResultCallback<>() {
                @Override
                public void onSuccess(Long localId) {
                    sendMsgLocalId.postValue(localId);
                }

                @Override
                public void onError(String msg) { /* логика ошибки */ }
            }));

        } else {
            controller.getNetStreams()[Math.abs((int) (localChatId % (AppController.POOL_SIZE - 1)))].execute(()->
                    controller.getNetworkService().sendMediaMessage(msg, new ResultCallback<>() {
                @Override
                public void onSuccess(Long localId) {
                    sendMsgLocalId.postValue(localId);
                }

                @Override
                public void onError(String msg) { /* логика ошибки */ }
            }));
        }
    }

    public void loadDbMessages(long chatId) {
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

    public void checkChatInDb(String login) {
        controller.getDbHelper().getChatIdByUsername(login, new ResultCallback<>() {
            @Override
            public void onSuccess(Chat chat) {
                foundChat.postValue(chat);
            }

            @Override
            public void onError(String login) {
                foundChat.postValue(null);
                searchUserOnServer(login);
            }
        });
    }

    private void searchUserOnServer(String login) {
        controller.getUserExecutor().execute(() -> controller.getNetworkService().searchUser(
                controller.getUsername(), controller.getPassword(), login, new ResultCallback<>() {

                    @Override
                    public void onSuccess(User user) {
                        searchChatOnServer(user);
                    }

                    @Override
                    public void onError(String errorMsg) {
                        errorStatus.postValue(errorMsg);
                    }
                }));
    }

    private void searchChatOnServer(User queryUser) {
        controller.getUserExecutor().execute(() -> controller.getNetworkService().searchNewChat(queryUser, new ResultCallback<>() {

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
}
