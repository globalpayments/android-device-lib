package com.globalpayments.library.terminals.entities;

import static com.globalpayments.library.utilities.NetworkUtils.isNetworkUnavailable;

import android.content.Context;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.global.api.terminals.abstractions.IDisposable;
import com.globalpayments.library.terminals.puck1000.Puck1000Controller;
import com.tsys.payments.host.portico.PorticoGatewayControllerFactory;
import com.tsys.payments.host.transit.TransitGatewayControllerFactory;
import com.tsys.payments.library.connection.ConnectionListener;
import com.tsys.payments.library.db.DatabaseConfig;
import com.tsys.payments.library.db.DatabaseController;
import com.tsys.payments.library.db.DatabaseControllerFactory;
import com.tsys.payments.library.db.SafListener;
import com.tsys.payments.library.db.entity.SafTransaction;
import com.tsys.payments.library.domain.*;
import com.tsys.payments.library.domain.CardholderInteractionRequest;
import com.tsys.payments.library.domain.CardholderInteractionResult;
import com.tsys.payments.library.domain.TerminalInfo;
import com.tsys.payments.library.enums.*;
import com.tsys.payments.library.exceptions.InitializationException;
import com.tsys.payments.library.gateway.GatewayController;
import com.tsys.payments.library.gateway.GatewayListener;
import com.tsys.payments.library.gateway.domain.GatewayRequest;
import com.tsys.payments.library.gateway.domain.GatewayResponse;
import com.tsys.payments.library.gateway.domain.SurchargeLookupResponse;
import com.tsys.payments.library.gateway.enums.GatewayAction;
import com.tsys.payments.library.gateway.enums.GatewayType;
import com.tsys.payments.library.terminal.*;
import com.tsys.payments.library.terminal.domain.TerminalInteractionRequest;
import com.tsys.payments.library.terminal.domain.TerminalInteractionResult;
import com.tsys.payments.library.terminal.domain.TerminalRequest;
import com.tsys.payments.library.terminal.domain.TerminalResponse;
import com.tsys.payments.library.terminal.enums.TerminalAction;
import com.tsys.payments.library.terminal.enums.TerminalDecisionType;
import com.tsys.payments.library.terminal.enums.TerminalInteractionType;
import com.tsys.payments.library.terminal.enums.TerminalStatus;
import com.tsys.payments.library.transaction.ITransactionManager;
import com.tsys.payments.library.transaction.TransactionListener;
import com.tsys.payments.library.exceptions.Error;
import com.tsys.payments.library.utils.CreditCardHelper;
import com.tsys.payments.library.utils.LibraryConfigHelper;
import com.tsys.payments.library.utils.ReceiptHelper;
import com.tsys.payments.transaction.BuildConfig;
import com.tsys.payments.transaction.SurchargeCalculation;

import org.jetbrains.annotations.NotNull;

import timber.log.Timber;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

public class TransManager implements ITransactionManager, IDisposable {
    private static final String TAG = TransManager.class.getSimpleName();
    private static boolean initialized;
    private static TransManager instance;
    private CardholderInteractionResult.HostProcessingAdditionalData additionalHostProcessingData;
    private final AtomicBoolean cancellable = new AtomicBoolean(true);
    private final AtomicBoolean cancelRequested = new AtomicBoolean(false);
    /** Serializes a cancellation request against the arming of a gateway dispatch. */
    private final Object cancelLock = new Object();
    /** True between the start of a transaction and the delivery of its final response. */
    private boolean transactionActive;
    /** True while a reversal/void started to undo an already authorized transaction is pending. */
    private volatile boolean postAuthReversalPending;
    private CardData cardData;
    private boolean connected;
    private final AtomicBoolean connectingTerminal = new AtomicBoolean(false);
    private UsbConnCallbackMarshaller connectionCallback;
    private Context context;
    private DatabaseController databaseController;
    private long finalTransactionTotal;
    private GatewayRequest followUpGatewayRequest;
    private boolean forceSafEnabled;
    private GatewayConfiguration gatewayConfig;
    private GatewayController gatewayController;
    private GatewayResponse gatewayResponse;
    private GatewayRequest gatewayRequest;
    private GatewayRequest initialGatewayRequest;
    private GatewayAction lastTransactionAction;
    private boolean networkUnavailable;
    private SafCallbackMarshaller safCallback;
    private boolean safEnabled;
    private String surchargeEligibility;
    private Map<TransactionType, String> transactionResponseMap = new HashMap<>();
    private Map<TransactionType, String> transactionResponseCodeMap = new HashMap<>();
    private TerminalConfiguration terminalConfig;
    private TerminalController terminalController;
    private TerminalInfo terminalInfo;
    private TransCallbackMarshaller transCallback;
    private TransactionConfiguration transactionConfig;
    private TransactionResponse transactionResponseForAdjustment;
    private volatile TransactionRequest transactionRequest;
    // Use this only for Puck1000 Device
    private boolean provisionalTransactionEnabled;
    // Mutual Authentication (Puck1000 only)
    private boolean puckMutualAuthEnabled;
    private String puckMutualAuthCertUrl;
    private SafTransManager safTransactionManager;
    private SurchargeCalculation surchargeCalculation;

    private TransManager() {
    }

    public static TransManager getInstance() {
        if (instance == null) {
            Class<TransManager> clazz = TransManager.class;
            synchronized (TransManager.class) {
                if (instance == null) {
                    instance = new TransManager();
                }
            }
        }

        return instance;
    }

    public Boolean isSignatureRequired() {
        if (!(terminalController instanceof Puck1000Controller)) {
            return null;
        }
        return ((Puck1000Controller) terminalController).isSignatureRequired();
    }

    /**
     * Check if force SAF (offline) processing is currently enabled.
     *
     * @return True if force SAF is enabled, false otherwise.
     */
    public boolean isForceSafEnabled() {
        return forceSafEnabled;
    }

    /**
     * Forces transactions to be stored offline even when the device has connectivity.
     * This has no effect unless SAF is enabled through the {@code DatabaseConfig}.
     *
     * @param forceSafEnabled True to store transactions offline, false to process them online.
     */
    public void setForceSafEnabled(boolean forceSafEnabled) {
        Timber.d("setForceSafEnabled() called with forceSafEnabled=[%s], safEnabled=[%s]", forceSafEnabled, safEnabled);
        this.forceSafEnabled = forceSafEnabled;
    }

    /**
     * PROPERTIES
     **/
    @Override
    public void getAvailableTerminalVersions(@NonNull TerminalUpdateType terminalUpdateType, @Nullable Map<String, String> credentials, @NotNull AvailableTerminalVersionsListener availableTerminalVersionsListener) {

    }

    private CommercialCardDataField[] getNecessaryCommercialFieldsToRequest() {
        Timber.d("getNecessaryCommercialFieldsToRequest() called");
        List<CommercialCardDataField> fieldList = new ArrayList<>();
        fieldList.add(CommercialCardDataField.PURCHASE_ORDER_NUMBER);
        if (CardType.AMERICAN_EXPRESS == gatewayResponse.getCardType()) {
            fieldList.add(CommercialCardDataField.SUPPLIER_REF_NUMBER);
            fieldList.add(CommercialCardDataField.CUSTOMER_REF_ID);
            fieldList.add(CommercialCardDataField.SHIP_TO_ZIP);
            fieldList.add(CommercialCardDataField.CHARGE_DESCRIPTOR);
        }

        CommercialCardDataField[] finalFields = new CommercialCardDataField[fieldList.size()];
        finalFields = fieldList.toArray(finalFields);
        Timber.d("getNecessaryCommercialFieldsToRequest() return called with fields: [%s]", Arrays.toString(finalFields));

        return finalFields;
    }

    private boolean isCommercialCardSupported(@Nullable CardType cardType) {
        return CardType.VISA == cardType || CardType.MASTERCARD == cardType || CardType.AMERICAN_EXPRESS == cardType;
    }

    @Override
    public boolean isConnected() {
        return connected;
    }

    @Override
    public void firmwareLog(boolean shouldStart) {
        // TODO: This will be picked up with the edits to the actual vital SDK transaction manager
    }

    public GatewayAction getGatewayProcessingAction() {
        Timber.d("getGatewayProcessingAction() called.");
        if (transactionRequest == null) {
            Timber.d("Transaction request is null.");
            return null;
        } else if (transactionRequest.getTransactionType() == null) {
            if (transCallback != null) {
                transCallback.callbackOnError(new Error() {
                    public ErrorType getType() {
                        return ErrorType.INVALID_DATA;
                    }

                    public String getMessage() {
                        return "Transaction request is missing a transaction type.";
                    }
                });
            }

            return null;
        } else {
            switch (transactionRequest.getTransactionType()) {
                case SALE:
                    return GatewayAction.SALE;
                case AUTH:
                    return GatewayAction.AUTH;
                case VERIFY:
                    return GatewayAction.VERIFY;
                case CAPTURE:
                    return GatewayAction.CAPTURE;
                case FORCED_AUTH:
                    return GatewayAction.FORCE_AUTH;
                case VOID:
                    return GatewayAction.VOID;
                case REFUND:
                    return GatewayAction.REFUND;
                case PARTIAL_REFUND:
                    return GatewayAction.PARTIAL_REFUND;
                case TIP_ADJUST:
                    return GatewayAction.TIP_ADJUST;
                case TOKENIZE:
                    return GatewayAction.TOKENIZE_CARD;
                case BATCH_CLOSE:
                default:
                    if (transCallback != null) {
                        final String svaError = transactionRequest.getTransactionType() == TransactionType.SVA ? "Unsupported card type for this transaction" : null;
                        transCallback.callbackOnError(new Error() {
                            public ErrorType getType() {
                                return ErrorType.INVALID_DATA;
                            }

                            public String getMessage() {
                                return TransManager.this.transactionRequest == null && svaError != null ? svaError : "Unsupported transaction type for host processing [" + TransManager.this.transactionRequest.getTransactionType() + "]";
                            }
                        });
                    }

                    return null;
            }
        }
    }

    @Override
    public boolean isInitialized() {
        return initialized;
    }


    @Nullable
    private Receipt getReceipt(@NonNull TransactionRequest transactionRequest, @NonNull CardData cardData, @Nullable GatewayResponse gatewayResponse) {
        Timber.d("getReceipt() called with: transactionRequest=[%s], cardData=[%s], gatewayResponse=[%s]", transactionRequest, cardData, gatewayResponse);
        CardDataSourceType dataSourceType = cardData.getCardDataSource();
        if (dataSourceType != CardDataSourceType.SCR && dataSourceType != CardDataSourceType.CONTACTLESS_MSR && dataSourceType != CardDataSourceType.CONTACTLESS_EMV) {
            if (dataSourceType != CardDataSourceType.KEYED && dataSourceType != CardDataSourceType.MSR && dataSourceType != CardDataSourceType.FALLBACK) {
                Timber.w("Unable to build receipt for: CardDataSourceType=[%s]", dataSourceType);
                return null;
            } else {
                return ReceiptHelper.buildMsrReceipt(transactionRequest, initialGatewayRequest, gatewayResponse, cardData);
            }
        } else {
            return ReceiptHelper.buildEmvReceipt(transactionRequest, initialGatewayRequest, gatewayResponse, cardData);
        }
    }

    @Nullable
    @Override
    public TerminalType[] getSupportedTerminalTypes(@Nullable GatewayType gatewayType) {
        return new TerminalType[0];
    }

    @Nullable
    @Override
    public ConnectionType[] getSupportedTerminalConnectionTypes(TerminalType terminalType) {
        return new ConnectionType[]{ConnectionType.USB};
    }

    private boolean isTransactionAdjustmentRequired() {
        Timber.d("isTransactionAdjustmentRequired() called.");
        if (gatewayResponse == null) {
            Timber.w("Gateway response is null. Not able to request specific card type commercial fields.");
            return false;
        } else {
            CardType cardType = gatewayResponse.getCardType();
            if (cardType == null) {
                Timber.w("cardType is null. Unable to request commercial fields.");
                return false;
            } else {
                if (initialGatewayRequest != null) {
                    if (initialGatewayRequest.getCommercialCardData() != null && (GatewayAction.SALE == gatewayResponse.getGatewayAction() || GatewayAction.AUTH == gatewayResponse.getGatewayAction())) {
                        return false;
                    }

                    Timber.w("Initial gateway request is null.");
                }

                boolean transactionAdjustmentRequired = false;
                if ((GatewayAction.SALE == gatewayResponse.getGatewayAction() ||
                        GatewayAction.AUTH == gatewayResponse.getGatewayAction()) &&
                        gatewayResponse.getCommercialCard() != null &&
                        (CommercialCard.BUSINESS == gatewayResponse.getCommercialCard() ||
                                CommercialCard.CORPORATE == gatewayResponse.getCommercialCard() ||
                                CommercialCard.PURCHASE == gatewayResponse.getCommercialCard())) {
                    if (isCommercialCardSupported(gatewayResponse.getCardType())) {
                        transactionAdjustmentRequired = true;
                    } else {
                        Timber.w("Commercial card: %s detected but no fields are requested according to requirements.", gatewayResponse.getCardType());
                    }
                }

                Timber.d("isTransactionAdjustmentRequired() called with return value: %s", new Object[]{transactionAdjustmentRequired});
                return transactionAdjustmentRequired;
            }
        }
    }

    private boolean isTransactionResultPostAuthReversal(TransactionType originalTransactionType) {
        return lastTransactionAction == GatewayAction.VOID && (originalTransactionType == TransactionType.AUTH || originalTransactionType == TransactionType.SALE || originalTransactionType == TransactionType.FORCED_AUTH);
    }

    private boolean isTransactionReversible(@Nullable TransactionRequest transactionRequest, @Nullable GatewayResponse gatewayResponse) {
        Timber.d("isTransactionReversible() called.");
        if (transactionRequest == null) {
            Timber.e("transactionRequest is null.");
            return false;
        } else if (gatewayResponse == null) {
            Timber.e("gatewayResponse is null.");
            return false;
        } else {
            return !TextUtils.isEmpty(gatewayResponse.getGatewayTransactionId()) || !TextUtils.isEmpty(transactionRequest.getPosReferenceNumber()) || !TextUtils.isEmpty(transactionRequest.getGatewayTransactionId());
        }
    }

    private long getTransactionTotal() {
        Timber.d("getTransactionTotal() called.");
        if (transactionRequest != null) {
            if (transactionRequest.getTotal() == null) {
                Timber.w("Transaction total is null. Returning 0");
                return 0L;
            } else {
                return transactionRequest.getTotal();
            }
        } else {
            Timber.w("mTransactionRequest is null");
            return 0L;
        }
    }

    @Nullable
    private TransactionType getTransactionType(GatewayAction action) {
        if (action == null) {
            return null;
        } else {
            switch (action) {
                case SALE:
                    return TransactionType.SALE;
                case TIP_ADJUST:
                    return TransactionType.TIP_ADJUST;
                case REFUND:
                    return TransactionType.REFUND;
                case PARTIAL_REFUND:
                    return TransactionType.PARTIAL_REFUND;
                case REVERSAL:
                    return TransactionType.REVERSAL;
                case AUTH:
                    return TransactionType.AUTH;
                case INCREMENT:
                    return TransactionType.INCREMENT;
                case VERIFY:
                case TRANSACTION_ADJUSTMENT:
                case CAPTURE:
                    return TransactionType.CAPTURE;
                case VOID:
                    return TransactionType.VOID;
                case FORCE_AUTH:
                    return TransactionType.FORCED_AUTH;
                case BATCH_CLOSE:
                    return TransactionType.BATCH_CLOSE;
                case TOKENIZE_CARD:
                    return TransactionType.TOKENIZE;
                case SURCHARGE:
                    return TransactionType.SURCHARGE;
                default:
                    return null;
            }
        }
    }


    /**
     * Initializations
     **/
    private void initDatabaseController(DatabaseConfig databaseConfig) throws InitializationException {

        safEnabled = databaseConfig.getSafDatabaseConfig().isSafEnabled();
        DatabaseControllerFactory[] databaseFactories = BuildConfig.DATABASE_FACTORIES;
        if (databaseFactories.length > 0) {
            for (DatabaseControllerFactory factory : databaseFactories) {
                GatewayType[] gatewayTypes = factory.getSupportedGateways();
                if (gatewayTypes != null) {
                    for (GatewayType gatewayType : gatewayTypes) {
                        if (gatewayType == databaseConfig.getGatewayType()) {
                            databaseController = factory.create(databaseConfig);
                            return;
                        }
                    }
                }
            }
        }
    }

    private void initGatewayController(GatewayConfiguration gatewayConfiguration, TerminalConfiguration terminalConfiguration) throws InitializationException {
        Timber.d("initGatewayController()");
        try {
            if (gatewayConfiguration.getGatewayType() == GatewayType.PORTICO) {
                gatewayController = new PorticoGatewayControllerFactory().create(gatewayConfiguration, new GatewayListenerImpl());
            } else if (gatewayConfiguration.getGatewayType() == GatewayType.TRANSIT) {
                gatewayController = new TransitGatewayControllerFactory().create(gatewayConfiguration, new GatewayListenerImpl());
            } else {
                throw new InitializationException("Unsupported gateway type: " + gatewayConfiguration.getGatewayType());
            }
        } catch (Exception e) {
            throw new InitializationException(e, "Error while initializing GatewayController Controller.");
        }
    }

    private void initTerminalController(@NonNull Context context, @NonNull TerminalConfiguration terminalConfiguration) throws InitializationException {
        Timber.d("initTerminalController()");
        try {
            if (terminalConfiguration.getTerminalType() == TerminalType.CASTLES_PUCK_1000) {
                terminalController = new Puck1000Controller(context, terminalConfiguration, transactionConfig, provisionalTransactionEnabled, puckMutualAuthEnabled, puckMutualAuthCertUrl, new TerminalListenerImpl());
            } else {
                throw new InitializationException("Unsupported terminal type: " + terminalConfiguration.getTerminalType());
            }
        } catch (Exception e) {
            throw new InitializationException(e, "Error while initializing Terminal Controller.");
        }
    }

    public void initialize(@NonNull Context context, @Nullable TerminalConfiguration terminalConfig, @NonNull TransactionConfiguration transactionConfig, @NonNull GatewayConfiguration gatewayConfig, @NonNull DatabaseConfig databaseConfig) throws InitializationException {
        this.initDatabaseController(databaseConfig);
        this.initialize(context, terminalConfig, transactionConfig, gatewayConfig);
    }

    @Override
    public void initialize(@NonNull Context context, @Nullable TerminalConfiguration terminalConfig, @NonNull TransactionConfiguration transactionConfig, @NonNull GatewayConfiguration hostConfig) throws InitializationException {
        this.context = context;
        this.transactionConfig = transactionConfig;
        this.gatewayConfig = hostConfig;
        this.terminalConfig = terminalConfig;

        // Init the gateway controller
        initGatewayController(gatewayConfig, terminalConfig);

        // Init the terminal controller
        if (terminalConfig != null && !isConnected()) {
            initTerminalController(context, terminalConfig);
        }

        if (databaseController != null) {
            safTransactionManager = SafTransManager.getInstance();
            safTransactionManager.initialize(terminalConfig, gatewayConfig, databaseController);
        }

        initialized = true;
    }


    /**
     * Core Functions
     **/
    @Override
    public void cancel() {
        Timber.d("cancel() called with cancelRequested=[%s], cancellable=[%s]", cancelRequested.get(), cancellable.get());

        final boolean firstRequest;
        final boolean completeLocally;
        final boolean forwardToTerminal;
        final TerminalController controller;

        /* The decision is taken under a lock so a cancellation and a gateway dispatch can never
           both believe they own the transaction. The terminal command and the integrator
           callbacks are issued outside the lock so foreign code never runs while holding it. */
        synchronized (cancelLock) {
            firstRequest = cancelRequested.compareAndSet(false, true);
            controller = terminalController;
            if (controller == null) {
                /* Technically, the reader would have never been instantiated or connected at this
                   point, so the cancellation is completed here. */
                completeLocally = firstRequest && claimFinalResponse();
                forwardToTerminal = false;
            } else {
                completeLocally = false;
                /* Repeated cancellations are always forwarded: the terminal may have moved into a
                   state where it can only now complete the cancellation. Forwarding is idempotent.
                   Once host processing started the terminal can no longer cancel, the pending host
                   response is inspected instead so an approved transaction is reversed. */
                forwardToTerminal = cancellable.get();
            }
        }

        if (controller == null) {
            if (!completeLocally) {
                Timber.d("cancel() ignored, a cancelled response has already been broadcast.");
                return;
            }

            Timber.d("cancel() completing immediately because terminal controller is unavailable.");
            TransactionResponse.Builder builder = new TransactionResponse.Builder();
            builder.setTransactionResult(TransactionResultType.CANCELLED);
            if (transCallback != null) {
                transCallback.callbackOnTransactionResponse(builder.build());
            } else {
                Timber.d("Transaction callback marshaller is null, could not broadcast transaction response");
            }
            return;
        }

        if (firstRequest) {
            notifyCancelAccepted();
        } else {
            Timber.d("cancel() already pending, forwarding the repeated request only.");
        }

        if (!forwardToTerminal) {
            Timber.d("cancel() host processing is already in progress.");
            return;
        }

        Timber.d("cancel() forwarding cancel to terminal controller.");
        controller.cancel();
    }

    /**
     * Marks the transaction as no longer cancellable right before a gateway request is dispatched.
     *
     * @return {@link DispatchDecision#SEND} when the request has to be sent,
     * {@link DispatchDecision#CANCELLED} when a cancellation was requested first and
     * {@link DispatchDecision#ABANDONED} when the transaction already produced a final response.
     */
    private DispatchDecision beginGatewayDispatch() {
        synchronized (cancelLock) {
            if (!transactionActive) {
                /* The transaction was already completed, most likely by a cancellation that was
                   processed while this request was being built. */
                return DispatchDecision.ABANDONED;
            }

            cancellable.set(false);
            return cancelRequested.get() ? DispatchDecision.CANCELLED : DispatchDecision.SEND;
        }
    }

    /**
     * Arms a gateway dispatch and reports the outcome of the non-dispatchable cases.
     *
     * @param requestName name of the request used for logging.
     * @return true when the caller may send its gateway request.
     */
    private boolean canSendGatewayRequest(String requestName) {
        DispatchDecision decision = beginGatewayDispatch();
        if (decision == DispatchDecision.ABANDONED) {
            Timber.d("%s skipped, the transaction already completed.", requestName);
            return false;
        }

        if (decision == DispatchDecision.CANCELLED) {
            Timber.d("%s skipped because cancellation was already requested.", requestName);
            completeCancelledBeforeDispatch();
            return false;
        }

        return true;
    }

    /**
     * @return {@link TransactionResultType#CANCELLED} when a cancellation is pending, otherwise
     * the unmodified {@code result}.
     */
    private TransactionResultType cancelledOr(TransactionResultType result) {
        return cancelRequested.get() ? TransactionResultType.CANCELLED : result;
    }

    /**
     * Completes a transaction that was cancelled before its gateway request could be dispatched.
     */
    private void completeCancelledBeforeDispatch() {
        Timber.d("Gateway dispatch abandoned, completing the transaction as cancelled.");
        TransactionResponse.Builder builder = new TransactionResponse.Builder();
        if (transactionRequest != null) {
            builder.setTransactionType(transactionRequest.getTransactionType())
                    .setTenderType(transactionRequest.getTenderType());
        }
        builder.setResponseMap(transactionResponseMap)
                .setResponseCodeMap(transactionResponseCodeMap)
                .setTransactionResult(TransactionResultType.CANCELLED);
        invokeTransactionResponseCallback(builder);
    }

    /**
     * Tells the integrator that the cancellation was accepted and is being processed. This is the
     * last status update broadcast for the transaction, every later terminal status is suppressed
     * so the integrator does not re-offer a cancel option while the cancellation completes.
     */
    private void notifyCancelAccepted() {
        if (transCallback != null) {
            transCallback.callbackOnStatusUpdate(TransactionStatus.PROCESSING);
        } else {
            Timber.d("Transaction callback marshaller is null, could not broadcast status [%s]",
                    TransactionStatus.PROCESSING);
        }
    }

    /**
     * @return true when a cancellation has been requested and the transaction is being wound down.
     */
    public boolean isCancelRequested() {
        return cancelRequested.get();
    }

    @Override
    public void connect(@NonNull ConnectionListener listener) {
        Timber.d("connect() called.");
//        this.validateInitWasCalled();

        connectionCallback = new UsbConnCallbackMarshaller(listener, TransExecutors.getInstance().getMainThread());
        if (terminalController != null) {
            runOnWorkerThread(new Runnable() {
                @Override
                public void run() {
                    TransManager.this.connectingTerminal.set(true);
                    TransManager.this.terminalController.connect();
                }
            });
        } else {
            connectionCallback.callbackOnError(new Error() {
                public ErrorType getType() {
                    return ErrorType.NOT_INITIALIZED;
                }

                public String getMessage() {
                    return "Terminal not available.";
                }
            });
        }
    }

    @Override
    public void disconnect() {
        Timber.d("disconnect()");
        if (terminalController != null && connected) {
            runOnWorkerThread(new Runnable() {
                @Override
                public void run() {
                    TransManager.this.terminalController.disconnect();
                }
            });
        }
    }

    @Override
    public void getDeviceInfo(@NonNull TerminalInfoListener listener) {

    }


    /**
     * Saf Processing
     **/
    @Override
    public void processSafTransactionsByPosReferenceIds(@NotNull List<Long> safLocalIds, @NotNull SafListener safListener) {
        Timber.d("processSafTransactionsByPosReferenceIds() called with safLocalIds=[%s]", safLocalIds);
        safTransactionManager.processSafTransactionsByPosReferenceIds(safLocalIds, safListener);
    }

    @Override
    public void processAllSafTransactions(@NonNull SafListener safListener) {
        Timber.d("processAllSafTransactions() called");
        safTransactionManager.processAllSafTransactions(safListener);
    }

    @Override
    public void getAllValidSafTransactions(@NonNull SafListener safListener) {
        safCallback = new SafCallbackMarshaller(safListener, TransactionExecutors.getInstance().getMainThread());

        if (databaseController != null) {
            runOnWorkerThread(new Runnable() {
                @Override
                public void run() {
                    List<SafTransaction> safTransactionList = databaseController.getAllSafTransactionsMetadata();
                    safCallback.callbackOnSafTransactionsRetrieved(safTransactionList);
                    /* This is a read-only query, only the listener used for it is released. The
                       transaction state must not be reset, a transaction may be in flight. */
                    safCallback = null;
                }
            });
        } else {
            callbackOnSafError(ErrorType.NOT_INITIALIZED, "Saf Database not initialized");
        }
    }

    private void storeTransactionOffline(final GatewayRequest gatewayRequest) {
        Timber.d("storeTransactionOffline() called with gatewayRequest->%s", gatewayRequest);
        if (databaseController != null && gatewayRequest != null &&
                checkSafSupportedGatewayAction(initialGatewayRequest)) {
            runOnWorkerThread(new Runnable() {
                @Override
                public void run() {
                    // returning POS id so consuming app can keep separate list of SAF transactions if desired
                    long mPosTransactionId = databaseController.storeSafTransaction(
                            databaseController
                                    .convertGatewayRequestToSafTransaction(gatewayRequest));
                    Timber.d("Successfully stored transaction offline with transactionId=>%d",
                            mPosTransactionId);

                    //get the total count and total amount
                    List<SafTransaction> storedList = databaseController.getAllSafTransactions();
                    int totalCount = storedList.size();
                    BigDecimal totalAmount = new BigDecimal(0);
                    String safId = null;
                    for (int i = 0; i < storedList.size(); i++) {
                        SafTransaction safTransaction = storedList.get(i);
                        totalAmount = totalAmount.add(safTransaction.getAmount());
                        if (i == storedList.size() - 1) {
                            safId = safTransaction.getSAFUniqueId();
                        }
                    }

                    invokeSafStoredCallback(safId, totalCount, totalAmount);

                    TransactionRequest pendingRequest = transactionRequest;
                    if (pendingRequest == null) {
                        Timber.w("The transaction completed before it could be stored offline.");
                        return;
                    }

                    GatewayResponse gatewayResponse =
                            new GatewayResponse(gatewayRequest.getGatewayAction());
                    gatewayResponse.setPosReferenceId(String.valueOf(mPosTransactionId));
                    if (gatewayRequest.getCardData() != null) {
                        gatewayResponse.setCardholderName(
                                gatewayRequest.getCardData().getCardholderName());
                    }
                    pendingRequest.setSaf(true);
                    handleGenericResponse(gatewayResponse, true, safId);

                    TerminalInteractionResult result = new TerminalInteractionResult();
                    result.setInteractionType(TerminalInteractionType.HOST_PROCESSING);
                    result.setGatewayResponse(gatewayResponse);
                    if (terminalController != null) {
                        terminalController.sendTerminalInteractionResult(result);
                    }
                }
            });
        } else {
            /* The transaction cannot be stored offline. Release the transaction state so the
               integrator can start again, the error below is its final notification. */
            resetFields();
            if (transCallback == null) {
                Timber.d("Transaction callback marshaller is null, could not broadcast the offline storage error");
                return;
            }

            transCallback.callbackOnError(new Error() {
                @Override
                public ErrorType getType() {
                    return ErrorType.INVALID_DATA;
                }

                @Override
                public String getMessage() {
                    return "Invalid gateway operation specified.";
                }
            });
        }
    }

    private void invokeSafStoredCallback(String id, int totalCount, BigDecimal totalAmount) {
        if (safCallback != null) {
            safCallback.callbackOnTransactionStored(id, totalCount, totalAmount);
        }
    }

    public void acknowledgeSAFTransaction(String uniqueSAFId) {
        if (databaseController != null) {
            databaseController.deleteSafTransactionByUniqueId(uniqueSAFId);
        }
    }

    public void cancelUploadSaf() {
        safTransactionManager.cancel();
    }


    /**
     * Perform Stuff
     **/
    private void performHostProcessing(TerminalInteractionRequest request) {
        Timber.d("performHostProcessing() called with:  request = [%s]", new Object[]{request});
        GatewayAction action = getGatewayProcessingAction();
        if (action != null) {
            cardData = request.getCardData();

            // Build the request
            GatewayRequest.Builder builder = (new GatewayRequest.Builder(action))
                    .setTenderType(transactionRequest.getTenderType())
                    .setTotal(finalTransactionTotal)
                    .setTip(transactionRequest.getTip())
                    .setCardData(cardData)
                    .setTax(transactionRequest.getTax())
                    .setTaxCategory(transactionRequest.getTaxCategory())
                    .setPosReferenceNumber(transactionRequest.getPosReferenceNumber())
                    .setAddress(transactionRequest.getAddress())
                    .setAutoSubstantiation(transactionRequest.getAutoSubstantiation())
                    .setAllowDuplicates(transactionRequest.isAllowDuplicates())
                    .setGenerateToken(transactionRequest.isGenerateToken());

            // Invoice Number
            if (!TextUtils.isEmpty(transactionRequest.getInvoiceNumber())) {
                builder.setInvoiceNumber(transactionRequest.getInvoiceNumber());
            } else if (additionalHostProcessingData != null) {
                builder.setInvoiceNumber(additionalHostProcessingData.getInvoiceNumber());
            }

            // Token Request
            if (this.transactionRequest.isGenerateToken()) {
                builder.setGenerateToken(true);
            } else if (additionalHostProcessingData != null) {
                builder.setGenerateToken(additionalHostProcessingData.isGenerateToken());
            }

            // Address
            if (transactionRequest.getAddress() != null) {
                builder.setAddress(transactionRequest.getAddress());
            } else if (additionalHostProcessingData != null && !TextUtils.isEmpty(additionalHostProcessingData.getPostalCode())) {
                Address address = new Address();
                address.setPostalCode(additionalHostProcessingData.getPostalCode());
                builder.setAddress(address);
            }

            // Email
            if (!TextUtils.isEmpty(this.transactionRequest.getEmailAddress())) {
                Customer customer = new Customer();
                customer.setEmail(transactionRequest.getEmailAddress());
                builder.setCustomerInfo(customer);
            } else if (additionalHostProcessingData != null && !TextUtils.isEmpty(additionalHostProcessingData.getEmail())) {
                Customer customer = new Customer();
                customer.setEmail(additionalHostProcessingData.getEmail());
                builder.setCustomerInfo(customer);
            }

            // Signature File
            if (additionalHostProcessingData != null && !TextUtils.isEmpty(additionalHostProcessingData.getSignatureFileLocation())) {
                builder.setSignatureFilelocation(additionalHostProcessingData.getSignatureFileLocation());
            }

            // Refund
            if (action == GatewayAction.REFUND) {
                builder.setVoidReason(ReversalReason.VOIDED_BY_CUSTOMER);
            }

            // Card Data
            if (cardData != null) {
                builder.setCvmType(cardData.getCvmResult());
            }

            if (terminalConfig != null) {
                TerminalCapabilities capabilities = new TerminalCapabilities();
                Timber.d("performHostProcessing: terminalConfig: %s", terminalConfig.getTerminalType());
                capabilities.setAuthenticationCapability(terminalConfig.getAuthenticationCapability());
                capabilities.setInputCapability(terminalConfig.getCapability());
                capabilities.setOperatingEnvironment(terminalConfig.getOperatingEnvironment());
                capabilities.setOutputCapability(terminalConfig.getOutputCapability());
                terminalInfo.setTerminalType(terminalConfig.getTerminalType());
                builder.setTerminalCapabilities(capabilities);
            }

            builder.setTerminalInfo(terminalInfo);
            initialGatewayRequest = builder.build();
            lastTransactionAction = initialGatewayRequest.getGatewayAction();
            additionalHostProcessingData = null;
            networkUnavailable = isNetworkUnavailable(context);
            Timber.d("performHostProcessing() - network avail: %s", networkUnavailable);
            Timber.d("performHostProcessing() - saf enabled: %s", safEnabled);
            DispatchDecision decision = beginGatewayDispatch();
            if (decision == DispatchDecision.ABANDONED) {
                Timber.d("Host processing skipped, the transaction already completed.");
            } else if (decision == DispatchDecision.CANCELLED) {
                /* The cardholder cancelled before the request left the device. Cancel the
                   terminal so it stops waiting for the host result and reports the
                   cancellation. */
                Timber.d("Host processing skipped because cancellation was already requested.");
                if (terminalController != null) {
                    terminalController.cancel();
                } else {
                    completeCancelledBeforeDispatch();
                }
            } else if ((forceSafEnabled || networkUnavailable) && safEnabled) {
                this.storeTransactionOffline(initialGatewayRequest);
            } else {
                this.runOnWorkerThread(new Runnable() {
                    public void run() {
                        TransManager.this.gatewayController.sendRequest(TransManager.this.initialGatewayRequest);
                    }
                });
            }
        } else {
            handleMissingHostAction(request.getCardData());
        }
    }

    private void performVerify(@NonNull TransactionRequest request) {
        Timber.d("performVerify()");
        if (request.getKeyedCardData() != null) {
            // Build manual transaction request
            performManualVerify(request);
        } else {
            // Build terminal request
            final TerminalRequest terminalRequest =
                    new TerminalRequest(TerminalAction.TENDER_GOODS);
            terminalRequest.setTransactionType(request.getTransactionType());
            terminalRequest.setTotalAmount(request.getTotal() == null ? 0L : request.getTotal());
            terminalRequest.setTenderType(request.getTenderType());

            sendTerminalRequest(terminalRequest);
        }
    }

    private void performManualVerify(@NonNull TransactionRequest request) {
        Timber.d("performManualVerify()");
        final GatewayRequest.Builder builder = new GatewayRequest.Builder(GatewayAction.VERIFY);
        builder.setTotal(request.getTotal() == null ? 0L : request.getTotal())
                .setTenderType(request.getTenderType())
                .setCardData(request.getKeyedCardData())
                .setInvoiceNumber(request.getInvoiceNumber())
                .setAutoSubstantiation(request.getAutoSubstantiation())
                .setAllowDuplicates(request.isAllowDuplicates())
                .setGenerateToken(request.isGenerateToken())
                .setTerminalInfo(terminalInfo);
        cardData = request.getKeyedCardData();
        initialGatewayRequest = builder.build();
        if (gatewayController != null) {
            if (!canSendGatewayRequest("Manual verify")) {
                return;
            }

            runOnWorkerThread(new Runnable() {
                @Override
                public void run() {
                    lastTransactionAction = initialGatewayRequest.getGatewayAction();
                    gatewayController.sendRequest(initialGatewayRequest);
                }
            });
        } else {
            handleGatewayControllerNotInitialized();
        }
    }

    private void performSale(@NonNull TransactionRequest request) {
        Timber.tag(TAG).d("performSale()");
        if (request.getKeyedCardData() != null) {
            // TODO: Not doing manual for the demo
//            performManualAuthSale(request);
        } else if (!TextUtils.isEmpty(request.getToken())) {
            performAuthSaleWithToken(request);
        } else {
            Timber.tag(TAG).d("performSale: request.getKeyedCardData() is null, request.getToken() is empty");
            TerminalRequest terminalRequest = new TerminalRequest(TerminalAction.TENDER_GOODS);
            terminalRequest.setTransactionType(request.getTransactionType());
            terminalRequest.setTotalAmount(request.getTotal());
            terminalRequest.setTipAmount(request.getTip());
            terminalRequest.setCashBackAmount(request.getCashBack());
            terminalRequest.setTenderType(request.getTenderType());
            terminalRequest.setSurchargeAmount(request.getSurcharge());
            sendTerminalRequest(terminalRequest);
        }
    }

    private void performIncrementalAuth(@NonNull TransactionRequest request) {
        Timber.d("performIncrementalAuth()");
        final GatewayRequest.Builder builder = new GatewayRequest.Builder(GatewayAction.INCREMENT);
        builder.setTotal(finalTransactionTotal)
                .setHostTransactionId(request.getGatewayTransactionId())
                .setTerminalInfo(terminalInfo);

        if (!TextUtils.isEmpty(request.getPosReferenceNumber())) {
            builder.setPosReferenceNumber(request.getPosReferenceNumber());
        }

        lastTransactionAction = GatewayAction.INCREMENT;
        if (gatewayController != null) {
            initialGatewayRequest = builder.build();
            if ((forceSafEnabled || isNetworkUnavailable(context)) && safEnabled) {
                storeTransactionOffline(initialGatewayRequest);
            } else {
                runOnWorkerThread(new Runnable() {
                    @Override
                    public void run() {
                        gatewayController.sendRequest(initialGatewayRequest);
                    }
                });
            }
        } else {
            handleGatewayControllerNotInitialized();
        }

    }

    private void performCapture(@NonNull TransactionRequest request) {
        Timber.d("performCapture()");
        if (!TextUtils.isEmpty(request.getToken())) {
            performCaptureWithToken(request);
        } else {
            final GatewayRequest.Builder builder = new GatewayRequest.Builder(GatewayAction.CAPTURE);
            builder.setTotal(request.getTotal() == null ? 0L : request.getTotal())
                    .setTax(request.getTax())
                    .setTip(request.getTip())
                    .setCardHolderId(request.getCardHolderId())
                    .setAutoSubstantiation(request.getAutoSubstantiation())
                    .setAllowDuplicates(request.isAllowDuplicates())
                    .setHostTransactionId(request.getGatewayTransactionId())
                    .setTerminalInfo(terminalInfo);
            if ((forceSafEnabled || isNetworkUnavailable(context)) && safEnabled) {
                if (initialGatewayRequest != null) {
                    lastTransactionAction = GatewayAction.CAPTURE;
                    builder.setCardData(initialGatewayRequest.getCardData())
                            .setCvmType(initialGatewayRequest.getCvmResult())
                            .setTenderType(initialGatewayRequest.getTenderType());
                }
                initialGatewayRequest = builder.build();
                storeTransactionOffline(initialGatewayRequest);
            } else if (gatewayController != null) {
                runOnWorkerThread(new Runnable() {
                    @Override
                    public void run() {
                        if (initialGatewayRequest != null) {
                            lastTransactionAction = GatewayAction.CAPTURE;
                            builder.setCardData(initialGatewayRequest.getCardData())
                                    .setCvmType(initialGatewayRequest.getCvmResult())
                                    .setTenderType(initialGatewayRequest.getTenderType());
                        }
                        initialGatewayRequest = builder.build();
                        gatewayController.sendRequest(initialGatewayRequest);
                    }
                });
            } else {
                handleGatewayControllerNotInitialized();
            }
        }
    }

    private void performCaptureWithToken(@NonNull TransactionRequest request) {
        Timber.d("performCaptureWithToken()");
        final GatewayRequest.Builder builder =
                new GatewayRequest.Builder(GatewayAction.CAPTURE);
        builder.setTotal(request.getTotal() == null ? 0L : request.getTotal())
                .setTax(request.getTax())
                .setTip(request.getTip())
                .setToken(request.getToken())
                .setCardHolderId(request.getCardHolderId())
                .setHostTransactionId(request.getGatewayTransactionId())
                .setAutoSubstantiation(request.getAutoSubstantiation())
                .setAllowDuplicates(request.isAllowDuplicates())
                .setSignatureBitmap(request.getSignatureBitmap())
                .setTerminalInfo(terminalInfo);

        if ((forceSafEnabled || isNetworkUnavailable(context)) && safEnabled) {
            if (initialGatewayRequest != null) {
                lastTransactionAction = GatewayAction.CAPTURE;
                builder.setCardData(initialGatewayRequest.getCardData())
                        .setCvmType(initialGatewayRequest.getCvmResult())
                        .setTenderType(initialGatewayRequest.getTenderType());
            }
            initialGatewayRequest = builder.build();
            storeTransactionOffline(initialGatewayRequest);
        } else {
            if (gatewayController != null) {
                runOnWorkerThread(new Runnable() {
                    @Override
                    public void run() {
                        if (initialGatewayRequest != null) {
                            lastTransactionAction = GatewayAction.CAPTURE;
                            builder.setCardData(initialGatewayRequest.getCardData())
                                    .setCvmType(initialGatewayRequest.getCvmResult())
                                    .setTenderType(initialGatewayRequest.getTenderType());
                        }
                        initialGatewayRequest = builder.build();
                        gatewayController.sendRequest(initialGatewayRequest);
                    }
                });
            } else {
                handleGatewayControllerNotInitialized();
            }
        }
    }

    private void performSurchargeTransaction(BigDecimal finalTotal, BigDecimal finalSurcharge, BigDecimal finalTax) {
        Timber.d("performSurchargeTransaction() called.");
        if (gatewayRequest == null || surchargeCalculation == null) {
            Timber.e("Unable to perform surcharge transaction. Gateway request or surcharge calculation is missing.");
            handleUnexpectedStateError();
            return;
        }

        //if passed in values are null, use old surcharge setup
        long total;
        long surcharge;
        long tax;

        //total amount
        if (finalTotal == null) {
            total = surchargeCalculation.getTotal();
        } else {
            total = finalTotal.movePointRight(2).longValue();
        }

        //surcharge amount
        if (finalSurcharge == null) {
            surcharge = surchargeCalculation.getSurcharge();
        } else {
            surcharge = finalSurcharge.movePointRight(2).longValue();
        }

        //tax amount
        if (finalTax == null) {
            tax = gatewayRequest.getTax() == null ? 0 : gatewayRequest.getTax();
        } else {
            tax = finalTax.movePointRight(2).longValue();
        }

        final GatewayRequest.Builder builder = new GatewayRequest.Builder(gatewayRequest.getGatewayAction());
        builder.setSAFIndicator(gatewayRequest.isSAFIndicator())
                .setTotal(total)
                .setTax(tax)
                .setTip(gatewayRequest.getTip())
                .setTenderType(gatewayRequest.getTenderType())
                .setTerminalInfo(gatewayRequest.getTerminalInfo())
                .setSurcharge(surcharge)
                .setAllowDuplicates(gatewayRequest.isAllowDuplicates())
                .setAutoSubstantiation(gatewayRequest.getAutoSubstantiation())
                .setAvsType(gatewayRequest.getAvsType())
                .setSignatureBitmap(gatewayRequest.getSignatureBitmap())
                .setCardHolderId(gatewayRequest.getCardHolderId())
                .setCommercialCardData(gatewayRequest.getCommercialCardData())
                .setForceAuthCode(gatewayRequest.getForceAuthCode())
                .setGenerateToken(gatewayRequest.isGenerateToken())
                .setInvoiceNumber(gatewayRequest.getInvoiceNumber())
                .setOperatingUserId(gatewayRequest.getOperatingUserId())
                .setCustomerInfo(gatewayRequest.getCustomer())
                .setRefundTax(gatewayRequest.getRefundTax())
                .setRefundTip(gatewayRequest.getRefundTip())
                .setSubtotal(gatewayRequest.getSubtotal())
                .setTaxCategory(gatewayRequest.getTaxCategory())
                .setTerminalCapabilities(gatewayRequest.getTerminalCapabilities());

        if (gatewayRequest.getCardData() != null) {
            builder.setCardData(gatewayRequest.getCardData());
            builder.setCvmType(gatewayRequest.getCvmResult());
        }

        if (!TextUtils.isEmpty(gatewayRequest.getCardBrandTxnId())) {
            builder.setCardBrandTxnId(gatewayRequest.getCardBrandTxnId());
        }

        if (!TextUtils.isEmpty(gatewayRequest.getPosReferenceNumber())) {
            builder.setPosReferenceNumber(gatewayRequest.getPosReferenceNumber());
        }

        if (!TextUtils.isEmpty(gatewayRequest.getGatewayTransactionId())) {
            builder.setHostTransactionId(gatewayRequest.getGatewayTransactionId());
        }

        gatewayRequest = builder.build();
        initialGatewayRequest = gatewayRequest;
        lastTransactionAction = gatewayRequest.getGatewayAction();
        networkUnavailable = isNetworkUnavailable(context);

        if ((forceSafEnabled || networkUnavailable) && safEnabled) {
            storeTransactionOffline(gatewayRequest);
        } else if (gatewayController != null) {
            if (!canSendGatewayRequest("Surcharge transaction")) {
                return;
            }

            runOnWorkerThread(new Runnable() {
                @Override
                public void run() {
                    gatewayController.sendRequest(gatewayRequest);
                }
            });
        } else {
            handleGatewayControllerNotInitialized();
        }
    }

    private void performTransactionAdjustment(CommercialCardData commercialCardData) {
        // TODO: Perform Transaction Adjustment
    }

    private void performVoid(@NonNull TransactionRequest transactionRequest) {
        Timber.d("performVoid() called with: transactionRequest =[%s]", transactionRequest);
        final GatewayRequest.Builder builder = new GatewayRequest.Builder(GatewayAction.VOID);
        builder.setTotal(finalTransactionTotal).setTax(transactionRequest.getTax()).setTip(transactionRequest.getTip()).setVoidReason(ReversalReason.VOIDED_BY_CUSTOMER).setTerminalInfo(terminalInfo);
        if (!TextUtils.isEmpty(transactionRequest.getGatewayTransactionId())) {
            builder.setHostTransactionId(transactionRequest.getGatewayTransactionId());
            if (!TextUtils.isEmpty(transactionRequest.getPosReferenceNumber())) {
                builder.setPosReferenceNumber(transactionRequest.getPosReferenceNumber());
            }

            if (gatewayController != null) {
                if ((forceSafEnabled || isNetworkUnavailable(context)) && safEnabled) {
                    initialGatewayRequest = builder.build();
                    storeTransactionOffline(initialGatewayRequest);
                } else {
                    this.runOnWorkerThread(new Runnable() {
                        public void run() {
                            TransManager.this.cancellable.set(false);
                            TransManager.this.initialGatewayRequest = builder.build();
                            TransManager.this.gatewayController.sendRequest(TransManager.this.initialGatewayRequest);
                        }
                    });
                }
            } else {
                handleGatewayControllerNotInitialized();
            }

        } else {
            handleReversalMissingParameters();
        }
    }

    private void performVoid(TerminalResponse response) {
        Timber.d("performVoid() called with: terminalResponse =[%s]", response);
        final GatewayRequest.Builder builder = new GatewayRequest.Builder(GatewayAction.VOID);
        builder.setTotal(finalTransactionTotal)
                .setTax(transactionRequest.getTax())
                .setHostTransactionId(gatewayResponse.getGatewayTransactionId())
                .setCardData(response.getCardData())
                .setVoidReason(toReversalReason(response.getTerminalDecisionType()))
                .setTerminalInfo(terminalInfo);
        if (gatewayController != null) {
            this.runOnWorkerThread(new Runnable() {
                public void run() {
                    TransManager.this.lastTransactionAction = GatewayAction.VOID;
                    TransManager.this.followUpGatewayRequest = builder.build();
                    TransManager.this.gatewayController.sendRequest(TransManager.this.followUpGatewayRequest);
                }
            });
        } else {
            this.handleGatewayControllerNotInitialized();
        }
    }

    private void performVoid(@NonNull TransactionRequest transactionRequest, @NonNull GatewayResponse gatewayResponse) {
        Timber.d("performVoid() called with: transactionRequest =[%s]", new Object[]{transactionRequest});
        final GatewayRequest.Builder builder = new GatewayRequest.Builder(GatewayAction.VOID);
        builder.setTotal(finalTransactionTotal)
                .setTax(transactionRequest.getTax())
                .setVoidReason(toReversalReason(null))
                .setTerminalInfo(terminalInfo);
        if (!TextUtils.isEmpty(gatewayResponse.getGatewayTransactionId())) {
            builder.setHostTransactionId(gatewayResponse.getGatewayTransactionId());
            if (!TextUtils.isEmpty(transactionRequest.getPosReferenceNumber())) {
                builder.setPosReferenceNumber(transactionRequest.getPosReferenceNumber());
            }
        } else {
            if (TextUtils.isEmpty(transactionRequest.getGatewayTransactionId())) {
                handleReversalMissingParameters();
                return;
            }

            builder.setHostTransactionId(transactionRequest.getGatewayTransactionId());
            if (!TextUtils.isEmpty(transactionRequest.getPosReferenceNumber())) {
                builder.setPosReferenceNumber(transactionRequest.getPosReferenceNumber());
            }
        }

        if (gatewayResponse != null && gatewayResponse.isGatewayTimeout() && cardData != null) {
            builder.setCardData(cardData);
        }

        if (gatewayController != null) {
            this.runOnWorkerThread(new Runnable() {
                public void run() {
                    TransManager.this.lastTransactionAction = GatewayAction.VOID;
                    TransManager.this.followUpGatewayRequest = builder.build();
                    TransManager.this.gatewayController.sendRequest(TransManager.this.followUpGatewayRequest);
                }
            });
        } else {
            handleGatewayControllerNotInitialized();
        }
    }

    /**
     * Converters
     **/
    private ReversalReason toReversalReason(@Nullable TerminalDecisionType terminalDecisionType) {
        if (terminalDecisionType == null) {
            return ReversalReason.VOIDED_BY_CUSTOMER;
        } else {
            switch (terminalDecisionType) {
                case APPROVED_ONLINE:
                case APPROVED_OFFLINE:
                    return ReversalReason.CHIP_DECLINED;
                case HOST_UNREACHABLE:
                    return ReversalReason.DEVICE_TIMEOUT;
                case TRANSACTION_CANCELLED:
                    return ReversalReason.VOIDED_BY_CUSTOMER;
                default:
                    return ReversalReason.DEVICE_UNAVAILABLE;
            }
        }
    }

    /**
     * Process Stuff
     **/
    private void processStartCardResponse(TerminalResponse response) {
        Timber.d("processStarCardResponse() called with: response=[%s]", new Object[]{response});
        TransactionResponse.Builder builder = new TransactionResponse.Builder();
        if (response.getCardData() != null) {
            Map<String, String> svaData = new HashMap<>();
            svaData.put("SVA", response.getCardData().getPan());
            svaData.put("Expiration", response.getCardData().getExpirationDate());
            builder.setTransactionType(TransactionType.SVA);
            builder.setSvaData(svaData);
        } else {
            Timber.w("No SVA data is available");
        }

        this.sendTransactionResponse(builder.build());
    }

    private void processTerminalResponse(TerminalResponse response) {
        Timber.d("processTerminalResponse() called with: response=[%s]", response.getTerminalAction().toString());

        TransactionResponse.Builder builder = new TransactionResponse.Builder();
        if (transactionRequest != null) {
            builder.setTransactionType(transactionRequest.getTransactionType()).setTenderType(transactionRequest.getTenderType());
        } else {
            Timber.w("mTransactionRequest is null. The transaction type and tender type cannot be determined.");
        }

        if (response.getCardData() != null) {
            cardData = response.getCardData();
            populateTransactionResponsePaymentInfo(builder, cardData, gatewayResponse);
        } else {
            Timber.d("Unable to get receipt because card data is missing.");
        }

        if (gatewayResponse != null) {
            builder.setApprovedAmount(
                            gatewayResponse.getApprovedAmount())
                    .setHostAuthCode(gatewayResponse.getAuthCode())
                    .setTipAmount(gatewayResponse.getTipAmount())
                    .setHostTransactionId(gatewayResponse.getGatewayTransactionId())
                    .setToken(gatewayResponse.getToken())
                    .setCardBrandTxnId(gatewayResponse.getCardBrandTxnId()
                    );

            // Original total
            if (initialGatewayRequest != null && initialGatewayRequest.getTotal() > 0L) {
                builder.setOrigTotal(initialGatewayRequest.getTotal());
            }

            if (gatewayRequest != null) {
                // original total (AGAIN?!)
                if (gatewayRequest.getTotal() > 0L) {
                    builder.setOrigTotal(gatewayRequest.getTotal());
                }
                 if (gatewayRequest.getSurcharge() != null && gatewayRequest.getSurcharge() > 0L) {
                    builder.setSurchargeAmount(gatewayRequest.getSurcharge());
                } else {
                    Timber.d("processTerminalResponse: gatewayRequest.getSurcharge() is null or 0");
                }
            }

            if (surchargeEligibility != null) {
                builder.setSurchargeEligibility(surchargeEligibility);
            }

            if (!TextUtils.isEmpty(gatewayResponse.getMaskedPan())) {
                builder.setMaskedPan(gatewayResponse.getMaskedPan());
            }

            builder.setHostTransactionId(gatewayResponse.getGatewayTransactionId());
            builder.setOriginalGatewayTxnId(gatewayResponse.getOriginalGatewayTxnId());
            builder.setOriginalRspDT(gatewayResponse.getOriginalRspDT());
            builder.setOriginalClientTxnId(gatewayResponse.getOriginalClientTxnId());
            builder.setOriginalAuthCode(gatewayResponse.getOriginalAuthCode());
            builder.setOriginalRefNbr(gatewayResponse.getOriginalRefNbr());
            builder.setOriginalAuthAmt(gatewayResponse.getOriginalAuthAmt());
            builder.setOriginalCardType(gatewayResponse.getOriginalCardType());
            builder.setOriginalCardNbrLast4(gatewayResponse.getOriginalCardNbrLast4());
        }

        if (gatewayConfig != null && gatewayConfig.getGatewayType() == GatewayType.MOCK) {
            handleTerminalResponseForMockedGateway(response, builder);
        } else {
            switch (response.getTerminalDecisionType()) {
                case APPROVED_ONLINE:
                case APPROVED_OFFLINE:
                    /* The cardholder requested a cancellation after host processing had already
                       started. The transaction may already be authorized, so it is reversed
                       instead of being reported as CANCELLED. */
                    if (cancelRequested.get() && gatewayResponse != null && gatewayResponse.isApproved()) {
                        Timber.d("Approved transaction completed while cancellation was pending. Attempting post-auth reversal.");
                        if (startTerminalPostAuthReversal(gatewayResponse)) {
                            return;
                        }

                        Timber.d("Cancellation was requested after host approval, but the transaction cannot be reversed.");
                    }
                    builder.setTransactionResult(TransactionResultType.APPROVED);
                    break;
                case DECLINED_OFFLINE:
                    builder.setTransactionResult(cancelledOr(TransactionResultType.DECLINED_OFFLINE));
                    break;
                case REVERSAL_REQUIRED:
                    // performReversal is only required for Puck1000 device.
                    if (startTerminalPostAuthReversal(gatewayResponse)) {
                        return;
                    }

                    builder.setTransactionResult(cancelledOr(TransactionResultType.DECLINED_ONLINE));
                    break;
                case TRANSACTION_CANCELLED:
                    builder.setTransactionResult(TransactionResultType.CANCELLED);
                    break;
                case DECLINED_ONLINE:
                case HOST_UNREACHABLE:
                default:
                    builder.setTransactionResult(cancelledOr(TransactionResultType.DECLINED_ONLINE));
            }
        }

        builder.setResponseMap(transactionResponseMap).setResponseCodeMap(transactionResponseCodeMap);
        TransactionResponse transactionResponse = builder.build();
        if (TransactionResultType.APPROVED == transactionResponse.getTransactionResult() && isTransactionAdjustmentRequired()) {
            transactionResponseForAdjustment = transactionResponse;
            requestCommercialCardData();
        } else {
            this.sendTransactionResponse(transactionResponse);
        }
    }


    /**
     * Update Stuff
     **/
    @Override
    public void updateTerminal(@NonNull TerminalUpdateType terminalUpdateType, @Nullable Map<String, String> credentials, @Nullable String version, @NonNull UpdateTerminalListener updateTerminalListener) {
        Timber.d("[TransactionManager] updateTerminal() called with terminalUpdateType=%s,credentials=%s,version=%s",
                terminalUpdateType, credentials, version);
        if (terminalController != null) {
            terminalController.updateTerminal(terminalUpdateType, credentials, version, new UpdateListener() {
                @Override
                public void onProgress(@Nullable Double completionPercentage, @Nullable String progressMessage) {
                    updateTerminalListener.onProgress(completionPercentage, progressMessage);
                }

                @Override
                public void onTerminalUpdateSuccess() {
                    updateTerminalListener.onTerminalUpdateSuccess();
                }

                @Override
                public void onTerminalUpdateError(TerminalError error, String message) {
                    updateTerminalListener.onTerminalUpdateError(new Error() {
                        @Override
                        public ErrorType getType() {
                            switch (error) {
                                case INITIALIZATION_ERROR:
                                    return ErrorType.NOT_INITIALIZED;
                                case CONFIGURATION_ERROR:
                                    return ErrorType.INVALID_DATA;
                                case BATTERY_TOO_LOW:
                                    break;
                                case UPDATE_NOT_REQUIRED:
                                case DEVICE_COMM_ERROR:
                                case SERVER_COMM_ERROR:
                                    return ErrorType.TERMINAL_ERROR;
                            }
                            return ErrorType.TERMINAL_ERROR;
                        }

                        @Override
                        public String getMessage() {
                            if (error == TerminalError.CONFIGURATION_ERROR) {
                                return "OTA EMV Failed - "+ message;
                            }
                            return message;
                        }
                    });
                }
            });
        } else {
            Timber.e("Cannot perform update. mTerminalController is not initialized");
            updateTerminalListener.onTerminalUpdateError(new Error() {
                @Override
                public ErrorType getType() {
                    return ErrorType.TERMINAL_ERROR;
                }

                @Override
                public String getMessage() {
                    return "Terminal is not initialized";
                }
            });
        }
    }

    @Override
    public void updateTerminalSetting(TerminalSettingType terminalSettingsType, String terminalSettingsValue, TerminalUpdateSettingListener terminalUpdateSettingListener) {

    }

    public void continueAfterRemoveCardPrompt() {
        if (terminalController instanceof Puck1000Controller) {
            ((Puck1000Controller) terminalController).continueAfterRemoveCardPrompt();
        }
    }

    @Override
    public void updateTransactionListener(@NonNull TransactionListener listener) {
        if (transCallback == null) {
            transCallback = new TransCallbackMarshaller(listener, TransExecutors.getInstance().getMainThread());
        }
        transCallback.updateListener(listener);
    }

    private void updateTransactionResult(TransactionResponse.Builder builder, GatewayResponse gatewayResponse, boolean isStoredAsSafTransactioon) {
        if (!gatewayResponse.isGatewayTimeout() && !gatewayResponse.isError()) {
            if (this.isTransactionResultPostAuthReversal(transactionRequest.getTransactionType())) {
                builder.setTransactionResult(TransactionResultType.POST_AUTH_REVERSAL);
            } else if (isStoredAsSafTransactioon) {
                builder.setTransactionResult(TransactionResultType.SAF);
            } else {
                builder.setTransactionResult(gatewayResponse.isApproved() ? TransactionResultType.APPROVED : TransactionResultType.DECLINED_ONLINE);
            }
        } else {
            builder.setTransactionResult(TransactionResultType.CANCELLED);
        }
    }


    /**
     * Send Stuff
     **/
    @Override
    public void sendCardholderInteractionResult(@NonNull CardholderInteractionResult cardholderInteractionResult) {
        final TerminalInteractionResult result = new TerminalInteractionResult();
        Timber.d("sendCardholderInteractionResult: type %s", cardholderInteractionResult.getCardholderInteractionType());
        switch (cardholderInteractionResult.getCardholderInteractionType()) {
            case EMV_APPLICATION_SELECTION:
                result.setInteractionType(TerminalInteractionType.EMV_APPLICATION_SELECTION);
                result.setSelectedApplicationIndex(cardholderInteractionResult.getSelectedAidIndex());
                break;
            case FINAL_AMOUNT_REQUEST:
                result.setInteractionType(TerminalInteractionType.FINAL_AMOUNT_REQUEST);
                result.setFinalAmount(cardholderInteractionResult.getFinalAmount().longValue());
                if (cardholderInteractionResult.getFinalAmount() != null) {
                    finalTransactionTotal = cardholderInteractionResult.getFinalAmount().longValue();
                }

                result.setCashbackAmount(cardholderInteractionResult.getCashbackAmount());
                result.setTipAmount(cardholderInteractionResult.getTipAmount());
                break;
            case FINAL_AMOUNT_CONFIRMATION:
                result.setInteractionType(TerminalInteractionType.FINAL_AMOUNT_CONFIRMATION);
                result.setFinalAmountConfirmed(cardholderInteractionResult.getFinalAmountConfirmed());
                break;
            case REQUEST_PROCEED_HOST_PROCESSING:
                result.setInteractionType(TerminalInteractionType.REQUEST_PROCEED_HOST_PROCESSING);
                result.setContinueHostProcessingConfirmed(cardholderInteractionResult.getContinueHostProcessingConfirmed());
                networkUnavailable = isNetworkUnavailable(context);
                additionalHostProcessingData = cardholderInteractionResult.getHostProcessingAdditionalData();
                break;
            case CARDHOLDER_SURCHARGE_CONFIRMATION:
                Timber.d("sendCardholderInteractionResult: CARDHOLDER_SURCHARGE_CONFIRMATION");
                if (cardholderInteractionResult.getFinalAmountConfirmed()) {
                    Timber.d("sendCardholderInteractionResult: amount confirmed : %s", cardholderInteractionResult.getFinalAmount());
                    result.setInteractionType(TerminalInteractionType.SURCHARGE_CONFIRMATION);
                    long surchargeAmount = 0;
                    if (cardholderInteractionResult.getFinalSurchargeAmount() != null) {
                        surchargeAmount = cardholderInteractionResult.getFinalSurchargeAmount().movePointRight(2).longValue();
                    }
                    result.setSurchargeAmount(surchargeAmount);
                    performSurchargeTransaction(cardholderInteractionResult.getFinalAmount(),
                            cardholderInteractionResult.getFinalSurchargeAmount(), cardholderInteractionResult.getFinalTaxAmount());
                } else {
                    Timber.d("sendCardholderInteractionResult: amount not confirmed");
                    /* Build the response before cancelling: the cancellation can complete the
                       transaction synchronously and reset the state this response is built from. */
                    TransactionResponse.Builder builder = new TransactionResponse.Builder();
                    if (transactionRequest != null) {
                        builder.setTransactionType(transactionRequest.getTransactionType());
                    }
                    builder.setResponseMap(transactionResponseMap)
                            .setResponseCodeMap(transactionResponseCodeMap)
                            .setTransactionResult(TransactionResultType.CANCELLED);

                    cancellable.set(true);
                    this.cancel();

                    /* A no-op when the cancellation already delivered the final response. */
                    invokeTransactionResponseCallback(builder);
                }

                return;
            case COMMERCIAL_CARD_DATA_ENTRY:
                performTransactionAdjustment(cardholderInteractionResult.getCommercialCardData());
                return;
        }

        if (result.getInteractionType() == TerminalInteractionType.REQUEST_PROCEED_HOST_PROCESSING
                && terminalController instanceof Puck1000Controller
                && ((Puck1000Controller) terminalController).isAwaitingCdcvmConfirmation()) {
            terminalController.sendTerminalInteractionResult(result);
            return;
        }

        if (result.getInteractionType() == TerminalInteractionType.EMV_APPLICATION_SELECTION
                && terminalController instanceof Puck1000Controller
                && ((Puck1000Controller) terminalController).isAwaitingApplicationSelection()) {
            terminalController.sendTerminalInteractionResult(result);
            return;
        }

        this.runOnWorkerThread(new Runnable() {
            public void run() {
                if (cardholderInteractionResult.getCardholderInteractionType() != CardholderInteractionType.COMMERCIAL_CARD_DATA_ENTRY) {
                    TransManager.this.terminalController.sendTerminalInteractionResult(result);
                }
            }
        });
    }

    // For now, supporting fallback event only for Puck1000.
    // If other devices support fallback, this method can be updated to handle those cases.
    public void resumePuckPolling() {
        if (terminalController instanceof Puck1000Controller) {
            ((Puck1000Controller) terminalController).resumePolling();
        }
    }

    public void setProvisionalTransactionEnabled(boolean provisionalTransactionEnabled) {
        this.provisionalTransactionEnabled = provisionalTransactionEnabled;
    }

    /**
     * Supplies the PUCK1000 Mutual Authentication settings used when constructing the terminal
     * controller. Only relevant for the PUCK1000 terminal type.
     *
     * @param enabled whether Mutual Authentication should be performed during connection
     * @param certUrl optional override for the certificate server host ({@code null} for default)
     */
    public void setPuckMutualAuthConfig(boolean enabled, String certUrl) {
        this.puckMutualAuthEnabled = enabled;
        this.puckMutualAuthCertUrl = certUrl;
    }

    private void sendTerminalRequest(final TerminalRequest terminalRequest) {
        Timber.d("sendTerminalRequest called with: request =[%s]", terminalRequest);
        if (terminalController != null) {
            if (connected) {
                runOnWorkerThread(new Runnable() {
                    @Override
                    public void run() {
                        TransManager.this.terminalController.sendRequest(terminalRequest);
                    }
                });
            } else {
                handleCardReaderNotConnected();
            }
        } else {
            handleTerminalControllerNotInitialized();
        }
    }

    private void sendTransactionResponse(final TransactionResponse transactionResponse) {
        if (this.transCallback != null) {
            if (!claimFinalResponse()) {
                return;
            }

            this.resetFields();
            this.transCallback.callbackOnTransactionResponse(transactionResponse);
        }
    }

    /**
     * Claims the right to deliver the final response of the current transaction.
     *
     * @return true for the first caller, false for every later one, which guarantees the
     * integrator receives exactly one transaction response per transaction.
     */
    private boolean claimFinalResponse() {
        synchronized (cancelLock) {
            if (!transactionActive) {
                Timber.d("A final transaction response was already delivered, ignoring the duplicate.");
                return false;
            }

            transactionActive = false;
            return true;
        }
    }

    /**
     * Resets the cancellation bookkeeping of a transaction.
     *
     * @param transactionStarting true when a new transaction is starting, false when the current
     *                            transaction is over.
     */
    private void resetCancellationState(boolean transactionStarting) {
        synchronized (cancelLock) {
            cancellable.set(true);
            cancelRequested.set(false);
            postAuthReversalPending = false;
            transactionActive = transactionStarting;
        }
    }


    /**
     * Uncategorized
     **/
    @Override
    public void startTransaction(@NonNull TransactionRequest request, @NonNull TransactionListener listener, SafListener safListener) {
        Timber.tag(TAG).d("startTransaction() called with: request=[%s]", request);
        //this.validateInitWasCalled();
        transCallback = new TransCallbackMarshaller(listener, TransExecutors.getInstance().getMainThread());
        if (safListener != null) {
            safCallback = new SafCallbackMarshaller(safListener, TransExecutors.getInstance().getMainThread());
        }

        transactionRequest = request;
        finalTransactionTotal = getTransactionTotal();
        networkUnavailable = isNetworkUnavailable(context);
        resetCancellationState(true);

        switch (request.getTransactionType()) {
            case SALE:
                this.performSale(request);
                break;
            case AUTH:
                this.performAuth(request);
                break;
            case VERIFY:
                performVerify(request);
                break;
            case INCREMENT:
                this.performIncrementalAuth(request);
                break;
            case CAPTURE:
                performCapture(request);
                break;
            case REFUND:
                performRefund();
                break;
            case PARTIAL_REFUND:
                performPartialRefund();
                break;
            case REVERSAL:
                performReversal(request);
                break;
            case VOID:
                performVoid(request);
                break;
            case TIP_ADJUST:
                performTipAdjust(request);
                break;
            case BATCH_CLOSE:
                performCloseBatch(request);
                break;
            default:
                handleUnsupportedTransactionType(request.getTransactionType());
                break;
        }
    }

    /**
     * Reports an unsupported transaction type back to the caller instead of silently
     * leaving the transaction without a response.
     */
    private void handleUnsupportedTransactionType(final TransactionType transactionType) {
        Timber.e("Unsupported transaction type=[%s]. The request cannot be processed.", transactionType);
        if (transCallback != null) {
            transCallback.callbackOnError(new Error() {
                @Override
                public ErrorType getType() {
                    return ErrorType.INVALID_DATA;
                }

                @Override
                public String getMessage() {
                    return "Unsupported transaction type: " + transactionType;
                }
            });
        }
    }

    private void performCloseBatch(@NonNull TransactionRequest request) {
        Timber.d("performCloseBatch()");
        final GatewayRequest.Builder builder =
                new GatewayRequest.Builder(GatewayAction.BATCH_CLOSE);
        builder.setOperatingUserId(request.getOperatingUserId())
                .setTerminalInfo(terminalInfo);

        if (gatewayController != null) {
            runOnWorkerThread(new Runnable() {
                @Override
                public void run() {
                    initialGatewayRequest = builder.build();
                    lastTransactionAction = GatewayAction.BATCH_CLOSE;
                    gatewayController.sendRequest(initialGatewayRequest);
                }
            });
        } else {
            handleGatewayControllerNotInitialized();
        }
    }

    /**
     * Perform a tip adjust on an existing transaction.
     */
    private void performTipAdjust(@NonNull TransactionRequest request) {
        Timber.d("performTipAdjust()");
        final GatewayRequest.Builder builder = new GatewayRequest.Builder(GatewayAction.TIP_ADJUST);
        builder.setTotal(finalTransactionTotal)
                .setTip(request.getTip())
                .setHostTransactionId(request.getGatewayTransactionId())
                .setTerminalInfo(terminalInfo);
        lastTransactionAction = GatewayAction.TIP_ADJUST;
        if (gatewayController != null) {
            initialGatewayRequest = builder.build();
            if ((forceSafEnabled || isNetworkUnavailable(context)) && safEnabled) {
                storeTransactionOffline(initialGatewayRequest);
            } else {
                runOnWorkerThread(new Runnable() {
                    @Override
                    public void run() {
                        gatewayController.sendRequest(initialGatewayRequest);
                    }
                });
            }
        } else {
            handleGatewayControllerNotInitialized();
        }
    }

    private void performPartialRefund() {
        performRefund(GatewayAction.PARTIAL_REFUND);
    }

    private void performRefund() {
        performRefund(GatewayAction.REFUND);
    }

    private void performRefund(GatewayAction refundAction) {
        Timber.d("performRefund() called with: transactionRequest =[%s]", transactionRequest);

        final GatewayRequest.Builder builder = new GatewayRequest.Builder(refundAction);
        builder.setTotal(finalTransactionTotal)
                .setTax(transactionRequest.getTax())
                .setVoidReason(ReversalReason.VOIDED_BY_CUSTOMER)
                .setTip(transactionRequest.getTip());

        if (!refundRequiresParentTransaction()) {
            // Refund using parent transaction id
            if (!TextUtils.isEmpty(transactionRequest.getGatewayTransactionId())) {
                builder.setHostTransactionId(transactionRequest.getGatewayTransactionId());
                if (!TextUtils.isEmpty(transactionRequest.getPosReferenceNumber())) {
                    builder.setPosReferenceNumber(transactionRequest.getPosReferenceNumber());
                }
                sendGatewayRequest(builder);

                // Refund using reference number
            }
        } else {
            // Refund using keyed card data without a parent transaction id
            // Currently this is only supported for Transit Gateway
            if (transactionRequest.getKeyedCardData() != null) {
                builder.setCardData(transactionRequest.getKeyedCardData());
                sendGatewayRequest(builder);
            } else {
                // Refund using card data without a parent transaction id
                // Currently this is only supported for Transit Gateway
                final TerminalRequest terminalRequest =
                        new TerminalRequest(TerminalAction.TENDER_REFUND);
                terminalRequest.setTransactionType(transactionRequest.getTransactionType());
                terminalRequest.setTipAmount(transactionRequest.getTip());
                terminalRequest.setTotalAmount(
                        transactionRequest.getTotal() == null ? 0L :
                                transactionRequest.getTotal());

                sendTerminalRequest(terminalRequest);
            }
        }
    }

    private void performAuth(@NonNull TransactionRequest request) {
        Timber.d("performAuth()");
        // FIXME: Condense the auth and sale transactions into one method if there is little difference in the
        // transaction information sent to the terminal.
        if (request.getKeyedCardData() != null) {
            // Build manual transaction request
            // performManualAuthSale(request);
        } else if (!TextUtils.isEmpty(request.getToken())) {
            // Build token request
            performAuthSaleWithToken(request);
        } else {
            // Build terminal request
            final TerminalRequest terminalRequest =
                    new TerminalRequest(TerminalAction.TENDER_GOODS);
            terminalRequest.setTransactionType(request.getTransactionType());
            terminalRequest.setTotalAmount(request.getTotal());
            terminalRequest.setTipAmount(request.getTip());
            terminalRequest.setCashBackAmount(request.getCashBack());
            terminalRequest.setTenderType(request.getTenderType());

            sendTerminalRequest(terminalRequest);
        }
    }


    private boolean refundRequiresParentTransaction() {
        boolean noParentTransaction = true;
        if (!TextUtils.isEmpty(transactionRequest.getGatewayTransactionId())) {
            noParentTransaction = false;
        }
        if (!TextUtils.isEmpty(transactionRequest.getPosReferenceNumber())) {
            noParentTransaction = false;
        }
        return noParentTransaction;
    }

    private void sendGatewayRequest(final GatewayRequest.Builder builder) {
        Timber.d("sendGatewayRequest() called");

        if (gatewayController != null) {
            runOnWorkerThread(new Runnable() {
                @Override
                public void run() {
                    cancellable.set(false);
                    lastTransactionAction = GatewayAction.REFUND;
                    builder.setTerminalInfo(terminalInfo);
                    initialGatewayRequest = builder.build();
                    if ((forceSafEnabled || isNetworkUnavailable(context)) && safEnabled) {
                        storeTransactionOffline(initialGatewayRequest);
                    } else {
                        gatewayController.sendRequest(initialGatewayRequest);
                    }
                }
            });
        } else {
            handleGatewayControllerNotInitialized();
        }
    }

    private void performReversal(@NonNull TransactionRequest transactionRequest, String gatewayTransactionId, String posReferenceNumber) {
        Timber.d("performReversal() called with: gatewayTransactionId =[%s], posReferenceNumber=[%s]", gatewayTransactionId, posReferenceNumber);
        final GatewayRequest.Builder gatewayRequestBuilder = new GatewayRequest.Builder(GatewayAction.REVERSAL);
        gatewayRequestBuilder.setTotal(finalTransactionTotal)
                .setTax(transactionRequest.getTax())
                .setTip(transactionRequest.getTip())
                .setVoidReason(transactionRequest.getReversalReason())
                .setTerminalInfo(terminalInfo);
        if (!TextUtils.isEmpty(gatewayTransactionId)) {
            gatewayRequestBuilder.setHostTransactionId(gatewayTransactionId);
            if (!TextUtils.isEmpty(posReferenceNumber)) {
                gatewayRequestBuilder.setPosReferenceNumber(posReferenceNumber);
            }
        } else {
            handleReversalMissingParameters();
            return;
        }

        if (gatewayController != null) {
            if ((forceSafEnabled || isNetworkUnavailable(context)) && safEnabled) {
                initialGatewayRequest = gatewayRequestBuilder.build();
                storeTransactionOffline(initialGatewayRequest);
            } else {
                runOnWorkerThread(() -> {
                    cancellable.set(false);
                    initialGatewayRequest = gatewayRequestBuilder.build();
                    gatewayController.sendRequest(initialGatewayRequest);
                });
            }
        } else {
            handleGatewayControllerNotInitialized();
        }
    }

    private void performReversal(@NotNull TransactionRequest transactionRequest) {
        Timber.d("performReversal() called with: transactionRequest =[%s]", transactionRequest);
        final GatewayRequest.Builder builder = new GatewayRequest.Builder(GatewayAction.REVERSAL);
        builder.setTotal(finalTransactionTotal)
                .setTax(transactionRequest.getTax())
                .setTip(transactionRequest.getTip())
                .setVoidReason(transactionRequest.getReversalReason())
                .setTerminalInfo(terminalInfo);
        if (!TextUtils.isEmpty(transactionRequest.getGatewayTransactionId())) {
            builder.setHostTransactionId(transactionRequest.getGatewayTransactionId());
            if (!TextUtils.isEmpty(transactionRequest.getPosReferenceNumber())) {
                builder.setPosReferenceNumber(transactionRequest.getPosReferenceNumber());
            }
        } else {
            handleReversalMissingParameters();
            return;
        }

        if (gatewayController != null) {
            if ((forceSafEnabled || isNetworkUnavailable(context)) && safEnabled) {
                initialGatewayRequest = builder.build();
                storeTransactionOffline(initialGatewayRequest);
            } else {
                runOnWorkerThread(new Runnable() {
                    @Override
                    public void run() {
                        cancellable.set(false);
                        initialGatewayRequest = builder.build();
                        gatewayController.sendRequest(initialGatewayRequest);
                    }
                });
            }
        } else {
            handleGatewayControllerNotInitialized();
        }
    }

    @Override
    public void readTerminalSetting(TerminalSettingType terminalSettingsType, TerminalReadSettingListener terminalReadSettingListener) {

    }

    private void resetFields() {
        Timber.d("resetFields() called.");
        cardData = null;
        initialGatewayRequest = null;
        gatewayRequest = null;
        surchargeEligibility = null;
        resetCancellationState(false);
        transactionResponseMap = new HashMap<>();
        transactionResponseCodeMap = new HashMap<>();
        lastTransactionAction = null;
        gatewayResponse = null;
        transactionRequest = null;
        followUpGatewayRequest = null;
        this.safCallback = null;
        networkUnavailable = false;
        surchargeCalculation = null;
    }

    private void runOnWorkerThread(Runnable runnable) {
        TransExecutors.getInstance().getWorkerThread().execute(runnable);
    }

    private void callbackOnSafError(final ErrorType errorType, final String message) {
        Timber.d("callbackOnSafError() called with errorType=[%s] and message=[%s]", errorType, message);
        if (safCallback != null) {
            safCallback.callbackOnError(new Error() {
                public ErrorType getType() {
                    return errorType;
                }

                public String getMessage() {
                    return message;
                }
            });
        } else {
            Timber.d("mSafCallbackMarshaller not initialized");
        }
    }


    /**
     * Request Stuff
     **/
    private void requestAmountConfirmation(TerminalInteractionRequest request) {
        CardholderInteractionRequest cardholderInteractionRequest = new CardholderInteractionRequest();
        /*//Want to check here if surcharge is enabled
        if(LibraryConfigHelper.isSurchargeEnabled()) {
            cardholderInteractionRequest.setCardholderInteractionType(CardholderInteractionType.CARDHOLDER_SURCHARGE_CONFIRMATION);
        }else{*/
        cardholderInteractionRequest.setCardholderInteractionType(CardholderInteractionType.FINAL_AMOUNT_CONFIRMATION);
        // }
        cardholderInteractionRequest.setFinalTransactionAmount(request.getFinalAmount());
        if (transCallback != null) {
            transCallback.callbackOnCardholderInteractionRequested(cardholderInteractionRequest);
        }
    }

    private void requestSurchargeConfirmation(TerminalInteractionRequest request) {
        CardholderInteractionRequest cardholderInteractionRequest = new CardholderInteractionRequest();
        cardholderInteractionRequest.setCardholderInteractionType(CardholderInteractionType.SURCHARGE_REQUESTED);
        cardholderInteractionRequest.setFinalTransactionAmount(request.getFinalAmount());
        cardholderInteractionRequest.setSurchargeAmount(request.getFinalSurchargeAmount());

        if (transCallback != null) {
            transCallback.callbackOnCardholderInteractionRequested(cardholderInteractionRequest);
        }
    }

    private void requestProceedHostProcessingConfirmation(TerminalInteractionRequest request) {
        CardholderInteractionRequest cardholderInteractionRequest = new CardholderInteractionRequest();
        cardholderInteractionRequest.setCardholderInteractionType(CardholderInteractionType.REQUEST_PROCEED_HOST_PROCESSING);
        if (request.getCardData() != null) {
            cardholderInteractionRequest.setCardDataSourceType(request.getCardData().getCardDataSource());
        }
        if (transCallback != null) {
            transCallback.callbackOnCardholderInteractionRequested(cardholderInteractionRequest);
        } else if (terminalController != null) {
            TerminalInteractionResult result = new TerminalInteractionResult();
            result.setInteractionType(TerminalInteractionType.REQUEST_PROCEED_HOST_PROCESSING);
            result.setContinueHostProcessingConfirmed(false);
            terminalController.sendTerminalInteractionResult(result);
        }
    }

    private void requestEmvAppSelection(TerminalInteractionRequest request) {
        Timber.d("requestEmvAppSelection() called with request=[%s]", request);
        CardholderInteractionRequest cardholderInteractionRequest = new CardholderInteractionRequest();
        cardholderInteractionRequest.setCardholderInteractionType(CardholderInteractionType.EMV_APPLICATION_SELECTION);
        cardholderInteractionRequest.setSupportedApplications(request.getApplicationNames());
        if (transCallback != null) {
            transCallback.callbackOnCardholderInteractionRequested(cardholderInteractionRequest);
        }
    }

    private void requestCommercialCardData() {
        Timber.d("requestCommercialCardData() called");
        CardholderInteractionRequest cardholderInteractionRequest = new CardholderInteractionRequest();
        cardholderInteractionRequest.setCardholderInteractionType(CardholderInteractionType.COMMERCIAL_CARD_DATA_ENTRY);
        cardholderInteractionRequest.setCommercialCardDataFields(getNecessaryCommercialFieldsToRequest());
        if (transCallback != null) {
            transCallback.callbackOnCardholderInteractionRequested(cardholderInteractionRequest);
        }
    }


    /**
     * Handle Stuff
     **/
    private void handleAuthResponse(GatewayResponse authResponse) {
        gatewayResponse = authResponse;
        handleAuthSaleResponse(authResponse);
    }

    private void handleAuthSaleGatewayResponse(GatewayResponse authSaleResponse) {
        TransactionResponse.Builder builder = new TransactionResponse.Builder();
        builder.setCardType(authSaleResponse.getCardType())
                .setTransactionType(transactionRequest.getTransactionType())
                .setApprovedAmount(authSaleResponse.getApprovedAmount())
                .setTipAmount(authSaleResponse.getTipAmount() == null ? 0L : authSaleResponse.getTipAmount())
                .setHostAuthCode(authSaleResponse.getAuthCode())
                .setToken(authSaleResponse.getToken())
                .setCardBrandTxnId(authSaleResponse.getCardBrandTxnId())
                .setCardHolderId(authSaleResponse.getCardHolderId())
                .setResponseMap(transactionResponseMap)
                .setResponseCodeMap(transactionResponseCodeMap)
                .setOriginalGatewayTxnId(authSaleResponse.getOriginalGatewayTxnId())
                .setOriginalRspDT(authSaleResponse.getOriginalRspDT())
                .setOriginalClientTxnId(authSaleResponse.getOriginalClientTxnId())
                .setOriginalAuthCode(authSaleResponse.getOriginalAuthCode())
                .setOriginalRefNbr(authSaleResponse.getOriginalRefNbr())
                .setOriginalAuthAmt(authSaleResponse.getOriginalAuthAmt())
                .setOriginalCardType(authSaleResponse.getOriginalCardType())
                .setOriginalCardNbrLast4(authSaleResponse.getOriginalCardNbrLast4());

        if (cardData != null) {
            this.populateTransactionResponsePaymentInfo(builder, cardData, gatewayResponse);
        }

        builder.setHostTransactionId(authSaleResponse.getGatewayTransactionId()).setTenderType(transactionRequest.getTenderType());

        if (authSaleResponse.isGatewayTimeout() && isTransactionReversible(transactionRequest, authSaleResponse)) {
            Timber.d("Gateway timeout on reversible transaction. Starting post-auth reversal.");
            /* Do not invoke the transaction response callback here. That would reset the
               transaction state required to process the upcoming reversal/void response. */
            if (performPostAuthReversal(authSaleResponse)) {
                return;
            }

            Timber.w("Gateway timeout could not be reversed, reporting the transaction as cancelled.");
            builder.setTransactionResult(TransactionResultType.CANCELLED);
            invokeTransactionResponseCallback(builder);
            return;
        }

        /* A cancellation was requested while the gateway operation was already in progress. */
        if (cancelRequested.get()) {
            if (authSaleResponse.isApproved()) {
                if (isTransactionReversible(transactionRequest, authSaleResponse)) {
                    Timber.d("Transaction approved while cancellation was pending. Starting post-auth reversal.");
                    /* Do not invoke the transaction response callback here. That would reset the
                       transaction state required to process the upcoming reversal/void response. */
                    if (performPostAuthReversal(authSaleResponse)) {
                        return;
                    }
                }

                /* Host approved but there is no reliable way to reverse it. Report APPROVED
                   rather than falsely reporting CANCELLED. */
                Timber.d("Cancellation requested after approval, but the transaction cannot be reversed.");
                builder.setTransactionResult(TransactionResultType.APPROVED);
                invokeTransactionResponseCallback(builder);
                return;
            }

            Timber.d("Cancellation pending and host transaction was not approved. Completing as CANCELLED.");
            builder.setTransactionResult(TransactionResultType.CANCELLED);
            invokeTransactionResponseCallback(builder);
            return;
        }

        if (authSaleResponse.isError()) {
            builder.setTransactionResult(TransactionResultType.DECLINED_ONLINE);
        } else {
            builder.setTransactionResult(authSaleResponse.isApproved() ? TransactionResultType.APPROVED : TransactionResultType.DECLINED_ONLINE);
        }
        invokeTransactionResponseCallback(builder);
    }

    /**
     * Reverses a transaction that has already been authorized by the host. Puck1000 requires a
     * reversal, every other terminal is reversed with a void.
     *
     * @return true when the reversal was started and its response will complete the transaction,
     * false when the transaction cannot be reversed and the caller has to report a final result.
     */
    private boolean performPostAuthReversal(@NonNull GatewayResponse response) {
        if (transactionRequest == null) {
            Timber.e("Unable to start a post-auth reversal, the transaction request is missing.");
            return false;
        }

        if (terminalConfig != null && terminalConfig.getTerminalType() == TerminalType.CASTLES_PUCK_1000) {
            if (TextUtils.isEmpty(response.getGatewayTransactionId())) {
                Timber.e("Unable to start a post-auth reversal, the gateway transaction id is missing.");
                return false;
            }

            postAuthReversalPending = true;
            performReversal(transactionRequest, response.getGatewayTransactionId(), response.getPosReferenceId());
            return true;
        }

        if (TextUtils.isEmpty(response.getGatewayTransactionId())
                && TextUtils.isEmpty(transactionRequest.getGatewayTransactionId())) {
            Timber.e("Unable to start a post-auth void, the gateway transaction id is missing.");
            return false;
        }

        postAuthReversalPending = true;
        performVoid(transactionRequest, response);
        return true;
    }

    /**
     * Starts the reversal of a transaction the terminal reported as authorized but that must be
     * undone (gateway timeout or a cancellation that arrived after approval).
     *
     * @return true when the reversal was started and its response will complete the transaction.
     */
    private boolean startTerminalPostAuthReversal(@Nullable GatewayResponse response) {
        if (transactionRequest == null || response == null) {
            Timber.e("Unable to start a post-auth reversal, the transaction state is missing.");
            return false;
        }

        if (TextUtils.isEmpty(response.getGatewayTransactionId())) {
            Timber.e("Unable to start a post-auth reversal, the gateway transaction id is missing.");
            return false;
        }

        postAuthReversalPending = true;
        // Updating transaction type for the transaction reversal request
        transactionRequest.setTransactionType(TransactionType.REVERSAL);
        performReversal(transactionRequest, response.getGatewayTransactionId(), response.getPosReferenceId());
        return true;
    }

    private void handleAuthSaleResponse(GatewayResponse authSaleResponse) {
        Timber.d("handleAuthSaleResponse() called with: hostResponse=[%s]", authSaleResponse);
        CardDataSourceType cardDataSourceType = authSaleResponse.getCardDataSourceType();
        if (terminalController == null || cardDataSourceType != CardDataSourceType.SCR && cardDataSourceType != CardDataSourceType.CONTACTLESS_EMV) {
            if (this.isTransactionAdjustmentRequired()) {
                requestCommercialCardData();
            } else {
                handleAuthSaleGatewayResponse(authSaleResponse);
            }
        } else {
            final TerminalInteractionResult result = new TerminalInteractionResult();
            result.setInteractionType(TerminalInteractionType.HOST_PROCESSING);
            result.setGatewayResponse(authSaleResponse);
            this.runOnWorkerThread(new Runnable() {
                public void run() {
                    TransManager.this.terminalController.sendTerminalInteractionResult(result);
                }
            });
        }
    }

    private void handleCardReaderNotConnected() {
        if (transCallback != null) {
            transCallback.callbackOnError(new Error() {
                public ErrorType getType() {
                    return ErrorType.TERMINAL_ERROR;
                }

                public String getMessage() {
                    return "Card reader not connected.";
                }
            });
        }
    }

    private void handleGatewayControllerNotInitialized() {
        if (transCallback != null) {
            transCallback.callbackOnError(new Error() {
                public ErrorType getType() {
                    return ErrorType.NOT_INITIALIZED;
                }

                public String getMessage() {
                    return "Gateway controller is not available. The request cannot be processed.";
                }
            });
        }

    }

    private void handleGatewayResponse(GatewayResponse gatewayResponse) {
        Timber.d("handleGatewayResponse() called with: gatewayResponse=[%s]", new Object[]{gatewayResponse});
        if (transactionRequest == null) {
            Timber.e("GatewayResponse received after transaction session has completed");
        } else {
            if (gatewayResponse != null && gatewayResponse.getGatewayAction() != null) {
                populateTransactionResponseMap(gatewayResponse);
                if (safEnabled && checkSafSupportedGatewayAction(initialGatewayRequest) && gatewayResponse.isSafError()) {
                    if (initialGatewayRequest.isSAFIndicator()) {
                        callbackOnSafError(ErrorType.NOT_CONNECTED, "Failed to connect to gateway.");
                    } else {
                        storeTransactionOffline(initialGatewayRequest);
                    }

                    return;
                }

                switch (gatewayResponse.getGatewayAction()) {
                    case SALE:
                        handleSaleResponse(gatewayResponse);
                        break;
                    case TIP_ADJUST:
                    case REFUND:
                    case PARTIAL_REFUND:
                        this.handleGenericResponse(gatewayResponse, false, (String)null);
                        break;
                    case REVERSAL:
                        handleReversalResponse(gatewayResponse);
                        break;
                    case AUTH:
                        this.handleAuthResponse(gatewayResponse);
                        break;
                    case VERIFY:
                        this.handleVerifyResponse(gatewayResponse);
                        break;
                    case CAPTURE:
                    case VOID:
                        if (isTransactionResultPostAuthReversal(transactionRequest.getTransactionType())) {
                            handlePostAuthReversalResponse(gatewayResponse, gatewayResponse);
                        } else {
                            handleGenericResponse(gatewayResponse, false, (String) null);
                        }
                        break;
                    case FORCE_AUTH:
                        //this.handleForcedAuthResponse(gatewayResponse);
                        break;
                    case INCREMENT:
                        handleIncrementalAuthGatewayResponse(gatewayResponse);
                        break;
                    case TRANSACTION_ADJUSTMENT:
                        this.handleTransactionAdjustmentResponse(this.gatewayResponse, gatewayResponse);
                        break;
                    case BATCH_CLOSE:
                        this.handleBatchCloseResponse(gatewayResponse);
                        break;
                    case TOKENIZE_CARD:
                        //this.handleTokenizeCardResponse(gatewayResponse);
                    case SURCHARGE:
                        break;
                    default:
                        if (safEnabled && checkSafSupportedGatewayAction(initialGatewayRequest)) {
                            this.storeTransactionOffline(initialGatewayRequest);
                            return;
                        }

                        if (transCallback != null) {
                            transCallback.callbackOnError(new Error() {
                                public ErrorType getType() {
                                    return ErrorType.INVALID_DATA;
                                }

                                public String getMessage() {
                                    return "The gateway responded with an action that could not be processed: action=[" + gatewayResponse.getGatewayAction() + "]";
                                }
                            });
                        }
                }
            }
        }
    }


    private void handleTransactionAdjustmentResponse(GatewayResponse originalGatewayResponse,
                                                     GatewayResponse lastGatewayResponse) {
        Timber.d("handleTransactionAdjustmentResponse() called with: originalGatewayResponse=[" +
                originalGatewayResponse + "] and lastGatewayResponse=[" + lastGatewayResponse +
                "]");

        if (!lastGatewayResponse.isApproved()) {
            Timber.w(
                    "TransactionAdjustment failed. Proceed with regular response of previous operation.");
            transactionResponseMap.clear();
            transactionResponseCodeMap.clear();
            populateTransactionResponseMap(originalGatewayResponse);
        }

        // In case we are in presence of a non Emv Auth Sale transaction
        CardDataSourceType cardDataSourceType = originalGatewayResponse.getCardDataSourceType();
        if (terminalController == null || (cardDataSourceType != CardDataSourceType.SCR &&
                cardDataSourceType != CardDataSourceType.CONTACTLESS_EMV)) {
            // TODO: Verify later if quick chip logic should be added here
            handleAuthSaleGatewayResponse(originalGatewayResponse);
        }
        // Transaction Adjustment of an Emv Auth, Sale
        else {
            if (transactionResponseForAdjustment != null) {
                if (transCallback != null) {
                    resetFields();
                    transCallback
                            .callbackOnTransactionResponse(transactionResponseForAdjustment);
                }
            } else {
                Timber.w("No Transaction Response present for Adjustment.");
            }
        }
    }

    private void handleBatchCloseResponse(GatewayResponse gatewayResponse) {
        if (transactionRequest == null) {
            Timber.d("handleBatchCloseResponse() called with null transactionRequest");
            handleUnexpectedStateError();
            return;
        }

        final TransactionResponse.Builder builder = new TransactionResponse.Builder();
        builder.setTransactionType(transactionRequest.getTransactionType())
                .setResponseCodeMap(transactionResponseCodeMap)
                .setResponseMap(transactionResponseMap)
                .setOriginalGatewayTxnId(gatewayResponse.getOriginalGatewayTxnId())
                .setOriginalRspDT(gatewayResponse.getOriginalRspDT())
                .setOriginalClientTxnId(gatewayResponse.getOriginalClientTxnId())
                .setOriginalAuthCode(gatewayResponse.getOriginalAuthCode())
                .setOriginalRefNbr(gatewayResponse.getOriginalRefNbr())
                .setOriginalAuthAmt(gatewayResponse.getOriginalAuthAmt())
                .setOriginalCardType(gatewayResponse.getOriginalCardType())
                .setOriginalCardNbrLast4(gatewayResponse.getOriginalCardNbrLast4());
        updateTransactionResult(builder, gatewayResponse, false);
        invokeTransactionResponseCallback(builder);
    }

    /**
     * Invoke when the TransactionManager is in an irrecoverable state.
     */
    private void handleUnexpectedStateError() {
        if (transCallback != null) {
            transCallback.callbackOnError(new Error() {
                @Override
                public ErrorType getType() {
                    return ErrorType.NOT_INITIALIZED;
                }

                @Override
                public String getMessage() {
                    return "An unexpected error occurred. Please try again later.";
                }
            });
        }
    }

    private void handleReversalResponse(GatewayResponse reversalResponse) {
        TransactionResponse.Builder builder = new TransactionResponse.Builder();
        builder.setTransactionType(transactionRequest.getTransactionType())
                .setApprovedAmount(reversalResponse.getApprovedAmount())
                .setResponseMap(transactionResponseMap)
                .setResponseCodeMap(transactionResponseCodeMap)
                .setHostTransactionId(reversalResponse.getGatewayTransactionId())
                .setOriginalRspDT(reversalResponse.getOriginalRspDT())
                .setOriginalClientTxnId(reversalResponse.getOriginalClientTxnId())
                .setTenderType(transactionRequest.getTenderType());

        if (reversalResponse.isError()) {
            builder.setTransactionResult(TransactionResultType.DECLINED_ONLINE);
        } else if (postAuthReversalPending && reversalResponse.isApproved()) {
            /* The reversal undid an already authorized transaction, report it as such instead of
               reporting the reversal itself as an approved sale. */
            builder.setTransactionResult(TransactionResultType.POST_AUTH_REVERSAL);
        } else {
            builder.setTransactionResult(reversalResponse.isApproved() ? TransactionResultType.APPROVED : TransactionResultType.DECLINED_ONLINE);
        }

        invokeTransactionResponseCallback(builder);
    }

    private void handleIncrementalAuthGatewayResponse(GatewayResponse incrementalAuthResponse) {
        TransactionResponse.Builder builder = new TransactionResponse.Builder();
        builder.setTransactionType(transactionRequest.getTransactionType())
                .setApprovedAmount(incrementalAuthResponse.getApprovedAmount())
                .setResponseMap(transactionResponseMap)
                .setResponseCodeMap(transactionResponseCodeMap)
                .setHostTransactionId(incrementalAuthResponse.getGatewayTransactionId())
                .setOriginalRspDT(incrementalAuthResponse.getOriginalRspDT())
                .setOriginalClientTxnId(incrementalAuthResponse.getOriginalClientTxnId())
                .setTenderType(transactionRequest.getTenderType());

        if (incrementalAuthResponse.isGatewayTimeout() && isTransactionReversible(transactionRequest, incrementalAuthResponse)) {
            builder.setTransactionResult(TransactionResultType.CANCELLED);
            performVoid(transactionRequest, incrementalAuthResponse);
        } else if (incrementalAuthResponse.isError()) {
            builder.setTransactionResult(TransactionResultType.DECLINED_ONLINE);
        } else {
            builder.setTransactionResult(incrementalAuthResponse.isApproved() ? TransactionResultType.APPROVED : TransactionResultType.DECLINED_ONLINE);
        }
        invokeTransactionResponseCallback(builder);
    }

    private void handleVerifyResponse(GatewayResponse verifyResponse) {
        gatewayResponse = verifyResponse;
        final TransactionResponse.Builder builder = new TransactionResponse.Builder();
        builder.setCardType(verifyResponse.getCardType())
                .setTransactionType(transactionRequest.getTransactionType())
                // Approved amount will always be null or zero for this gateway action
                .setApprovedAmount(verifyResponse.getApprovedAmount())
                .setHostAuthCode(verifyResponse.getAuthCode())
                .setToken(verifyResponse.getToken())
                .setCardBrandTxnId(verifyResponse.getCardBrandTxnId())
                .setResponseCodeMap(transactionResponseCodeMap)
                .setResponseMap(transactionResponseMap)
                .setOriginalGatewayTxnId(verifyResponse.getOriginalGatewayTxnId())
                .setOriginalRspDT(verifyResponse.getOriginalRspDT())
                .setOriginalClientTxnId(verifyResponse.getOriginalClientTxnId())
                .setOriginalAuthCode(verifyResponse.getOriginalAuthCode())
                .setOriginalRefNbr(verifyResponse.getOriginalRefNbr())
                .setOriginalAuthAmt(verifyResponse.getOriginalAuthAmt())
                .setOriginalCardType(verifyResponse.getOriginalCardType())
                .setOriginalCardNbrLast4(verifyResponse.getOriginalCardNbrLast4());

        if (cardData != null) {
            populateTransactionResponsePaymentInfo(builder, cardData, gatewayResponse);
        }
        builder.setHostTransactionId(verifyResponse.getGatewayTransactionId())
                .setTenderType(transactionRequest.getTenderType());

        if (verifyResponse.isGatewayTimeout() || verifyResponse.isError()) {
            builder.setTransactionResult(TransactionResultType.DECLINED_ONLINE);
        } else {
            builder.setTransactionResult(
                    verifyResponse.isApproved() ? TransactionResultType.APPROVED :
                            TransactionResultType.DECLINED_ONLINE);
        }

        invokeTransactionResponseCallback(builder);
    }

    private void handleGenericResponse(GatewayResponse gatewayResponse, boolean isStoredAsSafTransaction, String safId) {
        TransactionResponse.Builder builder = new TransactionResponse.Builder();
        builder.setCardType(gatewayResponse.getCardType())
                .setTransactionType(transactionRequest.getTransactionType())
                .setApprovedAmount(gatewayResponse.getApprovedAmount())
                .setTipAmount(gatewayResponse.getTipAmount())
                .setResponseMap(transactionResponseMap)
                .setResponseCodeMap(transactionResponseCodeMap)
                .setHostAuthCode(gatewayResponse.getAuthCode())
                .setOriginalGatewayTxnId(gatewayResponse.getOriginalGatewayTxnId())
                .setOriginalRspDT(gatewayResponse.getOriginalRspDT())
                .setOriginalClientTxnId(gatewayResponse.getOriginalClientTxnId())
                .setOriginalAuthCode(gatewayResponse.getOriginalAuthCode())
                .setOriginalRefNbr(gatewayResponse.getOriginalRefNbr())
                .setOriginalAuthAmt(gatewayResponse.getOriginalAuthAmt())
                .setOriginalCardType(gatewayResponse.getOriginalCardType())
                .setOriginalCardNbrLast4(gatewayResponse.getOriginalCardNbrLast4());

        if (cardData != null) {
            this.populateTransactionResponsePaymentInfo(builder, cardData, gatewayResponse);
        } else {
            Timber.w("Unable to add card data source, masked PAN, Card Type and receipt because mCardData is null.");
        }

        builder.setHostTransactionId(gatewayResponse.getGatewayTransactionId()).setTenderType(transactionRequest.getTenderType());
        if (isStoredAsSafTransaction) {
            builder.setPosReferenceNumber(gatewayResponse.getPosReferenceId());
        }

        this.updateTransactionResult(builder, gatewayResponse, isStoredAsSafTransaction);
        if (safCallback != null && transactionRequest.isSaf()) {
            this.gatewayResponse = gatewayResponse;
            if (isStoredAsSafTransaction) {
                invokeTransactionResponseCallback(builder);
            }
        } else {
            invokeTransactionResponseCallback(builder);
        }
    }

    private void handleMissingHostAction(@Nullable CardData cardData) {
        Timber.d("handleMissingHostAction called with cardData=[%s]", cardData);
        if (cardData != null) {
            if (cardData.getCardDataSource() != CardDataSourceType.MSR && cardData.getCardDataSource() != CardDataSourceType.CONTACTLESS_MSR) {
                if (cardData.getCardDataSource() == CardDataSourceType.CONTACTLESS_EMV || cardData.getCardDataSource() == CardDataSourceType.SCR) {
                    handleMissingHostActionEmv();
                }
            } else {
                handleMissingHostActionMsr();
            }
        } else if (transCallback != null) {
            transCallback.callbackOnError(new Error() {
                public ErrorType getType() {
                    return ErrorType.INVALID_DATA;
                }

                public String getMessage() {
                    return "Invalid gateway operation specified.";
                }
            });
        }
    }

    private void handleMissingHostActionEmv() {
        if (terminalController != null) {
            terminalController.cancel();
        }
    }

    private void handleMissingHostActionMsr() {
        if (transCallback != null) {
            transCallback.callbackOnError(new Error() {
                public ErrorType getType() {
                    return ErrorType.INVALID_DATA;
                }

                public String getMessage() {
                    return "Invalid operation specified. The transaction could not be completed.";
                }
            });
        }
    }

    private void handlePostAuthReversalResponse(GatewayResponse originalGatewayResponse, GatewayResponse lastGatewayResponse) {
        if (lastGatewayResponse.isApproved()) {
            transactionResponseMap.clear();
            transactionResponseCodeMap.clear();
            if (this.getTransactionType(originalGatewayResponse.getGatewayAction()) != null) {
                transactionResponseMap.put(this.getTransactionType(originalGatewayResponse.getGatewayAction()), "Tender authorization reversed");
            }
        }

        TransactionResponse.Builder builder = new TransactionResponse.Builder();
        builder.setCardType(originalGatewayResponse.getCardType())
                .setTransactionType(transactionRequest.getTransactionType())
                .setApprovedAmount(0L)
                .setResponseMap(transactionResponseMap)
                .setHostAuthCode(originalGatewayResponse.getAuthCode())
                .setHostTransactionId(originalGatewayResponse.getGatewayTransactionId())
                .setTenderType(transactionRequest.getTenderType())
                .setOriginalGatewayTxnId(originalGatewayResponse.getOriginalGatewayTxnId())
                .setOriginalRspDT(originalGatewayResponse.getOriginalRspDT())
                .setOriginalClientTxnId(originalGatewayResponse.getOriginalClientTxnId())
                .setOriginalAuthCode(originalGatewayResponse.getOriginalAuthCode())
                .setOriginalRefNbr(originalGatewayResponse.getOriginalRefNbr())
                .setOriginalAuthAmt(originalGatewayResponse.getOriginalAuthAmt())
                .setOriginalCardType(originalGatewayResponse.getOriginalCardType())
                .setOriginalCardNbrLast4(originalGatewayResponse.getOriginalCardNbrLast4());

        if (cardData != null) {
            this.populateTransactionResponsePaymentInfo(builder, cardData, originalGatewayResponse);
        }

        builder.setMaskedPan(originalGatewayResponse.getMaskedPan());
        updateTransactionResult(builder, lastGatewayResponse, false);

        invokeTransactionResponseCallback(builder);
    }

    private void handleReversalMissingParameters() {
        String errorMessageStart = "Unable to void the transaction.";
        if (transCallback != null) {
            transCallback.callbackOnError(new Error() {
                public ErrorType getType() {
                    return ErrorType.INVALID_DATA;
                }

                public String getMessage() {
                    return "Unable to void the transaction. " + "Missing transaction id or reference number.";
                }
            });
        }

    }

    private void handleSaleResponse(GatewayResponse saleResponse) {
        gatewayResponse = saleResponse;
        handleAuthSaleResponse(saleResponse);
    }

    private void handleSurchargeResponse(SurchargeLookupResponse surchargeResponse) {
        if (surchargeResponse == null) {
            Timber.w("Surcharge response is null.");
            return;
        }

        gatewayRequest = surchargeResponse.getGatewayRequest();
        surchargeEligibility = surchargeResponse.getShouldSurcharge();
        if ("Y".equals(surchargeEligibility)) {
            if (gatewayRequest == null) {
                Timber.w("Surcharge response is missing the gateway request.");
                handleUnexpectedStateError();
                return;
            }
            surchargeCalculation = new SurchargeCalculation(gatewayRequest, LibraryConfigHelper.isSurchargePreTax());
            TerminalInteractionRequest request = new TerminalInteractionRequest(TerminalInteractionType.SURCHARGE_CONFIRMATION);
            request.setFinalAmount(surchargeCalculation.getTotal());
            request.setFinalSurchargeAmount(surchargeCalculation.getSurcharge());
            requestSurchargeConfirmation(request);
        } else {
            Timber.d("Surcharge not applicable. Proceeding with transaction.");
        }
    }


    private void handleTerminalControllerNotInitialized() {
        if (transCallback != null) {
            transCallback.callbackOnError(new Error() {
                public ErrorType getType() {
                    return ErrorType.NOT_INITIALIZED;
                }

                public String getMessage() {
                    return "Terminal controller is not available. The request cannot be processed.";
                }
            });
        }
    }

    private void handleTerminalResponseForMockedGateway(@NonNull TerminalResponse response, @NonNull TransactionResponse.Builder builder) {
        if (gatewayResponse == null || !gatewayResponse.isApproved() || response.getTerminalDecisionType() != TerminalDecisionType.DECLINED_ONLINE && response.getTerminalDecisionType() != TerminalDecisionType.REVERSAL_REQUIRED) {
            switch (response.getTerminalDecisionType()) {
                case APPROVED_ONLINE:
                case APPROVED_OFFLINE:
                    builder.setTransactionResult(TransactionResultType.APPROVED);
                    break;
                case DECLINED_ONLINE:
                    builder.setTransactionResult(TransactionResultType.DECLINED_ONLINE);
                    break;
                case DECLINED_OFFLINE:
                    builder.setTransactionResult(TransactionResultType.DECLINED_OFFLINE);
                    break;
                case REVERSAL_REQUIRED:
                case HOST_UNREACHABLE:
                default:
                    builder.setTransactionResult(TransactionResultType.DECLINED_ONLINE);
                    break;
                case TRANSACTION_CANCELLED:
                    builder.setTransactionResult(TransactionResultType.CANCELLED);
            }
        } else {
            builder.setTransactionResult(TransactionResultType.APPROVED);
        }
    }


    /**
     * Populate Stuff
     **/
    private void populateTransactionResponseMap(GatewayResponse gatewayResponse) {
        if (getTransactionType(gatewayResponse.getGatewayAction()) != null) {
            if (!TextUtils.isEmpty(gatewayResponse.getErrorMessage())) {
                if (gatewayResponse.getGatewayAction() != null) {
                    transactionResponseMap.put(getTransactionType(gatewayResponse.getGatewayAction()), gatewayResponse.getErrorMessage());
                    transactionResponseCodeMap.put(getTransactionType(gatewayResponse.getGatewayAction()), gatewayResponse.getGatewayResponseCode());
                }
            } else {
                transactionResponseMap.put(getTransactionType(gatewayResponse.getGatewayAction()), gatewayResponse.getGatewayResponseText());
                transactionResponseCodeMap.put(getTransactionType(gatewayResponse.getGatewayAction()), gatewayResponse.getGatewayResponseCode());
            }
        }

    }

    private void populateTransactionResponsePaymentInfo(@NonNull TransactionResponse.Builder builder, @NonNull CardData cardData, @Nullable GatewayResponse gatewayResponse) {
        builder.setCardDataSourceType(cardData.getCardDataSource());
        builder.setMaskedPan(CreditCardHelper.getPanFromCardData(cardData));
        builder.setCardType(CreditCardHelper.typeFromCardData(cardData));
        builder.setReceipt(this.getReceipt(transactionRequest, cardData, gatewayResponse));
    }

    private void performAuthSaleWithToken(@NonNull TransactionRequest request) {
        Timber.d("performAuthSaleWithToken()");
        final GatewayRequest.Builder builder = new GatewayRequest.Builder(
                request.getTransactionType() == TransactionType.AUTH ? GatewayAction.AUTH :
                        GatewayAction.SALE);
        builder.setTotal(request.getTotal() == null ? 0L : request.getTotal())
                .setToken(request.getToken())
                .setTax(request.getTax())
                .setTip(request.getTip())
                .setTenderType(request.getTenderType())
                .setTaxCategory(request.getTaxCategory())
                .setInvoiceNumber(request.getInvoiceNumber())
                .setAddress(request.getAddress())
                .setCardHolderId(request.getCardHolderId())
                .setAutoSubstantiation(request.getAutoSubstantiation())
                .setAllowDuplicates(request.isAllowDuplicates())
                .setCardBrandTxnId(request.getCardBrandTxnId())
                .setPosReferenceNumber(request.getGatewayTransactionId())
                .setTerminalInfo(terminalInfo);
        initialGatewayRequest = builder.build();

        if (safEnabled && (forceSafEnabled || isNetworkUnavailable(context))) {
            storeTransactionOffline(initialGatewayRequest);
        } else if (gatewayController != null) {
            runOnWorkerThread(new Runnable() {
                @Override
                public void run() {
                    lastTransactionAction = initialGatewayRequest.getGatewayAction();
                    gatewayController.sendRequest(initialGatewayRequest);
                }
            });
        } else {
            handleGatewayControllerNotInitialized();
        }
    }

    private void invokeTransactionResponseCallback(TransactionResponse.Builder builder) {
        if (transCallback != null) {
            if (!claimFinalResponse()) {
                return;
            }

            this.resetFields();
            transCallback.callbackOnTransactionResponse(builder.build());
        }
    }


    /**
     * Check Stuff
     **/
    private boolean checkSafSupportedGatewayAction(GatewayRequest initialGatewayRequest) {
        if (databaseController != null) {
            for (GatewayAction gatewayAction : databaseController.getSupportedGatewayActions()) {
                if (initialGatewayRequest.getGatewayAction() == gatewayAction) {
                    return true;
                }
            }
        }
        return false;
    }


    /**
     * Implementations
     **/
    private class GatewayListenerImpl implements GatewayListener {

        @Override
        public void onGatewayResponse(GatewayResponse gatewayResponse) {
            TransManager.this.handleGatewayResponse(gatewayResponse);
        }

        @Override
        public void onSurchargeResponse(SurchargeLookupResponse surchargeResponse) {
            TransManager.this.handleSurchargeResponse(surchargeResponse);
        }
    }

    private class TerminalListenerImpl implements TerminalListener {

        @Override
        public void onConnected(TerminalInfo terminalInfo) {
            Timber.d("onConnected()");
            TransManager.this.terminalInfo = terminalInfo;
            TransManager.this.connected = true;

//            if (TransManager.this.terminalConfig != null && TransManager.this.terminalConfig.getTerminalType().emvCapable) {
//                TransManager.this.loadTerminalConfiguration(TransManager.this.terminalConfig.getTerminalType(), TransManager.this.gatewayConfig.getGatewayType());
//            } else {
            this.dispatchOnTerminalConnected(TransManager.this.terminalInfo);
//            }
        }

        private void dispatchOnTerminalConnected(TerminalInfo terminalInfo) {
            TransManager.this.connectingTerminal.set(false);
            if (TransManager.this.connectionCallback != null) {
                TransManager.this.connectionCallback.callbackOnConnect(terminalInfo);
            }
        }

        @Override
        public void onTerminalInfo(TerminalInfo terminalInfo) {

        }

        @Override
        public void onTerminalStatusUpdate(TerminalStatus status) {
            if (status != null) {
                Timber.d("onTerminalStatusUpdate() called with status=[%s]", status.name());
            }

            Timber.d("dispatchTransactionStatus() called with: status=[%s]", status);
            if (cancelRequested.get() && !requiresIntegratorAction(status)) {
                /* A cancellation is pending. Suppress the informational status updates so the
                   integrator is not told the transaction is still progressing and does not
                   re-offer cancel. Statuses the integrator has to act on to unblock the terminal
                   flow are still forwarded. */
                Timber.d("Suppressing status [%s] because a cancellation is pending.", status);
                return;
            }

            if (TransManager.this.transCallback != null) {
                TransManager.this.transCallback.callbackOnStatusUpdate(terminalStatusToTransactionStatus(status));
            }
        }

        /**
         * @return true for statuses the integrator must react to in order for the terminal flow to
         * continue (card removal prompts, fallback prompts). Suppressing these would park the
         * transaction forever.
         */
        private boolean requiresIntegratorAction(TerminalStatus status) {
            if (status == null) {
                return false;
            }

            switch (status) {
                case REMOVE_CARD:
                case DO_NOT_REMOVE_CARD:
                case CARD_REMOVED_AFTER_TRANSACTION_COMPLETE:
                case CARD_DETECTED:
                case TECHNICAL_FALLBACK_INITIATED:
                case CONTACTLESS_INTERFACE_FAILED_TRY_CONTACT:
                case ICC_SWIPE_DETECTED:
                    return true;
                default:
                    return false;
            }
        }

        @Nullable
        private TransactionStatus terminalStatusToTransactionStatus(TerminalStatus terminalStatus) {
            if (terminalStatus == null) {
                return null;
            } else {
                Timber.d("terminalStatusToTransactionStatus: terminalStatus=%s", terminalStatus);
                switch (terminalStatus) {
                    case WAITING_FOR_CARD:
                        return TransactionStatus.WAITING_FOR_CARD;
                    case BAD_READ_DETECTED:
                        return TransactionStatus.BAD_READ;
                    case MULTIPLE_CARDS_DETECTED:
                        return TransactionStatus.MULTIPLE_CARDS_DETECTED;
                    case ICC_SWIPE_DETECTED:
                        return TransactionStatus.ICC_SWIPED;
                    case TECHNICAL_FALLBACK_INITIATED:
                        return TransactionStatus.TECHNICAL_FALLBACK_INITIATED;
                    case CARD_DETECTED:
                        return TransactionStatus.CARD_READ;
                    case CARD_READ_ERROR:
                        return TransactionStatus.CARD_READ_ERROR;
                    case CARD_REMOVED_AFTER_TRANSACTION_COMPLETE:
                        return TransactionStatus.CARD_REMOVED_AFTER_TRANSACTION_COMPLETE;
                    case CONTACTLESS_CARD_STILL_IN_FIELD:
                        return TransactionStatus.CONTACTLESS_CARD_STILL_IN_FIELD;
                    case CONTACTLESS_INTERFACE_FAILED_TRY_CONTACT:
                        return TransactionStatus.CONTACTLESS_INTERFACE_FAILED_TRY_CONTACT;
                    case DO_NOT_REMOVE_CARD:
                        return TransactionStatus.DO_NOT_REMOVE_CARD;
                    case ENTER_PIN:
                        return TransactionStatus.ENTER_PIN;
                    case LAST_PIN_ATTEMPT:
                        return TransactionStatus.LAST_PIN_ATTEMPT;
                    case PIN_ACCEPTED:
                        return TransactionStatus.PIN_ACCEPTED;
                    case RETRY_PIN:
                        return TransactionStatus.RETRY_PIN;
                    case REMOVE_CARD:
                        return TransactionStatus.REMOVE_CARD;
                    case CONFIGURING:
                        return TransactionStatus.CONFIGURING;
                    case SEE_PHONE:
                        return TransactionStatus.SEE_PHONE;
                    case INSERT_CARD:
                        return TransactionStatus.INSERT_CARD;
                    case INSERT_TAP_CARD:
                        return TransactionStatus.INSERT_TAP_CARD;
                    case REINSERT_CARD:
                        return TransactionStatus.REINSERT_CARD;
                    case TAP_CARD:
                        return TransactionStatus.TAP_CARD;
                    case TAP_AGAIN_CARD:
                        return TransactionStatus.TAP_AGAIN_CARD;
                    case TRY_ANOTHER_CARD:
                        return TransactionStatus.TRY_ANOTHER_CARD;
                    case SWIPE_CARD:
                        return TransactionStatus.SWIPE_CARD;
                    case TAP_SWIPE_CARD:
                        return TransactionStatus.TAP_SWIPE_CARD;
                    case INSERT_SWIPE_OR_TRY_ANOTHER_CARD:
                        return TransactionStatus.INSERT_SWIPE_OR_TRY_ANOTHER_CARD;
                    case PROCESSING:
                        return TransactionStatus.PROCESSING;
                    default:
                        return null;
                }
            }
        }

        @Override
        public void onTerminalResponseReceived(TerminalResponse response) {
            Timber.d("onTerminalResponseReceived() with: response=[%s]", response);
            if (response.getTerminalAction() == TerminalAction.SETUP && response.getTerminalDecisionType() != TerminalDecisionType.TRANSACTION_CANCELLED) {
                this.dispatchOnTerminalConnected(TransManager.this.terminalInfo);
            } else {
                if (response != null) {
                    if (response.getTerminalAction() == TerminalAction.START_CARD) {
                        TransManager.this.processStartCardResponse(response);
                    } else {
                        TransManager.this.processTerminalResponse(response);
                    }
                } else {
                    transCallback.callbackOnError(new Error() {
                        public ErrorType getType() {
                            return ErrorType.INVALID_DATA;
                        }

                        public String getMessage() {
                            return "No response received from the terminal.";
                        }
                    });
                }
            }
        }

        @Override
        public void onTerminalInteractionRequested(TerminalInteractionRequest request) {
            switch (request.getInteractionType()) {
                case EMV_APPLICATION_SELECTION:
                    TransManager.this.requestEmvAppSelection(request);
                    break;
                case FINAL_AMOUNT_CONFIRMATION:
                    TransManager.this.requestAmountConfirmation(request);
                    break;
                case FINAL_AMOUNT_REQUEST:
                    //TransManager.this.requestFinalAmount();
                    break;
                case HOST_PROCESSING:
                    TransManager.this.performHostProcessing(request);
                    break;
                case REQUEST_PROCEED_HOST_PROCESSING:
                    TransManager.this.requestProceedHostProcessingConfirmation(request);
                    break;
                case SURCHARGE_CONFIRMATION:
                    TransManager.this.requestSurchargeConfirmation(request);
            }
        }

        @Override
        public void onDisconnected() {
            TransManager.this.connected = false;
            if (TransManager.this.connectionCallback != null) {
                TransManager.this.connectionCallback.callbackOnDisconnect();
            }

            if (TransManager.this.connectingTerminal.get()) {
                TransManager.this.connectingTerminal.set(false);
            } else if (TransManager.this.transCallback != null) {
                TransManager.this.transCallback.callbackOnError(new Error() {
                    public ErrorType getType() {
                        return ErrorType.NOT_CONNECTED;
                    }

                    public String getMessage() {
                        return "The terminal is disconnected.";
                    }
                });
            }
        }

        @Override
        public void onError(TerminalError error, String message) {
            if (TransManager.this.connectingTerminal.get()) {
                // Dispatch Terminal Connection Error
                if (TransManager.this.connectionCallback != null) {
                    TransManager.this.connectionCallback.callbackOnError(new Error() {
                        public ErrorType getType() {
                            return ErrorType.TERMINAL_ERROR;
                        }

                        public String getMessage() {
                            return "A connection could not be established with the terminal. Please try again.";
                        }
                    });
                }

                TransManager.this.connectingTerminal.set(false);
                TransManager.this.connectionCallback = null;
            } else if (TransManager.this.transCallback != null) {
                TransManager.this.transCallback.callbackOnError(new Error() {
                    public ErrorType getType() {
                        return error.equals(TerminalError.BT_READER_ALREADY_PAIRED) ? ErrorType.TERMINAL_ERROR : ErrorType.INVALID_DATA;
                    }

                    public String getMessage() {
                        return error.equals(TerminalError.BT_READER_ALREADY_PAIRED) ? "The bluetooth reader is currently paired to another device" : message;
                    }
                });
                TransManager.this.resetFields();
            }
        }
    }


    @Override
    public void dispose() {
        gatewayController = null;
        if (terminalController != null) {
            terminalController.disconnect();
            terminalController = null;
        }
    }
}
