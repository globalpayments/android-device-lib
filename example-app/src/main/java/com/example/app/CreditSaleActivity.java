package com.example.app;

import android.annotation.SuppressLint;
import android.os.Bundle;
import android.widget.CheckBox;
import android.widget.EditText;

import com.globalpayments.library.entities.TransactionDetails;
import com.globalpayments.library.terminals.IDevice;
import com.globalpayments.library.terminals.transactions.CreditSaleBuilder;
import com.tsys.payments.library.utils.LibraryConfigHelper;

import java.math.BigDecimal;

import timber.log.Timber;

public class CreditSaleActivity extends BaseTransactionActivity {

    private static final String TAG = "CreditSaleActivity";

    CheckBox requestTokenCheckBox;
    CheckBox withTokenCheckBox;
    CheckBox withTipCheckBox;
    EditText gratuity_amount;
    EditText token;

    boolean useToken = false;
    boolean useTip = false;

    @SuppressLint("MissingInflatedId")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_credit_sale);
        executeButton = findViewById(R.id.execute_button);
        requestTokenCheckBox = findViewById(R.id.creditsale_requesttoken);
        withTokenCheckBox = findViewById(R.id.creditsale_withtoken);
        withTipCheckBox = findViewById(R.id.creditsale_withtip);
        gratuity_amount = findViewById(R.id.gratuity_amount);
        token = findViewById(R.id.token);

        if (MainActivity.c2XDevice != null) {
            MainActivity.c2XDevice.setTransactionListener(transactionListener);
        } else if (MainActivity.mobyDevice != null) {
            MainActivity.mobyDevice.setTransactionListener(transactionListener);
        } else if (MainActivity.puck1000Device != null) {
            MainActivity.puck1000Device.setTransactionListener(transactionListener);
        }

        withTokenCheckBox.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked) {
                token.setHint(getString(R.string.required));
                useToken = true;
            } else {
                token.setHint(getString(R.string.optional));
                useToken = false;
            }
        });

        withTipCheckBox.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked) {
                gratuity_amount.setHint(getString(R.string.required));
                useTip = true;
            } else {
                gratuity_amount.setHint(getString(R.string.optional));
                useTip = false;
            }
        });

        executeButton.setOnClickListener(view -> {
            if (MainActivity.c2XDevice != null && !MainActivity.c2XDevice.isConnected()) {
                showAlertDialog(getString(R.string.error), getString(R.string.error_device_not_connected));
                return;
            } else if (MainActivity.mobyDevice != null && !MainActivity.mobyDevice.isConnected()) {
                showAlertDialog(getString(R.string.error), getString(R.string.error_device_not_connected));
                return;
            } else if (MainActivity.puck1000Device != null && !MainActivity.puck1000Device.isConnected()) {
                showAlertDialog(getString(R.string.error), getString(R.string.error_device_not_connected));
                return;
            }

            String amount = ((EditText) findViewById(R.id.amount)).getText().toString();

            if (amount.isEmpty()) {
                showAlertDialog(getString(R.string.error), getString(R.string.error_no_amount));
                return;
            }

            String gratuity = ((EditText) findViewById(R.id.gratuity_amount)).getText().toString();
            String clientTransactionId = ((EditText) findViewById(R.id.client_transaction_id)).getText().toString();
            String invoiceNumber = ((EditText) findViewById(R.id.invoice_number)).getText().toString();
            String token = ((EditText) findViewById(R.id.token)).getText().toString();
            String cardBrandTxnId = ((EditText) findViewById(R.id.card_brand_txn_id)).getText().toString();
            String taxAmount = ((EditText) findViewById(R.id.tax_amount)).getText().toString();
            boolean allowDuplicates = ((CheckBox) findViewById(R.id.creditsale_allowduplicates)).isChecked();
            boolean requestToken = requestTokenCheckBox.isChecked();
            if (useToken) {
                if (token.isEmpty() || cardBrandTxnId.isEmpty()) {
                    showAlertDialog(getString(R.string.error), getString(R.string.error_token_and_id));
                    return;
                }
            }
            if (useTip) {
                if (gratuity.isEmpty()) {
                    showAlertDialog(getString(R.string.error), getString(R.string.error_tip_required));
                    return;
                }
            }

            IDevice device = getDevice();
            if (device == null) {
                showAlertDialog(getString(R.string.error), getString(R.string.error_device_not_connected));
                return;
            }
            CreditSaleBuilder creditSaleBuilder = new CreditSaleBuilder(device);
            creditSaleBuilder.setAmount(new BigDecimal(amount));
            currentAmount = new BigDecimal(amount);
            currentTaxAmount = null;
            if (!gratuity.isEmpty()) {
                creditSaleBuilder.setGratuity(new BigDecimal(gratuity));
            }
            creditSaleBuilder.setReferenceNumber(clientTransactionId);
            TransactionDetails transactionDetails = new TransactionDetails();
            transactionDetails.setInvoiceNumber(invoiceNumber);
            creditSaleBuilder.setDetails(transactionDetails);
            if (useToken) {
                creditSaleBuilder.setToken(token);
                creditSaleBuilder.setCardBrandTxnId(cardBrandTxnId);
            }
            if (!taxAmount.isEmpty()) {
                creditSaleBuilder.setTaxAmount(new BigDecimal(taxAmount));
                currentTaxAmount = new BigDecimal(taxAmount);
            }

            Timber.tag(TAG).d("surcharge enabled : %s", LibraryConfigHelper.isSurchargeEnabled());
            if (LibraryConfigHelper.isSurchargeEnabled()) {
                creditSaleBuilder.setSurchargeAmount(getSurchargeAmount(taxAmount));
            }

            creditSaleBuilder.setAllowDuplicates(allowDuplicates);
            creditSaleBuilder.setRequestToken(requestToken);
            try {
                creditSaleBuilder.execute();
            } catch (Throwable e) {
                Timber.tag(TAG).e("Error executing credit sale: %s", e.getMessage());
            }
        });

        updateTransactionStatus();
    }

    /**
     * Calculates the surcharge amount based on the current amount and tax amount.
     *
     * @param taxAmount the tax amount as a string
     * @return the calculated surcharge amount as a BigDecimal
     */
    private BigDecimal getSurchargeAmount(String taxAmount) {
        BigDecimal finalSurchargeAmount;
        Timber.tag(TAG).d("getSurchargeAmount: taxAmount = %s", taxAmount);
        if (!taxAmount.isEmpty()) {
            //remove tax from total before calculating surcharge
            BigDecimal nonTaxTotal = currentAmount.subtract(currentTaxAmount);
            //calculate surcharge (example uses 3%)
            finalSurchargeAmount = nonTaxTotal.multiply(SURCHARGE_PERCENTAGE);
        } else {
            //calculation without taxes (example uses surcharge of 3%)
            finalSurchargeAmount = currentAmount.multiply(SURCHARGE_PERCENTAGE);
        }
        Timber.tag(TAG).d("getSurchargeAmount: finalSurchargeAmount = %s", finalSurchargeAmount);
        return finalSurchargeAmount;
    }
}