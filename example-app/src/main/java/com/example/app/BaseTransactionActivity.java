package com.example.app;

import android.content.DialogInterface;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.View.OnClickListener;
import com.globalpayments.library.terminals.IDevice;
import com.globalpayments.library.terminals.TransactionListener;
import com.globalpayments.library.terminals.entities.CardholderInteractionRequest;
import com.globalpayments.library.terminals.entities.CardholderInteractionResult;
import com.globalpayments.library.terminals.entities.TerminalResponse;
import com.globalpayments.library.terminals.enums.ErrorType;
import com.globalpayments.library.terminals.enums.TransactionStatus;
import com.tsys.payments.library.enums.CardholderInteractionType;
import java.math.BigDecimal;
import java.text.NumberFormat;
import static com.example.app.Dialogs.hideProgress;
import static com.example.app.Dialogs.showProgress;

public abstract class BaseTransactionActivity extends BaseActivity {

    private static final String TAG = "BaseTransactionActivity";

    protected BigDecimal currentAmount;
    protected BigDecimal currentTaxAmount;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        currentAmount = null;
        currentTaxAmount = null;
    }

    /*protected void updateTransactionStatus() {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                TextView transactionStatus = findViewById(R.id.transaction_status);
                switch (MainActivity.transactionState) {
                    case None:
                        transactionStatus.setText(MainActivity.transactionState.toString());
                        break;
                    case Processing:
                        transactionStatus.setText(MainActivity.transactionState + " - " + MainActivity.cardReaderStatus + " - " + MainActivity.transactionId);
                        break;
                    case Complete:
                        transactionStatus.setText(MainActivity.transactionState + " - " + MainActivity.transactionResult + " - " + MainActivity.transactionId);
                        break;
                }
                executeButton.setEnabled(MainActivity.transactionState != MainActivity.TransactionState.Processing);
            }
        });
    }*/

    protected TransactionListener transactionListener = new TransactionListener() {
        @Override
        public void onStatusUpdate(TransactionStatus transactionStatus) {
            Log.d(TAG, "onStatusUpdate - " + transactionStatus.toString());
            MainActivity.cardReaderStatus = transactionStatus.toString().replaceAll("_", " ");
            MainActivity.transactionState = MainActivity.TransactionState.Processing;
            updateTransactionStatus();
            if(!transactionStatus.name().equals(TransactionStatus.NONE.name())) {
                showProgress(BaseTransactionActivity.this, "Status", transactionStatus.name(), new OnClickListener() {
                    @Override
                    public void onClick(View view) {
                        IDevice device = MainActivity.c2XDevice != null ? MainActivity.c2XDevice : MainActivity.mobyDevice;
                        device.cancelTransaction();
                    }
                });
            }
        }

        @Override
        public boolean onCardholderInteractionRequested(CardholderInteractionRequest cardholderInteractionRequest) {
            Log.d(TAG, "onCardholderInteractionRequested - " + cardholderInteractionRequest.getCardholderInteractionType());
            // prompt user for action
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
                    if(MainActivity.c2XDevice != null) {
                        MainActivity.c2XDevice.sendCardholderInteractionResult(result);
                    } else {
                        MainActivity.mobyDevice.sendCardholderInteractionResult(result);
                    }

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
                        finalSurchargeAmount = nonTaxTotal.multiply(BigDecimal.valueOf(0.03));
                        //calculate tax amount increase caused by surcharge (example uses 7% tax rate)
                        finalTaxAmount = currentTaxAmount.add(finalSurchargeAmount.multiply(BigDecimal.valueOf(0.07)));
                        //calculate the final total
                        finalAmount = nonTaxTotal.add(finalSurchargeAmount).add(finalTaxAmount);
                    } else {
                        //calculation without taxes (example uses surcharge of 3%)
                        finalSurchargeAmount = currentAmount.multiply(BigDecimal.valueOf(0.03));
                        finalAmount = currentAmount.add(finalSurchargeAmount);
                        finalTaxAmount = BigDecimal.valueOf(0);
                    }

                    //example: $10.70 total with $0.70 tax amount before surcharge
                    //nonTaxTotal will be $10
                    //finalSurchargeAmount will be $0.30
                    //finalTaxAmount will be $0.72
                    //finalAmount will be $11.02

                    String surchargeAmount = NumberFormat.getCurrencyInstance().format(finalSurchargeAmount);*/

                    Dialogs.showListDialog("Confirm Surcharge amount of " + surchargeAmount,
                            BaseTransactionActivity.this, new String[] {"Accept", "Decline"},
                            new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface dialogInterface, int i) {
                                    result.setFinalAmountConfirmed(i == 0);

                                    //NEW SURCHARGE
                                    /*result.setFinalSurchargeAmount(finalSurchargeAmount);
                                    result.setFinalAmount(finalAmount);
                                    result.setFinalTaxAmount(finalTaxAmount);*/

                                    if(MainActivity.c2XDevice != null) {
                                        MainActivity.c2XDevice.sendCardholderInteractionResult(result);
                                    } else {
                                        MainActivity.mobyDevice.sendCardholderInteractionResult(result);
                                    }
                                }
                            });
                    return true;
                case FINAL_AMOUNT_CONFIRMATION:
                    // prompt user to confirm final amount
                    result = new CardholderInteractionResult(
                            cardholderInteractionRequest.getCardholderInteractionType()
                    );
                    result.setFinalAmountConfirmed(true);
                    if(MainActivity.c2XDevice != null) {
                        MainActivity.c2XDevice.sendCardholderInteractionResult(result);
                    } else {
                        MainActivity.mobyDevice.sendCardholderInteractionResult(result);
                    }
                    return true;
                default:
                    break;
            }
            return false;
        }

        @Override
        public void onTransactionComplete(TerminalResponse transaction) {
            Log.d(TAG, "onTransactionComplete - " + transaction.toString());
            hideProgress();
            if (transaction.getDeviceResponseCode() != null && !transaction.getDeviceResponseCode().equals("SAF")) {
                MainActivity.transactionId = transaction.getTransactionId();
                MainActivity.transactionResult = transaction.getDeviceResponseCode();
                MainActivity.transactionState = MainActivity.TransactionState.Complete;
                updateTransactionStatus();
            }

            boolean showReceiptOption = (transaction.getAuthorizationResponse() != null &&
                    transaction.getAuthorizationResponse().equals("00")) &&
                    (transaction.getTransactionType().equals("SALE") ||
                    transaction.getTransactionType().equals("AUTH") ||
                    transaction.getTransactionType().equals("REFUND"));

            showAlertDialog(getString(R.string.transaction_complete), Dialogs.constructTransactionMessage(transaction), showReceiptOption, transaction);
        }

        @Override
        public void onError(Error error, ErrorType errorType) {
            Log.e(TAG, "onError - " + error.getMessage() + ", " + errorType);
            hideProgress();
            MainActivity.transactionResult = error.getMessage();
            MainActivity.transactionState = MainActivity.TransactionState.Complete;
            updateTransactionStatus();

            showAlertDialog(getString(R.string.transaction_error), error.getMessage());
        }
    };
}