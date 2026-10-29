package com.example.app;

import android.app.AlertDialog;
import android.os.Bundle;
import android.util.Log;

import com.globalpayments.library.terminals.IDevice;
import com.globalpayments.library.terminals.puck1000.Puck1000Device;
import com.globalpayments.library.terminals.TransactionListener;
import com.globalpayments.library.terminals.entities.CardholderInteractionRequest;
import com.globalpayments.library.terminals.entities.CardholderInteractionResult;
import com.globalpayments.library.terminals.entities.TerminalResponse;
import com.globalpayments.library.terminals.enums.ErrorType;
import com.globalpayments.library.terminals.enums.TransactionStatus;
import com.tsys.payments.library.enums.CardholderInteractionType;
import com.tsys.payments.library.enums.TransactionType;
import java.math.BigDecimal;
import java.text.NumberFormat;
import timber.log.Timber;

import static com.example.app.Dialogs.hideProgress;
import static com.example.app.Dialogs.showProgress;

public abstract class BaseTransactionActivity extends BaseActivity {

    private static final String TAG = "BaseTransactionActivity";

    protected BigDecimal currentAmount;
    protected BigDecimal currentTaxAmount;

    private boolean cardPromptDialogVisible;
    private boolean cardPromptRequiresUserAction;

    protected final BigDecimal TAX_PERCENTAGE = BigDecimal.valueOf(0.07); // 7% tax rate
    protected final BigDecimal SURCHARGE_PERCENTAGE = BigDecimal.valueOf(0.03); // 3% surcharge rate

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        currentAmount = null;
        currentTaxAmount = null;
        cardPromptDialogVisible = false;
        cardPromptRequiresUserAction = false;
    }

    protected TransactionListener transactionListener = new TransactionListener() {
        @Override
        public void onStatusUpdate(TransactionStatus transactionStatus) {
            Timber.tag(TAG).d("onStatusUpdate - %s", transactionStatus.toString());
            MainActivity.cardReaderStatus = transactionStatus.toString().replace("_", " ");
            MainActivity.transactionState = MainActivity.TransactionState.Processing;
            updateTransactionStatus();

            if (!isCardPromptStatus(transactionStatus)) {
                // The terminal moved on (e.g. the card was finally swiped), so any card prompt
                // left on screen is stale and has to be cleared.
                dismissCardPromptDialog();
            }

            if (transactionStatus == TransactionStatus.REMOVE_CARD) {
                hideProgress();
                showProgress(
                        BaseTransactionActivity.this,
                        getString(R.string.status),
                        getString(R.string.remove_card),
                        null
                );
                return;
            }
            if (transactionStatus == TransactionStatus.CARD_READ) {
                hideProgress();
                showProgress(
                        BaseTransactionActivity.this,
                        getString(R.string.status),
                        getString(R.string.read_card_ok),
                        null
                );
            }

            if (isCardPromptStatus(transactionStatus)) {
                showCardPromptDialog(transactionStatus);
                return;
            }

            showTransactionProgress(transactionStatus);
        }

        @Override
        public boolean onCardholderInteractionRequested(CardholderInteractionRequest cardholderInteractionRequest) {
            Timber.tag(TAG).d("onCardholderInteractionRequested - %s", cardholderInteractionRequest.getCardholderInteractionType());
            // prompt user for action
            CardholderInteractionResult result;
            switch (cardholderInteractionRequest.getCardholderInteractionType()) {
                case EMV_APPLICATION_SELECTION:
                    String[] applications = cardholderInteractionRequest.getSupportedApplications();
                    Timber.d("onCardholderInteractionRequested: EMV_APPLICATION_SELECTION - %s", (Object) applications);
                    result = new CardholderInteractionResult(
                            cardholderInteractionRequest.getCardholderInteractionType());
                    if (applications == null || applications.length == 0) {
                        Timber.e("No EMV applications provided by terminal.");
                        result.setSelectedAidIndex(-1);
                        sendCardHolderInteractionResult(result);
                        return true;
                    }
                    final CardholderInteractionResult appSelectResult = result;
                    runOnUiThread(() -> {
                        hideProgress();
                        final int[] selectedIndex = {0};
                        final boolean[] hasSentResult = {false};
                        AlertDialog.Builder builder = new AlertDialog.Builder(BaseTransactionActivity.this);
                        builder.setTitle(getString(R.string.select_emv_application));
                        builder.setCancelable(false);
                        builder.setSingleChoiceItems(applications, 0, (dialog, which) -> selectedIndex[0] = which);
                        builder.setPositiveButton(getString(R.string.confirm), (dialog, which) -> {
                            if (hasSentResult[0]) {
                                return;
                            }
                            hasSentResult[0] = true;
                            Timber.d("EMV application confirmed at index: %d", selectedIndex[0]);
                            appSelectResult.setSelectedAidIndex(selectedIndex[0]);
                            sendCardHolderInteractionResult(appSelectResult);
                        });
                        builder.setNegativeButton(getString(R.string.cancel), (dialog, which) -> {
                            if (hasSentResult[0]) {
                                return;
                            }
                            hasSentResult[0] = true;
                            Timber.d("EMV application selection cancelled.");
                            appSelectResult.setSelectedAidIndex(-1);
                            sendCardHolderInteractionResult(appSelectResult);
                        });
                        builder.setOnDismissListener(dialog -> {
                            Timber.d("EMV application selection dialog dismissed.");
                            if (!hasSentResult[0]) {
                                hasSentResult[0] = true;
                                appSelectResult.setSelectedAidIndex(-1);
                                sendCardHolderInteractionResult(appSelectResult);
                            }
                        });
                        builder.show();
                    });
                    return true;
                case SURCHARGE_REQUESTED:
                    result = new CardholderInteractionResult(
                            CardholderInteractionType.CARDHOLDER_SURCHARGE_CONFIRMATION);

                    //OLD SURCHARGE
                    String surchargeAmount = NumberFormat.getCurrencyInstance().format((float)cardholderInteractionRequest.getFinalSurchargeAmount()/100);
                    //example for pre-tax with 2.5% surcharge: $10.70 total with $0.70 tax amount before surcharge
                    //nonTaxTotal will be $10
                    //finalSurchargeAmount will be $0.25
                    //finalAmount will be $10.95

                    //NEW SURCHARGE
                    //perform your own calculations for the surcharge amount, tax amount, and total amount
                    /*BigDecimal finalTaxAmount;
                    BigDecimal finalSurchargeAmount;
                    BigDecimal finalAmount;
                    if (currentTaxAmount != null) {
                        //remove tax from total before calculating surcharge
                        BigDecimal nonTaxTotal = currentAmount.subtract(currentTaxAmount);
                        //calculate surcharge (example uses 3%)
                        finalSurchargeAmount = nonTaxTotal.multiply(SURCHARGE_PERCENTAGE);
                        //calculate tax amount increase caused by surcharge (example uses 7% tax rate)
                        finalTaxAmount = currentTaxAmount.add(finalSurchargeAmount.multiply(TAX_PERCENTAGE));
                        //calculate the final total
                        finalAmount = nonTaxTotal.add(finalSurchargeAmount).add(finalTaxAmount);
                    } else {
                        //calculation without taxes (example uses surcharge of 3%)
                        finalSurchargeAmount = currentAmount.multiply(SURCHARGE_PERCENTAGE);
                        finalAmount = currentAmount.add(finalSurchargeAmount);
                        finalTaxAmount = BigDecimal.valueOf(0);
                    }

                    //example: $10.70 total with $0.70 tax amount before surcharge
                    //nonTaxTotal will be $10
                    //finalSurchargeAmount will be $0.30 (3% of $10)
                    //finalTaxAmount will be $0.70 ((0.70 + 0.30) * 7%)
                    //finalAmount will be $11.00

                    String surchargeAmount = NumberFormat.getCurrencyInstance().format(finalSurchargeAmount); */
                    Dialogs.showListDialog(getString(R.string.confirm_surcharge_amount, surchargeAmount),
                            BaseTransactionActivity.this, new String[]{getString(R.string.surcharge_accept), getString(R.string.surcharge_decline)},
                            (dialogInterface, i) -> {
                                Timber.tag(TAG).d("onClick: %s", i);
                                result.setFinalAmountConfirmed(i == 0);

                                //NEW SURCHARGE
                                /*result.setFinalSurchargeAmount(finalSurchargeAmount);
                                result.setFinalAmount(finalAmount);
                                result.setFinalTaxAmount(finalTaxAmount);*/
                                sendCardHolderInteractionResult(result);
                            });
                    return true;
                case FINAL_AMOUNT_CONFIRMATION:
                    // prompt user to confirm final amount
                    result = new CardholderInteractionResult(
                            cardholderInteractionRequest.getCardholderInteractionType()
                    );
                    result.setFinalAmountConfirmed(true);
                    sendCardHolderInteractionResult(result);
                    return true;
                case REQUEST_PROCEED_HOST_PROCESSING:
                    result = new CardholderInteractionResult(
                            cardholderInteractionRequest.getCardholderInteractionType()
                    );
                    Dialogs.showListDialog(getString(R.string.cdcvm_required_message),
                            BaseTransactionActivity.this, new String[] {getString(R.string.confirm), getString(R.string.cancel)},
                            (dialogInterface, i) -> {
                                result.setContinueHostProcessingConfirmed(i == 0);
                                sendCardHolderInteractionResult(result);
                            });
                    return true;
                default:
                    break;
            }
            return false;
        }

        private void sendCardHolderInteractionResult(CardholderInteractionResult result) {
            if(MainActivity.c2XDevice != null) {
                MainActivity.c2XDevice.sendCardholderInteractionResult(result);
            } else if (MainActivity.mobyDevice != null) {
                MainActivity.mobyDevice.sendCardholderInteractionResult(result);
            } else if (MainActivity.puck1000Device != null) {
                Timber.tag(TAG).d("sendCardHolderInteractionResult: %s", result.getCardholderInteractionType());
                MainActivity.puck1000Device.sendCardholderInteractionResult(result);
            }
        }

        @Override
        public void onTransactionComplete(TerminalResponse transaction) {
            Timber.tag(TAG).d("onTransactionComplete - %s", transaction.toString());
            hideProgress();
            dismissCardPromptDialog(true);
            if (transaction.getDeviceResponseCode() != null && !transaction.getDeviceResponseCode().equals("SAF")) {
                MainActivity.transactionId = transaction.getTransactionId();
                MainActivity.transactionResult = transaction.getDeviceResponseCode();
                MainActivity.transactionState = MainActivity.TransactionState.Complete;
                updateTransactionStatus();
            }

            boolean showReceiptOption = (transaction.getAuthorizationResponse() != null &&
                    transaction.getAuthorizationResponse().equals("00")) &&
                    (transaction.getTransactionType().equals(TransactionType.SALE.name()) ||
                    transaction.getTransactionType().equals(TransactionType.AUTH.name()) ||
                    transaction.getTransactionType().equals(TransactionType.REFUND.name()));

            showAlertDialog(getString(R.string.transaction_complete), Dialogs.constructTransactionMessage(transaction), showReceiptOption, transaction);
        }

        @Override
        public void onError(Error error, ErrorType errorType) {
            Timber.tag(TAG).e("onError - %s, %s", error.getMessage(), errorType);
            hideProgress();
            dismissCardPromptDialog(true);
            MainActivity.transactionResult = error.getMessage();
            MainActivity.transactionState = MainActivity.TransactionState.Complete;
            updateTransactionStatus();

            showAlertDialog(getString(R.string.transaction_error), error.getMessage());
        }
    };

    private void continueAfterRemoveCardPrompt() {
        if (getDevice() instanceof Puck1000Device) {
            ((Puck1000Device) getDevice()).continueAfterRemoveCardPrompt();
        }
    }

    private void showTransactionProgress(TransactionStatus transactionStatus) {
        if (!transactionStatus.name().equals(TransactionStatus.NONE.name())) {
            showProgress(BaseTransactionActivity.this,
                    getString(R.string.status),
                    transactionStatus.name(),
                    view -> {
                        IDevice device = getDevice();
                        if (device != null) {
                            device.cancelTransaction();
                        }
                    });
        }
    }

    private void showCardPromptDialog(TransactionStatus transactionStatus) {
        // Only the Puck 1000 needs the integrator to restart polling after a fallback prompt.
        // Every other terminal keeps the card read active, so the prompt is purely informational.
        boolean resumePollingRequired = isResumePollingStatus(transactionStatus)
                && isPuck1000Connected();

        dismissCardPromptDialog(true);
        hideProgress();

        cardPromptDialogVisible = true;
        cardPromptRequiresUserAction = resumePollingRequired;
        showAlertDialog(getString(R.string.error),
                getInsertPromptMessage(transactionStatus),
                () -> {
                    cardPromptDialogVisible = false;
                    cardPromptRequiresUserAction = false;
                    if (resumePollingRequired && MainActivity.puck1000Device != null) {
                        showTransactionProgress(TransactionStatus.WAITING_FOR_CARD);
                        MainActivity.puck1000Device.resumePolling();
                    }
                });
    }

    private void dismissCardPromptDialog() {
        dismissCardPromptDialog(false);
    }

    private void dismissCardPromptDialog(boolean force) {
        if (!cardPromptDialogVisible) {
            return;
        }

        if (cardPromptRequiresUserAction && !force) {
            // The terminal is parked until the user acknowledges the prompt, keep it on screen.
            return;
        }

        cardPromptDialogVisible = false;
        cardPromptRequiresUserAction = false;
        dismissAlertDialog();
    }

    private boolean isCardPromptStatus(TransactionStatus transactionStatus) {
        if (transactionStatus == null) {
            return false;
        }

        if (!isPuck1000Connected()) {
            // Moby/C2X render the card prompts on the terminal itself, so the app only reports
            // the status update, matching the behaviour before the Puck 1000 prompts were added.
            return false;
        }

        return isInsertPromptStatus(transactionStatus) || isResumePollingStatus(transactionStatus);
    }

    private boolean isPuck1000Connected() {
        return MainActivity.puck1000Device != null;
    }

    private boolean isInsertPromptStatus(TransactionStatus transactionStatus) {
        if (transactionStatus == null) {
            return false;
        }

        switch (transactionStatus) {
            case INSERT_CARD:
            case INSERT_TAP_CARD:
            case TRY_ANOTHER_CARD:
            case SWIPE_CARD:
            case TAP_CARD:
            case TAP_SWIPE_CARD:
            case INSERT_SWIPE_OR_TRY_ANOTHER_CARD:
                return true;
            default:
                return false;
        }
    }

    private boolean isResumePollingStatus(TransactionStatus transactionStatus) {
        return transactionStatus == TransactionStatus.TECHNICAL_FALLBACK_INITIATED
                || transactionStatus == TransactionStatus.CONTACTLESS_INTERFACE_FAILED_TRY_CONTACT
                || transactionStatus == TransactionStatus.ICC_SWIPED;
    }

    private String getInsertPromptMessage(TransactionStatus transactionStatus) {
        if (transactionStatus == null) {
            return getString(R.string.error_enter_valid_card);
        }

        switch (transactionStatus) {
            case INSERT_CARD:
                return getString(R.string.prompt_insert_card);
            case INSERT_TAP_CARD:
            case TAP_CARD:
            case TAP_AGAIN_CARD:
                return getString(R.string.prompt_tap_card_again);
            case SWIPE_CARD:
            case TECHNICAL_FALLBACK_INITIATED:
                return getString(R.string.prompt_swipe_card);
            case ICC_SWIPED:
                return getString(R.string.prompt_upgrade_to_emv);
            case TAP_SWIPE_CARD:
                return getString(R.string.prompt_tap_or_swipe_card);
            case TRY_ANOTHER_CARD:
                return getString(R.string.prompt_try_another_card);
            case INSERT_SWIPE_OR_TRY_ANOTHER_CARD:
                return getString(R.string.prompt_insert_swipe_or_try_another_card);
            case CONTACTLESS_INTERFACE_FAILED_TRY_CONTACT:
                return getString(R.string.prompt_contactless_payment_failed_try_contact);
            default:
                return getString(R.string.error_enter_valid_card);
        }
    }

}