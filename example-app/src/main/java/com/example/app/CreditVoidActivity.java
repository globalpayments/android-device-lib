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
import com.globalpayments.library.terminals.transactions.CreditVoidBuilder;
import static com.example.app.Dialogs.showProgress;

public class CreditVoidActivity extends BaseTransactionActivity {

    private static final String TAG = "CreditVoidActivity";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_credit_void);

        executeButton = findViewById(R.id.execute_button);

        if(MainActivity.c2XDevice != null) {
            MainActivity.c2XDevice.setTransactionListener(transactionListener);
        } else if (MainActivity.mobyDevice != null) {
            MainActivity.mobyDevice.setTransactionListener(transactionListener);
        } else if (MainActivity.puck1000Device != null) {
            MainActivity.puck1000Device.setTransactionListener(transactionListener);
        }

        Spinner voidReasonSpinner = findViewById(R.id.creditvoid_voidreason);
        ArrayAdapter<ReversalReason> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, ReversalReason.values());
        voidReasonSpinner.setAdapter(adapter);

        executeButton.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View view) {
                if (MainActivity.c2XDevice != null && !MainActivity.c2XDevice.isConnected()) {
                    showAlertDialog(getString(R.string.error), getString(R.string.error_device_not_connected));
                    return;
                } else if(MainActivity.mobyDevice != null && !MainActivity.mobyDevice.isConnected()){
                    showAlertDialog(getString(R.string.error), getString(R.string.error_device_not_connected));
                    return;
                } else if(MainActivity.puck1000Device != null && !MainActivity.puck1000Device.isConnected()){
                    showAlertDialog(getString(R.string.error), getString(R.string.error_device_not_connected));
                    return;
                }

                String transactionId = ((EditText) findViewById(R.id.transaction_id)).getText().toString();

                if (transactionId.isEmpty()) {
                    showAlertDialog(getString(R.string.error), getString(R.string.error_no_transaction_id));
                    return;
                }

                String clientTransactionId = ((EditText) findViewById(R.id.client_transaction_id)).getText().toString();
                boolean allowDuplicates = ((CheckBox) findViewById(R.id.creditvoid_allowduplicates)).isChecked();
                ReversalReason reversalReason = ReversalReason.values()[((Spinner) findViewById(R.id.creditvoid_voidreason)).getSelectedItemPosition()];
                IDevice device = getDevice();
                CreditVoidBuilder creditVoidBuilder = new CreditVoidBuilder(device);
                creditVoidBuilder.setReversalReason(reversalReason);
                creditVoidBuilder.setTransactionId(transactionId);
                if (clientTransactionId != null) {
                    creditVoidBuilder.setReferenceNumber(clientTransactionId);
                }
                creditVoidBuilder.setAllowDuplicates(allowDuplicates);
                try {
                    creditVoidBuilder.execute();
                } catch (Throwable e) {
                    e.printStackTrace();
                }
            }
        });

        updateTransactionStatus();
    }
}