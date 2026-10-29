package com.globalpayments.library.terminals.receivers;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import com.castles.payment.communication.ApiComPortManager;
import com.tsys.payments.library.connection.ConnectionListener;

import lombok.Setter;
import timber.log.Timber;

public class UsbReceiver extends BroadcastReceiver {
    public static final String USB_DEVICE_ATTACHED = "android.hardware.usb.action.USB_DEVICE_ATTACHED";
    public static final String USB_DEVICE_DETACHED = "android.hardware.usb.action.USB_DEVICE_DETACHED";
    public static final String ACTION_USB_PERMISSION = "com.globalpayments.library.terminals.USB_PERMISSION";

    @Setter private ConnectionListener connectionListener;

    public interface EventCallback {
        void execute(Context context, ConnectionListener listener);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) {
            return;
        }
        final String action = intent.getAction();
        if (action == null) {
            return;
        }
        switch (action) {
            case USB_DEVICE_ATTACHED: {
                Timber.d("USB Device Attached");
                ApiComPortManager.getInstance().init(context);
                //connectionListener.onConnected(new com.globalpayments.library.terminals.entities.TerminalInfo());
                break;
            }
            case USB_DEVICE_DETACHED: {
                Timber.d("USB Device Detached");
                ApiComPortManager.getInstance().close();
                if (connectionListener != null) {
                    connectionListener.onDisconnected();
                }
                break;
            }
            case ACTION_USB_PERMISSION: {
                Timber.d("USB Permission Action");
                UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                if (device == null) {
                    Timber.d("USB permission broadcast missing device extra");
                    return;
                }

                boolean granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
                
                if (granted) {
                    Timber.d("USB permission granted for device: %s", device != null ? device.getDeviceName() : "unknown");
                    if (connectionListener != null) {
                        connectionListener.onConnected(new com.globalpayments.library.terminals.entities.TerminalInfo());
                    }
                } else {
                    Timber.d("USB permission denied");
                    if (connectionListener != null) {
                        connectionListener.onDisconnected();
                    }
                }
                break;
            }
            default:
        }
    }
}
