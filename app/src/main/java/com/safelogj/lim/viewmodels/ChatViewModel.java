package com.safelogj.lim.viewmodels;

import android.app.Application;
import android.net.Uri;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.safelogj.lim.AppController;
import com.safelogj.lim.model.Chat;
import com.safelogj.lim.model.Message;
import com.safelogj.lim.model.User;

import java.util.List;

public class ChatViewModel extends AndroidViewModel {

    private final MutableLiveData<Chat> foundChat = new MutableLiveData<>();
    private final MutableLiveData<String> interlocutorPublicKey = new MutableLiveData<>();
    private final MutableLiveData<String> dbPublicKey = new MutableLiveData<>();
    private final MutableLiveData<String> errorStatus = new MutableLiveData<>();
    private final MutableLiveData<List<Message>> msgList = new MutableLiveData<>();
    private final MutableLiveData<Uri> selectedFileUri = new MutableLiveData<>();
    private final AppController controller;
    @Nullable
    private String selectedFileName;
    private boolean isLoadingMore = false;

    public ChatViewModel(@NonNull Application application) {
        super(application);
        controller = (AppController) application;
    }

    public LiveData<String> getErrorStatus() {
        return errorStatus;
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

    public LiveData<String> getInterlocutorPublicKey() {
        return interlocutorPublicKey;
    }

    public LiveData<String> getDbPublicKey() {
        return dbPublicKey;
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

    public void getDbPublicKey(long chatId) {
        controller.getDbHelper().getInterlocutorPublicKey(chatId, new ResultCallback<>() {

            @Override
            public void onSuccess(String publicKey) {
                dbPublicKey.postValue(publicKey);
            }

            @Override
            public void onError(String errorMsg) {
                Log.d(AppController.LOG_TAG, errorMsg);
            }
        });
    }

    public void sendMessage(Message msg, long localChatId) {
        controller.getDbHelper().saveMsgBeforeSending(msg);
        Log.w(AppController.LOG_TAG, "сообщение из чата c local id : " + localChatId + " (отправлено в нити "
                + Math.abs((int) (localChatId % (AppController.POOL_SIZE - 2))) + ")");
        if (Message.TYPE_TEXT.equals(msg.type)) {
            controller.getNetStreams()[Math.abs((int) (localChatId % (AppController.POOL_SIZE - 2)))].execute(()->
                    controller.getNetworkService().sendTextMessage(msg));
        } else {
            controller.getNetStreams()[Math.abs((int) (localChatId % (AppController.POOL_SIZE - 2)))].execute(()->
                    controller.getNetworkService().sendMediaMessage(msg));
        }
    }

    public void loadDbMessages(long chatId, int lastMsgListSize) {
        controller.getDbHelper().loadChatMessages(chatId, lastMsgListSize, new ResultCallback<>() {

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

    public void loadMoreMessages(long chatId) {
        List<Message> currentList = msgList.getValue();
        if (currentList == null || currentList.isEmpty() || isLoadingMore) return;
        isLoadingMore = true;
        controller.getDbHelper().loadMoreMessages(chatId, currentList.get(currentList.size() - 1).localId, new ResultCallback<>() {
            @Override
            public void onSuccess(List<Message> fullList) {
                msgList.postValue(fullList);
                isLoadingMore = false;
            }

            @Override
            public void onError(String errorMsg) {
                isLoadingMore = false;
                Log.e(AppController.LOG_TAG, errorMsg);
            }
        });
    }

    public void checkChatInDb(@NonNull String login) {
        controller.getDbHelper().getChatIdByUsername(login, new ResultCallback<>() {
            @Override
            public void onSuccess(Chat chat) {
                foundChat.postValue(chat);
            }

            @Override
            public void onError(String login) {
                foundChat.postValue(null);
                searchInterlocutorOnServer(login, null);
            }
        });
    }

    public void searchInterlocutorOnServer(@Nullable String login, @Nullable Long chatId) {
        controller.getUserExecutor().execute(() -> controller.getNetworkService().searchInterlocutor(
                controller.getUsername(), controller.getPassword(), login, chatId, new ResultCallback<>() {

                    @Override
                    public void onSuccess(User user) {
                        if (login != null) {
                            searchChatOnServer(user);
                        } else {
                            interlocutorPublicKey.postValue(user.publicKey);
                        }

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
