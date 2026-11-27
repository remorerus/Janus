package com.ymid.wakeonlan.ui.modify;

import android.os.Bundle;

public class AddNetworkScanDeviceActivity extends AddDeviceActivity {

    public static final String MACHINE_IP_KEY = "deviceIp";
    public static final String MACHINE_NAME_KEY = "deviceName";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prepopulateInputs();
    }

    private void prepopulateInputs() {
        Bundle extras = getIntent().getExtras();
        if (extras != null) {
            String machineName = extras.getString(MACHINE_NAME_KEY, null);
            String machineIp = extras.getString(MACHINE_IP_KEY, null);

            if (machineName != null && !machineName.trim().isEmpty()) {
                deviceNameInput.setText(machineName.trim());
            }
            if (machineIp != null && !machineIp.trim().isEmpty()) {
                // A Scan result is the same endpoint for reachability and the initial SSH route.
                // Credentials and the SSH key remain those explicitly configured by the user.
                deviceStatusIpInput.setText(machineIp.trim());
                deviceSshAddressInput.setRealText(machineIp.trim());
            }
            broadcastAutofill.callOnClick();
        }
    }
}
