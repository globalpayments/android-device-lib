package com.globalpayments.library.terminals.moby;

import android.Manifest.permission;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.content.DialogInterface;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import com.globalpayments.library.terminals.receivers.BluetoothDiscoveryListener;
import com.globalpayments.library.BuildConfig;
import com.globalpayments.library.R;
import com.globalpayments.library.terminals.AvailableTerminalVersionsListener;
import com.globalpayments.library.terminals.ConnectionConfig;
import com.globalpayments.library.terminals.DeviceListener;
import com.globalpayments.library.terminals.IDevice;
import com.globalpayments.library.terminals.SafListener;
import com.globalpayments.library.terminals.TransactionListener;
import com.globalpayments.library.terminals.UpdateTerminalListener;
import com.globalpayments.library.terminals.entities.CardholderInteractionResult;
import com.globalpayments.library.terminals.entities.TerminalResponse;
import com.globalpayments.library.terminals.enums.ConnectionMode;
import com.globalpayments.library.terminals.enums.Environment;
import com.globalpayments.library.terminals.enums.ErrorType;
import com.globalpayments.library.terminals.enums.TerminalUpdateType;
import com.globalpayments.library.terminals.receivers.BluetoothReceiver;
import com.roam.roamreaderunifiedapi.callback.LedPairingConfirmationCallback;
import com.roam.roamreaderunifiedapi.data.LedSequence;
import com.roam.roamreaderunifiedapi.view.PairingLedView;
import com.tsys.payments.library.connection.ConnectionListener;
import com.tsys.payments.library.connection.LedMobyPairingListener;
import com.tsys.payments.library.db.DatabaseConfig;
import com.tsys.payments.library.db.SafDatabaseConfig;
import com.tsys.payments.library.db.entity.SafTransaction;
import com.tsys.payments.library.domain.CardholderInteractionRequest;
import com.tsys.payments.library.domain.GatewayConfiguration;
import com.tsys.payments.library.domain.TerminalConfiguration;
import com.tsys.payments.library.domain.TerminalInfo;
import com.tsys.payments.library.domain.TransactionConfiguration;
import com.tsys.payments.library.domain.TransactionRequest;
import com.tsys.payments.library.domain.TransactionResponse;
import com.tsys.payments.library.enums.CardholderInteractionType;
import com.tsys.payments.library.enums.ConnectionType;
import com.tsys.payments.library.enums.CurrencyCode;
import com.tsys.payments.library.enums.TerminalAuthenticationCapability;
import com.tsys.payments.library.enums.TerminalInputCapability;
import com.tsys.payments.library.enums.TerminalOperatingEnvironment;
import com.tsys.payments.library.enums.TerminalOutputCapability;
import com.tsys.payments.library.enums.TerminalType;
import com.tsys.payments.library.enums.TransactionResultType;
import com.tsys.payments.library.enums.TransactionStatus;
import com.tsys.payments.library.enums.TransactionType;
import com.tsys.payments.library.exceptions.Error;
import com.tsys.payments.library.exceptions.InitializationException;
import com.tsys.payments.library.gateway.enums.GatewayType;
import com.tsys.payments.library.logging.GPLibraryLogger.GPLibraryLogCallback;
import com.tsys.payments.library.logging.GPLibraryLogLevel;
import com.tsys.payments.library.logging.GPLibraryLogManager;
import com.tsys.payments.library.logging.GPLibraryLogType;
import com.tsys.payments.library.terminal.TerminalInfoListener;
import com.tsys.payments.library.utils.LibraryConfigHelper;
import com.tsys.payments.transaction.TransactionManager;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import timber.log.Timber;

/**
 * Moby Device Implementation. Device: Ingenico Moby5500
 */
public class MobyDevice implements IDevice {
    private static final String TAG = MobyDevice.class.getSimpleName();

    private static boolean timberPlanted;
    private final TransactionManager transactionManager;
    private Context applicationContext;
    private Context mobyPairingContext;
    private ConnectionConfig connectionConfig;
    private HashSet<BluetoothDevice> bluetoothDevices;
    private BluetoothReceiver bluetoothReceiver;
    private TransactionConfiguration transactionConfig;
    private TerminalConfiguration terminalConfig;
    private GatewayConfiguration gatewayConfig;
    private DatabaseConfig databaseConfig;
    private DeviceListener deviceListener;
    private TransactionListener transactionListener;
    private SafListener safListener;
    private AvailableTerminalVersionsListener availableTerminalVersionsListener;
    private UpdateTerminalListener updateTerminalListener;
    private PairingLedView pairingLedView;
    private AlertDialog dialog;
    private boolean isDeviceSelected;
    private boolean isScanned;
    private final ExecutorService executor =
            Executors.newSingleThreadExecutor();
    private boolean bluetoothReceiverRegistered;

    private List<Long> safIDs;

    /**
     * Instantiates a new Moby device.
     *
     * @param context the context
     */
    public MobyDevice(Context context) {
        this.applicationContext = context;
        this.transactionManager = TransactionManager.getInstance();
    }

    /**
     * Instantiates a new Moby device.
     *
     * @param context the context
     * @param config  the config
     */
    public MobyDevice(Context context, ConnectionConfig config) throws Exception {
        this.applicationContext = context;
        this.setConnectionConfig(config);
        this.transactionManager = TransactionManager.getInstance();
    }

    /**
     * Set the Connection Configuration
     *
     * @param connectionConfig the connection config
     */
    public void setConnectionConfig(ConnectionConfig connectionConfig) throws Exception {
        this.connectionConfig = connectionConfig;

        LibraryConfigHelper.setDebugMode(connectionConfig.getEnvironment().equals(Environment.TEST));
        LibraryConfigHelper.setSdkNameVersion("android;version=" + BuildConfig.VERSION_NAME);
        LibraryConfigHelper.setMobyAutoRebootDisabled(connectionConfig.isMobyAutoRebootDisabled());
        if (connectionConfig.getEnvironment().equals(Environment.TEST) && !timberPlanted) {
            Timber.plant(new Timber.DebugTree());
            timberPlanted = true;
        }

        LibraryConfigHelper.setSurchargeEnabled(connectionConfig.isSurchargeEnabled());

        //Surcharge rate is handled by the application now
        /*LibraryConfigHelper.setSurchargePreTax(connectionConfig.isSurchargePreTax());
        if (LibraryConfigHelper.isSurchargeEnabled()) {
            //can ignore any custom value if surcharge isn't even enabled
            boolean surchargePercentUpdate =
                    LibraryConfigHelper.setSurchargePercent(connectionConfig.getSurchargePercent());
            if (!surchargePercentUpdate) {
                throw new Exception(applicationContext.getString(R.string.invalid_surcharge_amount));
            }
        }*/

        transactionConfig = new TransactionConfiguration();
        transactionConfig.setQuickChipEnabled(true);
        transactionConfig.setChipEnabled(true);
        transactionConfig.setContactlessEnabled(true);
        transactionConfig.setCurrencyCode(CurrencyCode.USD);
        transactionConfig.setMagStripeEnabled(true);

        terminalConfig = new TerminalConfiguration();
        terminalConfig.setTerminalType(TerminalType.INGENICO_MOBY_5500);
        terminalConfig.setCapability(TerminalInputCapability.ICC_CHIP_CONTACT_CONTACTLESS);
        terminalConfig.setOutputCapability(TerminalOutputCapability.PRINT_AND_DISPLAY);
        terminalConfig.setAuthenticationCapability(TerminalAuthenticationCapability.NO_CAPABILITY);
        terminalConfig.setOperatingEnvironment(TerminalOperatingEnvironment.ON_MERCHANT_PREMISES_ATTENDED);
        if (connectionConfig.getConnectionMode() == ConnectionMode.USB) {
            terminalConfig.setConnectionType(ConnectionType.USB);
        } else {
            terminalConfig.setConnectionType(ConnectionType.BLUETOOTH);
            terminalConfig.setPairingListener(new LedPairingListenerImpl());
        }

        final Long timeout = connectionConfig.getTimeout();
        terminalConfig.setTimeout(timeout != 0 ? timeout : 60000L);

        if (connectionConfig.getPort() != null && !connectionConfig.getPort().isEmpty()) {
            terminalConfig.setPort(Integer.parseInt(connectionConfig.getPort()));
        }

        HashMap<String, String> credentials = new HashMap<>();
        if (connectionConfig.getGateway() == GatewayType.PORTICO) {
            credentials.put("version_number", "3409");
            credentials.put("developer_id", "002914");
            credentials.put("user_name", connectionConfig.getCredentials().getUsername());
            credentials.put("license_id", connectionConfig.getCredentials().getLicenseId());
            credentials.put("site_id", connectionConfig.getCredentials().getSiteId());
            credentials.put("password", connectionConfig.getCredentials().getPassword());
            credentials.put("terminal_id", connectionConfig.getCredentials().getDeviceId());
        } else {
            credentials.put("merchant_id", connectionConfig.getCredentials().getMerchantId());
            credentials.put("user_name", connectionConfig.getCredentials().getUsername());
            credentials.put("password", connectionConfig.getCredentials().getPassword());
            credentials.put("device_id", connectionConfig.getCredentials().getDeviceId());
            credentials.put("developer_id", connectionConfig.getCredentials().getDeveloperId());
            credentials.put("transaction_key", connectionConfig.getCredentials().getTransactionKey());
        }

        gatewayConfig = new GatewayConfiguration();
        gatewayConfig.setGatewayType(connectionConfig.getGateway());
        gatewayConfig.setCredentials(credentials);
        gatewayConfig.setLibraryLogger(GPLibraryLogManager.getLibraryLogger());

        if (connectionConfig.isSafEnabled() && connectionConfig.getGateway() == GatewayType.TRANSIT) {
            //SAF is not yet supported for TransIT
            throw new Exception("SAF is not yet supported for TransIT");
        }
        SafDatabaseConfig safDatabaseConfig = new SafDatabaseConfig(connectionConfig.isSafEnabled(),
                connectionConfig.getSafExpirationInDays(), TimeUnit.DAYS);
        databaseConfig = new DatabaseConfig(applicationContext, "mobyDB", null,
                safDatabaseConfig, GatewayType.PORTICO);
        safIDs = new ArrayList<Long>();
    }

    /**
     * Initialize transaction manager.
     */
    protected void initializeTransactionManager() {
        try {
            transactionManager.initialize(
                    applicationContext,
                    terminalConfig,
                    transactionConfig,
                    gatewayConfig,
                    databaseConfig
            );
        } catch (InitializationException ex) {
            ex.printStackTrace();
        }
    }

    private Context getMobyPairingContext() {
        if (mobyPairingContext == null) {
            return applicationContext;
        }
        return mobyPairingContext;
    }

    @Override
    public void uploadSAF() {
        transactionManager.processAllSafTransactions(new SafListenerImpl());
    }

    /**
     * Check if force SAF is currently enabled.
     * @return True if force SAF is properly enabled, false otherwise.
     */
    @Override
    public boolean isForcedSafEnabled() {
        if (transactionManager != null) {
            return transactionManager.isForceSafEnabled();
        }
        return false;
    }

    /**
     * Sets force SAF enabled. This will have no effect if SAF is not enabled.
     * @param forcedSaf
     */
    @Override
    public void setForcedSafEnabled(boolean forcedSaf) {
        transactionManager.setForceSafEnabled(forcedSaf);
    }

    @Override
    public void acknowledgeSAFTransaction(String uniqueSafId) {
        if (transactionManager != null) {
            transactionManager.acknowledgeSAFTransaction(uniqueSafId);
        }
    }

    /**
     * Set the context for the pairing Dialog Moby Device.
     *
     * @param context the context
     */
    public void setMobyPairingContext(Context context) {
        this.mobyPairingContext = context;
    }

    /**
     * Set the device listener to get Device events.
     *
     * @param deviceListener the device listener
     */
    public void setDeviceListener(DeviceListener deviceListener) {
        this.deviceListener = deviceListener;
    }

    /**
     * Set the Transaction Listener to receive Transaction events
     *
     * @param transactionListener the transaction listener
     */
    public void setTransactionListener(TransactionListener transactionListener) {
        this.transactionListener = transactionListener;
    }

    public void setSafListener(SafListener safListener) {
        this.safListener = safListener;
    }

    public void setAvailableTerminalVersionsListener(
            AvailableTerminalVersionsListener availableTerminalVersionsListener) {
        this.availableTerminalVersionsListener = availableTerminalVersionsListener;
    }

    public void setUpdateTerminalListener(UpdateTerminalListener updateTerminalListener) {
        this.updateTerminalListener = updateTerminalListener;
    }

    /**
     * Sets the library log callback used by {@link GPLibraryLogManager}.
     * This callback is global, not scoped to a single {@code MobyDevice} instance.
     * Setting it here replaces any callback previously set by another device instance.
     *
     * @param logCallback the global library log callback
     */
    public void setLogCallback(GPLibraryLogCallback logCallback) {
        GPLibraryLogManager.setLibraryLogger(logCallback);
    }

    /**
     * Removes the library log callback from {@link GPLibraryLogManager}.
     * This clears the global callback for all instances.
     */
    public void removeLogCallback() {
        GPLibraryLogManager.clearLibraryLogger();
    }

    /**
     * Initialize is used to start the connection
     */
    public void initialize() {
        switch (connectionConfig.getConnectionMode()) {
            case USB:
                startConnect();
                break;
            default:
                scan();
                break;
        }
    }

    public void resetDevice() {
        if (isTransactionManagerConnected()) {
            transactionManager.resetDevice();
        }
    }

    private void scan() {
        isScanned = true;
        if (!isDeviceSelected) {
            if (bluetoothReceiver == null) {
                bluetoothReceiver = new BluetoothReceiver();
                bluetoothReceiver.setListener(new BluetoothListenerImpl());
            }

            if (applicationContext != null) {
                IntentFilter bluetoothFilter = new IntentFilter();
                bluetoothFilter.addAction(BluetoothDevice.ACTION_FOUND);
                bluetoothFilter.addAction(BluetoothAdapter.ACTION_DISCOVERY_STARTED);
                bluetoothFilter.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);

                applicationContext.registerReceiver(bluetoothReceiver, bluetoothFilter);
                bluetoothReceiverRegistered = true;
            }
        }
        startConnect();
    }

    /**
     * Unregister the Bluetooth Receiver
     */
    private void unregisterBluetoothReceiver() {
        if (applicationContext == null || bluetoothReceiver == null || !bluetoothReceiverRegistered) {
            return;
        }

        try {
            applicationContext.unregisterReceiver(bluetoothReceiver);
        } catch (IllegalArgumentException ex) {
            GPLibraryLogManager.emit(GPLibraryLogLevel.WARNING, GPLibraryLogType.DEVICE, TAG,
                    "Bluetooth receiver error", ex.toString());
        } finally {
            bluetoothReceiverRegistered = false;
        }
    }

    /**
     * Connect with the device address(MAC)
     *
     * @param deviceName the device name
     */
    public void connect(String deviceName) {
        terminalConfig.setHost(deviceName);
        GPLibraryLogManager.emit(GPLibraryLogLevel.INFO, GPLibraryLogType.DEVICE, TAG,
                "connect() called", deviceName);
        startConnect();
    }

    /**
     * Connect with the device object
     *
     * @param device the device
     */
    public void connect(BluetoothDevice device) {
        isDeviceSelected = true;
        connect(device.getAddress());
    }

    /**
     * Disconnect the connected device
     */
    public void disconnect() {
        unregisterBluetoothReceiver();
        GPLibraryLogManager.emit(GPLibraryLogLevel.INFO, GPLibraryLogType.DEVICE, TAG,
                "disconnect() called", null);

        if (isTransactionManagerConnected()) {
            transactionManager.disconnect();
        }
        isScanned = false;
    }

    /**
     * Check the status of connection
     *
     * @return boolean
     */
    public boolean isConnected() {
        return isTransactionManagerConnected();
    }

    /**
     * Get the device Information
     */
    public void getDeviceInfo() {
        if (isTransactionManagerConnected()) {
            transactionManager.getDeviceInfo(new TerminalInfoListenerImpl());
        }
    }

    private void startConnect() {
        GPLibraryLogManager.emit(GPLibraryLogLevel.DEBUG, GPLibraryLogType.DEVICE, TAG,
                "startConnect() called", null);
        if (isTransactionManagerConnected()) {
            return;
        }

        if (transactionManager != null && terminalConfig != null) {
            ConnectionType[] connectionTypes =
                    transactionManager.getSupportedTerminalConnectionTypes(terminalConfig.getTerminalType());

            if (connectionTypes.length > 1) {
                if (terminalConfig.getConnectionType() == connectionTypes[0]) {
                    terminalConfig.setConnectionType(connectionTypes[0]);
                } else {
                    terminalConfig.setConnectionType(connectionTypes[1]);
                }
            } else {
                terminalConfig.setConnectionType(connectionTypes[0]);
            }

            if (isScanned) {
                terminalConfig.setHost(null);
                isScanned = false;
            }

            executor.execute(() -> {
                initializeTransactionManager();

                if (transactionManager.isInitialized()) {
                    GPLibraryLogManager.emit(GPLibraryLogLevel.DEBUG, GPLibraryLogType.DEVICE, TAG,
                            "TransactionManager isInitialized() called", null);
                    transactionManager.connect(new ConnectionListenerImpl());
                    transactionManager.updateTransactionListener(new TransactionListenerImpl());
                }
            });
            /*final boolean[] initialized = {false};
            Thread thread = new Thread(new Runnable() {
                @Override
                public void run() {
                    Log.e("test", "thread start");
                    initializeTransactionManager();
                    Log.e("test", "initialize finish");
                    initialized[0] = true;
                }
            });
            thread.start();

            while (!initialized[0]) {
                try {
                    Log.e("test", "waiting on initialize");
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }*/


        }
    }



    /**
     * Is transaction manager connected boolean.
     *
     * @return the boolean
     */
    protected boolean isTransactionManagerConnected() {
        return transactionManager.isConnected();
    }

    @Override
    public void doTransaction(TransactionRequest transactionRequest) {
        GPLibraryLogManager.emit(GPLibraryLogLevel.INFO, GPLibraryLogType.TRANSACTION, TAG,
                "doTransaction() called", null);

        if (!transactionManager.isInitialized()) {
            initializeTransactionManager();
        }

        transactionManager.startTransaction(transactionRequest, new TransactionListenerImpl(),
                new SafListenerImpl());
    }

    /**
     * Start an SVA (gift card) transaction. This will return the gift card data by way of the TransactionListener
     * function onTransactionComplete().
     */
    public void doSvaStartCard() {
        if (!transactionManager.isInitialized()) {
            initializeTransactionManager();
        }
        TransactionRequest transactionRequest = new TransactionRequest();
        transactionRequest.setTransactionType(TransactionType.SVA);
        transactionManager.startTransaction(transactionRequest, new TransactionListenerImpl(), null);
    }

    /**
     * Cancel the current transaction. No effect if there is no transaction active.
     */
    public void cancelTransaction() {
        transactionManager.cancel();
    }

    public void cancelSAFUpload() {
        transactionManager.cancelUploadSaf();
    }

    //OTA methods
    public void getAvailableTerminalVersions(TerminalUpdateType terminalUpdateType) {
        if (!transactionManager.isInitialized()) {
            GPLibraryLogManager.emit(GPLibraryLogLevel.ERROR, GPLibraryLogType.DEVICE, TAG,
                    "TransactionManager not initialized, please connect to device first.", null);
            return;
        }
        if (availableTerminalVersionsListener == null) {
            GPLibraryLogManager.emit(GPLibraryLogLevel.ERROR, GPLibraryLogType.DEVICE, TAG,
                    "AvailableTerminalVersionsListener is null, please set a valid listener.", null);
            return;
        }

        com.tsys.payments.library.enums.TerminalUpdateType updateType =
                com.tsys.payments.library.enums.TerminalUpdateType.FIRMWARE;
        if (terminalUpdateType == TerminalUpdateType.CONFIG) {
            updateType = com.tsys.payments.library.enums.TerminalUpdateType.KERNEL;
        }

        transactionManager.getAvailableTerminalVersions(updateType, null, new AvailableTerminalVersionsListenerImpl());
    }

    public void updateTerminal(@NonNull TerminalUpdateType terminalUpdateType,
            @Nullable String version) {
        if (!transactionManager.isInitialized()) {
            GPLibraryLogManager.emit(GPLibraryLogLevel.ERROR, GPLibraryLogType.DEVICE, TAG,
                    "TransactionManager not initialized, please connect to device first.", version);
            return;
        }
        if (updateTerminalListener == null) {
            GPLibraryLogManager.emit(GPLibraryLogLevel.ERROR, GPLibraryLogType.DEVICE, TAG,
                    "UpdateTerminalListener is null, please set a valid listener.", version);
            return;
        }

        com.tsys.payments.library.enums.TerminalUpdateType updateType =
                com.tsys.payments.library.enums.TerminalUpdateType.FIRMWARE;
        if (terminalUpdateType == TerminalUpdateType.CONFIG) {
            updateType = com.tsys.payments.library.enums.TerminalUpdateType.KERNEL;
        } else if (terminalUpdateType == TerminalUpdateType.RKI) {
            updateType = com.tsys.payments.library.enums.TerminalUpdateType.RKI;
        }

        transactionManager.updateTerminal(updateType, null, version, new UpdateTerminalListenerImpl());
    }

    public void sendCardholderInteractionResult(CardholderInteractionResult cardholderInteractionResult) {
        if (isTransactionManagerConnected()) {
            transactionManager.sendCardholderInteractionResult(map(cardholderInteractionResult));
        }
    }

    private com.globalpayments.library.terminals.entities.TerminalInfo map(TerminalInfo info) {
        final com.globalpayments.library.terminals.entities.TerminalInfo ti =
                new com.globalpayments.library.terminals.entities.TerminalInfo();
        ti.setAppName(info.getAppName());
        ti.setAppVersion(info.getAppVersion());
        ti.setBatteryLevel(info.getBatteryLevel());
        ti.setFirmwareVersion(info.getFirmwareVersion());
        ti.setKernelVersion(info.getKernelVersion());
        ti.setSerialNumber(info.getSerialNumber());
        ti.setTerminalType(info.getTerminalType());
        ti.setModel(info.getModel());
        ti.setManufacturer(info.getManufacturer());
        return ti;
    }

    private ErrorType map(
            com.tsys.payments.library.enums.ErrorType errorType) {
        ErrorType result =
                ErrorType.values()[errorType.ordinal()];
        return result;
    }

    private com.globalpayments.library.terminals.entities.CardholderInteractionRequest map(
            CardholderInteractionRequest info) {
        final com.globalpayments.library.terminals.entities.CardholderInteractionRequest cr =
                new com.globalpayments.library.terminals.entities.CardholderInteractionRequest();
        cr.setCardholderInteractionType(info.getCardholderInteractionType());
        cr.setCommercialCardDataFields(info.getCommercialCardDataFields());
        cr.setFinalTransactionAmount(info.getFinalTransactionAmount());
        cr.setSupportedApplications(info.getSupportedApplications());
        return cr;
    }

    private com.tsys.payments.library.domain.CardholderInteractionResult map(CardholderInteractionResult info) {
        final com.tsys.payments.library.domain.CardholderInteractionResult result =
                new com.tsys.payments.library.domain.CardholderInteractionResult(info.getCardholderInteractionType());
        result.setCommercialCardData(info.getCommercialCardData());
        result.setFinalAmountConfirmed(info.getFinalAmountConfirmed());
        result.setSelectedAidIndex(info.getSelectedAidIndex());
        result.setFinalTaxAmount(info.getFinalTaxAmount());
        result.setFinalSurchargeAmount(info.getFinalSurchargeAmount());
        result.setFinalAmount(info.getFinalAmount());
        return result;
    }

    private void showPairingDialog(final LedPairingConfirmationCallback ledPairingConfirmationCallback) {
        AlertDialog.Builder builder = new AlertDialog.Builder(getMobyPairingContext());
        builder.setTitle("Confirm Led Sequence");
        View dialogView;
        dialogView = LayoutInflater.from(getMobyPairingContext()).inflate(R.layout.dialog_pairing_led, null);
        pairingLedView = dialogView.findViewById(R.id.pairingLedView);
        builder.setView(dialogView);
        builder.setCancelable(false);
        builder.setNeutralButton("Restart",
                new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialogInterface, int i) {
                        new Thread(new Runnable() {
                            @Override
                            public void run() {
                                ledPairingConfirmationCallback.restartLedPairingSequence();
                            }
                        }).start();
                    }
                });
        builder.setPositiveButton("Confirm",
                new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        new Thread(new Runnable() {
                            @Override
                            public void run() {
                                ledPairingConfirmationCallback.confirm();
                            }
                        }).start();
                    }
                });
        builder.setNegativeButton("Cancel",
                new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        new Thread(new Runnable() {
                            @Override
                            public void run() {
                                ledPairingConfirmationCallback.cancel();
                            }
                        }).start();
                    }
                });
        dialog = builder.create();
        dialog.show();
    }

    /**
     * Bluetooth listener implementation.
     */
    protected class BluetoothListenerImpl implements BluetoothDiscoveryListener {

        @Override
        public void onDiscoveryStarted() {
            bluetoothDevices = new HashSet<>();
        }

        @Override
        public void onDiscoveryFinished() {
            unregisterBluetoothReceiver();
            if (deviceListener == null) {
                return;
            }
            deviceListener.onBluetoothDeviceList(bluetoothDevices);
        }

        @Override
        public void onBluetoothDeviceFound(BluetoothDevice foundDevice) {
            if (foundDevice == null) {
                return;
            }

            if (ActivityCompat.checkSelfPermission(applicationContext, permission.BLUETOOTH_CONNECT) !=
                    PackageManager.PERMISSION_GRANTED) {
                // TODO: Consider calling
                //    ActivityCompat#requestPermissions
                // here to request the missing permissions, and then overriding
                //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
                //                                          int[] grantResults)
                // to handle the case where the user grants the permission. See the documentation
                // for ActivityCompat#requestPermissions for more details.
                return;
            }
            if (foundDevice.getType() != BluetoothDevice.DEVICE_TYPE_CLASSIC &&
                    foundDevice.getType() != BluetoothDevice.DEVICE_TYPE_LE &&
                    foundDevice.getType() != BluetoothDevice.DEVICE_TYPE_DUAL) {
                return;
            }

            if (foundDevice.getName() != null && foundDevice.getName().startsWith("MOB")) {
                bluetoothDevices.add(foundDevice);
                if (deviceListener != null) {
                    deviceListener.onBluetoothDeviceFound(foundDevice);
                }
            }
        }
    }

    /**
     * Led pairing listener implementation.
     */
    protected class LedPairingListenerImpl implements LedMobyPairingListener {

        @Override
        public void onLedPairSequence(Object led, Object confirm) {
            pairingLedView = new PairingLedView(getMobyPairingContext());
            List<LedSequence> sequenceList = (List<LedSequence>)led;
            LedPairingConfirmationCallback ledPairingConfirmationCallback
                    = (LedPairingConfirmationCallback)confirm;
            showPairingDialog(ledPairingConfirmationCallback);
            pairingLedView.show(sequenceList);
        }

        @Override
        public void onNotSupported() {
            GPLibraryLogManager.emit(GPLibraryLogLevel.DEBUG, GPLibraryLogType.DEVICE, TAG,
                    "MobyDevice callback :: LedPairingCallback -> notSupported", null);
        }

        @Override
        public void onSuccess() {
            GPLibraryLogManager.emit(GPLibraryLogLevel.DEBUG, GPLibraryLogType.DEVICE, TAG,
                    "MobyDevice callback :: LedPairingCallback -> success", null);
        }

        @Override
        public void onFail() {
            if (dialog != null) {
                dialog.dismiss();
            }
            if (deviceListener != null) {
                java.lang.Error err = new java.lang.Error("Pairing Failed");
                deviceListener.onError(err, ErrorType.NOT_CONNECTED);
            }
            GPLibraryLogManager.emit(GPLibraryLogLevel.DEBUG, GPLibraryLogType.DEVICE, TAG,
                    "MobyDevice callback :: LedPairingCallback -> failed", null);
        }

        @Override
        public void onCanceled() {
            if (deviceListener != null) {
                java.lang.Error err = new java.lang.Error("Pairing Canceled");
                deviceListener.onError(err, ErrorType.NOT_CONNECTED);
            }
            GPLibraryLogManager.emit(GPLibraryLogLevel.DEBUG, GPLibraryLogType.DEVICE, TAG,
                    "MobyDevice callback :: LedPairingCallback -> canceled", null);
        }
    }

    /**
     * The type Connection listener.
     */
    protected class ConnectionListenerImpl implements ConnectionListener {
        @Override
        public void onConnected(TerminalInfo terminalInfo) {
            GPLibraryLogManager.emit(GPLibraryLogLevel.INFO, GPLibraryLogType.DEVICE, TAG,
                    "Device connected", terminalInfo.getSerialNumber());
            if (deviceListener != null) {
                deviceListener.onConnected(map(terminalInfo));
            }
        }

        @Override
        public void onDisconnected() {
            GPLibraryLogManager.emit(GPLibraryLogLevel.WARNING, GPLibraryLogType.DEVICE, TAG,
                    "Device disconnected", null);
            if (deviceListener != null) {
                deviceListener.onDisconnected();
            }
        }

        @Override
        public void onError(Error error) {
            GPLibraryLogManager.emit(GPLibraryLogLevel.ERROR, GPLibraryLogType.DEVICE, TAG,
                    "Connection error", error.getMessage());
            if (deviceListener != null) {
                java.lang.Error err = new java.lang.Error(error.getMessage());
                ErrorType errorType = map(error.getType());
                deviceListener.onError(err, errorType);
            }
        }
    }

    /**
     * Transaction listener implementation.
     */
    protected class TransactionListenerImpl implements com.tsys.payments.library.transaction.TransactionListener {
        @Override
        public void onStatusUpdate(TransactionStatus transactionStatus) {
            GPLibraryLogManager.emit(GPLibraryLogLevel.INFO, GPLibraryLogType.TRANSACTION, TAG,
                    "Transaction status update",
                    String.valueOf(transactionStatus));
            if (transactionListener != null) {
                transactionListener.onStatusUpdate(
                        com.globalpayments.library.terminals.enums.TransactionStatus.fromVitalSdk(
                                transactionStatus)
                );
            }
        }

        @Override
        public void onCardholderInteractionRequested(CardholderInteractionRequest cardholderInteractionRequest) {

            if (transactionListener != null) {
                boolean interactionHandled = transactionListener.onCardholderInteractionRequested(map(cardholderInteractionRequest));
                if (!interactionHandled) {
                    CardholderInteractionResult result;
                    switch (cardholderInteractionRequest.getCardholderInteractionType()) {
                        case EMV_APPLICATION_SELECTION:
                            String[] applications =
                                    cardholderInteractionRequest.getSupportedApplications();
                            // send result
                            result = new CardholderInteractionResult(
                                    cardholderInteractionRequest.getCardholderInteractionType()
                            );
                            result.setSelectedAidIndex(0);
                            sendCardholderInteractionResult(result);
                            break;
                        case SURCHARGE_REQUESTED:
                            result = new CardholderInteractionResult(
                                    CardholderInteractionType.CARDHOLDER_SURCHARGE_CONFIRMATION);
                            result.setFinalAmountConfirmed(false);
                            sendCardholderInteractionResult(result);
                            GPLibraryLogManager.emit(GPLibraryLogLevel.ERROR, GPLibraryLogType.TRANSACTION, TAG,
                                    "Surcharge confirmation was not handled by client application, cancelling transaction",
                                    null);
                            break;
                        case FINAL_AMOUNT_CONFIRMATION:
                            result = new CardholderInteractionResult(
                                    cardholderInteractionRequest.getCardholderInteractionType()
                            );
                            result.setFinalAmountConfirmed(true);
                            sendCardholderInteractionResult(result);
                            break;
                        default:
                            break;
                    }
                }
            }
        }

        @Override
        public void onTransactionComplete(TransactionResponse transactionResponse) {
            GPLibraryLogManager.emit(GPLibraryLogLevel.INFO, GPLibraryLogType.TRANSACTION, TAG,
                    "Transaction complete", String.valueOf(transactionResponse));
            if (transactionResponse.getTransactionResult() == TransactionResultType.SAF) {
                safIDs.add(Long.valueOf(transactionResponse.getPosReferenceNumber()));
            }
            if (transactionListener != null) {
                transactionListener.onTransactionComplete(
                        TerminalResponse.fromTransactionResponse(transactionResponse));
            }
        }

        @Override
        public void onError(Error error) {
            GPLibraryLogManager.emit(GPLibraryLogLevel.ERROR, GPLibraryLogType.TRANSACTION, TAG,
                    "Transaction error", error.getMessage());
            if (transactionListener != null) {
                java.lang.Error err = new java.lang.Error(error.getMessage());
                ErrorType errorType = map(error.getType());
                transactionListener.onError(err, errorType);
            }
        }
    }

    /**
     * Terminal info listener implementation.
     */
    protected class TerminalInfoListenerImpl implements TerminalInfoListener {
        @Override
        public void onTerminalInfoReceived(TerminalInfo terminalInfo) {
            if (deviceListener != null) {
                deviceListener.onTerminalInfoReceived(map(terminalInfo));
            }
        }

        @Override
        public void onError(Error error) {
            if (deviceListener != null) {
                java.lang.Error err = new java.lang.Error(error.getMessage());
                ErrorType errorType = map(error.getType());
                deviceListener.onError(err, errorType);
            }
            if (transactionListener != null) {
                java.lang.Error err = new java.lang.Error(error.getMessage());
                ErrorType errorType = map(error.getType());
                transactionListener.onError(err, errorType);
            }
        }
    }

    protected class SafListenerImpl implements com.tsys.payments.library.db.SafListener {
        @Override
        public void onProcessingComplete(List<TransactionResponse> responses) {
            GPLibraryLogManager.emit(GPLibraryLogLevel.DEBUG, GPLibraryLogType.TRANSACTION, TAG,
                    "SAF processing complete", "Count: " + responses.size());
            for (TransactionResponse transactionResponse : responses) {
                GPLibraryLogManager.emit(GPLibraryLogLevel.DEBUG, GPLibraryLogType.TRANSACTION, TAG,
                        "SAF transaction response", String.valueOf(transactionResponse));
            }
            if (safListener != null) {
                safListener.onProcessingComplete(responses);
            }
        }

        @Override
        public void onAllSafTransactionsRetrieved(List<SafTransaction> obfuscatedSafTransactions) {
            GPLibraryLogManager.emit(GPLibraryLogLevel.DEBUG, GPLibraryLogType.TRANSACTION, TAG,
                    "All SAF transactions retrieved", "Count: " + obfuscatedSafTransactions.size());
            if (safListener != null) {
                safListener.onAllSafTransactionsRetrieved(obfuscatedSafTransactions);
            }
        }

        @Override
        public void onError(Error error) {
            GPLibraryLogManager.emit(GPLibraryLogLevel.ERROR, GPLibraryLogType.TRANSACTION, TAG,
                    "SAF error", error.getMessage());
            if (safListener != null) {
                safListener.onError(new java.lang.Error(error.getMessage()));
            }
        }

        @Override
        public void onTransactionStored(String id, int totalCount, BigDecimal totalAmount) {
            GPLibraryLogManager.emit(GPLibraryLogLevel.DEBUG, GPLibraryLogType.TRANSACTION, TAG,
                    "Transaction stored", "id=" + id + ", count=" + totalCount + ", amount=" + totalAmount);
            if (safListener != null) {
                safListener.onTransactionStored(id, totalCount, totalAmount);
            }
        }

        @Override
        public void onStoredTransactionComplete(String id, TransactionResponse transactionResponse) {
            GPLibraryLogManager.emit(GPLibraryLogLevel.DEBUG, GPLibraryLogType.TRANSACTION, TAG,
                    "Stored transaction complete", "id=" + id + ", response=" + transactionResponse);
            if (safListener != null) {
                safListener.onStoredTransactionComplete(id, transactionResponse);
            }
        }
    };

    protected class AvailableTerminalVersionsListenerImpl
            implements com.tsys.payments.library.terminal.AvailableTerminalVersionsListener {

        @Override
        public void onAvailableTerminalVersionsReceived(com.tsys.payments.library.enums.TerminalUpdateType type,
                List<String> versions) {
            if (availableTerminalVersionsListener != null) {
                TerminalUpdateType updateType = TerminalUpdateType.FIRMWARE;
                if (type == com.tsys.payments.library.enums.TerminalUpdateType.KERNEL) {
                    updateType = TerminalUpdateType.CONFIG;
                }
                availableTerminalVersionsListener.onAvailableTerminalVersionsReceived(updateType, versions);
            }
        }

        public void onTerminalVersionInfoError(Error error) {
            if (availableTerminalVersionsListener != null) {
                java.lang.Error err = new java.lang.Error(error.getMessage());
                availableTerminalVersionsListener.onTerminalVersionInfoError(err);
            }
        }
    };

    protected class UpdateTerminalListenerImpl implements com.tsys.payments.library.terminal.UpdateTerminalListener {

        @Override
        public void onProgress(@Nullable Double completionPercentage, @Nullable String progressMessage) {
            if (updateTerminalListener != null) {
                updateTerminalListener.onProgress(completionPercentage, progressMessage);
            }
        }

        public void onTerminalUpdateSuccess() {
            if (updateTerminalListener != null) {
                updateTerminalListener.onTerminalUpdateSuccess();
            }
        }

        public void onTerminalUpdateError(Error error) {
            if (updateTerminalListener != null) {
                java.lang.Error err = new java.lang.Error(error.getMessage());
                updateTerminalListener.onTerminalUpdateError(err);
            }
        }
    };
}
