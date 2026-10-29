package com.globalpayments.library.terminals.entities;

import android.os.Handler;
import android.os.Looper;
import androidx.annotation.NonNull;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

public class TransExecutors {
    private static TransExecutors sInstance = new TransExecutors();
    private final Executor workerThread = Executors.newSingleThreadExecutor();
    private final Executor mainThread = new MainThreadExecutor();

    public static TransExecutors getInstance() { return sInstance; }

    private TransExecutors() { }

    public Executor getMainThread() { return mainThread; }
    public Executor getWorkerThread() { return workerThread; }

    private static class MainThreadExecutor implements Executor {
        private final Handler mHandler = new Handler(Looper.getMainLooper());

        MainThreadExecutor() { }

        public void execute(@NonNull Runnable command) {
            this.mHandler.post(command);
        }
    }
}
