package com.globalpayments.library.terminals.entities;

import com.globalpayments.library.terminals.TransactionListener;
import lombok.NonNull;

import java.util.concurrent.Executor;

public abstract class CallbackMarshaller<T> {
    protected static final String TAG = TransCallbackMarshaller.class.getName();
    protected Executor executor;
    protected T listener;

    public CallbackMarshaller(@NonNull T listener, @NonNull Executor executor) {
        this.listener = listener;
        this.executor = executor;
    }

    public void updateListener(@NonNull T listener) {
        this.listener = listener;
    }
}
