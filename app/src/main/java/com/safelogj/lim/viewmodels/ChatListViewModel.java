package com.safelogj.lim.viewmodels;

import android.app.Application;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.MutableLiveData;

import com.safelogj.lim.AppController;
import com.safelogj.lim.model.Chat;

public class ChatListViewModel extends AndroidViewModel {

    private final MutableLiveData<Boolean> isChatHidden = new MutableLiveData<>();
    private final MutableLiveData<Boolean> isChatBlocked = new MutableLiveData<>();
    private final MutableLiveData<String> chatName = new MutableLiveData<>();
    private final AppController controller;

    public ChatListViewModel(@NonNull Application application) {
        super(application);
        controller = (AppController) application;
    }

    public void hideChat(Chat chat) {
        controller.getDbHelper().hideChatLocally(chat, new ResultCallback<>() {

            @Override
            public void onSuccess(Boolean result) {
                isChatHidden.postValue(result);
            }

            @Override
            public void onError(String errorMsg) {
                Log.d(AppController.LOG_TAG, errorMsg);
            }
        });
        controller.getNetStreams()[Math.abs((int) (chat.localId % (AppController.POOL_SIZE - 2)))].execute(()
                -> controller.getNetworkService().hideChat(chat.id));
    }

    public void setChatBlocked(Chat chat) {
        controller.getNetStreams()[Math.abs((int) (chat.localId % (AppController.POOL_SIZE - 2)))].execute(()
                -> controller.getNetworkService().blockChat(chat.id, new ResultCallback<>() {

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
