package com.example.app;

import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.Spinner;
import com.globalpayments.library.terminals.enums.ConnectionMode;
import com.globalpayments.library.terminals.puck1000.mutualauth.MutualAuthEnvironment;
import static com.example.app.Dialogs.showProgress;

public class BluetoothActivity extends BaseActivity implements View.OnClickListener {

    private static final String TAG = "BluetoothActivity";

    public static final String EXTRA_CONNECTION_MODE = "EXTRA_CONNECTION_MODE";
    public static final String EXTRA_IS_PUCK1000 = "EXTRA_IS_PUCK1000";

    private Button scanButton;
    private Button reconnectButton;
    private Button connectButton;
    private Spinner deviceTypeSpinner;
    private View deviceTypeContainer;
    private boolean isPuck1000;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_bluetooth);

        connectionMode = (ConnectionMode) getIntent().getSerializableExtra(EXTRA_CONNECTION_MODE);
        isPuck1000 = getIntent().getBooleanExtra(EXTRA_IS_PUCK1000, false);

        scanButton = findViewById(R.id.scan_button);
        reconnectButton = findViewById(R.id.reconnect_button);
        connectButton = findViewById(R.id.connect_button);
        deviceTypeSpinner = findViewById(R.id.device_type_spinner);
        deviceTypeContainer = findViewById(R.id.device_type_container);

        ArrayAdapter<CharSequence> deviceTypeAdapter = ArrayAdapter.createFromResource(
                this, R.array.device_type_options, android.R.layout.simple_spinner_item);
        deviceTypeAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        deviceTypeSpinner.setAdapter(deviceTypeAdapter);
        deviceTypeSpinner.setSelection(0); // default: DVT/EVT

        scanButton.setOnClickListener(this);
        reconnectButton.setOnClickListener(this);
        connectButton.setOnClickListener(this);

        //check for previous bluetooth device
        if (bluetoothDeviceName != null && connectionMode == ConnectionMode.BLUETOOTH) {
            updateReconnectButton(true, bluetoothDeviceName);
        }

        if (MainActivity.c2XDevice != null && MainActivity.c2XDevice.isConnected()) {
            updateConnectionStatus("Connected - " + bluetoothDeviceName, false);
        } else if(MainActivity.mobyDevice != null && MainActivity.mobyDevice.isConnected()) {
            updateConnectionStatus("Connected - " + bluetoothDeviceName, false);
        } else if(MainActivity.puck1000Device != null && MainActivity.puck1000Device.isConnected()) {
            updateConnectionStatus("Connected - " + bluetoothDeviceName, false);
        } else {
            updateConnectionStatus(null, true);
        }

        //change the visible buttons for USB
        if (connectionMode == ConnectionMode.USB) {
            scanButton.setVisibility(View.GONE);
            reconnectButton.setVisibility(View.GONE);
            connectButton.setVisibility(View.VISIBLE);
            // Device type selection (MA cert host) only applies to the PUCK1000 reader.
            if (isPuck1000 && MainActivity.puck1000Device != null) {
                deviceTypeContainer.setVisibility(View.VISIBLE);
            }
        }
    }

    /**
     * Updated the connection status TextView and the connect Button.
     * @param status Text status to be displayed.
     * @param connectButtonEnabled Whether the connect button should be enabled/disabled.
     */
    @Override
    protected void updateConnectionStatus(String status, boolean connectButtonEnabled) {
        //update connection status
        super.updateConnectionStatus(status, connectButtonEnabled);
        //update the scan button
        scanButton.setEnabled(connectButtonEnabled);
        //update the reconnect button
        reconnectButton.setEnabled(connectButtonEnabled);
        //update the connect button
        connectButton.setEnabled(connectButtonEnabled);
    }

    /**
     * Update the reconnect button text and enabled status.
     * @param enabled
     * @param deviceName
     */
    private void updateReconnectButton(boolean enabled, String deviceName) {
        //update enabled
        reconnectButton.setEnabled(enabled);
        //update the text
        reconnectButton.setText(enabled ? getString(R.string.bluetooth_reconnect) + " (" + deviceName + ")" :
                getString(R.string.bluetooth_reconnect));
    }

    @Override
    public void onClick(View view) {
        if (view.getId() == R.id.scan_button) {
            if (MainActivity.c2XDevice != null) {
                MainActivity.c2XDevice.initialize();
            } else {
                MainActivity.mobyDevice.initialize();
            }

            //update connection status
            updateConnectionStatus("Scanning", false);
            showProgress(this, "Scanning", "Scanning for Bluetooth devices", null);
        } else if (view.getId() == R.id.reconnect_button) {
            SharedPreferences sharedPreferences = getSharedPreferences(MainActivity.SAVED_PREFS, MODE_PRIVATE);
            String previousDevice = sharedPreferences.getString(MainActivity.BLUETOOTH_NAME, null);
            String previousAddress = sharedPreferences.getString(MainActivity.BLUETOOTH_ADDRESS, null);

            if(MainActivity.c2XDevice != null) {
                MainActivity.c2XDevice.connect(previousAddress);
            } else {
                MainActivity.mobyDevice.connect(previousAddress);
            }

            Log.d(TAG, "bluetooth device connect - " + previousDevice);

            //update connection status
            updateConnectionStatus("Connecting - " + previousDevice,
                    false);
        } else if (view.getId() == R.id.connect_button) {
            if (isPuck1000) {
                // PUCK1000: ask whether to enable Mutual Authentication before connecting.
                showMutualAuthPrompt();
            } else if (MainActivity.mobyDevice != null) {
                updateConnectionStatus("Connecting USB", false);
                showProgress(this, "Connecting", "Connecting to device using USB", null);
                MainActivity.mobyDevice.initialize();
            }
        }
    }

    /**
     * Prompts the user to enable or disable PUCK1000 Mutual Authentication, then connects over USB
     * using the chosen setting.
     */
    private void showMutualAuthPrompt() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(R.string.mutual_auth_title);
        builder.setMessage(R.string.mutual_auth_message);
        builder.setCancelable(true);
        builder.setPositiveButton(R.string.enable, (dialog, which) -> {
            dialog.dismiss();
            connectPuckUsb(true);
        });
        builder.setNegativeButton(R.string.disable, (dialog, which) -> {
            dialog.dismiss();
            connectPuckUsb(false);
        });
        builder.show();
    }

    private void connectPuckUsb(boolean mutualAuthEnabled) {
        if (MainActivity.puck1000Device == null) {
            return;
        }
        MainActivity.puck1000Device.setMutualAuthEnabled(mutualAuthEnabled);
        if (mutualAuthEnabled) {
            // PVT is index 1; everything else (DVT/EVT) uses the default environment. The actual
            // URLs live in the SDK (MutualAuthEnvironment); the app only selects the environment.
            MutualAuthEnvironment environment = deviceTypeSpinner.getSelectedItemPosition() == 1
                    ? MutualAuthEnvironment.PVT
                    : MutualAuthEnvironment.DVT_EVT;
            MainActivity.puck1000Device.setMutualAuthEnvironment(environment);
            Log.d(TAG, "Mutual Auth environment: " + environment);
        }
        updateConnectionStatus("Connecting USB", false);
        showProgress(this, "Connecting", "Connecting to device using USB", null);
        MainActivity.puck1000Device.initialize();
    }
}