package com.example.app;

import androidx.appcompat.app.AppCompatActivity;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.EditText;

public class CredentialsActivity extends BaseActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_credentials);
        //load saved credentials, if they exist
        SharedPreferences sharedPreferences = getSharedPreferences(MainActivity.SAVED_PREFS, MODE_PRIVATE);
        String savedPublicKey = sharedPreferences.getString(MainActivity.SAVED_PUBLIC_KEY, null);
        String savedUsername = sharedPreferences.getString(MainActivity.SAVED_USERNAME, null);
        String savedPassword = sharedPreferences.getString(MainActivity.SAVED_PASSWORD, null);
        String savedSiteId = sharedPreferences.getString(MainActivity.SAVED_SITE_ID, null);
        String savedDeviceId = sharedPreferences.getString(MainActivity.SAVED_DEVICE_ID, null);
        String savedLicenseId = sharedPreferences.getString(MainActivity.SAVED_LICENSE_ID, null);
        String savedMerchantId = sharedPreferences.getString(MainActivity.SAVED_MERCHANT_ID, null);
        String savedDeveloperId = sharedPreferences.getString(MainActivity.SAVED_DEVELOPER_ID, null);
        String savedTransactionKey = sharedPreferences.getString(MainActivity.SAVED_TRANSACTION_KEY, null);

        //set the saved values to the fields
        if (savedPublicKey != null) {
            ((EditText) findViewById(R.id.public_key)).setText(savedPublicKey);
        }
        if (savedUsername != null) {
            ((EditText) findViewById(R.id.username)).setText(savedUsername);
        }
        if (savedPassword != null) {
            ((EditText) findViewById(R.id.password)).setText(savedPassword);
        }
        if (savedSiteId != null) {
            ((EditText) findViewById(R.id.site_id)).setText(savedSiteId);
        }
        if (savedDeviceId != null) {
            ((EditText) findViewById(R.id.device_id)).setText(savedDeviceId);
        }
        if (savedLicenseId != null) {
            ((EditText) findViewById(R.id.license_id)).setText(savedLicenseId);
        }
        if (savedMerchantId != null) {
            ((EditText) findViewById(R.id.merchant_id)).setText(savedMerchantId);
        }
        if (savedDeveloperId != null) {
            ((EditText) findViewById(R.id.developer_id)).setText(savedDeveloperId);
        }
        if (savedTransactionKey != null) {
            ((EditText) findViewById(R.id.transaction_key)).setText(savedTransactionKey);
        }

        findViewById(R.id.save_button).setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View view) {
                //get values from fields
                String publicKey = ((EditText) findViewById(R.id.public_key)).getText().toString();
                String username = ((EditText) findViewById(R.id.username)).getText().toString();
                String password = ((EditText) findViewById(R.id.password)).getText().toString();
                String siteId = ((EditText) findViewById(R.id.site_id)).getText().toString();
                String deviceId = ((EditText) findViewById(R.id.device_id)).getText().toString();
                String licenseId = ((EditText) findViewById(R.id.license_id)).getText().toString();
                String merchantId = ((EditText) findViewById(R.id.merchant_id)).getText().toString();
                String developerId = ((EditText) findViewById(R.id.developer_id)).getText().toString();
                String transactionKey = ((EditText) findViewById(R.id.transaction_key)).getText().toString();

                //set the new values
                MainActivity.PUBLIC_KEY = publicKey;
                MainActivity.USERNAME = username;
                MainActivity.PASSWORD = password;
                MainActivity.SITE_ID = siteId;
                MainActivity.DEVICE_ID = deviceId;
                MainActivity.LICENSE_ID = licenseId;
                MainActivity.MERCHANT_ID = merchantId;
                MainActivity.DEVELOPER_ID = developerId;
                MainActivity.TRANSACTION_KEY = transactionKey;

                //save the new values
                SharedPreferences.Editor editor =
                        getSharedPreferences(MainActivity.SAVED_PREFS, MODE_PRIVATE).edit();
                editor.putString(MainActivity.SAVED_PUBLIC_KEY, publicKey);
                editor.putString(MainActivity.SAVED_USERNAME, username);
                editor.putString(MainActivity.SAVED_PASSWORD, password);
                editor.putString(MainActivity.SAVED_SITE_ID, siteId);
                editor.putString(MainActivity.SAVED_DEVICE_ID, deviceId);
                editor.putString(MainActivity.SAVED_LICENSE_ID, licenseId);
                editor.putString(MainActivity.SAVED_MERCHANT_ID, merchantId);
                editor.putString(MainActivity.SAVED_DEVELOPER_ID, developerId);
                editor.putString(MainActivity.SAVED_TRANSACTION_KEY, transactionKey);
                editor.commit();

                finish();
            }
        });
    }
}