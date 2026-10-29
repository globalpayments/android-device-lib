package com.example.app;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.Button;
import com.globalpayments.library.terminals.IDevice;
import com.globalpayments.library.terminals.puck1000.Puck1000Device;

public class TransactionListActivity extends BaseTransactionActivity implements OnClickListener {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_transaction_list);

        findViewById(R.id.creditsale_button).setOnClickListener(this);
        findViewById(R.id.creditadjust_button).setOnClickListener(this);
        findViewById(R.id.creditauth_button).setOnClickListener(this);
        findViewById(R.id.creditverify_button).setOnClickListener(this);
        updateCreditVerifyVisibility();
        findViewById(R.id.creditcapture_button).setOnClickListener(this);
        findViewById(R.id.creditincrementalauth_button).setOnClickListener(this);
        findViewById(R.id.creditreturn_button).setOnClickListener(this);
        findViewById(R.id.creditvoid_button).setOnClickListener(this);
        findViewById(R.id.creditreversal_button).setOnClickListener(this);
        findViewById(R.id.batchclose_button).setOnClickListener(this);
        findViewById(R.id.giftcard_button).setOnClickListener(this);
        findViewById(R.id.uploadsaf_button).setOnClickListener(this);
        findViewById(R.id.forcesaf_button).setOnClickListener(this);
        findViewById(R.id.test_cancel_button).setOnClickListener(this);
        findViewById(R.id.test_cancel_saf_button).setOnClickListener(this);
        if (getDevice() instanceof Puck1000Device) {
            findViewById(R.id.test_audio_button).setVisibility(View.VISIBLE);
            findViewById(R.id.test_audio_button).setOnClickListener(this);
        } else {
            findViewById(R.id.test_audio_button).setVisibility(View.GONE);
        }
    }

    @Override
    public void onClick(View view) {
        Intent intent;
        IDevice device;
        if (view.getId() == R.id.creditsale_button) {
            intent = new Intent(this, CreditSaleActivity.class);
            startActivity(intent);
        } else if (view.getId() == R.id.creditadjust_button) {
            intent = new Intent(this, CreditAdjustActivity.class);
            startActivity(intent);
        } else if (view.getId() == R.id.creditauth_button) {
            intent = new Intent(this, CreditAuthActivity.class);
            startActivity(intent);
        } else if (view.getId() == R.id.creditverify_button) {
            intent = new Intent(this, CreditVerifyActivity.class);
            startActivity(intent);
        } else if (view.getId() == R.id.creditcapture_button) {
            intent = new Intent(this, CreditCaptureActivity.class);
            startActivity(intent);
        } else if (view.getId() == R.id.creditreturn_button) {
            intent = new Intent(this, CreditReturnActivity.class);
            startActivity(intent);
        } else if (view.getId() == R.id.creditvoid_button) {
            intent = new Intent(this, CreditVoidActivity.class);
            startActivity(intent);
        } else if (view.getId() == R.id.creditreversal_button) {
            intent = new Intent(this, CreditReversalActivity.class);
            startActivity(intent);
        } else if (view.getId() == R.id.batchclose_button) {
            intent = new Intent(this, BatchCloseActivity.class);
            startActivity(intent);
        } else if (view.getId() == R.id.giftcard_button) {
            intent = new Intent(this, GiftCardActivity.class);
            startActivity(intent);
        } else if (view.getId() == R.id.creditincrementalauth_button) {
            intent = new Intent(this, CreditIncrementalAuthActivity.class);
            startActivity(intent);
        } else if (view.getId() == R.id.uploadsaf_button) {
            device = getDevice();
            if (device != null) {
                Dialogs.showProgress(this, getString(R.string.status), getString(R.string.uploadsaf), null);
                device.uploadSAF();
            }
        } else if (view.getId() == R.id.forcesaf_button) {
            device = getDevice();
            if (device != null) {
                boolean currentSetting = device.isForcedSafEnabled();
                device.setForcedSafEnabled(!currentSetting);

                boolean newSetting = device.isForcedSafEnabled();
                ((Button)view).setText(
                        newSetting ? getString(R.string.forcesaf_on) : getString(R.string.forcesaf_off));
            }
        } else if (view.getId() == R.id.test_cancel_button) {
            device = getDevice();
            if (device != null) {
                device.cancelTransaction();
            }
        } else if (view.getId() == R.id.test_cancel_saf_button) {
            device = getDevice();
            if (device != null) {
                device.cancelSAFUpload();
                Dialogs.hideProgress();
            }
        } else if (view.getId() == R.id.test_audio_button) {
            device = getDevice();
            if (device instanceof Puck1000Device) {
                ((Puck1000Device) device).beep(1000, 500, 1, 100);
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateCreditVerifyVisibility();
    }

    /** Show the Credit Verify button only when a Castles (Puck 1000) device is connected. */
    private void updateCreditVerifyVisibility() {
        View btn = findViewById(R.id.creditverify_button);
        if (btn == null) return;
        boolean castlesConnected = MainActivity.puck1000Device != null
                && MainActivity.puck1000Device.isConnected();
        btn.setVisibility(castlesConnected ? View.VISIBLE : View.GONE);
    }
}