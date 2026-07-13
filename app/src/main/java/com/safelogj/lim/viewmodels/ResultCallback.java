package com.safelogj.lim.viewmodels;

public interface ResultCallback<T> {

    void onSuccess(T result);

    void onError(String errorMsg);
}
