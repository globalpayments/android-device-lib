package com.example.app;

import android.os.Bundle;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.CheckBox;
import android.widget.EditText;
import com.globalpayments.library.terminals.IDevice;
import com.globalpayments.library.terminals.transactions.CreditReturnBuilder;
import java.math.BigDecimal;
import static com.example.app.Dialogs.showProgress;

public class CreditReturnActivity extends BaseTransactionActivity {

    private static final String TAG = "CreditReturnActivity";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_credit_return);

        executeButton = findViewById(R.id.execute_button);

        if(MainActivity.c2XDevice != null) {
            MainActivity.c2XDevice.setTransactionListener(transactionListener);
        } else if (MainActivity.mobyDevice != null) {
            MainActivity.mobyDevice.setTransactionListener(transactionListener);
        }

        executeButton.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View view) {
                if (MainActivity.c2XDevice != null && !MainActivity.c2XDevice.isConnected()) {
                    showAlertDialog(getString(R.string.error), getString(R.string.error_device_not_connected));
                    return;
                } else if(MainActivity.mobyDevice != null && !MainActivity.mobyDevice.isConnected()){
                    showAlertDialog(getString(R.string.error), getString(R.string.error_device_not_connected));
                    return;
                }

                String amount = ((EditText) findViewById(R.id.amount)).getText().toString();
                String transactionId = ((EditText) findViewById(R.id.transaction_id)).getText().toString();

                if (amount.isEmpty()) {
                    showAlertDialog(getString(R.string.error), getString(R.string.error_no_amount));
                    return;
                }

                String clientTransactionId = ((EditText) findViewById(R.id.client_transaction_id)).getText().toString();
                boolean allowDuplicates = ((CheckBox) findViewById(R.id.creditadjust_allowduplicates)).isChecked();

                IDevice device = MainActivity.c2XDevice != null ? MainActivity.c2XDevice : MainActivity.mobyDevice;
                CreditReturnBuilder creditReturnBuilder = new CreditReturnBuilder(device);
                creditReturnBuilder.setAmount(new BigDecimal(amount));
                if (transactionId != null && !transactionId.isEmpty()) {
                    creditReturnBuilder.setTransactionId(transactionId);
                }
                if (clientTransactionId != null) {
                    creditReturnBuilder.setReferenceNumber(clientTransactionId);
                }
                creditReturnBuilder.setAllowDuplicates(allowDuplicates);
                try {
                    creditReturnBuilder.execute();
                } catch (Throwable e) {
                    e.printStackTrace();
                }
            }
        });

        updateTransactionStatus();
    }
}