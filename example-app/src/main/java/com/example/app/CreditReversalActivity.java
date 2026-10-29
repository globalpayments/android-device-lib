package com.example.app;

import android.os.Bundle;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.Spinner;
import com.globalpayments.library.entities.ReversalReason;
import com.globalpayments.library.terminals.IDevice;
import com.globalpayments.library.terminals.transactions.CreditReversalBuilder;
import java.math.BigDecimal;

public class CreditReversalActivity extends BaseTransactionActivity {

    private static final String TAG = "CreditReversalActivity";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_credit_reversal);

        executeButton = findViewById(R.id.execute_button);

        if(MainActivity.c2XDevice != null) {
            MainActivity.c2XDevice.setTransactionListener(transactionListener);
        } else if (MainActivity.mobyDevice != null) {
            MainActivity.mobyDevice.setTransactionListener(transactionListener);
        }else if (MainActivity.puck1000Device != null) {
            MainActivity.puck1000Device.setTransactionListener(transactionListener);
        }

        Spinner reversalReasonSpinner = findViewById(R.id.creditreversal_reversalreason);
        ArrayAdapter<ReversalReason> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, ReversalReason.values());
        reversalReasonSpinner.setAdapter(adapter);

        executeButton.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View view) {
                if (MainActivity.c2XDevice != null && !MainActivity.c2XDevice.isConnected()) {
                    showAlertDialog(getString(R.string.error), getString(R.string.error_device_not_connected));
                    return;
                } else if(MainActivity.mobyDevice != null && !MainActivity.mobyDevice.isConnected()){
                    showAlertDialog(getString(R.string.error), getString(R.string.error_device_not_connected));
                    return;
                }else if(MainActivity.puck1000Device != null && !MainActivity.puck1000Device.isConnected()){
                    showAlertDialog(getString(R.string.error), getString(R.string.error_device_not_connected));
                    return;
                }

                String transactionId = ((EditText) findViewById(R.id.transaction_id)).getText().toString();
                String amount = ((EditText) findViewById(R.id.amount)).getText().toString();

                if (transactionId.isEmpty()) {
                    showAlertDialog(getString(R.string.error), getString(R.string.error_no_transaction_id));
                    return;
                }

                if (amount.isEmpty()) {
                    showAlertDialog(getString(R.string.error), getString(R.string.error_no_amount));
                    return;
                }

                BigDecimal totalAmount;
                try {
                    totalAmount = new BigDecimal(amount);
                } catch (NumberFormatException e) {
                    showAlertDialog(getString(R.string.error), getString(R.string.error_no_amount));
                    return;
                }

                String clientTransactionId = ((EditText) findViewById(R.id.client_transaction_id)).getText().toString();
                boolean allowDuplicates = ((CheckBox) findViewById(R.id.creditreversal_allowduplicates)).isChecked();
                ReversalReason reversalReason = ReversalReason.values()[((Spinner) findViewById(R.id.creditreversal_reversalreason)).getSelectedItemPosition()];

                IDevice device = getDevice();
                CreditReversalBuilder creditReversalBuilder = new CreditReversalBuilder(device);
                creditReversalBuilder.setTransactionId(transactionId);
                creditReversalBuilder.setAmount(totalAmount);
                if (clientTransactionId != null) {
                    creditReversalBuilder.setReferenceNumber(clientTransactionId);
                }
                creditReversalBuilder.setReversalReason(reversalReason);
                creditReversalBuilder.setAllowDuplicates(allowDuplicates);
                try {
                    creditReversalBuilder.execute();
                } catch (Throwable e) {
                    e.printStackTrace();
                }
            }
        });

        updateTransactionStatus();
    }
}