package com.safelogj.lim.viewmodels;

import android.app.Application;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.safelogj.lim.AppController;
import com.safelogj.lim.R;
import com.safelogj.lim.model.Chat;

import java.util.ArrayList;
import java.util.List;

public class ChatListViewModel extends AndroidViewModel {

    private final MutableLiveData<List<Chat>> dbChatList = new MutableLiveData<>();
    private final MutableLiveData<Boolean> isChatHidden = new MutableLiveData<>();
    private final MutableLiveData<Boolean> isChatBlocked = new MutableLiveData<>();
    private final MutableLiveData<String> chatName = new MutableLiveData<>();
    private final AppController controller;

    public ChatListViewModel(@NonNull Application application) {
        super(application);
        controller = (AppController) application;
    }

    public LiveData<List<Chat>> getChatList() {
        return dbChatList;
    }

    public LiveData<Boolean> isChatHidden() {
        return isChatHidden;
    }

    public LiveData<Boolean> isChatBlocked() {
        return isChatBlocked;
    }

    public LiveData<String> getChatName() {
        return chatName;
    }



    public void loadDbChatList() {
        controller.getDbHelper().getChatList(new ResultCallback<>() {

            @Override
            public void onSuccess(List<Chat> chats) {
                List<Chat> uiList = new ArrayList<>();
                uiList.add(Chat.createNewChatAction(controller.getResources().getString(R.string.new_chat), controller.getResources().getString(R.string.find_user)));
                uiList.addAll(chats);
                dbChatList.postValue(uiList);
            }

            @Override
            public void onError(String errorMsg) {
                Log.d(AppController.LOG_TAG, "Ошибка получения списка чатов: " + errorMsg);
            }
        });

    }

    public void hideChat(Chat chat) {
        controller.getDbHelper().hideChatLocally(chat.id, new ResultCallback<>() {

            @Override
            public void onSuccess(Boolean result) {
                isChatHidden.postValue(result);
            }

            @Override
            public void onError(String errorMsg) {
                Log.d(AppController.LOG_TAG, errorMsg);
            }
        });
        controller.getNetStreams()[Math.abs((int) (chat.localId % (AppController.POOL_SIZE - 1)))].execute(()
                -> controller.getNetworkService().hideChat(chat.id));
    }

    public void setChatBlockedState(Chat chat) {
        controller.getNetStreams()[Math.abs((int) (chat.localId % (AppController.POOL_SIZE - 1)))].execute(()
                -> controller.getNetworkService().setChatBlockedState(chat.id, new ResultCallback<>() {

            @Override
            public void onSuccess(Boolean result) {
                isChatBlocked.postValue(result);
            }

            @Override
            public void onError(String errorMsg) {
                Log.d(AppController.LOG_TAG, errorMsg);
            }
        }));

    }

    public void renameChat(Chat chat, String newName) {
        controller.getDbHelper().renameChat(chat.id, newName, new ResultCallback<>() {
            @Override
            public void onSuccess(String name) {
                chatName.postValue(name);
            }

            @Override
            public void onError(String errorMsg) {
                Log.d(AppController.LOG_TAG, errorMsg);
            }
        });
    }
}
