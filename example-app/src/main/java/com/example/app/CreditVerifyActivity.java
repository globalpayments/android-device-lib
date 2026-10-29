package com.example.app;

import android.annotation.SuppressLint;
import android.os.Bundle;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.CheckBox;

import com.globalpayments.library.terminals.IDevice;
import com.globalpayments.library.terminals.transactions.CreditVerifyBuilder;

public class CreditVerifyActivity extends BaseTransactionActivity {

    private static final String TAG = "CreditVerifyActivity";
    CheckBox requestTokenCheckBox;

    @SuppressLint("MissingInflatedId")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_credit_verify);

        executeButton = findViewById(R.id.execute_button);
        requestTokenCheckBox = findViewById(R.id.creditverify_requesttoken);

        if (MainActivity.c2XDevice != null) {
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
                    showAlertDialog(getString(R.string.error), getString(R.string.error_castles_device_not_connected));
                    return;
                }

                IDevice device = getDevice();
                if (device == null) {
                    showAlertDialog(getString(R.string.error), getString(R.string.error_device_not_connected));
                    return;
                }

                boolean requestToken = requestTokenCheckBox.isChecked();

                CreditVerifyBuilder creditVerifyBuilder = new CreditVerifyBuilder(device);
                creditVerifyBuilder.setRequestToken(requestToken);

                try {
                    creditVerifyBuilder.execute();
                } catch (Throwable e) {
                    e.printStackTrace();
                }
            }
        });

        updateTransactionStatus();
    }
}



