package com.safelogj.limserver;

import org.jetbrains.annotations.NotNull;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class ServerThreadPool {

    private ServerThreadPool() {
    }

    private static class TaskQueue extends LinkedBlockingQueue<Runnable> {
        private transient ThreadPoolExecutor executor;

        public TaskQueue(int capacity) {
            super(capacity);
        }

        public void setExecutor(ThreadPoolExecutor executor) {
            this.executor = executor;
        }

        @Override
        public boolean offer(@NotNull Runnable runnable) {
            if (executor == null) {
                return super.offer(runnable);
            }
            int currentPoolSize = executor.getPoolSize();
            int maximumPoolSize = executor.getMaximumPoolSize();
            if (currentPoolSize < maximumPoolSize) {
                return false;
            }
            return super.offer(runnable);
        }
    }

    public static ThreadPoolExecutor createPool(int poolSize, int queueSize) {
        TaskQueue queue = new TaskQueue(queueSize);
        ThreadPoolExecutor executor = new ThreadPoolExecutor(2, poolSize, 30, TimeUnit.MINUTES, queue);
        queue.setExecutor(executor);
        return executor;
    }
}
