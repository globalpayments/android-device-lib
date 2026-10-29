package com.globalpayments.library.terminals.entities;

import android.text.TextUtils;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.tsys.payments.library.db.DatabaseController;
import com.tsys.payments.library.db.SafListener;
import com.tsys.payments.library.db.entity.SafTransaction;
import com.tsys.payments.library.domain.CardData;
import com.tsys.payments.library.domain.GatewayConfiguration;
import com.tsys.payments.library.domain.Receipt;
import com.tsys.payments.library.domain.TerminalConfiguration;
import com.tsys.payments.library.domain.TerminalInfo;
import com.tsys.payments.library.domain.TransactionRequest;
import com.tsys.payments.library.domain.TransactionResponse;
import com.tsys.payments.library.enums.CardDataSourceType;
import com.tsys.payments.library.enums.ErrorType;
import com.tsys.payments.library.enums.TenderType;
import com.tsys.payments.library.enums.TerminalType;
import com.tsys.payments.library.enums.TransactionResultType;
import com.tsys.payments.library.enums.TransactionType;
import com.tsys.payments.library.exceptions.Error;
import com.tsys.payments.library.exceptions.InitializationException;
import com.tsys.payments.library.gateway.GatewayController;
import com.tsys.payments.library.gateway.GatewayControllerFactory;
import com.tsys.payments.library.gateway.GatewayListener;
import com.tsys.payments.library.gateway.domain.GatewayRequest;
import com.tsys.payments.library.gateway.domain.GatewayResponse;
import com.tsys.payments.library.gateway.domain.SurchargeLookupResponse;
import com.tsys.payments.library.gateway.enums.GatewayAction;
import com.tsys.payments.library.gateway.enums.GatewayType;
import com.tsys.payments.library.utils.ReceiptHelper;
import com.tsys.payments.transaction.BuildConfig;
import com.tsys.payments.transaction.TransactionManager;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import timber.log.Timber;

public class SafTransManager {

    private static SafTransManager sInstance;

    private TransactionExecutors mExecutors = TransactionExecutors.getInstance();

    private GatewayController mGatewayController;
    private DatabaseController mDatabaseController;

    private TerminalInfo mTerminalInfo;
    private TransactionRequest mTransactionRequest;
    private GatewayResponse mGatewayResponse;
    private GatewayRequest mInitialGatewayRequest;
    private ArrayDeque<SafTransaction> mSafTransactions;
    private SafTransaction mCurrentSafTransaction;
    private List<TransactionResponse> mTransactionResponses;
    private List<SafTransaction> mAllSafTransactions;
    private boolean mCancelCurrentSafUpload;
    private GatewayAction mLastTransactionAction;
    private Map<TransactionType, String> mTransactionResponseMap = new HashMap<>();
    private Map<TransactionType, String> mTransactionResponseCodeMap = new HashMap<>();
    private SafCallbackMarshaller mSafCallbackMarshaller;

    public static SafTransManager getInstance() {
        if (sInstance == null) {
            synchronized (SafTransManager.class) {
                if (sInstance == null) {
                    sInstance = new SafTransManager();
                }
            }
        }
        return sInstance;
    }

    public void initialize(TerminalConfiguration terminalConfig, GatewayConfiguration gatewayConfig,
            DatabaseController databaseController) throws InitializationException {
        initGatewayController(gatewayConfig, terminalConfig);

        mDatabaseController = databaseController;
        // set serial number to late gateway generate unique id
        mTerminalInfo = new TerminalInfo();
        mTerminalInfo.setTerminalType(terminalConfig.getTerminalType());
    }

    private void initGatewayController(GatewayConfiguration gatewayConfig,
            TerminalConfiguration terminalConfig)
            throws InitializationException {
        GatewayControllerFactory[] gatewayFactories = BuildConfig.GATEWAY_FACTORIES;

        if (gatewayFactories.length > 0) {

            if (terminalConfig == null || terminalConfig.getTerminalType() == null) {
                //  Rely solely on the gateway id to initialize the gateway controller when a terminal setup is not
                // present.
                for (GatewayControllerFactory factory : gatewayFactories) {
                    GatewayType[] gatewayTypes = factory.getSupportedGateways();
                    if (gatewayTypes != null) {
                        for (GatewayType gateway : gatewayTypes) {
                            if (gateway == gatewayConfig.getGatewayType()) {
                                mGatewayController =
                                        factory.create(gatewayConfig, new GatewayListenerImpl());
                                return;
                            }
                        }
                    }
                }
            } else {

                for (GatewayControllerFactory factory : gatewayFactories) {
                    GatewayType[] gatewayTypes = factory.getSupportedGateways();
                    if (gatewayTypes != null) {
                        for (GatewayType gateway : gatewayTypes) {
                            if (gateway == gatewayConfig.getGatewayType()) {
                                TerminalType[] supportedTerminals = factory.getSupportedTerminals();

                                if (supportedTerminals != null) {
                                    for (TerminalType terminal : supportedTerminals) {

                                        if (terminal == terminalConfig.getTerminalType()) {
                                            mGatewayController =
                                                    factory.create(gatewayConfig,
                                                            new GatewayListenerImpl());
                                            return;
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        throw new

                InitializationException(
                "initGatewayController() :: No gateway controller factories are provided.");
    }

    private TransactionType getTransactionType(GatewayAction action) {
        if (action == null) return null;
        switch (action) {
            case SALE:
                return TransactionType.SALE;
            case TIP_ADJUST:
                return TransactionType.TIP_ADJUST;
            case AUTH:
                return TransactionType.AUTH;
            case CAPTURE:
                return TransactionType.CAPTURE;
            case REFUND:
                return TransactionType.REFUND;
            case PARTIAL_REFUND:
                return TransactionType.PARTIAL_REFUND;
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

    private boolean isTransactionReversible(@Nullable TransactionRequest transactionRequest,
            @Nullable GatewayResponse gatewayResponse) {
        Timber.d("isTransactionReversible() called.");
        if (transactionRequest == null) {
            Timber.e("transactionRequest is null.");
            return false;
        }
        if (gatewayResponse == null) {
            Timber.e("gatewayResponse is null.");
            return false;
        }
        return (!TextUtils.isEmpty(gatewayResponse.getGatewayTransactionId()) ||
                !TextUtils.isEmpty(transactionRequest.getPosReferenceNumber())
                || !TextUtils.isEmpty(transactionRequest.getGatewayTransactionId()));
    }

    private Receipt getReceipt(@NonNull TransactionRequest transactionRequest,
            @NonNull CardData cardData,
            @Nullable GatewayResponse gatewayResponse) {
        Timber.d("getReceipt() called with: transactionRequest=[%s], cardData=[%s], gatewayResponse=[%s]",
                transactionRequest, cardData, gatewayResponse);
        CardDataSourceType dataSourceType = cardData.getCardDataSource();
        if (dataSourceType == CardDataSourceType.SCR ||
                dataSourceType == CardDataSourceType.CONTACTLESS_MSR ||
                dataSourceType == CardDataSourceType.CONTACTLESS_EMV) {
            return ReceiptHelper
                    .buildEmvReceipt(transactionRequest, mInitialGatewayRequest, gatewayResponse,
                            cardData);
        } else if (dataSourceType == CardDataSourceType.KEYED ||
                dataSourceType == CardDataSourceType.MSR ||
                dataSourceType == CardDataSourceType.FALLBACK) {
            return ReceiptHelper.buildMsrReceipt(transactionRequest, mInitialGatewayRequest,
                    gatewayResponse, cardData);
        } else {
            Timber.w("Unable to build receipt for: CardDataSourceType=[%s]", dataSourceType);
            return null;
        }
    }

    public void processAllSafTransactions(@NonNull SafListener safListener) {
        mSafCallbackMarshaller = new SafCallbackMarshaller(safListener,
                mExecutors.getMainThread());

        if (mDatabaseController != null) {
            runOnWorkerThread(new Runnable() {
                @Override
                public void run() {
                    List<SafTransaction> safTransactions = mDatabaseController.getAllPendingSafTransactions();
                    if (safTransactions != null && !safTransactions.isEmpty()) {
                        mAllSafTransactions = new ArrayList<>(safTransactions);
                        mSafTransactions = new ArrayDeque<>(safTransactions);
                        processPendingSafTransactions();
                    } else {
                        callbackOnSafError(ErrorType.INVALID_DATA, "Saf Transactions not found");
                    }
                }
            });
        } else {
            callbackOnSafError(ErrorType.NOT_INITIALIZED, "Saf Database not initialized");
        }
    }

    public void processSafTransactionsByPosReferenceIds(@NonNull final List<Long> safLocalIds,
            @NonNull SafListener safListener) {

        mSafCallbackMarshaller = new SafCallbackMarshaller(safListener,
                TransactionExecutors.getInstance().getMainThread());

        if (mDatabaseController != null) {
            runOnWorkerThread(new Runnable() {
                @Override
                public void run() {
                    List<SafTransaction> safTransactions = mDatabaseController
                            .getSafTransactionsByLocalIdList(safLocalIds);
                    if (safTransactions != null && !safTransactions.isEmpty()) {
                        mSafTransactions = new ArrayDeque<>(safTransactions);
                        processPendingSafTransactions();
                    } else {
                        callbackOnSafError(ErrorType.INVALID_DATA, "Saf Transactions not found");
                    }
                }
            });
        } else {
            callbackOnSafError(ErrorType.NOT_INITIALIZED, "Saf Database not initialized");
        }
    }

    private void processPendingSafTransactions() {
        Timber.d("processPendingSafTransactions() called");

        if (mSafTransactions == null || mSafTransactions.isEmpty()) {
            Timber.d("No SAF transactions to process");
            return;
        }

        final SafTransaction currentSafTransaction = mSafTransactions.poll();
        if (currentSafTransaction == null) {
            callbackOnSafError(ErrorType.INVALID_DATA, "No SAF transactions to process — queue is empty");
            return;
        }

        mCurrentSafTransaction = currentSafTransaction;
        final GatewayRequest request =
                mDatabaseController.convertSafTransactionToGatewayRequest(currentSafTransaction);

        if (request == null) {
            callbackOnSafError(ErrorType.INVALID_DATA, "Invalid SAF transaction");
            return;
        }

        mInitialGatewayRequest = request;
        mLastTransactionAction = request.getGatewayAction();

        buildTransactionRequestFromInitialGatewayRequest();
        mTransactionRequest.setSaf(true);

        if (TransManager.getInstance().isConnected()) {
            if (mGatewayController != null) {
                runOnWorkerThread(new Runnable() {
                    @Override
                    public void run() {
                        request.setTerminalInfo(mTerminalInfo);
                        mGatewayController.sendRequest(request);
                    }
                });
            } else {
                callbackOnSafError(ErrorType.NOT_INITIALIZED, "Gateway Controller not initialized");
            }
        } else {
            callbackOnSafError(ErrorType.NOT_CONNECTED, "Device is not connected");
        }
    }

    /**
     * Several handle response methods assume transaction request is not null, setting here to avoid exceptions
     */
    private void buildTransactionRequestFromInitialGatewayRequest() {
        Timber.d("buildTransactionRequestFromInitialGatewayRequest() called");
        mTransactionRequest = new TransactionRequest();
        mTransactionRequest
                .setTransactionType(getTransactionType(mInitialGatewayRequest.getGatewayAction()));
        mTransactionRequest.setTenderType(TenderType.CREDIT);
    }

    private void callbackOnSafError(final ErrorType errorType, final String message) {
        Timber.d("callbackOnSafError() called with errorType=[%s] and message=[%s]", errorType,
                message);
        if (mSafCallbackMarshaller != null) {
            mSafCallbackMarshaller.callbackOnError(new Error() {
                @Override
                public ErrorType getType() {
                    return errorType;
                }

                @Override
                public String getMessage() {
                    return message;
                }
            });
        } else {
            Timber.d("mSafCallbackMarshaller not initialized");
        }
    }

    public void cancel() {
        //currently uploading SAF items
        if (mSafTransactions != null && !mSafTransactions.isEmpty()) {
            //clear the SAF transactions list so it finishes after the current item
            mCancelCurrentSafUpload = true;
        }
    }

    private boolean checkSafSupportedGatewayAction(GatewayRequest initialGatewayRequest) {
        if (mDatabaseController != null) {
            for (GatewayAction gatewayAction : mDatabaseController.getSupportedGatewayActions()) {
                if (initialGatewayRequest.getGatewayAction() == gatewayAction) {
                    return true;
                }
            }
        }
        return false;
    }

    private void handleGatewayResponse(final GatewayResponse gatewayResponse) {
        Timber.d("handleGatewayResponse() called with: gatewayResponse=[%s]", gatewayResponse);
        if (mTransactionRequest == null) {
            /* GatewayListener outlives a particular transaction session, meaning that a second response
               from the gateway may be received after resetFields() has been called and the response has been
               delivered to the application. In this situation nothing should be reported */
            Timber.e("GatewayResponse received after transaction session has completed");
            return;
        }
        if (gatewayResponse != null) {
            if (gatewayResponse.getGatewayAction() != null) {
                populateTransactionResponseMap(gatewayResponse);

                if (checkSafSupportedGatewayAction(mInitialGatewayRequest) && gatewayResponse.isSafError()) {
                    if (mInitialGatewayRequest.isSAFIndicator()) {
                        if (!gatewayResponse.getErrorMessage().isEmpty()){
                            callbackOnSafError(ErrorType.INVALID_DATA, gatewayResponse.getErrorMessage());
                        }
                        callbackOnSafError(ErrorType.NOT_CONNECTED, "Failed to connect to gateway.");
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
                        handleGenericResponse(gatewayResponse);
                        break;
                    case AUTH:
                        handleAuthResponse(gatewayResponse);
                        break;
                    case CAPTURE:
                    case VOID:
                    case REVERSAL:
                        if (isTransactionResultPostAuthReversal(
                                mTransactionRequest.getTransactionType())) {
                            handlePostAuthReversalResponse(mGatewayResponse, gatewayResponse);
                        } else {
                            handleGenericResponse(gatewayResponse);
                        }
                        break;
                    case TOKENIZE_CARD:
                        handleTokenizeCardResponse(gatewayResponse);
                        break;
                    default:
                        if (mSafCallbackMarshaller != null) {
                            mSafCallbackMarshaller.callbackOnError(new Error() {
                                @Override
                                public ErrorType getType() {
                                    return ErrorType.INVALID_DATA;
                                }

                                @Override
                                public String getMessage() {
                                    return "The gateway responded with an action that could not be processed: " +
                                            "action=[" + gatewayResponse.getGatewayAction() + "]";
                                }
                            });
                        }
                }
            }
        }
    }

    private void populateTransactionResponseMap(GatewayResponse gatewayResponse) {
        if (getTransactionType(gatewayResponse.getGatewayAction()) != null) {
            if (!TextUtils.isEmpty(gatewayResponse.getErrorMessage())) {
                if (gatewayResponse.getGatewayAction() != null) {
                    mTransactionResponseMap
                            .put(getTransactionType(gatewayResponse.getGatewayAction()),
                                    gatewayResponse.getErrorMessage());
                    mTransactionResponseCodeMap
                            .put(getTransactionType(gatewayResponse.getGatewayAction()),
                                    gatewayResponse.getGatewayResponseCode());
                }
            } else {
                mTransactionResponseMap
                        .put(getTransactionType(gatewayResponse.getGatewayAction()),
                                gatewayResponse.getGatewayResponseText());
                mTransactionResponseCodeMap
                        .put(getTransactionType(gatewayResponse.getGatewayAction()),
                                gatewayResponse.getGatewayResponseCode());
            }
        }
    }

    private void handleAuthResponse(GatewayResponse authResponse) {
        mGatewayResponse = authResponse;
        handleAuthSaleResponse(authResponse);
    }

    private void handleSaleResponse(GatewayResponse saleResponse) {
        mGatewayResponse = saleResponse;
        handleAuthSaleResponse(saleResponse);
    }

    private void handleAuthSaleResponse(GatewayResponse authSaleResponse) {
        Timber.d("handleSafAuthSaleResponse() called with: hostResponse=[" + authSaleResponse + "]");
        handleAuthSaleGatewayResponse(authSaleResponse);
    }

    private void handleAuthSaleGatewayResponse(GatewayResponse authSaleResponse) {
        final TransactionResponse.Builder builder = new TransactionResponse.Builder();
        builder.setCardType(authSaleResponse.getCardType())
                .setTransactionType(mTransactionRequest.getTransactionType())
                .setApprovedAmount(authSaleResponse.getApprovedAmount())
                .setTipAmount(authSaleResponse.getTipAmount() == null ?
                        0L : authSaleResponse.getTipAmount())
                .setHostAuthCode(authSaleResponse.getAuthCode())
                .setToken(authSaleResponse.getToken())
                .setCardBrandTxnId(authSaleResponse.getCardBrandTxnId())
                .setCardHolderId(authSaleResponse.getCardHolderId())
                .setResponseMap(mTransactionResponseMap)
                .setResponseCodeMap(mTransactionResponseCodeMap)
                .setOriginalGatewayTxnId(authSaleResponse.getOriginalGatewayTxnId())
                .setOriginalRspDT(authSaleResponse.getOriginalRspDT())
                .setOriginalClientTxnId(authSaleResponse.getOriginalClientTxnId())
                .setOriginalAuthCode(authSaleResponse.getOriginalAuthCode())
                .setOriginalRefNbr(authSaleResponse.getOriginalRefNbr())
                .setOriginalAuthAmt(authSaleResponse.getOriginalAuthAmt())
                .setOriginalCardType(authSaleResponse.getOriginalCardType())
                .setOriginalCardNbrLast4(authSaleResponse.getOriginalCardNbrLast4());

        builder.setHostTransactionId(authSaleResponse.getGatewayTransactionId())
                .setTenderType(mTransactionRequest.getTenderType());
        if (authSaleResponse.isGatewayTimeout() &&
                isTransactionReversible(mTransactionRequest, authSaleResponse)) {
            builder.setTransactionResult(TransactionResultType.CANCELLED);
        } else if (authSaleResponse.isError()) {
            builder.setTransactionResult(TransactionResultType.DECLINED_ONLINE);
        } else {
            builder.setTransactionResult(
                    authSaleResponse.isApproved() ? TransactionResultType.APPROVED :
                            TransactionResultType.DECLINED_ONLINE);
        }

        // Check if SafCallbackMarshaller is initialized, indicating that SAF processing is happening
        if (mSafCallbackMarshaller != null) {
            invokeSafProcessingResponseCallback(builder);
        }
    }

    private void handlePostAuthReversalResponse(GatewayResponse originalGatewayResponse,
            GatewayResponse lastGatewayResponse) {
        // In case Transactions are approved display success message otherwise leave host messages
        if (lastGatewayResponse.isApproved()) {
            mTransactionResponseMap.clear();
            mTransactionResponseCodeMap.clear();
            if (getTransactionType(originalGatewayResponse.getGatewayAction()) != null) {
                mTransactionResponseMap.put(getTransactionType(originalGatewayResponse
                        .getGatewayAction()), TransactionManager.REVERSAL_SUCCESS_MESSAGE);
            }
        }

        final TransactionResponse.Builder builder = new TransactionResponse.Builder();
        builder.setCardType(originalGatewayResponse.getCardType())
                .setTransactionType(mTransactionRequest.getTransactionType())
                .setApprovedAmount(0L)
                .setResponseMap(mTransactionResponseMap)
                .setHostAuthCode(originalGatewayResponse.getAuthCode())
                .setHostTransactionId(originalGatewayResponse.getGatewayTransactionId())
                .setTenderType(mTransactionRequest.getTenderType())
                .setOriginalGatewayTxnId(originalGatewayResponse.getOriginalGatewayTxnId())
                .setOriginalRspDT(originalGatewayResponse.getOriginalRspDT())
                .setOriginalClientTxnId(originalGatewayResponse.getOriginalClientTxnId())
                .setOriginalAuthCode(originalGatewayResponse.getOriginalAuthCode())
                .setOriginalRefNbr(originalGatewayResponse.getOriginalRefNbr())
                .setOriginalAuthAmt(originalGatewayResponse.getOriginalAuthAmt())
                .setOriginalCardType(originalGatewayResponse.getOriginalCardType())
                .setOriginalCardNbrLast4(originalGatewayResponse.getOriginalCardNbrLast4());

        builder.setMaskedPan(originalGatewayResponse.getMaskedPan());
        updateTransactionResult(builder, lastGatewayResponse);
    }

    private void handleTokenizeCardResponse(GatewayResponse gatewayResponse) {
        mGatewayResponse = gatewayResponse;
        final TransactionResponse.Builder builder = new TransactionResponse.Builder();
        builder.setTransactionType(mTransactionRequest.getTransactionType())
                .setResponseMap(mTransactionResponseMap)
                .setResponseCodeMap(mTransactionResponseCodeMap)
                .setHostAuthCode(gatewayResponse.getAuthCode())
                .setHostTransactionId(gatewayResponse.getGatewayTransactionId())
                .setMaskedPan(gatewayResponse.getMaskedPan())
                .setCardHolderId(gatewayResponse.getCardHolderId())
                .setToken(gatewayResponse.getToken())
                .setCardBrandTxnId(gatewayResponse.getCardBrandTxnId())
                .setOriginalGatewayTxnId(gatewayResponse.getOriginalGatewayTxnId())
                .setOriginalRspDT(gatewayResponse.getOriginalRspDT())
                .setOriginalClientTxnId(gatewayResponse.getOriginalClientTxnId())
                .setOriginalAuthCode(gatewayResponse.getOriginalAuthCode())
                .setOriginalRefNbr(gatewayResponse.getOriginalRefNbr())
                .setOriginalAuthAmt(gatewayResponse.getOriginalAuthAmt())
                .setOriginalCardType(gatewayResponse.getOriginalCardType())
                .setOriginalCardNbrLast4(gatewayResponse.getOriginalCardNbrLast4());

        updateTransactionResult(builder, gatewayResponse);

        // Check if SafCallbackMarshaller is initialized, indicating that SAF processing is happening
        if (mSafCallbackMarshaller != null) {
            invokeSafProcessingResponseCallback(builder);
        }
    }

    private void handleGenericResponse(GatewayResponse gatewayResponse) {
        final TransactionResponse.Builder builder = new TransactionResponse.Builder();
        builder.setCardType(gatewayResponse.getCardType())
                .setTransactionType(mTransactionRequest.getTransactionType())
                .setApprovedAmount(gatewayResponse.getApprovedAmount())
                .setTipAmount(gatewayResponse.getTipAmount())
                .setResponseMap(mTransactionResponseMap)
                .setResponseCodeMap(mTransactionResponseCodeMap)
                .setHostAuthCode(gatewayResponse.getAuthCode())
                .setOriginalGatewayTxnId(gatewayResponse.getOriginalGatewayTxnId())
                .setOriginalRspDT(gatewayResponse.getOriginalRspDT())
                .setOriginalClientTxnId(gatewayResponse.getOriginalClientTxnId())
                .setOriginalAuthCode(gatewayResponse.getOriginalAuthCode())
                .setOriginalRefNbr(gatewayResponse.getOriginalRefNbr())
                .setOriginalAuthAmt(gatewayResponse.getOriginalAuthAmt())
                .setOriginalCardType(gatewayResponse.getOriginalCardType())
                .setOriginalCardNbrLast4(gatewayResponse.getOriginalCardNbrLast4());

        builder.setHostTransactionId(gatewayResponse.getGatewayTransactionId())
                .setTenderType(mTransactionRequest.getTenderType());
        updateTransactionResult(builder, gatewayResponse);

        if (mSafCallbackMarshaller != null) {
            mGatewayResponse = gatewayResponse;
            invokeSafProcessingResponseCallback(builder);
        }
    }

    /**
     * Method to invoke callback to main thread or continue processing SAF transactions. Allows to check if SAF
     * processing is occurring to delete transaction from database once processed.
     *
     * @param builder TransactionResponse.Builder passed from calling handle response method.
     */
    private void invokeSafProcessingResponseCallback(TransactionResponse.Builder builder) {

        final SafTransaction currentSaf = mCurrentSafTransaction;

        if (currentSaf == null) {
            Timber.e("SAF response received but current SAF transaction is null");
        } else {
            builder.setSafLocalId(String.valueOf(currentSaf.getSafLocalId()));
        }

        if (mTransactionResponses == null) {
            mTransactionResponses = new ArrayList<>();
        }

        final TransactionResponse response = builder.build();
        mTransactionResponses.add(response);

        // Only do SAF dependency updates if we have a valid SAF transaction
        if (currentSaf != null && mSafTransactions != null && !mSafTransactions.isEmpty()) {
            final String id = currentSaf.getSAFUniqueId();
            final String gatewayTxnId = response.getGatewayTransactionId();

            if (id != null && gatewayTxnId != null) {
                // Update in-memory list
                for (SafTransaction safTransaction : mAllSafTransactions) {
                    if (id.equals(safTransaction.getTransactionId())) {
                        safTransaction.setTransactionId(gatewayTxnId);

                        runOnWorkerThread(() ->
                                mDatabaseController.updateSafTransactionId(
                                        safTransaction,
                                        gatewayTxnId
                                )
                        );
                    }
                }

                // Update SAF queue
                for (SafTransaction safTransaction : mSafTransactions) {
                    if (id.equals(safTransaction.getTransactionId())) {
                        safTransaction.setTransactionId(gatewayTxnId);
                    }
                }
            }
        }

        final String safId = updateUniqueSAFId();
        invokeSafTransactionComplete(safId, response);

        // Continue or finish SAF processing
        if (mSafTransactions != null && !mSafTransactions.isEmpty() && !mCancelCurrentSafUpload) {
            runOnWorkerThread(new Runnable() {
                @Override
                public void run() {
                    processPendingSafTransactions();
                }
            });
        } else {
            mSafCallbackMarshaller.callbackOnProcessingResponse(mTransactionResponses);
            onSafProcessingComplete();
        }
    }

    private String updateUniqueSAFId() {
        //Remove "P" from unique SAF ID to indicate it is no longer pending
        if (mGatewayResponse != null &&
                !(mGatewayResponse.isGatewayTimeout() || mGatewayResponse.isSafError())) {
            if (mCurrentSafTransaction == null) {
                return null;
            }
            String id = mCurrentSafTransaction.getSAFUniqueId();
            id = id.substring(1);

            //update the database
            mDatabaseController.updateUniqueSafId(mCurrentSafTransaction, id);
            return id;
        }
        return null;
    }

    private void invokeSafTransactionComplete(String id, TransactionResponse transactionResponse) {
        if (mSafCallbackMarshaller != null) {
            mSafCallbackMarshaller.callbackOnStoredTransactionComplete(id, transactionResponse);
        }
    }

    private boolean isTransactionResultPostAuthReversal(TransactionType originalTransactionType) {
        return (mLastTransactionAction == GatewayAction.VOID || mLastTransactionAction == GatewayAction.REVERSAL) &&
                ((originalTransactionType == TransactionType.AUTH) ||
                        (originalTransactionType == TransactionType.SALE)
                        || (originalTransactionType == TransactionType.FORCED_AUTH));
    }

    private void updateTransactionResult(TransactionResponse.Builder builder,
            GatewayResponse gatewayResponse) {
        if (gatewayResponse.isGatewayTimeout() || gatewayResponse.isError()) {
            builder.setTransactionResult(TransactionResultType.CANCELLED);
        } else if (isTransactionResultPostAuthReversal(mTransactionRequest.getTransactionType())) {
            builder.setTransactionResult(TransactionResultType.POST_AUTH_REVERSAL);
        } else {
            builder.setTransactionResult(
                    gatewayResponse.isApproved() ? TransactionResultType.APPROVED :
                            TransactionResultType.DECLINED_ONLINE);
        }
    }

    private void runOnWorkerThread(Runnable runnable) {
        mExecutors.getWorkerThread().execute(runnable);
    }

    private void onSafProcessingComplete() {
        Timber.d("SAF processing complete, cleaning up");
        if (mSafTransactions != null) {
            mSafTransactions.clear();
        }
        mCurrentSafTransaction = null;
        mCancelCurrentSafUpload = false;
        mTransactionResponses = null;
        mInitialGatewayRequest = null;
        mLastTransactionAction = null;
        mTransactionRequest = null;
        mGatewayResponse = null;
        mTransactionResponseMap = new HashMap<>();
        mTransactionResponseCodeMap = new HashMap<>();
        mSafCallbackMarshaller = null;
    }

    private class GatewayListenerImpl implements GatewayListener {

        @Override
        public void onGatewayResponse(GatewayResponse gatewayResponse) {
            handleGatewayResponse(gatewayResponse);
        }

        @Override
        public void onSurchargeResponse(SurchargeLookupResponse surchargeResponse) {
            //This shouldn't ever occur for SAF transactions
        }
    }
}
