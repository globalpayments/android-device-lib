package com.example.app;

import android.annotation.SuppressLint;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothDevice;
import android.content.DialogInterface;
import android.content.DialogInterface.OnClickListener;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore.Images;
import android.util.Log;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;
import com.example.app.MainActivity.TransactionState;
import com.globalpayments.library.terminals.DeviceListener;
import com.globalpayments.library.terminals.IDevice;
import com.globalpayments.library.terminals.SafListener;
import com.globalpayments.library.terminals.entities.TerminalInfo;
import com.globalpayments.library.terminals.entities.TerminalResponse;
import com.globalpayments.library.terminals.enums.ConnectionMode;
import com.globalpayments.library.terminals.enums.ErrorType;
import com.globalpayments.library.utilities.ReceiptHelper;
import com.tsys.payments.library.db.entity.SafTransaction;
import com.tsys.payments.library.domain.TransactionResponse;
import com.tsys.payments.library.enums.TransactionResultType;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import static com.example.app.Dialogs.hideProgress;

import timber.log.Timber;

public abstract class BaseActivity extends AppCompatActivity {

    private static final String TAG = "BaseActivity";

    protected AlertDialog mAlertDialog;
    protected static String bluetoothDeviceName = null;
    protected static String bluetoothDeviceAddress = null;
    private static String connectionStatus;
    protected BluetoothDevice previousSelected;
    protected ConnectionMode connectionMode;
    protected static boolean connectButtonEnabled;

    protected Button executeButton;
    private TerminalResponse pendingReceiptTransaction;
    private boolean reopenReceiptTypeDialogOnResume;
    private boolean isInReceiptEmailFlow;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (bluetoothDeviceName == null) {
            SharedPreferences sharedPreferences = getSharedPreferences(MainActivity.SAVED_PREFS, MODE_PRIVATE);
            bluetoothDeviceName = sharedPreferences.getString(MainActivity.BLUETOOTH_NAME, null);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();

        if (isInReceiptEmailFlow && reopenReceiptTypeDialogOnResume && pendingReceiptTransaction != null) {
            reopenReceiptTypeDialogOnResume = false;
            isInReceiptEmailFlow = false;
            showReceiptTypeDialog(pendingReceiptTransaction);
        }

        if(MainActivity.mobyDevice != null) {
            MainActivity.mobyDevice.setMobyPairingContext(this);
            MainActivity.mobyDevice.setDeviceListener(mobyDeviceListener);
            MainActivity.mobyDevice.setSafListener(safListener);
        }

        if(MainActivity.c2XDevice != null) {
            MainActivity.c2XDevice.setDeviceListener(c2xDeviceListener);
            MainActivity.c2XDevice.setSafListener(safListener);
        }

        if(MainActivity.puck1000Device != null) {
            MainActivity.puck1000Device.setDeviceListener(usbDeviceListener);
            MainActivity.puck1000Device.setSafListener(safListener);
        }

        if (!(this instanceof BluetoothActivity)) {
            updateConnectionStatus(connectionStatus, connectButtonEnabled);
        }
    }

    protected void updateTransactionStatus() {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                TextView transactionStatus = findViewById(R.id.transaction_status);
                if (transactionStatus != null) {
                    switch (MainActivity.transactionState) {
                        case None:
                            transactionStatus.setText(MainActivity.transactionState.toString());
                            break;
                        case Processing:
                            transactionStatus.setText(
                                    MainActivity.transactionState + " - " + MainActivity.cardReaderStatus + " - " +
                                            MainActivity.transactionId);
                            break;
                        case Complete:
                            transactionStatus.setText(
                                    MainActivity.transactionState + " - " + MainActivity.transactionResult + " - " +
                                            MainActivity.transactionId);
                            break;
                    }
                }
                if (executeButton != null) {
                    executeButton.setEnabled(MainActivity.transactionState != MainActivity.TransactionState.Processing);
                }
            }
        });
    }

    protected void updateConnectionStatus(String status, boolean connectButtonEnabled) {
        this.connectButtonEnabled = connectButtonEnabled;
        //update the actionbar
        if (status != null && !status.isEmpty()) {
            connectionStatus = status;
        } else {
            connectionStatus = getString(R.string.bluetooth_status_not_connected);
        }
        getSupportActionBar().setTitle(connectionStatus);
    }

    protected void showAlertDialog(String title, String message) {
        showAlertDialog(title, message, null);
    }

    protected void showAlertDialog(String title, String message, @Nullable Runnable onOk) {
        if (mAlertDialog != null && mAlertDialog.isShowing()) {
            Timber.tag(TAG).e("showAlertDialog - dialog is already showing");
            return;
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(title);
        builder.setMessage(message);
        builder.setPositiveButton(getString(R.string.ok), (dialogInterface, i) -> {
            mAlertDialog.dismiss();
            if (onOk != null) {
                onOk.run();
            }
        });
        mAlertDialog = builder.create();
        mAlertDialog.setCancelable(false);
        mAlertDialog.show();
    }

    protected void dismissAlertDialog() {
        runOnUiThread(() -> {
            if (mAlertDialog == null) {
                return;
            }

            try {
                if (mAlertDialog.isShowing()) {
                    mAlertDialog.dismiss();
                }
            } catch (IllegalArgumentException e) {
                Timber.tag(TAG).w(e, "Ignoring dialog dismiss after window detachment.");
            }
            mAlertDialog = null;
        });
    }

    protected void showAlertDialog(String title, String message, boolean showReceiptOption, TerminalResponse transaction) {
        if (mAlertDialog != null && mAlertDialog.isShowing()) {
            Log.e("BaseActivity", "showAlertDialog - dialog is already showing");
            return;
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(title);
        builder.setMessage(message);
        builder.setPositiveButton(getString(R.string.ok), new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialogInterface, int i) {
                mAlertDialog.dismiss();
            }
        });
        if (showReceiptOption) {
            builder.setNeutralButton(getString(R.string.email_receipt), new OnClickListener() {
                @Override
                public void onClick(DialogInterface dialogInterface, int i) {
                    mAlertDialog.dismiss();
                    showReceiptTypeDialog(transaction);
                }
            });
        }
        mAlertDialog = builder.create();
        mAlertDialog.setCancelable(false);
        mAlertDialog.show();
    }

    private void showReceiptTypeDialog(final TerminalResponse transaction) {
        final CharSequence[] receiptOptions = new CharSequence[] {
                getString(R.string.merchant_receipt),
                getString(R.string.customer_copy)
        };

        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.select_receipt_type))
                .setItems(receiptOptions, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialogInterface, int which) {
                        sendReceiptEmail(transaction, which == 0);
                    }
                })
                .setNegativeButton(getString(R.string.cancel), new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialogInterface, int which) {
                        reopenReceiptTypeDialogOnResume = false;
                        pendingReceiptTransaction = null;
                    }
                })
                .show();
    }

    private void sendReceiptEmail(TerminalResponse transaction, boolean isMerchantReceipt) {
        Bitmap receipt = ReceiptHelper.createReceiptImage(transaction, isMerchantReceipt);
        String path = Images.Media.insertImage(
                getContentResolver(),
                receipt,
                "receipt_" + System.currentTimeMillis(),
                null);
        if (path == null) {
            showAlertDialog(getString(R.string.error), getString(R.string.error_receipt_image_attachment));
            return;
        }

        final Intent emailIntent = new Intent(Intent.ACTION_SEND);
        emailIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        emailIntent.putExtra(Intent.EXTRA_STREAM, Uri.parse(path));
        emailIntent.setType("image/png");
        pendingReceiptTransaction = transaction;
        // Only reopen dialog on first return; after that, stay in email flow
        if (!isInReceiptEmailFlow) {
            reopenReceiptTypeDialogOnResume = true;
            isInReceiptEmailFlow = true;
        }
        startActivity(Intent.createChooser(emailIntent, "Send email using"));
    }

    protected void disconnectEvent() {
        //let children activities override if needed
    }

    protected boolean shouldCloseAfterConnect() {
        return (this instanceof BluetoothActivity) && connectionMode == ConnectionMode.USB;
    }

    protected void hideAlertDialog() {
        if (mAlertDialog != null && mAlertDialog.isShowing()) {
            mAlertDialog.hide();
        }
    }

    private interface ISelection {
        void onSelection(BluetoothDevice device);
    }

    final ISelection selectionListener = new ISelection() {
        @Override
        @SuppressLint("MissingPermission")
        public void onSelection(BluetoothDevice device) {
            try {
                //Connect the selected device.
                if(MainActivity.c2XDevice != null) {
                    MainActivity.c2XDevice.connect(device);
                } else {
                    MainActivity.mobyDevice.connect(device);
                }
                updateConnectionStatus("Connecting - " + device.getName(), false);
            } catch (Exception e) {
                e.printStackTrace();
                updateConnectionStatus("An exception occurred.", true);
            }
        }
    };

    protected DeviceListener mobyDeviceListener = new DeviceListener() {
        @Override
        @SuppressLint("MissingPermission")
        public void onBluetoothDeviceFound(BluetoothDevice bluetoothDevice) {
            Log.d(TAG, "onBluetoothDeviceFound - " + bluetoothDevice.getName());
        }

        @Override
        @SuppressLint("MissingPermission")
        public void onBluetoothDeviceList(HashSet<BluetoothDevice> deviceList) {
            hideProgress();
            if (deviceList == null || deviceList.isEmpty()) {
                return;
            }

            //Use this flag is the list of bluetooth keep popping up with the list of bluetooth.
            if (previousSelected != null) {
                return;
            }

            // receive list of available bluetooth devices
            // present to user for selection/confirmation
            final List<BluetoothDevice> list = new ArrayList<BluetoothDevice>(deviceList);
            final List<String> btList = new ArrayList<String>();

            for (BluetoothDevice dev : list) {
                if (dev.getName() != null) {
                    btList.add(dev.getName().trim());
                } else {
                    btList.add(dev.getAddress());
                }
            }

            //This is a dialog to show the list of devices
            //Here we use a dialog with a callback and a listener but you can use your own user selection
            AlertDialog d = Dialogs.showListDialog("Available devices",
                    BaseActivity.this, btList.toArray(new String[0]),
                    new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(final DialogInterface dialogInterface, int i) {
                            BTSelection.getInstance()
                                    .Selection(list.get(i), new BTSelection.SelectionCallback() {
                                        @Override
                                        public void onSelection(BluetoothDevice btDevice) {
                                            //keep track of current device info
                                            bluetoothDeviceName = btDevice.getName();
                                            bluetoothDeviceAddress = btDevice.getAddress();
                                            dialogInterface.dismiss();
                                            if (selectionListener != null) {
                                                previousSelected = btDevice;
                                                selectionListener.onSelection(btDevice);
                                            }
                                        }
                                    });
                        }
                    });
        }

        @Override
        public void onConnected(TerminalInfo terminalInfo) {
            hideProgress();
            Log.d(TAG, "onConnected - " + terminalInfo.toString());
            //keep the device version data
            MainActivity.firmwareVersion = terminalInfo.getFirmwareVersion();
            MainActivity.kernelVersion = terminalInfo.getKernelVersion();

            MainActivity.mobyDevice.getDeviceInfo();

            //update connection status
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    Toast.makeText(getApplicationContext(), "Connected - " +
                            terminalInfo.getSerialNumber(), Toast.LENGTH_LONG).show();
                    updateConnectionStatus("Connected - " + terminalInfo.getSerialNumber(),
                            false);
                    if (shouldCloseAfterConnect()) {
                        finish();
                    }
                }
            });

            if (connectionMode == ConnectionMode.BLUETOOTH) {
                //save the bluetooth data
                SharedPreferences.Editor editor =
                        getSharedPreferences(MainActivity.SAVED_PREFS, MODE_PRIVATE).edit();
                editor.putString(MainActivity.BLUETOOTH_NAME, bluetoothDeviceName);
                if (bluetoothDeviceAddress != null) {
                    editor.putString(MainActivity.BLUETOOTH_ADDRESS, bluetoothDeviceAddress);
                }
                editor.commit();
            }
        }

        @Override
        public void onDisconnected() {
            Log.d(TAG, "onDisconnected");
            MainActivity.mobyDevice = null;
            MainActivity.c2XDevice = null;
            //update connection status
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    Toast.makeText(getApplicationContext(), "Disconnected", Toast.LENGTH_LONG).show();
                    updateConnectionStatus("Disconnected", false);
                    disconnectEvent();
                }
            });
        }

        @Override
        public void onError(Error error, ErrorType errorType) {
            Log.e(TAG, "onError - " + error.toString() + ", " + errorType);

            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    if (Dialogs.pd != null && Dialogs.pd.isShowing()) {
                        Dialogs.hideProgress();
                    }
                    Toast.makeText(getApplicationContext(), error.getMessage(), Toast.LENGTH_LONG).show();
                    //update connection status
                    updateConnectionStatus("Error - " + error.getMessage(), true);
                }
            });

        }

        @Override
        public void onTerminalInfoReceived(TerminalInfo terminalInfo) {
            Log.d(TAG, "onTerminalInfoReceived - " + terminalInfo.toString());
            if (MainActivity.isShowAbout()) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        Toast.makeText(getApplicationContext(), "Provision Version: " + terminalInfo.getAppVersion(),
                                Toast.LENGTH_LONG).show();
                    }
                });
            }
        }
    };

    protected DeviceListener c2xDeviceListener = new DeviceListener() {
        @Override
        @SuppressLint("MissingPermission")
        public void onBluetoothDeviceFound(BluetoothDevice bluetoothDevice) {
            Log.d(TAG, "onBluetoothDeviceFound - " + bluetoothDevice.getName());
        }

        @Override
        @SuppressLint("MissingPermission")
        public void onBluetoothDeviceList(final HashSet<BluetoothDevice> deviceList) {
            hideProgress();
            // receive list of available bluetooth devices
            // present to user for selection/confirmation
            if (deviceList == null || deviceList.isEmpty()) {
                return;
            }

            Log.e(TAG, "onBluetoothDeviceList - size " + deviceList.size());

            //Use this flag is the list of bluetooth keep popping up with the list of bluetooth.
            if (previousSelected != null) {
                return;
            }

            // receive list of available bluetooth devices
            // present to user for selection/confirmation
            final List<BluetoothDevice> list = new ArrayList<BluetoothDevice>(deviceList);
            final List<String> btList = new ArrayList<String>();

            for (BluetoothDevice dev : list) {
                if (dev.getName() != null) {
                    btList.add(dev.getName().trim());
                } else {
                    btList.add(dev.getAddress());
                }
            }

            //This is a dialog to show the list of devices
            //Here we use a dialog with a callback and a listener but you can use your own user selection
            AlertDialog d = Dialogs.showListDialog("Available devices",
                    BaseActivity.this, btList.toArray(new String[0]),
                    new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(final DialogInterface dialogInterface, int i) {
                            BTSelection.getInstance()
                                    .Selection(list.get(i), new BTSelection.SelectionCallback() {
                                        @Override
                                        public void onSelection(BluetoothDevice btDevice) {
                                            //keep track of current device info
                                            bluetoothDeviceName = btDevice.getName();
                                            bluetoothDeviceAddress = btDevice.getAddress();
                                            dialogInterface.dismiss();
                                            if (selectionListener != null) {
                                                previousSelected = btDevice;
                                                selectionListener.onSelection(btDevice);
                                            }
                                        }
                                    });
                        }
                    });
        }

        @Override
        public void onConnected(final TerminalInfo terminalInfo) {
            Log.d(TAG, "onConnected - " + terminalInfo.toString());
            //keep the device version data
            MainActivity.firmwareVersion = terminalInfo.getFirmwareVersion();
            MainActivity.kernelVersion = terminalInfo.getKernelVersion();

            //MainActivity.c2XDevice.getDeviceInfo();

            //update connection status
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    Toast.makeText(getApplicationContext(), "Connected - " +
                            terminalInfo.getSerialNumber(), Toast.LENGTH_LONG).show();
                    updateConnectionStatus("Connected - " + terminalInfo.getSerialNumber(),
                            false);
                    if (shouldCloseAfterConnect()) {
                        finish();
                    }
                }
            });

            //save the bluetooth data
            SharedPreferences.Editor editor =
                    getSharedPreferences(MainActivity.SAVED_PREFS, MODE_PRIVATE).edit();
            editor.putString(MainActivity.BLUETOOTH_NAME, bluetoothDeviceName);
            if (bluetoothDeviceAddress != null) {
                editor.putString(MainActivity.BLUETOOTH_ADDRESS, bluetoothDeviceAddress);
            }
            editor.commit();
        }

        @Override
        public void onDisconnected() {
            Log.d(TAG, "onDisconnected");
            MainActivity.c2XDevice = null;
            //update connection status
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    Toast.makeText(getApplicationContext(), "Disconnected", Toast.LENGTH_LONG).show();
                    updateConnectionStatus("Disconnected", false);
                    disconnectEvent();
                }
            });
        }

        @Override
        public void onError(Error error, ErrorType errorType) {
            Log.e(TAG, "onError - " + error.toString() + ", " + errorType);
            if (Dialogs.pd != null && Dialogs.pd.isShowing()) {
                Dialogs.hideProgress();
            }
            Toast.makeText(getApplicationContext(), error.getMessage(), Toast.LENGTH_LONG).show();
            //update connection status
            updateConnectionStatus("Error - " + error.getMessage(), true);
        }

        @Override
        public void onTerminalInfoReceived(TerminalInfo terminalInfo) {
            Log.d(TAG, "onTerminalInfoReceived - " + terminalInfo.toString());
        }
    };

    protected DeviceListener usbDeviceListener = new DeviceListener() {
        @Override
        public void onBluetoothDeviceFound(BluetoothDevice bluetoothDevice) {
        }

        @Override
        public void onBluetoothDeviceList(HashSet<BluetoothDevice> deviceList) {
        }

        @Override
        public void onConnected(TerminalInfo terminalInfo) {
            hideProgress();
            Log.d(TAG, "onConnected - " + terminalInfo.toString());
            MainActivity.setPuckTerminalDetails(terminalInfo);

            //update connection status
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    Toast.makeText(getApplicationContext(), "Connected - " + terminalInfo.getSerialNumber(), Toast.LENGTH_LONG).show();
                    updateConnectionStatus("Connected - " + terminalInfo.getSerialNumber(), false);
                    if (shouldCloseAfterConnect()) {
                        finish();
                    }
                }
            });
        }

        @Override
        public void onDisconnected() {
            Log.d(TAG, "onDisconnected");
            MainActivity.puck1000Device = null;
            MainActivity.clearPuckTerminalDetails();
            //update connection status
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    Toast.makeText(getApplicationContext(), "Disconnected", Toast.LENGTH_LONG).show();
                    updateConnectionStatus("Disconnected", false);
                    disconnectEvent();
                }
            });
        }

        @Override
        public void onError(Error error, ErrorType errorType) {
            Log.e(TAG, "onError - " + error.toString() + ", " + errorType);
            if (Dialogs.pd != null && Dialogs.pd.isShowing()) {
                Dialogs.hideProgress();
            }
            Toast.makeText(getApplicationContext(), error.getMessage(), Toast.LENGTH_LONG).show();
            //update connection status
            updateConnectionStatus("Error - " + error.getMessage(), true);
        }

        @Override
        public void onTerminalInfoReceived(TerminalInfo terminalInfo) {
            MainActivity.setPuckTerminalDetails(terminalInfo);
        }
    };

    protected SafListener safListener = new SafListener() {
        @Override
        public void onProcessingComplete(List<TransactionResponse> responses) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    hideProgress();
                    showAlertDialog(getString(R.string.saf_transactions_complete),
                            Dialogs.constructSAFTransactionMessage(responses));
                }
            });
        }

        @Override
        public void onAllSafTransactionsRetrieved(List<SafTransaction> obfuscatedSafTransactions) {
            if (obfuscatedSafTransactions == null || obfuscatedSafTransactions.isEmpty()) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        hideProgress();
                    }
                });
            }
        }

        @Override
        public void onError(Error error) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    hideProgress();
                    String errorMessage = (error != null && error.getMessage() != null
                            && !error.getMessage().isEmpty())
                            ? error.getMessage()
                            : getString(R.string.error);
                    showAlertDialog(getString(R.string.error), errorMessage);
                }
            });
        }

        @Override
        public void onTransactionStored(String id, int totalCount, BigDecimal totalAmount) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    CharSequence message = "Transaction Stored: " + id + ", Total Count/Amount: " +
                            totalCount + " / $" + totalAmount.divide(BigDecimal.valueOf(100));
                    Toast.makeText(BaseActivity.this, message, Toast.LENGTH_LONG).show();

                    MainActivity.transactionId = id;
                    MainActivity.transactionResult = "SAF";
                    MainActivity.transactionState = TransactionState.Complete;
                    updateTransactionStatus();
                }
            });
        }

        @Override
        public void onStoredTransactionComplete(String id, TransactionResponse transactionResponse) {
            //acknowledge the transaction so it can be deleted
            IDevice device = getDevice();
            if (transactionResponse.getTransactionResult() != TransactionResultType.CANCELLED) {
                device.acknowledgeSAFTransaction(id);
            }

            //show toast
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    CharSequence message = "SAF Transaction Complete: " + id + ", Response: " +
                            transactionResponse.getTransactionResult();
                    Toast.makeText(BaseActivity.this, message, Toast.LENGTH_LONG).show();
                }
            });
        }
    };

    protected IDevice getDevice() {
        if(MainActivity.c2XDevice != null) {
            return MainActivity.c2XDevice;
        } else if (MainActivity.mobyDevice != null) {
            return MainActivity.mobyDevice;
        } else if (MainActivity.puck1000Device != null) {
            return MainActivity.puck1000Device;
        }else{
            return null;
        }
    }
}