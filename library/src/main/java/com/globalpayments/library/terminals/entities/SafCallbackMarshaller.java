package com.globalpayments.library.terminals.entities;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.tsys.payments.library.db.SafListener;
import com.tsys.payments.library.db.entity.SafTransaction;
import com.tsys.payments.library.domain.TransactionResponse;
import com.tsys.payments.library.exceptions.Error;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.Executor;

import timber.log.Timber;

public class SafCallbackMarshaller extends CallbackMarshaller<SafListener> {
    private static final String TAG = com.tsys.payments.transaction.SafCallbackMarshaller.class.getName();

    @NonNull
    private final Executor mMainThreadExecutor;
    @Nullable
    private SafListener safListener;

    SafCallbackMarshaller(@NonNull SafListener listener, @NonNull Executor mainThreadExecutor) {
        super(listener, mainThreadExecutor);
        mMainThreadExecutor = mainThreadExecutor;
        safListener = listener;
    }

    void callbackOnError(final Error error) {
        Timber.d("callbackOnError() called with: error=[%s]", error);
        mMainThreadExecutor.execute(new Runnable() {
            @Override
            public void run() {
                if (safListener != null) {
                    safListener.onError(error);
                } else {
                    Timber.d("SAF Listener is null -- unable to perform callback");
                }
            }
        });
    }

    void callbackOnProcessingResponse(final List<TransactionResponse> responses) {
        Timber.d("callbackOnProcessingResponse called with response=[%s]", responses);
        mMainThreadExecutor.execute(new Runnable() {
            @Override
            public void run() {
                if (safListener != null) {
                    safListener.onProcessingComplete(responses);
                } else {
                    Timber.d("SAF Listener is null -- unable to perform callback");
                }
            }
        });
    }

    void callbackOnSafTransactionsRetrieved(final List<SafTransaction> transactions) {
        Timber.d("callbackOnSafTransactionsRetrieved called with transactions=[%s]", transactions);
        mMainThreadExecutor.execute(new Runnable() {
            @Override
            public void run() {
                if (safListener != null) {
                    safListener.onAllSafTransactionsRetrieved(transactions);
                } else {
                    Timber.d("SAF Listener is null -- unable to perform callback");
                }
            }
        });
    }

    void callbackOnTransactionStored(String id, int totalCount, BigDecimal totalAmount) {
        Timber.d("callbackOnTransactionStored called with id=[%s] totalCount=[%d] totalAmount=[%s]", id, totalCount, totalAmount.toString());
        mMainThreadExecutor.execute(new Runnable() {
            @Override
            public void run() {
                if (safListener != null) {
                    safListener.onTransactionStored(id, totalCount, totalAmount);
                } else {
                    Timber.d("SAF Listener is null -- unable to perform callback");
                }
            }
        });
    }

    void callbackOnStoredTransactionComplete(String id, TransactionResponse transactionResponse) {
        if (safListener != null) {
            safListener.onStoredTransactionComplete(id, transactionResponse);
        }
    }
}
