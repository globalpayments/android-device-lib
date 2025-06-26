package com.globalpayments.library.terminals;

import java.util.HashSet;

import android.bluetooth.BluetoothDevice;

import com.globalpayments.library.terminals.entities.TerminalInfo;
import com.globalpayments.library.terminals.enums.ErrorType;

public interface DeviceListener {
    void onBluetoothDeviceFound(BluetoothDevice bluetoothDevice);
    void onBluetoothDeviceList(HashSet<BluetoothDevice> deviceList);
    void onConnected(TerminalInfo terminalInfo);
    void onDisconnected();
    void onError(Error Error, ErrorType errorType);
    void onTerminalInfoReceived(TerminalInfo terminalInfo);
}