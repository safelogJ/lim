package com.safelogj.lim.model;

import java.util.concurrent.CountDownLatch;

public class MediaLatch extends CountDownLatch {

    private final boolean isWorker;

    public MediaLatch(boolean isWorker) {
        super(1);
        this.isWorker = isWorker;
    }
    public boolean isWorker() {
        return isWorker;
    }
}
