package com.example.app;

import android.os.Bundle;
import android.widget.TextView;

public class TerminalInfoActivity extends BaseActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_terminal_info);

        TextView terminalTypeValue = findViewById(R.id.terminal_type_value);
        TextView serialValue = findViewById(R.id.terminal_serial_value);
        TextView appNameValue = findViewById(R.id.terminal_app_name_value);
        TextView appVersionValue = findViewById(R.id.terminal_app_version_value);
        TextView firmwareValue = findViewById(R.id.terminal_firmware_value);
        TextView modelValue = findViewById(R.id.terminal_model_value);
        TextView manufacturerValue = findViewById(R.id.terminal_manufacturer_value);
        TextView kernelValue = findViewById(R.id.terminal_kernel_value);
        TextView batteryLevelValue = findViewById(R.id.terminal_battery_level_value);

        terminalTypeValue.setText(getDisplayValue(MainActivity.terminalType));
        serialValue.setText(getDisplayValue(MainActivity.terminalSerialNumber));
        appNameValue.setText(getDisplayValue(MainActivity.terminalAppName));
        appVersionValue.setText(getDisplayValue(MainActivity.terminalAppVersion));
        firmwareValue.setText(getDisplayValue(MainActivity.firmwareVersion));
        modelValue.setText(getDisplayValue(MainActivity.terminalModel));
        manufacturerValue.setText(getDisplayValue(MainActivity.terminalManufacturer));
        kernelValue.setText(getDisplayValue(MainActivity.kernelVersion));
        batteryLevelValue.setText(getDisplayValue(MainActivity.terminalBatteryLevel));
    }

    private String getDisplayValue(String value) {
        if (value == null || value.trim().isEmpty()) {
            return getString(R.string.terminal_info_not_available);
        }
        return value;
    }
}
