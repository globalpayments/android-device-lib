package com.example.app;

import android.os.Bundle;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.CheckBox;
import android.widget.EditText;

import com.globalpayments.library.terminals.IDevice;
import com.globalpayments.library.terminals.transactions.CreditIncrementalAuthBuilder;

import java.math.BigDecimal;

public class CreditIncrementalAuthActivity extends BaseTransactionActivity {

    private static final String TAG = "CreditIncrementalAuthActivity";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_credit_incremental_auth);

        executeButton = findViewById(R.id.execute_button);

        if(MainActivity.c2XDevice != null) {
            MainActivity.c2XDevice.setTransactionListener(transactionListener);
        } else if (MainActivity.mobyDevice != null) {
            MainActivity.mobyDevice.setTransactionListener(transactionListener);
        } else if (MainActivity.puck1000Device != null) {
            MainActivity.puck1000Device.setTransactionListener(transactionListener);
        }

        executeButton.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View view) {
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

                String clientTransactionId = ((EditText) findViewById(R.id.client_transaction_id)).getText().toString();

                IDevice device = getDevice();
                CreditIncrementalAuthBuilder creditIncrementalAuthBuilder = new CreditIncrementalAuthBuilder(device);
                creditIncrementalAuthBuilder.setTransactionId(transactionId);
                creditIncrementalAuthBuilder.setAmount(new BigDecimal(amount));
                if (clientTransactionId != null && !clientTransactionId.isEmpty()) {
                    creditIncrementalAuthBuilder.setReferenceNumber(clientTransactionId);
                }

                try {
                    creditIncrementalAuthBuilder.execute();
                } catch (Throwable e) {
                    e.printStackTrace();
                }
            }
        });

        updateTransactionStatus();
    }
}



