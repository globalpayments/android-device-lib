package com.example.app;

import android.os.Bundle;
import android.view.View;
import android.view.View.OnClickListener;
import static com.example.app.Dialogs.showProgress;

public class GiftCardActivity extends BaseTransactionActivity {

    private static final String TAG = "GiftCardActivity";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_gift_card);

        executeButton = findViewById(R.id.execute_button);

        if (MainActivity.mobyDevice != null) {
            MainActivity.mobyDevice.setTransactionListener(transactionListener);
        }

        executeButton.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View view) {
                if(MainActivity.mobyDevice == null || (MainActivity.mobyDevice != null &&
                        !MainActivity.mobyDevice.isConnected())){
                    showAlertDialog(getString(R.string.error), getString(R.string.error_device_not_connected));
                    return;
                }

                MainActivity.mobyDevice.doSvaStartCard();
            }
        });

        updateTransactionStatus();
    }
}