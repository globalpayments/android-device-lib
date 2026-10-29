package com.globalpayments.library.terminals.puck1000;

import android.app.PendingIntent;
import android.content.Context;

import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.castles.credit.constant.Host;
import com.castles.credit.credit.Credit;
import com.globalpayments.library.BuildConfig;
import com.globalpayments.library.terminals.ConnectionConfig;
import com.globalpayments.library.terminals.DeviceListener;
import com.globalpayments.library.terminals.IDevice;
import com.globalpayments.library.terminals.SafListener;
import com.globalpayments.library.terminals.TransactionListener;
import com.globalpayments.library.terminals.UpdateTerminalListener;
import com.globalpayments.library.terminals.entities.TerminalResponse;
import com.globalpayments.library.terminals.entities.TransManager;
import com.globalpayments.library.terminals.enums.ConnectionMode;
import com.globalpayments.library.terminals.enums.Environment;
import com.globalpayments.library.terminals.puck1000.mutualauth.MutualAuthEnvironment;
import com.globalpayments.library.terminals.receivers.UsbReceiver;
import com.globalpayments.library.utilities.ConvertUtil;
import com.tsys.payments.library.connection.ConnectionListener;
import com.tsys.payments.library.db.DatabaseConfig;
import com.tsys.payments.library.db.SafDatabaseConfig;
import com.tsys.payments.library.db.entity.SafTransaction;
import com.tsys.payments.library.domain.*;
import com.tsys.payments.library.enums.*;
import com.tsys.payments.library.exceptions.Error;
import com.tsys.payments.library.exceptions.InitializationException;
import com.tsys.payments.library.gateway.enums.GatewayType;
import com.tsys.payments.library.utils.LibraryConfigHelper;

import timber.log.Timber;
import com.tsys.payments.library.logging.GPLibraryLogLevel;
import com.tsys.payments.library.logging.GPLibraryLogManager;
import com.tsys.payments.library.logging.GPLibraryLogType;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.TimeUnit;

public class Puck1000Device implements IDevice {
    private static final String TAG = Puck1000Device.class.getSimpleName();

    private Context applicationContext;
    private ConnectionConfig connectionConfig;
    private DatabaseConfig databaseConfig;
    private DeviceListener deviceListener;
    private GatewayConfiguration gatewayConfig;
    private List<Long> safIDs;
    private SafListener safListener;
    private boolean timberPlanted = false;
    private TerminalConfiguration terminalConfig;
    private TransactionConfiguration transactionConfig;
    private TransactionListener transactionListener;
    private TransManager transactionManager;
    private UsbReceiver usbReceiver;
    private UpdateTerminalListener updateTerminalListener;
    private boolean usbReceiverRegistered = false;
    private Boolean isSignatureRequired = null;
    private boolean provisionalTransactionEnabled;
    private boolean mutualAuthEnabled;
    private String mutualAuthCertUrl;

    public Puck1000Device(Context context) { this.applicationContext = context; }
    public Puck1000Device(Context context, ConnectionConfig config) throws Exception {
        this.applicationContext = context;
        this.setConnectionConfig(config);
    }

    public ConnectionConfig getConnectionConfig() {
        return connectionConfig;
    }

    /**
     * Enables or disables PUCK1000 Mutual Authentication for the next connection. This may be
     * called after {@link #setConnectionConfig(ConnectionConfig)} but before {@link #initialize()}
     * (for example, in response to a user prompt on the connect screen).
     *
     * @param enabled whether Mutual Authentication should be performed during connection
     */
    public void setMutualAuthEnabled(boolean enabled) {
        this.mutualAuthEnabled = enabled;
        if (connectionConfig != null) {
            connectionConfig.setPuckMutualAuthEnabled(enabled);
        }
    }

    /**
     * Overrides the Mutual Authentication certificate server host for the next connection. This may
     * be called after {@link #setConnectionConfig(ConnectionConfig)} but before {@link #initialize()}
     * (for example, to switch between DVT/EVT and PVT environments from the connect screen). Passing
     * {@code null} or an empty value falls back to the SDK default host.
     *
     * @param certUrl the certificate server base URL to use for Mutual Authentication
     */
    public void setMutualAuthCertUrl(String certUrl) {
        this.mutualAuthCertUrl = certUrl;
        if (connectionConfig != null) {
            connectionConfig.setPuckMutualAuthCertUrl(certUrl);
        }
    }

    /**
     * Selects the Mutual Authentication certificate-server environment (DVT/EVT or PVT) for the next
     * connection. The URLs are owned by the SDK; the integrator only chooses the environment. When
     * this is never set, the SDK defaults to {@link MutualAuthEnvironment#DEFAULT} (DVT/EVT).
     *
     * @param environment the Mutual Authentication environment to use
     */
    public void setMutualAuthEnvironment(com.globalpayments.library.terminals.puck1000.mutualauth.MutualAuthEnvironment environment) {
        com.globalpayments.library.terminals.puck1000.mutualauth.MutualAuthEnvironment resolved =
                environment == null
                        ? com.globalpayments.library.terminals.puck1000.mutualauth.MutualAuthEnvironment.DEFAULT
                        : environment;
        setMutualAuthCertUrl(resolved.getUrl());
    }

    /** PROPERTIES **/
    public void setConnectionConfig(ConnectionConfig connectionConfig) throws Exception {
        this.connectionConfig = connectionConfig;
        provisionalTransactionEnabled = connectionConfig.isProvisionalTransactionEnabled();
        mutualAuthEnabled = connectionConfig.isPuckMutualAuthEnabled();
        mutualAuthCertUrl = connectionConfig.getPuckMutualAuthCertUrl();

        // Library Config Helper
        LibraryConfigHelper.setDebugMode(connectionConfig.getEnvironment().equals(Environment.TEST));
        LibraryConfigHelper.setSdkNameVersion("android;version=" + BuildConfig.VERSION_NAME);

        // Plant Timber if test
        if(connectionConfig.getEnvironment().equals(Environment.TEST) && !timberPlanted) {
            Timber.plant(new Timber.DebugTree());
            timberPlanted = true;
        }

        // Surcharge
        LibraryConfigHelper.setSurchargeEnabled(connectionConfig.isSurchargeEnabled());

        // Transaction Config
        transactionConfig = new TransactionConfiguration();
        transactionConfig.setQuickChipEnabled(false);
        transactionConfig.setChipEnabled(false);
        transactionConfig.setContactlessEnabled(true);
        transactionConfig.setCurrencyCode(CurrencyCode.USD);
        transactionConfig.setMagStripeEnabled(false);

        // Terminal Config
        terminalConfig = new TerminalConfiguration();
        terminalConfig.setTerminalType(TerminalType.CASTLES_PUCK_1000);
        terminalConfig.setCapability(TerminalInputCapability.ICC_CONTACTLESS_ONLY);
        terminalConfig.setOutputCapability(TerminalOutputCapability.DISPLAY_ONLY);
        terminalConfig.setAuthenticationCapability(TerminalAuthenticationCapability.NO_CAPABILITY);
        terminalConfig.setOperatingEnvironment(TerminalOperatingEnvironment.ON_MERCHANT_PREMISES_ATTENDED);
        terminalConfig.setConnectionType(ConnectionType.USB);

        HashMap<String, String> credentials = new HashMap<>();
        GatewayType gateway = connectionConfig.getGateway();
        if (gateway == GatewayType.PORTICO) {
            credentials.put("version_number", "3409");
            credentials.put("developer_id", "002914");
            credentials.put("user_name", connectionConfig.getCredentials().getUsername());
            credentials.put("license_id", connectionConfig.getCredentials().getLicenseId());
            credentials.put("site_id", connectionConfig.getCredentials().getSiteId());
            credentials.put("password", connectionConfig.getCredentials().getPassword());
            credentials.put("terminal_id", connectionConfig.getCredentials().getDeviceId());
        } else if (gateway == GatewayType.TRANSIT) {
            credentials.put("merchant_id", connectionConfig.getCredentials().getMerchantId());
            credentials.put("user_name", connectionConfig.getCredentials().getUsername());
            credentials.put("password", connectionConfig.getCredentials().getPassword());
            credentials.put("device_id", connectionConfig.getCredentials().getDeviceId());
            credentials.put("developer_id", connectionConfig.getCredentials().getDeveloperId());
            credentials.put("transaction_key", connectionConfig.getCredentials().getTransactionKey());
        } else {
            throw new Exception("Invalid gateway type: " + gateway);
        }

        gatewayConfig = new GatewayConfiguration();
        gatewayConfig.setGatewayType(connectionConfig.getGateway());
        gatewayConfig.setCredentials(credentials);
        gatewayConfig.setLibraryLogger(GPLibraryLogManager.getLibraryLogger());

        SafDatabaseConfig safDatabaseConfig = new SafDatabaseConfig(connectionConfig.isSafEnabled(), connectionConfig.getSafExpirationInDays(), TimeUnit.DAYS);
        databaseConfig = new DatabaseConfig(applicationContext, "puck1000DB", null, safDatabaseConfig, GatewayType.PORTICO);
        safIDs = new ArrayList<>();
    }
    public boolean isConnected() {
        return isTransactionManagerConnected();
    }
    public void setDeviceListener(DeviceListener deviceListener) { this.deviceListener = deviceListener; }
    public void setSafListener(SafListener safListener) {
        this.safListener = safListener;
    }
    public void setTransactionListener(TransactionListener transactionListener) {
        this.transactionListener = transactionListener;
    }

    public void setUpdateTerminalListener(UpdateTerminalListener updateTerminalListener) {
        this.updateTerminalListener = updateTerminalListener;
    }

    /**
     * Plays a short audible tone on the terminal as a simple alert.
     * This method is intended for lightweight user feedback, such as a quick confirmation beep.
     * It only triggers the terminal's built-in speaker and does not provide advanced audio
     * capabilities.
     *
     * @param frequencyHz the tone frequency in hertz to play for each beep
     * @param durationMs  the length of each beep in milliseconds
     * @param repeatCount the number of times to repeat the beep sequence
     * @param intervalMs  the pause in milliseconds between repeated beeps
     */
    public void beep(int frequencyHz, int durationMs, int repeatCount, int intervalMs) {
        Credit.beep(frequencyHz, durationMs, repeatCount, intervalMs);
    }

    /**
     * @return the shared transaction manager, creating it on first use.
     */
    private TransManager transactionManager() {
        if (transactionManager == null) {
            transactionManager = TransManager.getInstance();
        }
        return transactionManager;
    }

    protected boolean isTransactionManagerConnected() {
        return transactionManager().isConnected();
    }

    @Override
    public boolean isForcedSafEnabled() {
        return transactionManager().isForceSafEnabled();
    }

    /**
     * Forces transactions to be stored offline (SAF) even when the device has connectivity.
     * This has no effect unless SAF is enabled through the {@code DatabaseConfig}.
     *
     * @param forcedSaf True to store transactions offline, false to process them online.
     */
    @Override
    public void setForcedSafEnabled(boolean forcedSaf) {
        transactionManager().setForceSafEnabled(forcedSaf);
    }

    public void initialize() {
        if(connectionConfig.getConnectionMode().equals(ConnectionMode.USB)) {
            if (applicationContext == null) {
                Log.e("UsbComm", "applicationContext is null");
                return;
            }
            registerUsbReceiverIfNeeded();
            if (requestUsbPermissionIfNeeded()) {
                return;
            }
        }
        startConnect();
    }

    private void registerUsbReceiverIfNeeded() {
        if (applicationContext == null) {
            Log.e("UsbComm", "applicationContext is null");
            return;
        }
        if (usbReceiverRegistered) {
            return;
        }
        usbReceiver = new UsbReceiver();
        usbReceiver.setConnectionListener(new UsbPermissionConnectionListener());

        IntentFilter usbFilter = new IntentFilter();
        usbFilter.addAction(UsbReceiver.USB_DEVICE_ATTACHED);
        usbFilter.addAction(UsbReceiver.USB_DEVICE_DETACHED);
        usbFilter.addAction(UsbReceiver.ACTION_USB_PERMISSION);
        if (Build.VERSION.SDK_INT >= 33) {
            try {
                Context.class
                        .getMethod("registerReceiver", android.content.BroadcastReceiver.class, IntentFilter.class, int.class)
                        .invoke(applicationContext, usbReceiver, usbFilter, 4 /* Context.RECEIVER_NOT_EXPORTED */);
            } catch (ReflectiveOperationException ex) {
                throw new IllegalStateException("Unable to register USB receiver with receiver flags on API 33+", ex);
            }
        } else {
            applicationContext.registerReceiver(usbReceiver, usbFilter);
        }
        usbReceiverRegistered = true;
    }

    private boolean requestUsbPermissionIfNeeded() {
        if (applicationContext == null) {
            Log.e("UsbComm", "applicationContext is null");
            return false;
        }
        UsbManager usbManager = (UsbManager) applicationContext.getSystemService(Context.USB_SERVICE);
        if (usbManager == null) {
            Log.e("UsbComm", "UsbManager is null");
            return false;
        }

        HashMap<String, UsbDevice> deviceList = usbManager.getDeviceList();
        if (deviceList.isEmpty()) {
            Timber.d("No USB devices found");
            return false;
        }

        boolean permissionRequested = false;
        // Request permission only for devices that still need it
        for (UsbDevice device : deviceList.values()) {
            if (usbManager.hasPermission(device)) {
                continue;
            }
            Intent intent = new Intent(UsbReceiver.ACTION_USB_PERMISSION);
            intent.setPackage(applicationContext.getPackageName());
            Timber.d("Requesting USB permission for device: %s", device.getDeviceName());
            int pendingIntentFlags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                pendingIntentFlags |= PendingIntent.FLAG_MUTABLE;
            } else {
                pendingIntentFlags |= PendingIntent.FLAG_IMMUTABLE;
            }
            PendingIntent permissionIntent = PendingIntent.getBroadcast(
                    applicationContext,
                    0, intent,
                    pendingIntentFlags
            );
            usbManager.requestPermission(device, permissionIntent);
            permissionRequested = true;
        }
        return permissionRequested;
    }

    protected void initializeTransactionManager() {
        try {
            transactionManager.setProvisionalTransactionEnabled(provisionalTransactionEnabled);
            transactionManager.setPuckMutualAuthConfig(mutualAuthEnabled, mutualAuthCertUrl);
            transactionManager.initialize(
                    applicationContext,
                    terminalConfig,
                    transactionConfig,
                    gatewayConfig,
                    databaseConfig
            );
        } catch (InitializationException ex) {
            Timber.d(Arrays.toString(ex.getStackTrace()));
        }
    }

    private void startConnect() {
        Timber.d("startConnect() called.");
        if (isTransactionManagerConnected()) {
            return;
        }

        initializeTransactionManager();
        if (transactionManager.isInitialized()) {
            Timber.d("TransactionManager isInitialized() called");
            transactionManager.connect(new ConnectionListenerImpl());
            transactionManager.updateTransactionListener(new TransactionListenerImpl());
        }
    }

    public void disconnect() {
        if(isTransactionManagerConnected()) {
            transactionManager.disconnect();
            dispose();
        }
    }

    @Override
    public void doTransaction(TransactionRequest transactionRequest) {
        Timber.tag(TAG).d("doTransaction() called");
        if (!transactionManager().isInitialized()) {
            initializeTransactionManager();
        }
        transactionManager.startTransaction(transactionRequest, new TransactionListenerImpl(), new SafListenerImpl());
    }

    @Override
    public void cancelTransaction() {
        transactionManager().cancel();
    }

    @Override
    public void cancelSAFUpload() {
        transactionManager.cancelUploadSaf();
    }

    @Override
    public void uploadSAF() {
        transactionManager().processAllSafTransactions(new SafListenerImpl());
    }

    @Override
    public void acknowledgeSAFTransaction(String uniqueSafId) {
        if (transactionManager != null) {
            transactionManager.acknowledgeSAFTransaction(uniqueSafId);
        }
    }

    public void sendCardholderInteractionResult(com.globalpayments.library.terminals.entities.CardholderInteractionResult cardholderInteractionResult) {
        if (isTransactionManagerConnected()) {
            Log.d(TAG, "sendCardholderInteractionResult: "+cardholderInteractionResult.getCardholderInteractionType());
            transactionManager.sendCardholderInteractionResult(ConvertUtil.MapCardHolderInteractionResult(cardholderInteractionResult));
        }
    }

    public void resumePolling() {
        if (isTransactionManagerConnected()) {
            transactionManager.resumePuckPolling();
        }
    }
    public void continueAfterRemoveCardPrompt() {
        if (isTransactionManagerConnected()) {
            transactionManager.continueAfterRemoveCardPrompt();
        }
    }

    public void setSurchargeEnabled(boolean checked) {
        connectionConfig.setSurchargeEnabled(checked);
        LibraryConfigHelper.setSurchargeEnabled(checked);
    }

    public void setDebugMode(boolean isDebugMode) {
        connectionConfig.setEnvironment(isDebugMode ? Environment.TEST : Environment.PRODUCTION);
        LibraryConfigHelper.setDebugMode(isDebugMode);
    }

    /** Implementations **/
    protected class ConnectionListenerImpl implements ConnectionListener {

        @Override
        public void onConnected(TerminalInfo terminalInfo) {
            if(deviceListener != null) {
                deviceListener.onConnected(ConvertUtil.MapTerminalInfo(terminalInfo));
                if (gatewayConfig.getGatewayType() == GatewayType.PORTICO) {
                    Credit.setTxnHostCfg(Host.PORTICO, "0001");
                    Timber.tag(TAG).d("Host - %s Key Index - %s", Host.PORTICO, "0001");
                } else if (gatewayConfig.getGatewayType() == GatewayType.TRANSIT) {
                    Credit.setTxnHostCfg(Host.TSYS_TRANSIT, "0007");
                    Timber.tag(TAG).d("Host - %s Key Index - %s", Host.TSYS_TRANSIT, "0007");
                }
            }
        }

        @Override
        public void onDisconnected() {
            if(deviceListener != null) {
                deviceListener.onDisconnected();
            }
        }

        @Override
        public void onError(Error error) {
            if(deviceListener != null) {
                java.lang.Error err = new java.lang.Error(error.getMessage());
                com.globalpayments.library.terminals.enums.ErrorType errorType = ConvertUtil.MapErrorType(error.getType());
                deviceListener.onError(err, errorType);
            }
        }

    }

    protected class UpdateTerminalListenerImpl implements com.tsys.payments.library.terminal.UpdateTerminalListener {

        @Override
        public void onProgress(@Nullable Double completionPercentage, @Nullable String progressMessage) {
            if (updateTerminalListener != null) {
                updateTerminalListener.onProgress(completionPercentage, progressMessage);
            }
        }

        @Override
        public void onTerminalUpdateSuccess() {
            if (updateTerminalListener != null) {
                updateTerminalListener.onTerminalUpdateSuccess();
            }
        }

        @Override
        public void onTerminalUpdateError(Error error) {
            if (updateTerminalListener != null) {
                java.lang.Error err = new java.lang.Error(error.getMessage());
                updateTerminalListener.onTerminalUpdateError(err);
            }
        }
    }




    public void updateTerminal(@NonNull com.globalpayments.library.terminals.enums.TerminalUpdateType terminalUpdateType,
                               @Nullable String version) {
        if (!transactionManager.isInitialized()) {
            Log.e(TAG, "TransactionManager not initialized, please connect to device first.");
            return;
        }
        if (updateTerminalListener == null) {
            Log.e(TAG, "UpdateTerminalListener is null, please set a valid listener.");
            return;
        }

        com.tsys.payments.library.enums.TerminalUpdateType updateType =
                com.tsys.payments.library.enums.TerminalUpdateType.FIRMWARE;
        if (terminalUpdateType == com.globalpayments.library.terminals.enums.TerminalUpdateType.CONFIG) {
            updateType = com.tsys.payments.library.enums.TerminalUpdateType.KERNEL;
        } else if (terminalUpdateType == com.globalpayments.library.terminals.enums.TerminalUpdateType.RKI) {
            updateType = com.tsys.payments.library.enums.TerminalUpdateType.RKI;
        }

        transactionManager.updateTerminal(updateType, null, version, new UpdateTerminalListenerImpl());
    }


    protected class UsbPermissionConnectionListener implements ConnectionListener {
        @Override
        public void onConnected(TerminalInfo terminalInfo) {
            startConnect();
        }

        @Override
        public void onDisconnected() {
            if (deviceListener != null) {
                deviceListener.onDisconnected();
            }
        }

        @Override
        public void onError(Error error) {
            if (deviceListener != null) {
                java.lang.Error err = new java.lang.Error(error.getMessage());
                com.globalpayments.library.terminals.enums.ErrorType errorType = ConvertUtil.MapErrorType(error.getType());
                deviceListener.onError(err, errorType);
            }
        }
    }

    protected class TransactionListenerImpl implements com.tsys.payments.library.transaction.TransactionListener {
        @Override
        public void onStatusUpdate(TransactionStatus transactionStatus) {
            if(Puck1000Device.this.transactionListener != null) {
                if (transactionManager != null && transactionManager.isCancelRequested()
                        && transactionStatus == TransactionStatus.PROCESSING) {
                    // A cancellation is in flight, surface it as a dedicated status so integrators
                    // can stop offering a cancel option.
                    Puck1000Device.this.transactionListener.onStatusUpdate(
                            com.globalpayments.library.terminals.enums.TransactionStatus.CANCELLING);
                    return;
                }
                Puck1000Device.this.transactionListener.onStatusUpdate(com.globalpayments.library.terminals.enums.TransactionStatus.fromVitalSdk(transactionStatus));
            }
        }

        @Override
        public void onCardholderInteractionRequested(CardholderInteractionRequest request) {
            Timber.tag(TAG).d("onCardholderInteractionRequested: request = %s", request.getCardholderInteractionType()!=null ? request.getCardholderInteractionType().name() : "null");
            if(Puck1000Device.this.transactionListener != null) {

                boolean interactionHandled = transactionListener.onCardholderInteractionRequested(
                        ConvertUtil.MapCardHolderInteractionRequest(request)
                );
                Timber.tag(TAG).d("onCardholderInteractionRequested: interactionHandled = %s", interactionHandled);
                 if (!interactionHandled) {
                     com.globalpayments.library.terminals.entities.CardholderInteractionResult result;
                    switch (request.getCardholderInteractionType()) {
                        case EMV_APPLICATION_SELECTION:
                            result = new com.globalpayments.library.terminals.entities.CardholderInteractionResult(
                                    CardholderInteractionType.EMV_APPLICATION_SELECTION
                            );
                            Timber.e("EMV application selection was not handled by client application, cancelling transaction.");
                            result.setSelectedAidIndex(-1);
                            sendCardholderInteractionResult(result);
                            break;
                        case SURCHARGE_REQUESTED:
                            result = new com.globalpayments.library.terminals.entities.CardholderInteractionResult(
                                    CardholderInteractionType.CARDHOLDER_SURCHARGE_CONFIRMATION
                            );
                            result.setFinalAmountConfirmed(false);
                            sendCardholderInteractionResult(result);
                            Timber.e("Surcharge confirmation was not handled by client application, cancelling transaction. ");
                            break;
                        case FINAL_AMOUNT_CONFIRMATION:
                            result = new com.globalpayments.library.terminals.entities.CardholderInteractionResult(
                                    request.getCardholderInteractionType()
                            );
                            result.setFinalAmountConfirmed(true);
                            sendCardholderInteractionResult(result);
                            break;
                        case REQUEST_PROCEED_HOST_PROCESSING:
                            result = new com.globalpayments.library.terminals.entities.CardholderInteractionResult(
                                    request.getCardholderInteractionType()
                            );
                            result.setContinueHostProcessingConfirmed(false);
                            sendCardholderInteractionResult(result);
                            Timber.e("CDCVM confirmation was not handled by client application, cancelling transaction.");
                            break;
                        default:
                            break;
                    }
                }else{
                    Timber.d("Cardholder interaction handled by client application");
                }
            }
        }

        @Override
        public void onTransactionComplete(TransactionResponse response) {
            Timber.d("onTransactionComplete - %s", response);
            Timber.d("onTransactionComplete id: %s", response.getPosReferenceNumber());
            if (response.getTransactionResult() == TransactionResultType.SAF) {
                Puck1000Device.this.safIDs.add(Long.valueOf(response.getPosReferenceNumber()));
            }
            if (Puck1000Device.this.transactionListener != null) {
                Puck1000Device.this.isSignatureRequired = transactionManager == null ? null : transactionManager.isSignatureRequired();
                TerminalResponse terminalResponse = TerminalResponse.fromTransactionResponse(response);
                terminalResponse.setSignatureStatus(Puck1000Device.this.isSignatureRequired == null ? null : String.valueOf(Puck1000Device.this.isSignatureRequired));
                Puck1000Device.this.transactionListener.onTransactionComplete(terminalResponse);
            }
        }

        @Override
        public void onError(Error error) {
            if(Puck1000Device.this.transactionListener != null) {
                java.lang.Error err = new java.lang.Error(error.getMessage());
                com.globalpayments.library.terminals.enums.ErrorType errorType = ConvertUtil.MapErrorType(error.getType());
                Puck1000Device.this.transactionListener.onError(err, errorType);
            }
        }
    }

    protected class SafListenerImpl implements com.tsys.payments.library.db.SafListener {
        @Override
        public void onProcessingComplete(List<TransactionResponse> responses) {
            GPLibraryLogManager.emit(
                    GPLibraryLogLevel.DEBUG,
                    GPLibraryLogType.TRANSACTION,
                    TAG,
                    "SAF processing complete",
                    "Count: " + responses.size()
            );
            for (TransactionResponse transactionResponse : responses) {
                GPLibraryLogManager.emit(
                        GPLibraryLogLevel.DEBUG,
                        GPLibraryLogType.TRANSACTION,
                        TAG,
                        "SAF transaction response",
                        String.valueOf(transactionResponse)
                );
            }
            if (safListener != null) {
                safListener.onProcessingComplete(responses);
            }
        }

        @Override
        public void onAllSafTransactionsRetrieved(List<SafTransaction> obfuscatedSafTransactions) {
            GPLibraryLogManager.emit(
                    GPLibraryLogLevel.DEBUG,
                    GPLibraryLogType.TRANSACTION,
                    TAG,
                    "All SAF transactions retrieved",
                    "Count: " + obfuscatedSafTransactions.size()
            );
            if (safListener != null) {
                safListener.onAllSafTransactionsRetrieved(obfuscatedSafTransactions);
            }
        }

        @Override
        public void onError(Error error) {
            GPLibraryLogManager.emit(
                    GPLibraryLogLevel.ERROR,
                    GPLibraryLogType.TRANSACTION,
                    TAG, "SAF error",
                    error.getMessage()
            );
            if (safListener != null) {
                safListener.onError(new java.lang.Error(error.getMessage()));
            }
        }

        @Override
        public void onTransactionStored(String id, int totalCount, BigDecimal totalAmount) {
            GPLibraryLogManager.emit(
                    GPLibraryLogLevel.DEBUG,
                    GPLibraryLogType.TRANSACTION,
                    TAG,
                    "Transaction stored",
                    "id=" + id + ", count=" + totalCount + ", amount=" + totalAmount
            );
            if (safListener != null) {
                safListener.onTransactionStored(id, totalCount, totalAmount);
            }
        }

        @Override
        public void onStoredTransactionComplete(String id, TransactionResponse transactionResponse) {
            GPLibraryLogManager.emit(
                    GPLibraryLogLevel.DEBUG,
                    GPLibraryLogType.TRANSACTION,
                    TAG,
                    "Stored transaction complete",
                    "id=" + id + ", response=" + transactionResponse
            );
            if (safListener != null) {
                safListener.onStoredTransactionComplete(id, transactionResponse);
            }
        }
    };

    // TODO: Make this as a contract through an interface to be implemented in other device classes as well.
    public void dispose() {
        if (usbReceiverRegistered && applicationContext != null && usbReceiver != null) {
            try {
                applicationContext.unregisterReceiver(usbReceiver);
            } catch (IllegalArgumentException ex) {
                GPLibraryLogManager.emit(
                        GPLibraryLogLevel.DEBUG,
                        GPLibraryLogType.TRANSACTION,
                        TAG,
                        "USB receiver already unregistered.",
                        ""
                );
            }
            usbReceiverRegistered = false;
            usbReceiver = null;
        }
        if(transactionManager != null) {
            transactionManager.dispose();
            transactionManager = null;
        }
        deviceListener = null;
        safListener = null;
        transactionListener = null;
    }
}
