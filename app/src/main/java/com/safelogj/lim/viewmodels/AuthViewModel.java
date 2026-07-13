package com.safelogj.lim.viewmodels;

import android.app.Application;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.safelogj.lim.AppController;

public class AuthViewModel extends AndroidViewModel {

    private final MutableLiveData<String> resultMessage = new MutableLiveData<>();
    private final AppController controller;

    public AuthViewModel(@NonNull Application application) {
        super(application);
        controller = (AppController) application;
    }

    public LiveData<String> getResultMessage() {
        return resultMessage;
    }

    public void setResultMessage(String message) {
        resultMessage.setValue(message);
    }

    public void register(String user, String pass, String displayName) {
        controller.getDbExecutor().execute(() ->
                controller.getNetworkService().register(user, pass, displayName, new ResultCallback<>() {
                    @Override
                    public void onSuccess(String result) {
                        resultMessage.postValue(result);
                    }

                    @Override
                    public void onError(String errorMsg) {
                        resultMessage.postValue(errorMsg);
                    }
                })
        );
    }

    public void deleteAccount(String username, String password) {
        controller.getDbExecutor().execute(() ->
                controller.getNetworkService().deleteAccount(username, password, new ResultCallback<>() {
                    @Override
                    public void onSuccess(String result) {
                        resultMessage.postValue(result);
                    }

                    @Override
                    public void onError(String errorMsg) {
                        resultMessage.postValue(errorMsg);
                    }
                })
        );
    }

    public void editUser(String username, String password, String dName, String newPass) {
        controller.getDbExecutor().execute(() ->
                controller.getNetworkService().editUser(username, password, dName, newPass, new ResultCallback<>() {
                    @Override
                    public void onSuccess(String result) {
                        resultMessage.postValue(result);
                    }

                    @Override
                    public void onError(String errorMsg) {
                        resultMessage.postValue(errorMsg);
                    }
                })
        );
    }
}
