package com.globalpayments.library.terminals.entities;

import com.castles.payment.communication.ApiComPortManager;
import com.tsys.payments.library.connection.ConnectionListener;
import com.tsys.payments.library.domain.TerminalInfo;
import com.tsys.payments.library.exceptions.Error;
import timber.log.Timber;

import java.util.concurrent.Executor;

public class UsbConnCallbackMarshaller extends CallbackMarshaller<ConnectionListener> {
    public UsbConnCallbackMarshaller(ConnectionListener listener, Executor mainThreadExecutor) {
        super(listener, mainThreadExecutor);
    }

    public void callbackOnConnect(TerminalInfo terminalInfo) {
        Timber.d("callbackOnConnected() called");
        executor.execute(new Runnable() {
            @Override
            public void run() {
                if(listener != null) {
                    listener.onConnected(terminalInfo);
                }
            }
        });
    }

    public void callbackOnDisconnect() {
        Timber.d("callbackOnDisconnect() called");
        executor.execute(new Runnable() {
            public void run() {
                ApiComPortManager.getInstance().close();
                if(listener != null) {
                    listener.onDisconnected();
                }
            }
        });
    }

    public void callbackOnError(final Error error) {
        Timber.w("callbackOnError() called with: error=[%s]", error);
        if (executor != null) {
            executor.execute(new Runnable() {
                public void run() {
                    if (UsbConnCallbackMarshaller.this.listener != null) {
                        UsbConnCallbackMarshaller.this.listener.onError(error);
                    }
                }
            });
        }
    }
}
