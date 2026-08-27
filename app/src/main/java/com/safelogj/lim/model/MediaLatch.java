package com.safelogj.lim.model;

import java.util.concurrent.CountDownLatch;

public class MediaLatch extends CountDownLatch {

    private final boolean isWorker;
    private final boolean isPseudoWorker;

    public MediaLatch(boolean isWorker, boolean isPseudoWorker) {
        super(1);
        this.isWorker = isWorker;
        this.isPseudoWorker = isPseudoWorker;
    }

    public boolean isWorker() {
        return isWorker;
    }
    public boolean isPseudoWorker() {
        return isPseudoWorker;
    }
}
