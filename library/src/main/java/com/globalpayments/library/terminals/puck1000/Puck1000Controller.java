package com.globalpayments.library.terminals.puck1000;

import android.content.Context;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.castles.credit.bean.CommAppBean;
import com.castles.credit.bean.ContinueBean;
import com.castles.credit.bean.TxnRequestBean;
import com.castles.credit.bean.TxnResponseBean;
import com.castles.credit.bean.VersionBean;
import com.castles.credit.constant.CipherMode;
import com.castles.credit.constant.EMVResult;
import com.castles.credit.constant.EntryMode;
import com.castles.credit.constant.ErrorCode;
import com.castles.credit.constant.OnlineAction;
import com.castles.credit.constant.PaddingMethod;
import com.castles.credit.constant.ReturnCode;
import com.castles.credit.constant.Track2DataFormat;
import com.castles.credit.constant.TxnCurrency;
import com.castles.credit.constant.TxnType;
import com.castles.credit.context.AppContext;
import com.castles.credit.credit.Credit;
import com.castles.credit.credit.CreditTxnData;
import com.castles.credit.credit.callback.PaymentCallback;
import com.castles.credit.credit.constant.TxnResult;
import com.castles.credit.credit.event.*;
import com.castles.credit.util.ThreadPoolManager;
import com.castles.payment.debug.DebugUtilEx;
import com.google.gson.Gson;
import com.globalpayments.library.terminals.puck1000.mutualauth.MutualAuthCertificate;
import com.globalpayments.library.terminals.puck1000.mutualauth.MutualAuthException;
import com.globalpayments.library.terminals.puck1000.mutualauth.MutualAuthService;
import com.tsys.payments.library.domain.CardData;
import com.tsys.payments.library.domain.TerminalConfiguration;
import com.tsys.payments.library.domain.TerminalInfo;
import com.tsys.payments.library.domain.TransactionConfiguration;
import com.tsys.payments.library.enums.CardDataSourceType;
import com.tsys.payments.library.enums.EncryptionType;
import com.tsys.payments.library.enums.TerminalError;
import com.tsys.payments.library.enums.TerminalSettingType;
import com.tsys.payments.library.enums.TerminalType;
import com.tsys.payments.library.enums.TerminalUpdateType;
import com.tsys.payments.library.enums.TransactionType;
import com.tsys.payments.library.gateway.domain.GatewayResponse;
import com.tsys.payments.library.terminal.*;
import com.tsys.payments.library.terminal.domain.TerminalInteractionRequest;
import com.tsys.payments.library.terminal.domain.TerminalInteractionResult;
import com.tsys.payments.library.terminal.domain.TerminalRequest;
import com.tsys.payments.library.terminal.domain.TerminalResponse;
import com.tsys.payments.library.terminal.enums.TerminalAction;
import com.tsys.payments.library.terminal.enums.TerminalDecisionType;
import com.tsys.payments.library.terminal.enums.TerminalInteractionType;
import com.tsys.payments.library.terminal.enums.TerminalStatus;
import com.tsys.payments.library.tlv.ConstructedTlvObject;
import com.tsys.payments.library.tlv.EmvTagDescriptor;
import com.tsys.payments.library.tlv.Tag;
import com.tsys.payments.library.tlv.TlvObject;
import com.tsys.payments.library.tlv.TlvObjectBuilder;
import com.tsys.payments.library.utils.ByteUtils;
import org.apache.commons.codec.binary.Hex;
import org.jetbrains.annotations.NotNull;
import timber.log.Timber;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

public class Puck1000Controller implements TerminalController {
    private static final String TAG = Puck1000Controller.class.getSimpleName();
    private final AtomicBoolean cancel = new AtomicBoolean(false);
    private CardData cardData;
    private boolean connected = false;
    private final Context context;
    private boolean processing = false;
    private TerminalAction terminalAction;
    private final TerminalConfiguration terminalConfiguration;
    private TerminalInfo terminalInfo;
    private final TerminalListener terminalListener;
    private TerminalRequest terminalRequest;
    private final AtomicBoolean timeout = new AtomicBoolean(false);
    private TransactionConfiguration transactionConfiguration;
    private TransactionType transactionType;
    private boolean awaitingChkCardResult = false;
    private TerminalStatus lastEmittedTerminalStatus;
    private boolean shouldRequestChkCardOnRemove = false;
    private boolean awaitingHostProcessingAfterCardRemoval = false;
    private GatewayResponse pendingHostResponse;
    private boolean awaitingHostProcessingResult = false;
    private boolean awaitingApplicationSelection = false;
    private UpdateListener mTerminalVersionUpdateListener;
    private TxnResponseBean cardResponseTemp = null;
    private final Object cdcvmConfirmationLock = new Object();
    private boolean awaitingCdcvmConfirmation = false;
    private Boolean cdcvmConfirmationResult = null;
    private Boolean isSignatureRequired = null;
    private final List<CommAppBean> availableApplications = new ArrayList<>();
    private final Object emvSelectionLock = new Object();
    private B0012_AppSelect pendingAppSelectEvent;

    private final boolean provisionalTransactionEnabled;
    private final boolean mutualAuthEnabled;
    private final MutualAuthService mutualAuthService;
    private long finalTransactionAmount = 0L;
    private static final long PROVISIONAL_CONTACTLESS_POLLING_AMOUNT = 1L;

    private static final int MAX_RETRY = 2;
    private int countRetry = 0;

    /**
     * Set once a technical fallback has been initiated so the next tender transaction is started
     * with the MSR interface only (contact and contactless must stay disabled for a fallback swipe).
     */
    private boolean technicalFallbackInitiated = false;

    /**
     * Error code reported by the terminal when the chip (contact) interface itself fails.
     * It is not exposed by {@link ErrorCode}, hence the local declaration.
     */
    private static final String ERROR_CODE_CONTACT_INTERFACE_FAILURE = "00000009";

    /**
     * The model name can be used to identify whether the PUCK Reader is a 2-in-1 or 3-in-1 model
     * PUCK1000-D-OEM: 2-in-1 (Contact & Contactless)
     * PUCK1000-A-OEM: 3-in-1 (MSR, Contact & Contactless)
     */
    private static final String PUCK1000_3_IN_1_MODEL_NAME = "PUCK1000-A-OEM";

    public Puck1000Controller(Context context, TerminalConfiguration terminalConfiguration, TransactionConfiguration transactionConfiguration, boolean provisionalTransactionEnabled, boolean mutualAuthEnabled, String mutualAuthCertUrl, TerminalListener listener) {
        this.context = context.getApplicationContext();
        this.terminalConfiguration = terminalConfiguration;
        this.transactionConfiguration = transactionConfiguration;
        this.provisionalTransactionEnabled = provisionalTransactionEnabled;
        this.mutualAuthEnabled = mutualAuthEnabled;
        this.mutualAuthService = mutualAuthEnabled ? new MutualAuthService(mutualAuthCertUrl) : null;
        this.terminalListener = listener;

        Timber.d("Constructing new Puck1000Controller instance");
        //AppContext.context = this.context;
    }

    /** Properties **/
    @Override
    public void getAvailableTerminalVersions(@NonNull TerminalUpdateType terminalUpdateType, @Nullable Map<String, String> credentials, @NonNull AvailableVersionsListener availableVersionsListener) {

    }

    @Override
    public void setCardData(@NonNull CardData cardData) {

    }

    private boolean isDeviceEmvCapable() {
        return terminalConfiguration != null && terminalConfiguration.getTerminalType() != null && terminalConfiguration.getTerminalType().emvCapable;
    }

    private void getTerminalInfo() {
        Timber.w("getTerminalInfo() called.");
        terminalInfo = new TerminalInfo();

        VersionBean versionInfo = getVersionInfo();

        terminalInfo.setTerminalType(TerminalType.CASTLES_PUCK_1000);
        if (versionInfo != null) {
            terminalInfo.setAppName(versionInfo.getModelNumber());
            terminalInfo.setAppVersion(versionInfo.getApVersion());
            //terminalInfo.setBatteryLevel();
            terminalInfo.setFirmwareVersion(versionInfo.getKmsVersion());
            terminalInfo.setKernelVersion(versionInfo.getEmvclVersion());
            terminalInfo.setManufacturer("PUCK1000");
            terminalInfo.setModel(versionInfo.getModelName());
            //terminalInfo.setSerialNumber(versionInfo.getSerialNumber());
            terminalInfo.setSerialNumber("PUCK1000 - " + versionInfo.getSerialNumber());
        }
    }

    /**
     * Retrieves the version information of the terminal.
     *
     * @return VersionBean containing the version information.
     */
    private VersionBean getVersionInfo() {
        VersionBean versionInfo = CreditTxnData.getInstance().getVersion();
        if (versionInfo == null || versionInfo.getSerialNumber() == null) {
            try {
                new Credit().startTxn(createTxnRequestJson(TxnType.GET_VERSION, -1),
                        new PaymentsCallbackMarshaller());
                versionInfo = CreditTxnData.getInstance().getVersion();
            } catch (Exception e) {
                callbackOnTerminalInteractionError(TerminalError.TRANSACTION_ERROR, e.getMessage());
            }
        }
        return versionInfo;
    }

    private String get9F1EData() {
        VersionBean versionBean = getVersionInfo();
        if (versionBean == null || versionBean.getSerialNumber() == null) {
            return null;
        }

        // Get last 8 characters
        String serialNumber = versionBean.getSerialNumber()
                .substring(versionBean.getSerialNumber().length() - 8);

        return Hex.encodeHexString(serialNumber.getBytes(StandardCharsets.UTF_8));
    }

    /** Core Functions **/
    @Override
    public void cancel() {
        cancel.set(true);
        completeCdcvmConfirmation(false);
        // If a cancel arrives while awaiting EMV app selection, release the blocked
        // B0012_AppSelect event as a user cancel so the SDK flow thread does not stay parked.
        boolean releasePendingSelection = false;
        synchronized (emvSelectionLock) {
            if (pendingAppSelectEvent != null) {
                pendingAppSelectEvent.isUserCancel = true;
                pendingAppSelectEvent = null;
                awaitingApplicationSelection = false;
                availableApplications.clear();
                releasePendingSelection = true;
            }
        }
        if (releasePendingSelection) {
            ThreadPoolManager.INSTANCE.releaseBlocking();
        }

        // When the flow is parked waiting on the integrator (final amount confirmation, card
        // removal acknowledgement or the host processing result) there is no device command in
        // flight to observe the cancel flag, so the cancellation is completed here instead of
        // leaving the caller without a response.
        boolean deviceCommandInFlight = processing
                && !awaitingHostProcessingResult
                && !awaitingHostProcessingAfterCardRemoval
                && !shouldRequestChkCardOnRemove;
        if (!awaitingChkCardResult && !deviceCommandInFlight) {
            completeAsCancelled(TxnResult.USER_CANCEL);
        }
    }

    @Override
    public void connect() {
        if(connected) {
            if(processing) {
                Timber.d("Terminal is connected. Device is busy processing a command.");
            }
            else callbackOnTerminalConnected();
        }
        else {
            if(processing) {
                Timber.d("Terminal is currently processing another command and awaiting a response");
                return;
            }

            // Init the Credit Module
            Timber.d("Connection type is [%s]. Initializing module...", terminalConfiguration.getConnectionType());
            ThreadPoolManager.INSTANCE.getSingleExecutor().submit(() -> {
                // Every connection attempt here starts from a disconnected state, so force a fresh
                // Credit.init() with the currently selected Mutual Authentication mode. Without this,
                // a still-set Credit.boInit flag from a previous session would make connect() skip
                // initialization and report "connected" even when the new selection must be
                // re-validated by the reader (for example, choosing Disable on a FW v24 reader that
                // requires mutual auth must not silently reuse the previous MA session).
                if (Credit.boInit.get()) {
                    Timber.d("Clearing Credit init flag to re-initialize with the selected Mutual Auth mode.");
                    Credit.boInit.set(false);
                }
                if(!Credit.boInit.get()) {
                    ReturnCode initResponse;
                    if (mutualAuthEnabled) {
                        // Mutual Authentication: download the COTS/PUCK certificates and perform the
                        // MA handshake during Credit.init(). If certificate retrieval fails the
                        // reader cannot operate, so surface the error and abort the connection.
                        Timber.d("Mutual Authentication enabled. Requesting certificates...");
                        MutualAuthCertificate certificate;
                        try {
                            certificate = mutualAuthService.requestCertificate();
                        } catch (MutualAuthException e) {
                            Timber.e(e, "Mutual Authentication certificate request failed");
                            Credit.boInit.set(false);
                            callbackOnTerminalInteractionError(TerminalError.DEVICE_COMM_ERROR, "Mutual Auth failed: " + e.getMessage());
                            return;
                        }
                        initResponse = Credit.init(
                                Puck1000Controller.this.context,
                                AppContext.KERNEL_DEBUG,
                                certificate.getCotsCertificatePem(),
                                certificate.getCotsPrivateKeyPem(),
                                certificate.getCotsIntermediatePem(),
                                certificate.getPuckFullChainPem());
                    } else {
                        initResponse = Credit.init(Puck1000Controller.this.context, AppContext.KERNEL_DEBUG);
                    }
                    switch(initResponse) {
                        case INIT_CONNECTION_FAIL:
                        case INIT_CONNECTION_NOT_READY:
                        case INIT_EMV_CONFIG_FAIL:
                            // Leave the SDK uninitialized so a subsequent attempt (e.g. enabling
                            // Mutual Authentication) can re-run Credit.init().
                            Credit.boInit.set(false);
                            callbackOnTerminalInteractionError(TerminalError.DEVICE_COMM_ERROR, initResponse.toString());
                            break;
                        case OK:
                            Puck1000Controller.this.handleOnConnected();
                            break;
                        default:
                            Credit.boInit.set(false);
                            callbackOnTerminalInteractionError(TerminalError.GENERAL_ERROR, initResponse.toString());
                    }
                }
                else Puck1000Controller.this.handleOnConnected();
            });
        }
    }

    @Override
    public void disconnect() {
        Timber.d("disconnect() called.");

        connected = false;
        processing = false;
        // Clear the SDK init flag so the next connection re-runs Credit.init() with the mutual-auth
        // mode selected at that time, rather than silently reusing this session's initialization.
        Credit.boInit.set(false);
        completeCdcvmConfirmation(false);
        terminalListener.onDisconnected();
    }

    @Override
    public void firmwareLog(boolean shouldStart) {

    }


    /** Send Stuff **/
    public void sendFinalConfirmationResult(boolean confirmed) {
        Timber.d("sendFinalConfirmationResult invoked; amount = %s; confirmed = %s", terminalRequest.getTotalAmount(), confirmed);

        if (!connected) {
            Timber.w("Terminal disconnected before the transaction could be initiated.");
            this.callbackOnTerminalInteractionError(TerminalError.INITIALIZATION_ERROR, "The terminal is not connected.");
        }
        else if (confirmed && terminalRequest.getTotalAmount() != null && (terminalRequest.getTotalAmount() > 0L || terminalRequest.getTransactionType() == TransactionType.TOKENIZE) || confirmed && terminalRequest.getTransactionType() == TransactionType.SVA || confirmed && terminalRequest.getTransactionType() == TransactionType.REVERSAL || confirmed && terminalRequest.getTransactionType() == TransactionType.VERIFY) {
            if (!this.isDeviceEmvCapable()) {
                Timber.d("sendFinalConfirmationResult: Terminal is not EMV capable. Waiting for swipe...");
                // TODO: Wait for swipe
            } else {
                Timber.d("sendFinalConfirmationResult :: sending command to device");
                processing = true;
                cancel.set(false);
                shouldRequestChkCardOnRemove = false;

                // Build the request
                /* { "txnAmount" : "", "txnCurrency" : "USD", "txnType" : "SALE" } V0.0.01 */
                /* {"txnAmount":"","txnContinue":{},"txnCurrency":"USD","txnType":"SALE"} V0.0.02 */
                long amount = terminalRequest.getTotalAmount();
                if (terminalRequest.getTransactionType() == TransactionType.VERIFY){
                    amount = 0L;
                }

                String rawRequest;
                if (terminalRequest.getTransactionType() == TransactionType.REFUND || terminalRequest.getTransactionType() == TransactionType.PARTIAL_REFUND || terminalRequest.getTransactionType() == TransactionType.REVERSAL) {
                    rawRequest = createTxnRequestJson(TxnType.REFUND, amount);
                } else {
                    rawRequest = createTxnRequestJson(TxnType.SALE, amount);
                }

                // Send it to the device
                try {
                    // puck configuration
                    Credit.setDefTrack2DataPadding(
                            PaddingMethod.CUSTOMER_CHAR,
                            "0"
                    );

                    Credit.setDefTrack2DataCipherMode(
                            CipherMode.CBC,
                            null
                    );

                    Credit.setDefTrack2DataFormat(
                            Track2DataFormat.ASCII_WITH_SENTINEL,
                            Track2DataFormat.ASCII_WITH_SENTINEL,
                            Track2DataFormat.ASCII_WITH_SENTINEL
                    );

                    Credit credit = new Credit();
                    credit.startTxn(rawRequest, new PaymentsCallbackMarshaller());
                } catch (Exception e) {
                    this.callbackOnTerminalInteractionError(TerminalError.TRANSACTION_ERROR, e.getMessage());
                }
            }
        }
        else { // CANCEL IT
            TerminalResponse terminalResponse = new TerminalResponse(terminalRequest.getTerminalAction());
            terminalResponse.setTerminalDecisionType(TerminalDecisionType.TRANSACTION_CANCELLED);
            callbackOnTerminalResponse(terminalResponse);
            resetFields();
        }
    }

    public void sendOnlineProcessingResult(TerminalInteractionResult result) {
        Timber.d("sendOnlineProcessingResult() called with: result=[%s].", new Object[]{result});
        GatewayResponse response = result.getGatewayResponse();
        if (response.getCardDataSourceType() != CardDataSourceType.SCR && response.getCardDataSourceType() != CardDataSourceType.CONTACTLESS_EMV) {
            this.callbackOnTerminalInteractionError(TerminalError.INVALID_REQUEST, "The terminal is unable to complete processing of the transaction.");
            Timber.w("Terminal processing not available for host responses to transactions performed with Card Data Sourced from: [%s]", response.getCardDataSourceType());
        } else {
            handleHostResponse(response);
        }
    }

    @Override
    public void sendRequest(TerminalRequest terminalRequest) {
        Timber.d("sendRequest() called with: terminalRequest=[%s]", terminalRequest);
        terminalAction = terminalRequest.getTerminalAction();
        transactionType = terminalRequest.getTransactionType();
        // Drop any card data left over from the previous transaction so a cancellation before a
        // card is read does not report the previous cardholder's data.
        cardData = null;
        // A new request must always start on every supported interface, even when the previous
        // transaction ended in a technical fallback.
        countRetry = 0;
        technicalFallbackInitiated = false;

        // Set minimum provisional amount 0.01 if provisional transaction is enabled
        if (provisionalTransactionEnabled && terminalAction == TerminalAction.TENDER_GOODS) {
            Timber.tag(TAG).d("sendRequest: Provisional transaction enabled. Setting provisional amount to %s", PROVISIONAL_CONTACTLESS_POLLING_AMOUNT);
            finalTransactionAmount = terminalRequest.getTotalAmount() == null ? 0L : terminalRequest.getTotalAmount();
            terminalRequest.setTotalAmount(PROVISIONAL_CONTACTLESS_POLLING_AMOUNT);
        }

        this.terminalRequest = terminalRequest;
        Timber.d("sendRequest: Terminal Action: " + terminalAction);
        if(terminalAction == TerminalAction.SETUP) {
            Timber.d("Terminal Action is SETUP. Performing terminal configuration...");
            // TODO: Terminal Configuration
//            terminalTransactionConfiguration = terminalRequest.getTerminalConfiguration();
//            configureTerminal(transactionConfiguration);
        } else if(terminalAction == TerminalAction.INFO) {
            // TODO: Get Device Info
//            getDeviceInfo();
        } else if (terminalAction != TerminalAction.TENDER_GOODS && terminalAction != TerminalAction.TENDER_SERVICES && terminalAction != TerminalAction.TENDER_REFUND) {
            if (terminalAction == TerminalAction.START_CARD) {
                proceedWithStartCard();
            }
            else {
                Timber.e("Unsupported terminal action in: terminalRequest=[%s]", terminalRequest);
                callbackOnTerminalInteractionError(TerminalError.INVALID_REQUEST, String.format("Unsupported terminal action=[%s]", terminalAction));
            }
        } else {
            proceedWithStartTenderTransaction();
        }
    }

    // This method is called before sending the sale transactions to Host only for contact transactions.
    private void tnxContinue() {
        try {
            ContinueBean continueBean = new ContinueBean();
            Timber.d("Host response is not approved or declined. Proceeding with Unable to go online decline.");
            continueBean.setOnlineAction(OnlineAction.UNABLE_GO_ONLINE);
            continueBean.setAuthCode("Z3");
            TxnRequestBean txnRequestBean = new TxnRequestBean();
            txnRequestBean.txnType = TxnType.CONTINUE;
            txnRequestBean.txnContinue = continueBean;

            new Credit().startTxn(new Gson().toJson(txnRequestBean), new PaymentsCallbackMarshaller());
        } catch (Exception e) {
            this.callbackOnTerminalInteractionError(TerminalError.TRANSACTION_ERROR, e.getMessage());
        }
    }

    public void sendRequestHostProcessing(CardData cardData) {
        Timber.d("requestHostProcessing() called.");
        processing = true;
        awaitingHostProcessingResult = true;
        awaitingHostProcessingAfterCardRemoval = false;
        TerminalInteractionRequest terminalInteractionRequest = new TerminalInteractionRequest(TerminalInteractionType.HOST_PROCESSING);
        terminalInteractionRequest.setCardData(cardData);
        if (provisionalTransactionEnabled && finalTransactionAmount > 0L) {
            Timber.tag(TAG).d("sendRequestHostProcessing: Setting final amount to %s", finalTransactionAmount);
            terminalInteractionRequest.setFinalAmount(finalTransactionAmount);
        }

        callbackOnTerminalInteractionRequest(terminalInteractionRequest);
        callbackOnTerminalStatusUpdated(TerminalStatus.PROCESSING);
    }

    @Override
    public void sendTerminalInteractionResult(TerminalInteractionResult terminalInteractionResult) {
        Timber.d("sendTerminalInteractionResult() called with: terminalInteractionResult=[%s]", terminalInteractionResult);
        if (terminalInteractionResult.getInteractionType() != null) {
            switch (terminalInteractionResult.getInteractionType()) {
                case HOST_PROCESSING:
                    sendOnlineProcessingResult(terminalInteractionResult);
                    break;
                case EMV_APPLICATION_SELECTION:
                    logEmvSelectionState("sendTerminalInteractionResult: EMV_APPLICATION_SELECTION received from app");
                    sendApplicationSelectionResult(terminalInteractionResult.getSelectedApplicationIndex());
                    break;
                case FINAL_AMOUNT_CONFIRMATION:
                    sendFinalConfirmationResult(terminalInteractionResult.getFinalAmountConfirmed());
                    break;
                case SURCHARGE_CONFIRMATION:
                case REQUEST_PROCEED_HOST_PROCESSING:
                    if (!completeCdcvmConfirmation(terminalInteractionResult)) {
                        sendRequestHostProcessing(cardData);
                    }
                    break;
            }
        } else {
            this.callbackOnTerminalInteractionError(TerminalError.INVALID_REQUEST, "Invalid interaction type requested.");
        }
    }

    public void continueAfterRemoveCardPrompt() {
        Timber.d("continueAfterRemoveCardPrompt() called.");
        if (!shouldRequestChkCardOnRemove) {
            Timber.w("continueAfterRemoveCardPrompt() ignored because CHK_CARD is not pending.");
            return;
        }

        shouldRequestChkCardOnRemove = false;
        requestChkCardCommand();
    }

    public void sendApplicationSelectionResult(int selectedApplicationIndex) {
        Timber.d("sendApplicationSelectionResult() called with selectedApplicationIndex=[%s]", selectedApplicationIndex);
        logEmvSelectionState("sendApplicationSelectionResult: applying selected index");
        try {
            synchronized (emvSelectionLock) {
                B0012_AppSelect event = pendingAppSelectEvent;
                if (event == null) {
                    Timber.w("sendApplicationSelectionResult ignored: no pending EMV application selection.");
                    return;
                }

                // Resolve the selected AID against the current, validated app list.
                String selectedAid = null;
                if (selectedApplicationIndex >= 0 && selectedApplicationIndex < availableApplications.size()) {
                    CommAppBean selectedApplication = availableApplications.get(selectedApplicationIndex);
                    if (selectedApplication != null && !TextUtils.isEmpty(selectedApplication.aid)) {
                        selectedAid = selectedApplication.aid;
                    }
                }

                // Record the decision on the blocked event before releasing it.
                if (selectedAid == null) {
                    Timber.d("sendApplicationSelectionResult: no valid AID selected, cancelling.");
                    event.isUserCancel = true;
                    event.selectedAid = null;
                } else {
                    Timber.d("sendApplicationSelectionResult: selectedAid=%s", selectedAid);
                    event.selectedAid = selectedAid;
                }

                // Clear selection state now that the decision is recorded on the event.
                awaitingApplicationSelection = false;
                availableApplications.clear();
                pendingAppSelectEvent = null;
            }
        } catch (Exception e) {
            Timber.e(e, "Error sending application selection result");
            synchronized (emvSelectionLock) {
                if (pendingAppSelectEvent != null) {
                    pendingAppSelectEvent.isUserCancel = true;
                    pendingAppSelectEvent = null;
                }
                awaitingApplicationSelection = false;
                availableApplications.clear();
            }
        } finally {
            // Release the blocked B0012_AppSelect event so the SDK can continue the transaction.
            ThreadPoolManager.INSTANCE.releaseBlocking();
            logEmvSelectionState("sendApplicationSelectionResult: released block for selected AID");
        }
    }

    // Handles an EMV application-selection event: validates the app list, surfaces the
    // selection UI to the client app, then blocks the calling (SDK flow) thread until
    // sendApplicationSelectionResult() records the choice and calls releaseBlocking().
    private void handleApplicationSelection(B0012_AppSelect event) {
        Timber.tag(TAG).d( "eventHandle: Application Selection: %s" , event.toString());
        TerminalInteractionRequest request = new TerminalInteractionRequest(TerminalInteractionType.EMV_APPLICATION_SELECTION);
        List<CommAppBean> list = event.list;
        if (list == null || list.isEmpty()) {
            synchronized (emvSelectionLock) {
                awaitingApplicationSelection = false;
                availableApplications.clear();
                pendingAppSelectEvent = null;
            }
            event.isUserCancel = true;
            callbackOnTerminalInteractionError(TerminalError.TRANSACTION_ERROR,
                    "No EMV applications available for selection.");
            return;
        }
        String[] appLabelList = new String[list.size()];
        for (int i = 0; i < list.size(); i++) {
            CommAppBean bean = list.get(i);
            if (bean == null || TextUtils.isEmpty(bean.aid)) {
                synchronized (emvSelectionLock) {
                    awaitingApplicationSelection = false;
                    availableApplications.clear();
                    pendingAppSelectEvent = null;
                }
                event.isUserCancel = true;
                callbackOnTerminalInteractionError(TerminalError.TRANSACTION_ERROR,
                        "Invalid EMV application details received from terminal.");
                return;
            }
            DebugUtilEx.debugInfo(new Throwable(), "aid = " + bean.aid + ", appLabel = " + bean.appLabel);
            appLabelList[i] = TextUtils.isEmpty(bean.appLabel) ? bean.aid : bean.appLabel;
        }
        synchronized (emvSelectionLock) {
            availableApplications.clear();
            availableApplications.addAll(list);
            awaitingApplicationSelection = true;
            pendingAppSelectEvent = event;
        }
        logEmvSelectionState("eventHandle: B0012_AppSelect received from terminal");
        request.setApplicationNames(appLabelList);
        callbackOnTerminalInteractionRequest(request);
        ThreadPoolManager.INSTANCE.blocking();
        logEmvSelectionState("eventHandle: B0012_AppSelect resumed after selection");
    }
    /** Proceed with Stuff **/
    private void proceedWithStartCard() {
        // TODO: Proceed with Start card
    }

    private void proceedWithStartTenderTransaction() {
        Timber.d("proceedWithStartTenderTransaction() called."+terminalRequest.getSurchargeAmount());
        if (this.isDeviceEmvCapable()) {
            Timber.d("proceedWithStartTenderTransaction: Terminal is EMV capable. Requesting final amount confirmation.");
            TerminalInteractionRequest request;

            request = new TerminalInteractionRequest(TerminalInteractionType.FINAL_AMOUNT_CONFIRMATION);
            request.setFinalAmount(terminalRequest.getTotalAmount());
            callbackOnTerminalInteractionRequest(request);
        } else {
            Timber.d("proceedWithStartTenderTransaction: Terminal is not EMV capable. Sending final confirmation result as true.");
            sendFinalConfirmationResult(true);
        }
    }

    /** Update Stuff **/
    @Override
    public void updateTerminal(@NotNull TerminalUpdateType terminalUpdateType, @Nullable Map<String, String> credentials, @Nullable String version, @NonNull UpdateListener updateListener) {
        Timber.d("updateTerminal() called with terminalUpdateType=%s, credentials=%s, version=%s",
                terminalUpdateType, credentials, version);

        mTerminalVersionUpdateListener = updateListener;

        startOTAEMVUpdate();
    }

    private void startOTAEMVUpdate() {
        Timber.d("startOTAEMVUpdate() called.");
        if (!connected) {
            Timber.w("Terminal disconnected before the OTA update could be initiated.");
            this.callbackOnTerminalInteractionError(TerminalError.INITIALIZATION_ERROR, "The terminal is not connected.");
            return;
        }
        try{
            TxnRequestBean tnx = new TxnRequestBean();
            tnx.txnType = TxnType.OTA_EMV_CONFIG;

            new Credit().startTxn(new Gson().toJson(tnx), new PaymentsCallbackMarshaller());
        } catch (Exception e) {
            mTerminalVersionUpdateListener.onTerminalUpdateError(TerminalError.CONFIGURATION_ERROR, e.getMessage());
        }

        processing = true;
        cancel.set(false);
    }

    @Override
    public void updateTerminalSetting(TerminalSettingType terminalSettingsType, String terminalSettingsValue, TerminalUpdateSettingListener terminalUpdateSettingListener) {

    }


    /** Uncategorized **/
    @Override
    public void readTerminalSetting(TerminalSettingType terminalSettingsType, TerminalReadSettingListener terminalReadSettingListener) {

    }

    private void resetFields() {
        Timber.d("resetFields() called.");
//        requestDipCount = 1;
//        availableAids = null;
//        selectedAid = null;
//        currentAid = null;
        synchronized (emvSelectionLock) {
            availableApplications.clear();
            pendingAppSelectEvent = null;
        }
        terminalAction = null;
        transactionType = null;
        cardResponseTemp = null;
        isSignatureRequired = null;
        completeCdcvmConfirmation(false);
        awaitingHostProcessingAfterCardRemoval = false;
        synchronized (emvSelectionLock) {
            awaitingApplicationSelection = false;
        }
        shouldRequestChkCardOnRemove = false;
        countRetry = 0;
        technicalFallbackInitiated = false;
//        cardData = null;
//        dataSource = null;
//        reversalRequired = false;
        processing = false;
        awaitingHostProcessingResult = false;
        lastEmittedTerminalStatus = null;
//        awaitingCancelCommandResponse.set(false);
//        hostRequestCardData = null;
//        sendStopCommandAfterOnlineProcessingRequested = false;
    }
//Added EMV_AID_FLOW trace logs so you can confirm exact sequence
    private void logEmvSelectionState(String step) {
        Timber.tag("EMV_AID_FLOW").d(
                "%s | awaitingSelection=%s, appCount=%s, awaitingChkCard=%s, awaitingHostResult=%s, awaitingHostAfterRemoval=%s, processing=%s",
                step,
                awaitingApplicationSelection,
                availableApplications.size(),
                awaitingChkCardResult,
                awaitingHostProcessingResult,
                awaitingHostProcessingAfterCardRemoval,
                processing
        );
    }

    /** Handle Stuff **/
    private void handleOnConnected() {
        Puck1000Controller.this.connected = true;
        Puck1000Controller.this.processing = false;
//        if(isDeviceEmvCapable()) {
//            Puck1000Controller.this.terminalInfo = new TerminalInfo();
//            Puck1000Controller.this.terminalInfo.setTerminalType(terminalConfiguration.getTerminalType());
//            Puck1000Controller.this.callbackOnTerminalConnected();
//        }
//        else {
            Puck1000Controller.this.getTerminalInfo();
            Puck1000Controller.this.callbackOnTerminalConnected();
//        }
    }

    private String getTxnType(B0004_ShowTxnResult event) {
        if (event == null || event.txnType == null) {
            return null;
        }

        String txnType = event.txnType.toString();
        int lastDotIndex = txnType.lastIndexOf('.');
        if (lastDotIndex >= 0 && lastDotIndex < txnType.length() - 1) {
            txnType = txnType.substring(lastDotIndex + 1);
        }

        return txnType.trim();
    }

    private boolean isTxnType(B0004_ShowTxnResult event, String expectedTxnType) {
        String txnType = getTxnType(event);
        return !TextUtils.isEmpty(txnType) && expectedTxnType.equalsIgnoreCase(txnType);
    }

    private TerminalStatus getChkCardStatus(@Nullable TxnResponseBean cardResponse) {
        if (cardResponse == null || cardResponse.getEntryMode() == null) {
            return TerminalStatus.CARD_REMOVED_AFTER_TRANSACTION_COMPLETE;
        }

        switch (cardResponse.getEntryMode()) {
            case CONTACT:
                return TerminalStatus.DO_NOT_REMOVE_CARD;
            case CONTACTLESS:
                return TerminalStatus.CONTACTLESS_CARD_STILL_IN_FIELD;
            default:
                return TerminalStatus.CARD_REMOVED_AFTER_TRANSACTION_COMPLETE;
        }
    }

    private void requestChkCardCommand() {;
        try {
            awaitingChkCardResult = true;
            processing = true;
            new Credit().startTxn(createTxnRequestJson(TxnType.CHK_CARD, -1),
                    new PaymentsCallbackMarshaller());
        } catch (Exception e) {
            awaitingChkCardResult = false;
            processing = false;
            callbackOnTerminalInteractionError(TerminalError.TRANSACTION_ERROR, e.getMessage());
        }
    }

    private void requestCdcvmConfirmationAndBlock() {
        Timber.d("CDCVM required. Requesting cardholder confirmation before resuming card polling.");

        synchronized (cdcvmConfirmationLock) {
            awaitingCdcvmConfirmation = true;
            cdcvmConfirmationResult = null;
        }

        callbackOnTerminalStatusUpdated(TerminalStatus.SEE_PHONE);
        TerminalInteractionRequest request = new TerminalInteractionRequest(TerminalInteractionType.REQUEST_PROCEED_HOST_PROCESSING);
        callbackOnTerminalInteractionRequest(request);

        boolean confirmed = waitForCdcvmConfirmation();
        if (confirmed) {
            Timber.d("CDCVM confirmation received. Resuming card polling; user should tap the mobile device again.");
            callbackOnTerminalStatusUpdated(TerminalStatus.TAP_AGAIN_CARD);
        } else {
            Timber.w("CDCVM confirmation was cancelled or not confirmed.");
            boolean cancelledByRequest = cancel.get();
            cancel.set(true);
            if (cancelledByRequest) {
                // The cardholder cancelled, report a cancelled transaction rather than an error so
                // the integrator still receives a final transaction response.
                completeAsCancelled(TxnResult.USER_CANCEL);
            } else {
                callbackOnTerminalInteractionError(TerminalError.TRANSACTION_ERROR, "CDCVM confirmation was cancelled.");
            }
        }
    }

    private boolean waitForCdcvmConfirmation() {
        synchronized (cdcvmConfirmationLock) {
            while (awaitingCdcvmConfirmation && cdcvmConfirmationResult == null && !cancel.get() && !timeout.get()) {
                try {
                    cdcvmConfirmationLock.wait(1000L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    cdcvmConfirmationResult = false;
                    break;
                }
            }

            boolean confirmed = Boolean.TRUE.equals(cdcvmConfirmationResult) && !cancel.get() && !timeout.get();
            awaitingCdcvmConfirmation = false;
            cdcvmConfirmationResult = null;
            cdcvmConfirmationLock.notifyAll();
            return confirmed;
        }
    }

    private boolean completeCdcvmConfirmation(TerminalInteractionResult result) {
        Boolean confirmed = result.getContinueHostProcessingConfirmed();
        if (confirmed == null) {
            confirmed = result.getFinalAmountConfirmed();
        }
        return completeCdcvmConfirmation(Boolean.TRUE.equals(confirmed));
    }

    private boolean completeCdcvmConfirmation(boolean confirmed) {
        synchronized (cdcvmConfirmationLock) {
            if (!awaitingCdcvmConfirmation) {
                return false;
            }

            cdcvmConfirmationResult = confirmed;
            cdcvmConfirmationLock.notifyAll();
            return true;
        }
    }

    public boolean isAwaitingCdcvmConfirmation() {
        synchronized (cdcvmConfirmationLock) {
            return awaitingCdcvmConfirmation;
        }
    }

    public boolean isAwaitingApplicationSelection() {
        synchronized (emvSelectionLock) {
            return awaitingApplicationSelection;
        }
    }
    public Boolean isSignatureRequired() {
        return isSignatureRequired;
    }

    private void handleHostResponse(GatewayResponse hostResponse) {
        Timber.d("handleHostResponse() called with: hostResponse=[%s]", hostResponse);

        if (!TextUtils.isEmpty(hostResponse.getEmvIssuerAuthenticationData())) {
            byte[] issuerAuthDataBytes = ByteUtils.hexStringToByteArray(hostResponse.getEmvIssuerAuthenticationData());
            if (issuerAuthDataBytes != null) {
                TlvObjectBuilder builder = TlvObjectBuilder.create(EmvTagDescriptor.ISSUER_AUTHENTICATION_DATA);
                builder.setValue(issuerAuthDataBytes);
                if (cardData.getEmvTlvData() == null) {
                    cardData.setEmvTlvData(new ArrayList<>());
                }
                cardData.getEmvTlvData().add(builder.build());
            }
        }

        TerminalResponse terminalResponse = new TerminalResponse(terminalAction);
        if (hostResponse.isGatewayTimeout()) {
            terminalResponse.setTerminalDecisionType(TerminalDecisionType.REVERSAL_REQUIRED);
        } else {
            terminalResponse.setTerminalDecisionType(hostResponse.isApproved() ? TerminalDecisionType.APPROVED_ONLINE : TerminalDecisionType.DECLINED_ONLINE);
        }
        terminalResponse.setGatewayTransactionId(hostResponse.getGatewayTransactionId());
        terminalResponse.setPosReferenceId(hostResponse.getPosReferenceId());
        terminalResponse.setCardData(cardData);
        awaitingHostProcessingResult = false;
        processing = false;
        callbackOnTerminalResponse(terminalResponse);
    }


    /** Callbacks **/
    private void callbackOnTerminalConnected() {
        Timber.d("callbackOnTerminalConnected() called.");
        terminalListener.onConnected(terminalInfo);
        lastEmittedTerminalStatus = null;
    }

    private void callbackOnTerminalInteractionError(final TerminalError errorType, final String error) {
        Timber.d("callbackOnTerminalInteractionError() called with: errorType=[%s]", errorType);
        terminalListener.onError(errorType, error);
        lastEmittedTerminalStatus = null;
    }

    private void callbackOnTerminalInteractionRequest(final TerminalInteractionRequest request) {
        Timber.d("callbackOnTerminalInteractionRequest() called with: request=[%s]", request);
        terminalListener.onTerminalInteractionRequested(request);
    }

    private void callbackOnTerminalResponse(final TerminalResponse terminalResponse) {
        Timber.d("callbackOnTerminalResponse() called with: terminalResponse=[%s]", terminalResponse);
        terminalListener.onTerminalResponseReceived(terminalResponse);
    }

    private void callbackOnTerminalStatusUpdated(final TerminalStatus status) {
        Timber.d("callbackOnTerminalStatusUpdated() called with: terminalStatus=[%s]", status);
        lastEmittedTerminalStatus = status;
        terminalListener.onTerminalStatusUpdate(status);
    }

    public void resumePolling() {
        proceedWithStartTenderTransaction();
    }


    /** Implementations **/
    private class PaymentsCallbackMarshaller implements PaymentCallback {

        @Override
        public boolean onCancel() {
            return Puck1000Controller.this.cancel.get();
        }

        @Override
        public boolean onTimeout() {
            return Puck1000Controller.this.timeout.get();
        }

        @Override
        public BaseEvent eventHandle(BaseEvent baseEvent) {
            Timber.tag(TAG).d("eventHandle: base Event: %s", baseEvent.toString());
            try{
                if (baseEvent instanceof B0001_PollCardCountdown) {
                    B0001_PollCardCountdown event = (B0001_PollCardCountdown) baseEvent;
                }
                else if (baseEvent instanceof B0002_PollCardRestart) {
                    B0002_PollCardRestart event = (B0002_PollCardRestart) baseEvent;
                }
                else if (baseEvent instanceof B0003_PollCardSeePhone) {
                    B0003_PollCardSeePhone event = (B0003_PollCardSeePhone) baseEvent;
                    Puck1000Controller.this.requestCdcvmConfirmationAndBlock();
                }
                else if (baseEvent instanceof B0004_ShowTxnResult) { // 3
                    B0004_ShowTxnResult event = (B0004_ShowTxnResult) baseEvent;
                    Timber.tag(TAG).d("eventHandle: Transaction Result: %s", event.txnResult);
                    Timber.tag(TAG).d("eventHandle: Transaction Type: %s", getTxnType(event));
                    final boolean isChkCardTxn = isTxnType(event, "CHK_CARD");
                    final boolean isOTAEMV = isTxnType(event, "OTA_EMV_CONFIG");
                    switch(event.txnResult) {
                        case TIMEOUT:
                        case FAIL:
                            if (isChkCardTxn) {
                                awaitingChkCardResult = false;
                                awaitingHostProcessingAfterCardRemoval = false;
                            }

                            if (isOTAEMV) {
                                ThreadPoolManager.INSTANCE.getSingleExecutor().submit(() -> mTerminalVersionUpdateListener.onTerminalUpdateError(TerminalError.CONFIGURATION_ERROR, event.errorMsg));
                                break;
                            }

                            if (cancel.get()) {
                                // The terminal aborted the transaction because a cancellation was
                                // requested. Report it as a cancellation instead of a failure.
                                Timber.tag(TAG).d("Transaction result [%s] received after a cancel request. Reporting as cancelled.", event.txnResult);
                                completeAsCancelled(event.txnResult);
                                break;
                            }

                            // Reset last emitted terminal status, to make sure that new showLoading/Msg event will not be fired for repeated terminal statuses,
                            // like REINSERT_CARD or WAITING_FOR_CARD
                            lastEmittedTerminalStatus = null;
                            
                            handleTransactionTimeoutAndFailure(event);
                            break;
                        case USER_CANCEL:
                            if (isChkCardTxn) {
                                awaitingChkCardResult = false;
                                awaitingHostProcessingAfterCardRemoval = false;
                            }
                            completeAsCancelled(event.txnResult);
                            break;
                        case SUCCESS: {
                            if (isChkCardTxn) {
                                awaitingChkCardResult = false;
                                Timber.d("CHK_CARD command completed with SUCCESS");
                                Timber.d("eventHandle: event chkCardBean is inserted %s", event.chkCardBean != null ? event.chkCardBean.getInserted() : null);
                                Timber.d("eventHandle: event chkCardBean is tapped %s", event.chkCardBean != null ? event.chkCardBean.getTapped() : null);
                                TerminalStatus chkCardStatus = getChkCardStatus(event.txnResponseBean);
                                Puck1000Controller.this.callbackOnTerminalStatusUpdated(chkCardStatus);
                                if (awaitingHostProcessingAfterCardRemoval && chkCardStatus == TerminalStatus.CARD_REMOVED_AFTER_TRANSACTION_COMPLETE) {
                                    awaitingHostProcessingAfterCardRemoval = false;
                                    sendRequestHostProcessing(Puck1000Controller.this.cardData);
                                }
                                break;
                            }

                            if (isOTAEMV) {
                                if (mTerminalVersionUpdateListener != null) {
                                        mTerminalVersionUpdateListener.onTerminalUpdateSuccess();
                                }
                                break;
                            }

                            TxnResponseBean cardResponse = event.txnResponseBean;
                            if(event.txnType == TxnType.CONTINUE) {
                                Timber.d("Received CONTINUE response from device. Processing host response...");
                                cardResponse = cardResponseTemp;
                            } else if((event.txnType == TxnType.SALE || event.txnType == TxnType.REFUND) && event.txnResponseBean.getEntryMode() == EntryMode.CONTACT && event.txnResponseBean.getEmvResult() == EMVResult.GO_ONLINE) {
                                Timber.d("Received SALE or REFUND response from device. Processing card data...");
                                // Cache the original response so CONTINUE can complete with the same card payload.
                                cardResponseTemp = event.txnResponseBean;
                                tnxContinue();
                                break;
                            }

                            if(cardResponse != null) {
                                Timber.tag(TAG).d("eventHandle: Card Response: %s", cardResponse.toString());
                                isSignatureRequired = cardResponse.getSignatureRequired();
                                CardData cardData = new CardData();
                                cardData.setEncryptionType(EncryptionType.DUKPT);

                                // appLabel "VISA DEBIT"
                                // emvAdditionalTagData DF8F4F020004DF81290830F0F0F090F0FF00DF8115060000000000FFDF8116161B0000001300000000000000000000000000000000009F0607A00000000310108407A0000000031010DF01060000003001009F5A051108400840
                                // emvTagData "9F0206000000001000820200009F03060000000000005F24032608319F2608548A92B40CEBE6819F2701805F280208405F2A0208409F6E04207000009F100706061203A00000500A564953412044454249549F3303E020C89F34033F00005F340100950500000000009F150270119F3501009F360202AE9F3704751639B99A032603289F1A0208409B0200009C0100
                                // emvResult "GO_ONLINE"
                                // encPan "E3C8BB55E9B54D7349C4FD3F8D09D7C95DC1C77D4209176AADBD88E83B8F488F607308F6123B44D11B0C3CEA8389B322"
                                // entryMode "CONTACTLESS"
                                // maskedPan "334373**************************�ȻU�MsI��?�	��]��}B	j����;�H�`s�;D�<ꃉ�"��}̠*��bz6ʞ��Bs�E��Z�d�"
                                // endTrack2Data "607308F6123B44D11B0C3CEA8389B3221AA415BC057DCCA02AFFBD0A627A36CA9EF2ED104273D545F4F7045AD064E511"
                                // encTrack2Ksn "FFFF9876543210E0002E"

//                                cardData.setAvsType(cardResponse);

                                // A fallback swipe happens only after the chip interface has failed MAX_RETRY times.
                                boolean isFallbackSwipe = countRetry >= MAX_RETRY && cardResponse.getEntryMode() == EntryMode.MSR;
                                if (isFallbackSwipe) {
                                    cardData.setCardDataSource(CardDataSourceType.FALLBACK);
                                } else {
                                    cardData.setCardDataSource(MapEntryMode(cardResponse.getEntryMode()));
                                }
                                // Resetting the retry count after a successful card read
                                countRetry = 0;
                                if (cardResponse.getEntryMode() != EntryMode.MSR && !TextUtils.isEmpty(cardResponse.getEmvTagData())) {
                                    ArrayList<TlvObject> emvTags = new ArrayList<>(ConstructedTlvObject.parse(cardResponse.getEmvTagData()));

                                    String serialNumber = get9F1EData();
                                    if (serialNumber != null) {
                                        // Check if default 9F1E tag is present in the list
                                        int tag9F1EIndex = -1;
                                        for (int index = 0; index < emvTags.size(); index++) {
                                            if (emvTags.get(index).getTag().equals(Tag.fromTagDescriptor(EmvTagDescriptor.IFD_SERIAL_NUMBER))) {
                                                tag9F1EIndex = index;
                                                break;
                                            }
                                        }

                                        // If default 9F1E tag is present, remove it
                                        if (tag9F1EIndex != -1) {
                                            emvTags.remove(tag9F1EIndex);
                                        }

                                        // Add 9F1E tag, computed from Serial number
                                        TlvObjectBuilder builder = TlvObjectBuilder.create(EmvTagDescriptor.IFD_SERIAL_NUMBER);
                                        builder.setValue(ByteUtils.hexStringToByteArray(serialNumber));
                                        emvTags.add(builder.build());
                                    }
                                    cardData.setEmvTlvData(emvTags);
                                }
//                                cardData.setCardType(cardResponse);

//                                cardData.setEncryptedData(cardResponse.getEncTrack2Data());
                                cardData.setEncryptedData(cardResponse.getEncTrack2Data());

//                                cardData.setCardholderAddress(cardResponse);
//                                cardData.setCardholderName(cardResponse);
//                                cardData.setCvmResult(cardResponse);
//                                cardData.setCvv2(cardResponse);
//                                cardData.setEncryptionType(cardResponse);
//                                cardData.setExpirationDate(cardResponse);
//                                cardData.setFallbackReason(cardResponse);
                                cardData.setKsn(cardResponse.getEncTrack2KSN());
//                                cardData.setLastChipRead(cardResponse);
//                                cardData.setPackedEncryptedData(cardResponse);
                                cardData.setPan(cardResponse.getMaskPan());
                                cardData.setPinBlock(cardResponse.getOnlinePinBlock());
                                cardData.setPinKsn(cardResponse.getPinBlockKSN());
//                                cardData.setPostalCode(cardResponse);
//                                cardData.setTrack1(cardResponse);
//                                cardData.setTrack2(cardResponse);
//                                cardData.setTrack3(cardResponse);
                                // TODO: CVM Result
                                // Add KSN and TrackData as tags?!

                                if(cardResponse.getEntryMode() != EntryMode.MSR) {
                                    // Initialize if needed
                                    if(cardData.getEmvTlvData() == null) {
                                        cardData.setEmvTlvData(new ArrayList<>());
                                    }

                                    // Add the KSN as tag data
                                    if(!TextUtils.isEmpty(cardData.getKsn())) {
                                        TlvObjectBuilder builder = TlvObjectBuilder.create(EmvTagDescriptor.INGENICO_KSN);
                                        byte[] ksnBytes = ByteUtils.hexStringToByteArray(cardData.getKsn());
                                        if(ksnBytes != null) {
                                            builder.setValue(ksnBytes);
                                        }

                                        cardData.getEmvTlvData().add(builder.build());
                                    }

                                    // Add the track data as tag data
                                    if(!TextUtils.isEmpty(cardData.getEncryptedData())) {
                                        TlvObjectBuilder builder = TlvObjectBuilder.create(EmvTagDescriptor.INGENICO_ENCRYPTED_TRACK);
                                        byte[] trackBytes = ByteUtils.hexStringToByteArray(cardData.getEncryptedData());
                                        if(trackBytes != null) {
                                            builder.setValue(trackBytes);
                                        }

                                        cardData.getEmvTlvData().add(builder.build());
                                    }
                                }else {
                                    cardData.setTrack2(cardResponse.getEncTrack2Data());
                                }
                                Puck1000Controller.this.cardData = cardData;
                                if (cardResponse == null) {
                                    callbackOnTerminalInteractionError(TerminalError.TRANSACTION_ERROR, "Missing transaction response from terminal.");
                                    break;
                                }

                                if (cardResponse.getEntryMode() != EntryMode.MSR) {
                                    if (event.txnType == TxnType.REFUND){
                                        sendRequestHostProcessing(Puck1000Controller.this.cardData);
                                        break;
                                    }
                                    EMVResult emvResult = cardResponse.getEmvResult();
                                    if (emvResult == null) {
                                        callbackOnTerminalInteractionError(TerminalError.TRANSACTION_ERROR, "Missing EMV result from terminal response.");
                                        break;
                                    }

                                    if (emvResult == EMVResult.GO_ONLINE) {
                                        awaitingHostProcessingAfterCardRemoval = true;
                                        shouldRequestChkCardOnRemove = true;
                                        if (cardResponse.getEntryMode() == EntryMode.CONTACT) {
                                            Puck1000Controller.this.callbackOnTerminalStatusUpdated(TerminalStatus.REMOVE_CARD);
                                        } else {
                                            Puck1000Controller.this.callbackOnTerminalStatusUpdated(TerminalStatus.CARD_DETECTED);
                                        }
                                        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(Puck1000Controller.this::continueAfterRemoveCardPrompt, 2000);
                                        break;
                                    } else if (emvResult == EMVResult.DECLINE) {
                                        TerminalResponse terminalResponse = new TerminalResponse(terminalAction);
                                        terminalResponse.setTerminalDecisionType(TerminalDecisionType.DECLINED_OFFLINE);
                                        terminalResponse.setCardData(cardData);
                                        callbackOnTerminalResponse(terminalResponse);
                                        break;
                                    } else if (emvResult == EMVResult.OFFLINE_APPROVAL) {
                                        TerminalResponse terminalResponse = new TerminalResponse(terminalAction);
                                        terminalResponse.setTerminalDecisionType(TerminalDecisionType.APPROVED_OFFLINE);
                                        terminalResponse.setCardData(cardData);
                                        callbackOnTerminalResponse(terminalResponse);
                                        break;
                                    }
                                }else{
                                    if (!isFallbackSwipe && Boolean.TRUE.equals(cardResponse.getChipExisted())) {
                                        Puck1000Controller.this.callbackOnTerminalStatusUpdated(TerminalStatus.ICC_SWIPE_DETECTED);
                                        break;
                                    }
                                    sendRequestHostProcessing(Puck1000Controller.this.cardData);
                                }

                            }

                            VersionBean versionResponse = event.versionBean;
                            if(versionResponse != null) {
                                Timber.tag(TAG).d("eventHandle: Version Response: %s", versionResponse.toString());
                                // TODO: Handle Version Info (maybe...)
                            }
                        } break;
                        default:
                            callbackOnTerminalInteractionError(TerminalError.GENERAL_ERROR, "Unknown Transaction Result: " + event.txnResult);
                    }

                    if (!awaitingChkCardResult && !awaitingHostProcessingResult && !awaitingHostProcessingAfterCardRemoval) {
                        processing = false;
                    }
                }
                else if (baseEvent instanceof B0005_ShowLoading) { // 1
                    B0005_ShowLoading event = (B0005_ShowLoading) baseEvent;
                    //awaitingApplicationSelection Prevented WAITING_FOR_CARD from being re-fired while app selection is active.
                    if (!awaitingChkCardResult
                            && !awaitingHostProcessingResult
                            && !awaitingHostProcessingAfterCardRemoval
                            && !awaitingApplicationSelection
                            && countRetry <= MAX_RETRY) {
                        TerminalStatus status = countRetry > 0
                                ? TerminalStatus.REINSERT_CARD
                                : TerminalStatus.WAITING_FOR_CARD;
                        // Only emit the status update if it differs from the last emitted one,
                        // to avoid redundant duplicate callbacks.
                        if (lastEmittedTerminalStatus != status) {
                            Puck1000Controller.this.callbackOnTerminalStatusUpdated(status);
                        }
                    }
                }
                else if (baseEvent instanceof B0006_ShowRemoveCard) {
                    B0006_ShowRemoveCard event = (B0006_ShowRemoveCard) baseEvent;
                    Puck1000Controller.this.callbackOnTerminalStatusUpdated(TerminalStatus.REMOVE_CARD);
                }
                else if (baseEvent instanceof B0009_AmountInput) {
                    B0009_AmountInput event = (B0009_AmountInput) baseEvent;
                }
                else if (baseEvent instanceof B0010_ShowPollCard) {
                    B0010_ShowPollCard event = (B0010_ShowPollCard) baseEvent;
                }
                else if (baseEvent instanceof B0011_ShowLoadingMsg) { // 2
                    B0011_ShowLoadingMsg event = (B0011_ShowLoadingMsg) baseEvent;
                    if (mTerminalVersionUpdateListener != null) {
                        ThreadPoolManager.INSTANCE.getSingleExecutor().submit(() -> {
                            mTerminalVersionUpdateListener.onProgress(50d, event.message);
                        });
                    }
                    TerminalStatus status = countRetry > 0 && countRetry <= MAX_RETRY
                            ? TerminalStatus.REINSERT_CARD
                            : TerminalStatus.WAITING_FOR_CARD;

                    // Only emit the status update if it differs from the last emitted one,
                    // to avoid redundant duplicate callbacks.
                    if (lastEmittedTerminalStatus != status) {
                        Puck1000Controller.this.callbackOnTerminalStatusUpdated(status);
                    }
                }
                else if (baseEvent instanceof B0012_AppSelect) {
                    handleApplicationSelection((B0012_AppSelect) baseEvent);
                    Timber.tag(TAG).d("EMV_AID_FLOW: B0012_AppSelect event handled.%s", ((B0012_AppSelect) baseEvent).selectedAid);
                }
                else if (baseEvent instanceof B0016_PollCardFallback) {
                    countRetry = MAX_RETRY;
                    technicalFallbackInitiated = true;
                    Puck1000Controller.this.callbackOnTerminalStatusUpdated(TerminalStatus.TECHNICAL_FALLBACK_INITIATED);
                }
                else Timber.d("Event process not implemented: %s", baseEvent);
                return baseEvent;
            }
            catch(Exception e) {
                Timber.e(e, "error handling event");
            }

            return baseEvent;
        }

        private CardDataSourceType MapEntryMode(EntryMode entryMode) {
            switch (entryMode) {
                case CONTACTLESS:
                    return CardDataSourceType.CONTACTLESS_EMV;
                case MSR:
                    return CardDataSourceType.MSR;
                case CONTACT:
                    return CardDataSourceType.SCR;
                default: return null;
            }
        }
    }

    /**
     * Decides how to recover from a failed transaction: retry on the same interface, initiate a
     * technical fallback, or surface the failure to the integrator.
     */
    private void handleTransactionTimeoutAndFailure(@NonNull B0004_ShowTxnResult event) {
        if (hasErrorCode(event, ErrorCode.CL_TXN_LIMIT_EXCEEDED.getCode())) {
            callbackOnTerminalStatusUpdated(TerminalStatus.CONTACTLESS_INTERFACE_FAILED_TRY_CONTACT);
            return;
        }

        // Only sale and refund transactions are retryable; anything else is reported straight away.
        if (!isTxnType(event, TxnType.SALE.name()) && !isTxnType(event, TxnType.REFUND.name())) {
            failTransaction(event);
            return;
        }

        if (event.txnResult == TxnResult.TIMEOUT) {
            failTransaction(event);
            return;
        }

        EntryMode entryMode = getEntryMode(event);
        Timber.tag(TAG).i("Transaction failed: errorCode=%s, entryMode=%s", event.errorCode, entryMode);

        switch (entryMode) {
            case MSR:
                retryTransaction(event, false);
                break;
            case CONTACT:
                if (hasErrorCode(event, ERROR_CODE_CONTACT_INTERFACE_FAILURE)) {
                    failTransaction(event);
                } else {
                    // Verify with the terminal model, if it is MSR or Non-MSR.
                    // Only MSR devices support transaction fallback to swipe only.
                    boolean isFallbackNeeded = terminalInfo.getModel() != null
                            && terminalInfo.getModel().equalsIgnoreCase(PUCK1000_3_IN_1_MODEL_NAME);
                    retryTransaction(event, isFallbackNeeded);
                }
                break;
            default:
                failTransaction(event);
                break;
        }
    }

    @NonNull
    private EntryMode getEntryMode(@NonNull B0004_ShowTxnResult event) {
        if (event.txnResponseBean == null || event.txnResponseBean.getEntryMode() == null) {
            return EntryMode.NONE;
        }

        return event.txnResponseBean.getEntryMode();
    }

    private boolean hasErrorCode(@NonNull B0004_ShowTxnResult event, @NonNull String errorCode) {
        return errorCode.equalsIgnoreCase(event.errorCode);
    }

    /**
     * Retries the transaction while the retry budget allows it. Once {@link #MAX_RETRY} attempts have
     * been made, either a technical fallback is initiated (when the interface supports it) or the
     * failure is reported to the integrator.
     */
    private void retryTransaction(@NonNull B0004_ShowTxnResult event, boolean fallbackNeeded) {
        if (countRetry < MAX_RETRY) {
            countRetry++;
            proceedWithStartTenderTransaction();
        } else if (countRetry == MAX_RETRY && fallbackNeeded) {
            countRetry++;
            technicalFallbackInitiated = true;
            callbackOnTerminalStatusUpdated(TerminalStatus.TECHNICAL_FALLBACK_INITIATED);
        } else {
            failTransaction(event);
        }
    }

    private void failTransaction(@NonNull B0004_ShowTxnResult event) {
        callbackOnTerminalInteractionError(TerminalError.TRANSACTION_ERROR, String.valueOf(event.txnResult));
        resetFields();
    }

    /**
     * Reports a cancelled transaction rather than a generic error so the integrator always
     * receives a final transaction response for a cancellation.
     */
    private void completeAsCancelled(@Nullable TxnResult txnResult) {
        if (terminalAction == null) {
            /* The transaction was already completed (a cancelled response has been reported and
               resetFields() cleared the action). Do not report anything else. */
            Timber.tag(TAG).d("completeAsCancelled() ignored for [%s], the transaction already completed.", txnResult);
            return;
        }

        TerminalResponse cancelledResponse = new TerminalResponse(terminalAction);
        cancelledResponse.setTerminalDecisionType(TerminalDecisionType.TRANSACTION_CANCELLED);
        cancelledResponse.setCardData(this.cardData);
        callbackOnTerminalResponse(cancelledResponse);
        resetFields();
    }

    /**
     * Retry logic for failed transactions.
     * If the transaction fails, we will retry up to MAX_RETRY times.
     * If it still fails after MAX_RETRY attempts, we will initiate a technical fallback.
     */
    private void handleRetryOrFallback(@NonNull B0004_ShowTxnResult event, boolean isFallbackNeeded) {
        if (countRetry < MAX_RETRY) {
            countRetry++;
            proceedWithStartTenderTransaction();
        } else if (countRetry == MAX_RETRY && isFallbackNeeded) {
            countRetry++;
            technicalFallbackInitiated = true;
            callbackOnTerminalStatusUpdated(TerminalStatus.TECHNICAL_FALLBACK_INITIATED);
        } else {
            callbackOnTerminalInteractionError(TerminalError.TRANSACTION_ERROR, event.txnResult.toString());
            resetFields();
        }
    }

    /**
     * Utility method to create a JSON string for a transaction request.
     *
     * @param txnType Transaction type (e.g., SALE, REFUND, CHK_CARD, etc.)
     * @param amount  Transaction amount in cents. If negative, it will be treated as an empty string.
     * @return A JSON string representing the transaction request.
     */
    private String createTxnRequestJson(TxnType txnType, long amount) {
        TxnRequestBean txnRequestBean = new TxnRequestBean();
        txnRequestBean.txnType = txnType;
        txnRequestBean.txnAmount = amount >= 0 ? String.valueOf(amount) : "";
        txnRequestBean.txnCurrency = TxnCurrency.USD;
        txnRequestBean.txnContinue = new ContinueBean();
        applyEntryMode(txnRequestBean, txnType);

        String response = new Gson().toJson(txnRequestBean);
        Timber.tag(TAG).e("createTxnRequestJson: %s", response);
        return response;
    }

    /**
     * Restricts the card interfaces the terminal may poll. After a technical fallback only the
     * magnetic stripe reader is allowed, otherwise the transaction could be re-attempted on the
     * chip interface that already failed. Non tender transactions keep the terminal defaults.
     */
    private void applyEntryMode(@NonNull TxnRequestBean txnRequestBean, @Nullable TxnType txnType) {
        if (txnType != TxnType.SALE && txnType != TxnType.REFUND) {
            return;
        }

        boolean msrOnly = technicalFallbackInitiated;
        Timber.tag(TAG).d("applyEntryMode: txnType=%s, fallbackSwipeOnly=%s", txnType, msrOnly);
        // setTxnEntryMode(msr, contact, contactless)
        txnRequestBean.setTxnEntryMode(true, !msrOnly, !msrOnly);
    }
}
