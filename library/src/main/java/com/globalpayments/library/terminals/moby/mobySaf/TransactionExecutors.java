package com.globalpayments.library.terminals.moby.mobySaf;

import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

class TransactionExecutors {
    private static final TransactionExecutors sInstance = new TransactionExecutors();
    private final Executor mWorkerThread = Executors.newSingleThreadExecutor();
    private final Executor mMainThread = new MainThreadExecutor();

    public static TransactionExecutors getInstance() {
        return sInstance;
    }

    private TransactionExecutors() {
    }

    Executor getMainThread() {
        return this.mMainThread;
    }

    Executor getWorkerThread() {
        return this.mWorkerThread;
    }

    private static class MainThreadExecutor implements Executor {
        private final Handler mHandler = new Handler(Looper.getMainLooper());

        MainThreadExecutor() {
        }

        public void execute(@NonNull Runnable command) {
            this.mHandler.post(command);
        }
    }
}
