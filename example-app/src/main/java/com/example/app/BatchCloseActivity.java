package com.example.app;

import android.os.Bundle;
import android.view.View;
import android.view.View.OnClickListener;
import com.globalpayments.library.terminals.IDevice;
import com.globalpayments.library.terminals.transactions.BatchCloseBuilder;
import static com.example.app.Dialogs.showProgress;

public class BatchCloseActivity extends BaseTransactionActivity {

    private static final String TAG = "BatchCloseActivity";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_batch_close);

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

                IDevice device = MainActivity.c2XDevice != null ? MainActivity.c2XDevice : MainActivity.mobyDevice;
                BatchCloseBuilder batchCloseBuilder = new BatchCloseBuilder(device);
                try {
                    batchCloseBuilder.execute();
                } catch (Throwable e) {
                    e.printStackTrace();
                }
            }
        });

        updateTransactionStatus();
    }
}