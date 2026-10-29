package com.globalpayments.library.terminals.entities;

import com.tsys.payments.library.domain.CardholderInteractionRequest;
import com.tsys.payments.library.domain.TransactionResponse;
import com.tsys.payments.library.enums.TransactionStatus;
import com.tsys.payments.library.transaction.TransactionListener;
import com.tsys.payments.library.exceptions.Error;

import timber.log.Timber;

import java.util.Arrays;
import java.util.concurrent.Executor;

public class TransCallbackMarshaller extends CallbackMarshaller<TransactionListener> {

    public TransCallbackMarshaller(TransactionListener listener, Executor mainThreadExecutor) {
        super(listener, mainThreadExecutor);
    }

    public void callbackOnStatusUpdate(final TransactionStatus status) {
        Timber.d("callbackOnStatusUpdate() called with: status=[%s]", status);
        if (executor != null) {
            executor.execute(new Runnable() {
                public void run() {
                    if (TransCallbackMarshaller.this.listener != null) {
                        TransCallbackMarshaller.this.listener.onStatusUpdate(status);
                    }
                }
            });
        }
    }

    public void callbackOnCardholderInteractionRequested(final CardholderInteractionRequest request) {
        Timber.d("callbackOnCardholderInteractionRequested() called with: request=[%s]", request);
        Timber.d("callbackOnCardholderInteractionRequested() called with: getCardholderInteractionType=[%s]", request.getCardholderInteractionType()!=null ? request.getCardholderInteractionType().name() : "null");
        Timber.d("callbackOnCardholderInteractionRequested() called with: getCardDataSourceType=[%s]", request.getCardDataSourceType()!=null ? request.getCardDataSourceType().name() : "null");
        Timber.d("callbackOnCardholderInteractionRequested() called with: getSupportedApplications=[%s]", request.getSupportedApplications()!=null ? Arrays.toString(request.getSupportedApplications()) : "null");
        executor.execute(new Runnable() {
            @Override
            public void run() {
                if(listener != null) {
                    listener.onCardholderInteractionRequested(request);
                }
            }
        });
    }

    public void callbackOnTransactionResponse(final TransactionResponse response) {
        Timber.d("callbackOnTransactionResponse() called with: response=[%s]", response);
        executor.execute(new Runnable() {
            @Override
            public void run() {
                if(listener != null) {
                    listener.onTransactionComplete(response);
                    listener = null;
                }
            }
        });
    }

    public void callbackOnError(Error error) {
        Timber.w("callbackOnError() called with: error=[%s]", error);
        executor.execute(new Runnable() {
            @Override
            public void run() {
                if(listener != null) {
                    listener.onError(error);
                    listener = null;
                }
            }
        });
    }
}
