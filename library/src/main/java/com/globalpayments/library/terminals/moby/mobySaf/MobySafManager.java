package com.globalpayments.library.terminals.moby.mobySaf;

import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.tsys.payments.database.portico.PorticoDatabaseController;
import com.tsys.payments.library.db.DatabaseController;
import com.tsys.payments.library.db.SafListener;
import com.tsys.payments.library.db.entity.SafTransaction;
import com.tsys.payments.library.domain.GatewayConfiguration;
import com.tsys.payments.library.domain.TerminalConfiguration;
import com.tsys.payments.library.domain.TerminalInfo;
import com.tsys.payments.library.domain.TransactionRequest;
import com.tsys.payments.library.domain.TransactionResponse;
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
import com.tsys.payments.transaction.BuildConfig;
import com.tsys.payments.transaction.TransactionManager;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import timber.log.Timber;

public class MobySafManager {
    private static final String TAG = "MobySafManager";
    private static MobySafManager sInstance;
    private SafCallbackMarshaller mSafCallbackMarshaller;

    private TransactionExecutors mExecutors = TransactionExecutors.getInstance();

    private DatabaseController mDatabaseController;
    private GatewayController mGatewayController;

    // state data
    private List<SafTransaction> mAllSafTransactions;
    private ArrayDeque<SafTransaction> mSafTransactions;
    private SafTransaction mCurrentSafTransaction;
    private GatewayRequest mInitialGatewayRequest;
    private GatewayAction mLastTransactionAction;
    private TransactionRequest mTransactionRequest;

    private GatewayResponse mGatewayResponse;
    private Map<TransactionType, String> mTransactionResponseMap = new HashMap();
    private Map<TransactionType, String> mTransactionResponseCodeMap = new HashMap();

    private TerminalInfo mTerminalInfo;

    public static MobySafManager getInstance() {
        if (sInstance == null) {
            synchronized(MobySafManager.class) {
                if (sInstance == null) {
                    sInstance = new MobySafManager();
                }
            }
        }

        return sInstance;
    }


    public void initialize(TerminalConfiguration terminalConfig, GatewayConfiguration gatewayConfig) throws InitializationException {
        initGatewayController(gatewayConfig, terminalConfig);

        mDatabaseController = PorticoDatabaseController.getInstance();
        // set serial number to late gateway generate unique id
        mTerminalInfo = new TerminalInfo();
        mTerminalInfo.setTerminalType(terminalConfig.getTerminalType());
    }

    private void initGatewayController(GatewayConfiguration gatewayConfig, TerminalConfiguration terminalConfig) throws InitializationException {
        GatewayControllerFactory[] gatewayFactories = BuildConfig.GATEWAY_FACTORIES;
        if (gatewayFactories.length > 0) {
            if (terminalConfig != null && terminalConfig.getTerminalType() != null) {
                for(GatewayControllerFactory factory : gatewayFactories) {
                    GatewayType[] gatewayTypes = factory.getSupportedGateways();
                    if (gatewayTypes != null) {
                        for(GatewayType gateway : gatewayTypes) {
                            if (gateway == gatewayConfig.getGatewayType()) {
                                TerminalType[] supportedTerminals = factory.getSupportedTerminals();
                                if (supportedTerminals != null) {
                                    for(TerminalType terminal : supportedTerminals) {
                                        if (terminal == terminalConfig.getTerminalType()) {
                                            this.mGatewayController = factory.create(gatewayConfig, new GatewayListenerImpl());
                                            return;
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                for(GatewayControllerFactory factory : gatewayFactories) {
                    GatewayType[] gatewayTypes = factory.getSupportedGateways();
                    if (gatewayTypes != null) {
                        for(GatewayType gateway : gatewayTypes) {
                            if (gateway == gatewayConfig.getGatewayType()) {
                                this.mGatewayController = factory.create(gatewayConfig, new GatewayListenerImpl());
                                return;
                            }
                        }
                    }
                }
            }
        }

        throw new InitializationException("initGatewayController() :: No gateway controller factories are provided.", new Object[0]);
    }

    private class GatewayListenerImpl implements GatewayListener {
        private GatewayListenerImpl() {
        }

        public void onGatewayResponse(GatewayResponse gatewayResponse) {
            MobySafManager.this.handleGatewayResponse(gatewayResponse);
        }

        public void onSurchargeResponse(SurchargeLookupResponse surchargeResponse) {
            Timber.d( "Received unexpected surcharge response in SAF manager: = [%s]", new Object[]{surchargeResponse});
            throw new RuntimeException("Received unexpected surcharge response in SAF manager: " + surchargeResponse);
        }
    }

    private void handleGatewayResponse(final GatewayResponse gatewayResponse) {
        Timber.d("handleGatewayResponse() called with: gatewayResponse=[%s]", new Object[]{gatewayResponse});
        if (this.mTransactionRequest == null) {
            Timber.d("GatewayResponse received after transaction session has completed");
        } else {
            if (gatewayResponse != null && gatewayResponse.getGatewayAction() != null) {
                this.populateTransactionResponseMap(gatewayResponse);
                if (this.checkSafSupportedGatewayAction() && gatewayResponse.isSafError()) {
                    this.callbackOnSafError(ErrorType.NOT_CONNECTED, "Failed to connect to gateway.");
                    return;
                }

                switch (gatewayResponse.getGatewayAction()) {
                    case SALE:
                        this.handleSaleResponse(gatewayResponse);
                        break;
                    case TIP_ADJUST:
                    case REFUND:
                    case PARTIAL_REFUND:
                        this.handleGenericResponse(gatewayResponse);
                        break;
                    case AUTH:
                        this.handleAuthResponse(gatewayResponse);
                        break;
                    case CAPTURE:
                    case VOID:
                    case REVERSAL:
                        if (this.isTransactionResultPostAuthReversal(this.mTransactionRequest.getTransactionType())) {
                            this.handlePostAuthReversalResponse(this.mGatewayResponse, gatewayResponse);
                        } else {
                            this.handleGenericResponse(gatewayResponse);
                        }
                        break;
                    case TOKENIZE_CARD:
                        this.handleTokenizeCardResponse(gatewayResponse);
                    default:
                        return;
                }
            }

        }
    }

    private void handleTokenizeCardResponse(GatewayResponse gatewayResponse) {
        this.mGatewayResponse = gatewayResponse;
        TransactionResponse.Builder builder = new TransactionResponse.Builder();
        builder.setTransactionType(this.mTransactionRequest.getTransactionType()).setResponseMap(this.mTransactionResponseMap).setResponseCodeMap(this.mTransactionResponseCodeMap).setHostAuthCode(gatewayResponse.getAuthCode()).setHostTransactionId(gatewayResponse.getGatewayTransactionId()).setMaskedPan(gatewayResponse.getMaskedPan()).setCardHolderId(gatewayResponse.getCardHolderId()).setToken(gatewayResponse.getToken()).setCardBrandTxnId(gatewayResponse.getCardBrandTxnId()).setOriginalGatewayTxnId(gatewayResponse.getOriginalGatewayTxnId()).setOriginalRspDT(gatewayResponse.getOriginalRspDT()).setOriginalClientTxnId(gatewayResponse.getOriginalClientTxnId()).setOriginalAuthCode(gatewayResponse.getOriginalAuthCode()).setOriginalRefNbr(gatewayResponse.getOriginalRefNbr()).setOriginalAuthAmt(gatewayResponse.getOriginalAuthAmt()).setOriginalCardType(gatewayResponse.getOriginalCardType()).setOriginalCardNbrLast4(gatewayResponse.getOriginalCardNbrLast4());
        this.updateTransactionResult(builder, gatewayResponse);
        this.invokeSafProcessingResponseCallback(builder);
    }

    private void handleGenericResponse(GatewayResponse gatewayResponse) {
        TransactionResponse.Builder builder = new TransactionResponse.Builder();
        builder.setCardType(gatewayResponse.getCardType()).setTransactionType(this.mTransactionRequest.getTransactionType()).setApprovedAmount(gatewayResponse.getApprovedAmount()).setTipAmount(gatewayResponse.getTipAmount()).setResponseMap(this.mTransactionResponseMap).setResponseCodeMap(this.mTransactionResponseCodeMap).setHostAuthCode(gatewayResponse.getAuthCode()).setOriginalGatewayTxnId(gatewayResponse.getOriginalGatewayTxnId()).setOriginalRspDT(gatewayResponse.getOriginalRspDT()).setOriginalClientTxnId(gatewayResponse.getOriginalClientTxnId()).setOriginalAuthCode(gatewayResponse.getOriginalAuthCode()).setOriginalRefNbr(gatewayResponse.getOriginalRefNbr()).setOriginalAuthAmt(gatewayResponse.getOriginalAuthAmt()).setOriginalCardType(gatewayResponse.getOriginalCardType()).setOriginalCardNbrLast4(gatewayResponse.getOriginalCardNbrLast4());
//        if (this.mCardData != null) {
//            this.populateTransactionResponsePaymentInfo(builder, this.mCardData, this.mGatewayResponse);
//        } else {
//            Timber.w("Unable to add card data source, masked PAN, Card Type and receipt because mCardData is null.", new Object[0]);
//        }

        builder.setHostTransactionId(gatewayResponse.getGatewayTransactionId()).setTenderType(this.mTransactionRequest.getTenderType());

        this.updateTransactionResult(builder, gatewayResponse);
        this.mGatewayResponse = gatewayResponse;
        this.invokeSafProcessingResponseCallback(builder);
    }

    private void updateTransactionResult(TransactionResponse.Builder builder, GatewayResponse gatewayResponse) {
        if (!gatewayResponse.isGatewayTimeout() && !gatewayResponse.isError()) {
            if (this.isTransactionResultPostAuthReversal(this.mTransactionRequest.getTransactionType())) {
                builder.setTransactionResult(TransactionResultType.POST_AUTH_REVERSAL);
            } else {
                builder.setTransactionResult(gatewayResponse.isApproved() ? TransactionResultType.APPROVED : TransactionResultType.DECLINED_ONLINE);
            }
        } else {
            builder.setTransactionResult(TransactionResultType.CANCELLED);
        }
    }

    private void handlePostAuthReversalResponse(GatewayResponse originalGatewayResponse, GatewayResponse lastGatewayResponse) {
        if (lastGatewayResponse.isApproved()) {
            this.mTransactionResponseMap.clear();
            this.mTransactionResponseCodeMap.clear();
            if (this.getTransactionType(originalGatewayResponse.getGatewayAction()) != null) {
                this.mTransactionResponseMap.put(this.getTransactionType(originalGatewayResponse.getGatewayAction()), "Tender authorization reversed");
            }
        }

        TransactionResponse.Builder builder = new TransactionResponse.Builder();
        builder.setCardType(originalGatewayResponse.getCardType()).setTransactionType(this.mTransactionRequest.getTransactionType()).setApprovedAmount(0L).setResponseMap(this.mTransactionResponseMap).setHostAuthCode(originalGatewayResponse.getAuthCode()).setHostTransactionId(originalGatewayResponse.getGatewayTransactionId()).setTenderType(this.mTransactionRequest.getTenderType()).setOriginalGatewayTxnId(originalGatewayResponse.getOriginalGatewayTxnId()).setOriginalRspDT(originalGatewayResponse.getOriginalRspDT()).setOriginalClientTxnId(originalGatewayResponse.getOriginalClientTxnId()).setOriginalAuthCode(originalGatewayResponse.getOriginalAuthCode()).setOriginalRefNbr(originalGatewayResponse.getOriginalRefNbr()).setOriginalAuthAmt(originalGatewayResponse.getOriginalAuthAmt()).setOriginalCardType(originalGatewayResponse.getOriginalCardType()).setOriginalCardNbrLast4(originalGatewayResponse.getOriginalCardNbrLast4());

        builder.setMaskedPan(originalGatewayResponse.getMaskedPan());
        this.updateTransactionResult(builder, lastGatewayResponse);
    }

    private boolean isTransactionResultPostAuthReversal(TransactionType originalTransactionType) {
        return (this.mLastTransactionAction == GatewayAction.VOID || this.mLastTransactionAction == GatewayAction.REVERSAL) && (originalTransactionType == TransactionType.AUTH || originalTransactionType == TransactionType.SALE || originalTransactionType == TransactionType.FORCED_AUTH);
    }

    private void handleSaleResponse(GatewayResponse saleResponse) {
        this.mGatewayResponse = saleResponse;
        this.handleAuthSaleResponse(saleResponse);
    }

    private void handleAuthResponse(GatewayResponse authResponse) {
        this.mGatewayResponse = authResponse;
        this.handleAuthSaleResponse(authResponse);
    }

    private void handleAuthSaleResponse(GatewayResponse authSaleResponse) {
        Timber.d("handleAuthSaleResponse() called with: hostResponse=[%s]", new Object[]{authSaleResponse});
        this.handleAuthSaleGatewayResponse(authSaleResponse);
    }

    private void handleAuthSaleGatewayResponse(GatewayResponse authSaleResponse) {
        TransactionResponse.Builder builder = new TransactionResponse.Builder();
        builder.setCardType(authSaleResponse.getCardType()).setTransactionType(this.mTransactionRequest.getTransactionType()).setApprovedAmount(authSaleResponse.getApprovedAmount()).setTipAmount(authSaleResponse.getTipAmount() == null ? 0L : authSaleResponse.getTipAmount()).setHostAuthCode(authSaleResponse.getAuthCode()).setToken(authSaleResponse.getToken()).setCardBrandTxnId(authSaleResponse.getCardBrandTxnId()).setCardHolderId(authSaleResponse.getCardHolderId()).setResponseMap(this.mTransactionResponseMap).setResponseCodeMap(this.mTransactionResponseCodeMap).setOriginalGatewayTxnId(authSaleResponse.getOriginalGatewayTxnId()).setOriginalRspDT(authSaleResponse.getOriginalRspDT()).setOriginalClientTxnId(authSaleResponse.getOriginalClientTxnId()).setOriginalAuthCode(authSaleResponse.getOriginalAuthCode()).setOriginalRefNbr(authSaleResponse.getOriginalRefNbr()).setOriginalAuthAmt(authSaleResponse.getOriginalAuthAmt()).setOriginalCardType(authSaleResponse.getOriginalCardType()).setOriginalCardNbrLast4(authSaleResponse.getOriginalCardNbrLast4());

        builder.setHostTransactionId(authSaleResponse.getGatewayTransactionId()).setTenderType(this.mTransactionRequest.getTenderType());
        if (authSaleResponse.isGatewayTimeout() && this.isTransactionReversible(this.mTransactionRequest, authSaleResponse)) {
            builder.setTransactionResult(TransactionResultType.CANCELLED);
            Timber.e("Transaction timed out at gateway but is reversible. Marking transaction as cancelled and performing void to reverse the transaction at the gateway.");

        } else if (authSaleResponse.isError()) {
            builder.setTransactionResult(TransactionResultType.DECLINED_ONLINE);
        } else {
            builder.setTransactionResult(authSaleResponse.isApproved() ? TransactionResultType.APPROVED : TransactionResultType.DECLINED_ONLINE);
        }

        this.invokeSafProcessingResponseCallback(builder);

    }

    private void invokeSafProcessingResponseCallback(TransactionResponse.Builder builder) {
        SafTransaction currentSaf = this.mCurrentSafTransaction;
        if (currentSaf == null) {
            Timber.e("SAF response received but current SAF transaction is null");
        } else {
            builder.setSafLocalId(String.valueOf(currentSaf.getSafLocalId()));
        }

        List<TransactionResponse> transactionResponses = new ArrayList<>();

        TransactionResponse response = builder.build();
        transactionResponses.add(response);
        if (currentSaf != null && this.mSafTransactions != null && !this.mSafTransactions.isEmpty()) {
            String id = currentSaf.getSAFUniqueId();
            String gatewayTxnId = response.getGatewayTransactionId();
            if (id != null && gatewayTxnId != null) {
                for(SafTransaction safTransaction : this.mAllSafTransactions) {
                    if (id.equals(safTransaction.getTransactionId())) {
                        safTransaction.setTransactionId(gatewayTxnId);
                        this.runOnWorkerThread(() -> this.mDatabaseController.updateSafTransactionId(safTransaction, gatewayTxnId));
                    }
                }

                for(SafTransaction safTransaction : this.mSafTransactions) {
                    if (id.equals(safTransaction.getTransactionId())) {
                        safTransaction.setTransactionId(gatewayTxnId);
                    }
                }
            }
        }

        String safId = this.updateUniqueSAFId();
        this.mSafCallbackMarshaller.callbackOnStoredTransactionComplete(safId, response);
        if (this.mSafTransactions != null && !this.mSafTransactions.isEmpty()) {
            this.runOnWorkerThread(new Runnable() {
                public void run() {
                    MobySafManager.this.processPendingSafTransactions();
                }
            });
        } else {
            this.mSafCallbackMarshaller.callbackOnProcessingResponse(transactionResponses);
            this.onSafProcessingComplete();
        }
    }

    private void onSafProcessingComplete() {
        Timber.d("SAF processing complete, cleaning up");
        if (this.mSafTransactions != null) {
            this.mSafTransactions.clear();
        }

        this.mCurrentSafTransaction = null;
    }

    private String updateUniqueSAFId() {
        if (this.mGatewayResponse != null && !this.mGatewayResponse.isGatewayTimeout() && !this.mGatewayResponse.isSafError()) {
            if (this.mCurrentSafTransaction == null) {
                return null;
            } else {
                String id = this.mCurrentSafTransaction.getSAFUniqueId();
                id = id.substring(1);
                this.mDatabaseController.updateUniqueSafId(this.mCurrentSafTransaction, id);
                return id;
            }
        } else {
            return null;
        }
    }

    private boolean isTransactionReversible(@Nullable TransactionRequest transactionRequest, @Nullable GatewayResponse gatewayResponse) {
        Timber.d("isTransactionReversible() called.");
        if (transactionRequest == null) {
            Timber.d("transactionRequest is null.");
            return false;
        } else if (gatewayResponse == null) {
            Timber.d("gatewayResponse is null.");
            return false;
        } else {
            return !TextUtils.isEmpty(gatewayResponse.getGatewayTransactionId()) || !TextUtils.isEmpty(transactionRequest.getPosReferenceNumber()) || !TextUtils.isEmpty(transactionRequest.getGatewayTransactionId());
        }
    }

    private boolean checkSafSupportedGatewayAction() {
        if (this.mDatabaseController != null) {
            for(GatewayAction gatewayAction : this.mDatabaseController.getSupportedGatewayActions()) {
                if (this.mInitialGatewayRequest.getGatewayAction() == gatewayAction) {
                    return true;
                }
            }
        }

        return false;
    }

    private void populateTransactionResponseMap(GatewayResponse gatewayResponse) {
        if (this.getTransactionType(gatewayResponse.getGatewayAction()) != null) {
            if (!TextUtils.isEmpty(gatewayResponse.getErrorMessage())) {
                if (gatewayResponse.getGatewayAction() != null) {
                    this.mTransactionResponseMap.put(this.getTransactionType(gatewayResponse.getGatewayAction()), gatewayResponse.getErrorMessage());
                    this.mTransactionResponseCodeMap.put(this.getTransactionType(gatewayResponse.getGatewayAction()), gatewayResponse.getGatewayResponseCode());
                }
            } else {
                this.mTransactionResponseMap.put(this.getTransactionType(gatewayResponse.getGatewayAction()), gatewayResponse.getGatewayResponseText());
                this.mTransactionResponseCodeMap.put(this.getTransactionType(gatewayResponse.getGatewayAction()), gatewayResponse.getGatewayResponseCode());
            }
        }

    }

    public void processAllSafTransactions(@NonNull SafListener safListener) {
        Timber.d("processAllSafTransactions() called");
        this.mSafCallbackMarshaller = new SafCallbackMarshaller(safListener, mExecutors.getMainThread());
        if (this.mDatabaseController != null) {
            this.runOnWorkerThread(new Runnable() {
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
            this.callbackOnSafError(ErrorType.NOT_INITIALIZED, "Saf Database not initialized");
        }

    }

    private void processPendingSafTransactions() {
        Timber.d("processPendingSafTransactions() called");
        if (this.mSafTransactions != null && !this.mSafTransactions.isEmpty()) {
            SafTransaction currentSafTransaction = (SafTransaction)this.mSafTransactions.poll();
            if (currentSafTransaction == null) {
                this.callbackOnSafError(ErrorType.INVALID_DATA, "No SAF transactions to process — queue is empty");
            } else {
                this.mCurrentSafTransaction = currentSafTransaction;
                final GatewayRequest request = this.mDatabaseController.convertSafTransactionToGatewayRequest(currentSafTransaction);
                if (request == null) {
                    this.callbackOnSafError(ErrorType.INVALID_DATA, "Invalid SAF transaction");
                } else {
                    this.mInitialGatewayRequest = request;
                    this.mLastTransactionAction = request.getGatewayAction();
                    this.buildTransactionRequestFromInitialGatewayRequest();
                    this.mTransactionRequest.setSaf(true);
                    if (TransactionManager.getInstance().isConnected()) {
                        if (this.mGatewayController != null) {
                            this.runOnWorkerThread(new Runnable() {
                                public void run() {
                                    request.setTerminalInfo(MobySafManager.this.mTerminalInfo);
                                    MobySafManager.this.mGatewayController.sendRequest(request);
                                }
                            });
                        } else {
                            this.callbackOnSafError(ErrorType.NOT_INITIALIZED, "Gateway Controller not initialized");
                        }
                    } else {
                        this.callbackOnSafError(ErrorType.NOT_CONNECTED, "Device is not connected");
                    }

                }
            }
        } else {
            Timber.d("No SAF transactions to process");
        }
    }

    private void buildTransactionRequestFromInitialGatewayRequest() {
        Timber.d("buildTransactionRequestFromInitialGatewayRequest() called");
        this.mTransactionRequest = new TransactionRequest();
        this.mTransactionRequest.setTransactionType(this.getTransactionType(this.mInitialGatewayRequest.getGatewayAction()));
        this.mTransactionRequest.setTenderType(TenderType.CREDIT);
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
                case AUTH:
                    return TransactionType.AUTH;
                case VERIFY:
                case REVERSAL:
                case TRANSACTION_ADJUSTMENT:
                default:
                    return null;
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
            }
        }
    }

    private void callbackOnSafError(final ErrorType errorType, final String message) {
        Timber.d("callbackOnSafError() called with errorType=[" + errorType + "] and message=[" + message + "]");
        if (this.mSafCallbackMarshaller != null) {
            this.mSafCallbackMarshaller.callbackOnError(new Error() {
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

    private void runOnWorkerThread(Runnable runnable) {
        this.mExecutors.getWorkerThread().execute(runnable);
    }
}
