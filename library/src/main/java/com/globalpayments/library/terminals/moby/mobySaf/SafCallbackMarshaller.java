package com.globalpayments.library.terminals.moby.mobySaf;

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

public class SafCallbackMarshaller {
    private static final String TAG = SafCallbackMarshaller.class.getName();
    @NonNull
    private final Executor mMainThreadExecutor;
    @Nullable
    private SafListener mSafListener;

    SafCallbackMarshaller(@NonNull SafListener listener, @NonNull Executor mainThreadExecutor) {
        this.mMainThreadExecutor = mainThreadExecutor;
        this.mSafListener = listener;
    }

    void callbackOnError(final Error error) {
        Timber.d("callbackOnError() called with: error=[%s]", new Object[]{error});
        this.mMainThreadExecutor.execute(new Runnable() {
            public void run() {
                SafCallbackMarshaller.this.mSafListener.onError(new java.lang.Error(error.getMessage()));
            }
        });
    }

    void callbackOnProcessingResponse(final List<TransactionResponse> responses) {
        Timber.d("callbackOnProcessingResponse called with response=[%s]", new Object[]{responses});
        this.mMainThreadExecutor.execute(new Runnable() {
            public void run() {
                SafCallbackMarshaller.this.mSafListener.onProcessingComplete(responses);
            }
        });
    }

    void callbackOnSafTransactionsRetrieved(final List<SafTransaction> transactions) {
        Timber.d("callbackOnSafTransactionsRetrieved called with transactions=[%s]", new Object[]{transactions});
        this.mMainThreadExecutor.execute(new Runnable() {
            public void run() {
                SafCallbackMarshaller.this.mSafListener.onAllSafTransactionsRetrieved(transactions);
            }
        });
    }

    void callbackOnTransactionStored(final String id, final int totalCount, final BigDecimal totalAmount) {
        Timber.tag("SafCallbackMarshaller").d("callbackOnTransactionStored called with id=[%s] totalCount=[%d] totalAmount=[%s]", id, totalCount, totalAmount.toString());
        this.mMainThreadExecutor.execute(new Runnable() {
            public void run() {
                SafCallbackMarshaller.this.mSafListener.onTransactionStored(id, totalCount, totalAmount);
            }
        });
    }

    void callbackOnStoredTransactionComplete(String id, TransactionResponse transactionResponse) {
        if (this.mSafListener != null) {
            this.mSafListener.onStoredTransactionComplete(id, transactionResponse);
        }

    }
}
