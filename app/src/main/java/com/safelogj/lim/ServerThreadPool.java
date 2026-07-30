package com.safelogj.lim;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class ServerThreadPool {

    /**
     * Специальная очередь, которая заставляет ThreadPoolExecutor
     * создавать новые нити ДО того, как задача попадет в очередь.
     */
    private static class TaskQueue extends LinkedBlockingQueue<Runnable> {
        private ThreadPoolExecutor executor;

        public TaskQueue(int capacity) {
            super(capacity);
        }

        public void setExecutor(ThreadPoolExecutor executor) {
            this.executor = executor;
        }

        @Override
        public boolean offer(Runnable runnable) {
            int currentPoolSize = executor.getPoolSize();
            int maximumPoolSize = executor.getMaximumPoolSize();

            // Если текущее количество нитей меньше максимума (например, меньше 8),
            // возвращаем false! Это заставляет ThreadPoolExecutor СОЗДАТЬ новую нить,
            // а не класть задачу в очередь.
            if (currentPoolSize < maximumPoolSize) {
                return false;
            }

            // Если все 8 нитей уже созданы и заняты — кладем задачу в очередь.
            return super.offer(runnable);
        }
    }

    /**
     * Фабричный метод для создания идеального пула
     */
    public static ThreadPoolExecutor createSmartPool(int minThreads, int maxThreads, long keepAliveMinutes, int queueCapacity) {
        TaskQueue queue = new TaskQueue(queueCapacity);

        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                minThreads,         // Минимальное кол-во нитей в покое (например, 2)
                maxThreads,         // Максимальное кол-во нитей (например, 8)
                keepAliveMinutes,   // Время жизни лишних нитей (например, 60 секунд)
                TimeUnit.MINUTES,   // Единица измерения времени
                queue,              // Наша умная очередь
                new ThreadPoolExecutor.CallerRunsPolicy() // Если и 8 нитей заняты, и очередь на 500 заполнена
        );

        // Связываем очередь с эксекьютором
        queue.setExecutor(executor);

        return executor;
    }
}
