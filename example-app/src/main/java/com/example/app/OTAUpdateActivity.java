package com.example.app;

import static com.example.app.Dialogs.hideProgress;
import static com.example.app.Dialogs.showProgress;
import static com.example.app.Dialogs.updateProgress;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import android.Manifest;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.util.Log;
import android.view.View;

import com.globalpayments.library.terminals.AvailableTerminalVersionsListener;
import com.globalpayments.library.terminals.UpdateTerminalListener;
import com.globalpayments.library.terminals.enums.TerminalUpdateType;

import java.util.ArrayList;
import java.util.List;

import timber.log.Timber;

public class OTAUpdateActivity extends BaseActivity implements View.OnClickListener,
        AvailableTerminalVersionsListener, UpdateTerminalListener {

    private static final String TAG = OTAUpdateActivity.class.getSimpleName();
    private static final int REQUEST_STORAGE_PERMISSIONS = 200;
    private static final int REQUEST_MANAGE_STORAGE = 201;

    TerminalUpdateType terminalUpdateType;
    private View otaEmvButton;
    private View checkFirmwareButton;
    private View checkKernelButton;
    private View rkiButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_otaupdate);

        checkFirmwareButton = findViewById(R.id.check_firmware_button);
        checkKernelButton = findViewById(R.id.check_kernel_button);
        rkiButton = findViewById(R.id.rki_button);
        otaEmvButton = findViewById(R.id.ota_emv_button);

        checkFirmwareButton.setOnClickListener(this);
        checkKernelButton.setOnClickListener(this);
        rkiButton.setOnClickListener(this);
        otaEmvButton.setOnClickListener(this);
        updatePuckOnlyButtonVisibility();

        if (MainActivity.mobyDevice != null) {
            MainActivity.mobyDevice.setAvailableTerminalVersionsListener(this);
            MainActivity.mobyDevice.setUpdateTerminalListener(this);
        } else if (MainActivity.c2XDevice != null) {
            MainActivity.c2XDevice.setAvailableTerminalVersionsListener(this);
            MainActivity.c2XDevice.setUpdateTerminalListener(this);
        } else if (MainActivity.puck1000Device != null) {
//            MainActivity.puck1000Device.setAvailableTerminalVersionsListener(this);
            MainActivity.puck1000Device.setUpdateTerminalListener(this);

        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        updatePuckOnlyButtonVisibility();
    }

    private boolean isPuckConnected() {
        return MainActivity.puck1000Device != null && MainActivity.puck1000Device.isConnected();
    }

    private void setVisible(View view, boolean visible) {
        view.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    private void updatePuckOnlyButtonVisibility() {
        boolean puckConnected = isPuckConnected();

        // Puck only supports OTA EMV for now.
        setVisible(otaEmvButton, puckConnected);

        // Non-puck flows: firmware, kernel, and RKI.
        boolean showNonPuckOptions = !puckConnected;
        setVisible(checkFirmwareButton, showNonPuckOptions);
        setVisible(checkKernelButton, showNonPuckOptions);
        setVisible(rkiButton, showNonPuckOptions);
    }

    @Override
    public void onClick(View view) {
        if (view.getId() == R.id.check_firmware_button) {
            if ((MainActivity.mobyDevice == null || !MainActivity.mobyDevice.isConnected()) &&
                    (MainActivity.c2XDevice == null || !MainActivity.c2XDevice.isConnected())) {
                showAlertDialog(getString(R.string.error), getString(R.string.error_device_not_connected_ota));
                return;
            }

            showProgress(this, getString(R.string.checking), getString(R.string.checking_firmware), null);

            if (MainActivity.mobyDevice != null) {
                MainActivity.mobyDevice.getAvailableTerminalVersions(TerminalUpdateType.FIRMWARE);
            } else {
                MainActivity.c2XDevice.getAvailableTerminalVersions(TerminalUpdateType.FIRMWARE);
            }
        } else if (view.getId() == R.id.check_kernel_button) {
            if ((MainActivity.mobyDevice == null || !MainActivity.mobyDevice.isConnected()) &&
                    (MainActivity.c2XDevice == null || !MainActivity.c2XDevice.isConnected())) {
                showAlertDialog(getString(R.string.error), getString(R.string.error_device_not_connected_ota));
                return;
            }

            showProgress(this, getString(R.string.checking), getString(R.string.checking_kernel), null);

            if (MainActivity.mobyDevice != null) {
                MainActivity.mobyDevice.getAvailableTerminalVersions(TerminalUpdateType.CONFIG);
            } else {
                MainActivity.c2XDevice.getAvailableTerminalVersions(TerminalUpdateType.CONFIG);
            }
        } else if (view.getId() == R.id.rki_button) {
            if ((MainActivity.mobyDevice == null || !MainActivity.mobyDevice.isConnected()) &&
                    (MainActivity.c2XDevice == null || !MainActivity.c2XDevice.isConnected())) {
                showAlertDialog(getString(R.string.error), getString(R.string.error_device_not_connected_ota));
                return;
            }

            if (MainActivity.mobyDevice != null) {
                showAlertDialog(getString(R.string.error), getString(R.string.error_device_not_supported_rki));
                return;
            }

            terminalUpdateType = TerminalUpdateType.RKI;
            showProgress(this, getString(R.string.checking), getString(R.string.checking_kernel), null);
            MainActivity.c2XDevice.remoteKeyInjection();
        } else if (view.getId() == R.id.ota_emv_button) {
            if (!isPuckConnected()) {
                showAlertDialog(getString(R.string.error), getString(R.string.error_device_not_connected_ota));
                return;
            }

            if (hasAllStoragePermissions()) {
                startOtaEmvUpdate();
            } else {
                requestStoragePermissions();
            }
        }
    }

    // ── Storage permission helpers ────────────────────────────────────────────

    private boolean hasAllStoragePermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return Environment.isExternalStorageManager();
        }
        return ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                        == PackageManager.PERMISSION_GRANTED
                && ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                        == PackageManager.PERMISSION_GRANTED;
    }

    private void requestStoragePermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // MANAGE_EXTERNAL_STORAGE requires a Settings redirect on Android 11+
            new AlertDialog.Builder(this)
                    .setTitle("Storage Permission Required")
                    .setMessage("OTA EMV update requires access to all files. Please grant \"All files access\" in the next screen.")
                    .setPositiveButton("Open Settings", (d, w) -> {
                        Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                Uri.parse("package:" + getPackageName()));
                        startActivityForResult(intent, REQUEST_MANAGE_STORAGE);
                    })
                    .setNegativeButton(getString(R.string.cancel), null)
                    .show();
        } else {
            List<String> needed = new ArrayList<>();
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.READ_EXTERNAL_STORAGE);
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
            }
            ActivityCompat.requestPermissions(this,
                    needed.toArray(new String[0]), REQUEST_STORAGE_PERMISSIONS);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
            @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_STORAGE_PERMISSIONS) {
            boolean allGranted = true;
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }
            if (allGranted) {
                startOtaEmvUpdate();
            } else {
                showAlertDialog(getString(R.string.error),
                        "Storage permission is required for OTA EMV update.");
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_MANAGE_STORAGE) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                    && Environment.isExternalStorageManager()) {
                startOtaEmvUpdate();
            } else {
                showAlertDialog(getString(R.string.error),
                        "All-files access permission is required for OTA EMV update.");
            }
        }
    }

    private void startOtaEmvUpdate() {
        terminalUpdateType = TerminalUpdateType.CONFIG;
        showProgress(this, getString(R.string.ota_emv), "Starting OTA EMV update...", 0);
        // Run updateTerminal on background thread to prevent UI blocking
        new Thread(() -> {
            MainActivity.puck1000Device.updateTerminal(TerminalUpdateType.CONFIG, null);
        }).start();
    }

    @Override
    public void onAvailableTerminalVersionsReceived(TerminalUpdateType type, List<String> versions) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                hideProgress();
                if (type == TerminalUpdateType.FIRMWARE) {
                    AlertDialog d = Dialogs.showListDialog("Available firmware versions",
                            OTAUpdateActivity.this, versions.toArray(new String[0]),
                            new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(final DialogInterface dialogInterface, int i) {
                                    terminalUpdateType = TerminalUpdateType.FIRMWARE;
                                    showProgress(OTAUpdateActivity.this, "Updating", "Updating firmware...", 0);
                                    if (MainActivity.mobyDevice != null) {
                                        MainActivity.mobyDevice.updateTerminal(TerminalUpdateType.FIRMWARE, null);
                                    } else {
                                        MainActivity.c2XDevice.updateTerminal(TerminalUpdateType.FIRMWARE, versions.get(i));
                                    }
                                }
                            });
                } else {
                    AlertDialog d = Dialogs.showListDialog("Available kernel versions",
                            OTAUpdateActivity.this, versions.toArray(new String[0]),
                            new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(final DialogInterface dialogInterface, int i) {
                                    terminalUpdateType = TerminalUpdateType.CONFIG;
                                    showProgress(OTAUpdateActivity.this, "Updating", "Updating kernel...", 0);
                                    if (MainActivity.mobyDevice != null) {
                                        MainActivity.mobyDevice.updateTerminal(TerminalUpdateType.CONFIG, versions.get(i));
                                    } else {
                                        MainActivity.c2XDevice.updateTerminal(TerminalUpdateType.CONFIG, versions.get(i));
                                    }
                                }
                            });
                }

            }
        });
    }

    @Override
    public void onTerminalVersionInfoError(Error error) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                hideProgress();
                Timber.e(TAG, "onTerminalVersionInfoError: " + error.getMessage());
            }
        });
    }

    @Override
    public void onProgress(@Nullable Double completionPercentage, @Nullable String progressMessage) {
        Log.d(TAG, "onProgress - " + completionPercentage + ", " + progressMessage);
        if (terminalUpdateType == TerminalUpdateType.RKI
                && completionPercentage != null
                && completionPercentage <= 1) {
            showProgress(OTAUpdateActivity.this, "Injecting", "Injecting Keys...", 0);
        }
        updateProgress(completionPercentage, progressMessage);
    }

    @Override
    public void onTerminalUpdateSuccess() {
        runOnUiThread(() -> {
            String msg = "";
            hideProgress();
            if(terminalUpdateType != TerminalUpdateType.RKI &&
                    (terminalUpdateType != TerminalUpdateType.CONFIG && MainActivity.mobyDevice != null)) {
                if (MainActivity.mobyDevice != null) {
                    MainActivity.mobyDevice.disconnect();
                } else {
                    MainActivity.c2XDevice.disconnect();
                }
                msg = "Terminal successfully updated. The device has been disconnected so please reconnect.";
            } else {
                msg = "Terminal successfully updated.";
            }
            showAlertDialog("Update Success", msg);
        });
    }

    @Override
    public void onTerminalUpdateError(Error error) {
        runOnUiThread(() -> {
            hideProgress();
            showAlertDialog("Update Error", "Terminal failed to update - " + error.getMessage());
        });
    }
}