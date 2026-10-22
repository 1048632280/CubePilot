package com.cubepilot.core;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/** CubePilot 的有界后台任务池，避免网络请求和文件扫描占用 Minecraft 主线程。 */
public final class CubePilotTasks {
    private static final AtomicInteger THREAD_ID = new AtomicInteger();
    private static final ThreadFactory THREAD_FACTORY = runnable -> {
        Thread thread = new Thread(runnable, "CubePilot-Worker-" + THREAD_ID.incrementAndGet());
        thread.setDaemon(true);
        return thread;
    };
    private static final ExecutorService EXECUTOR = new ThreadPoolExecutor(
            2, 6, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(64), THREAD_FACTORY,
            new ThreadPoolExecutor.AbortPolicy());

    private CubePilotTasks() {
    }

    public static ExecutorService executor() {
        return EXECUTOR;
    }

    public static <T> CompletableFuture<T> supplyAsync(Supplier<T> supplier) {
        try {
            return CompletableFuture.supplyAsync(supplier, EXECUTOR);
        } catch (RejectedExecutionException exception) {
            return CompletableFuture.failedFuture(new IllegalStateException(
                    "CubePilot 后台任务过多，请稍后重试", exception));
        }
    }
}
