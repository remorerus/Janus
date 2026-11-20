package com.ymid.wakeonlan.ui.modify;

import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;

import androidx.annotation.NonNull;

import com.ymid.wakeonlan.R;
import com.ymid.wakeonlan.persistence.models.Device;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class AddDeviceActivity extends ModifyDeviceActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Never query Room on the UI thread. Populate a safe initial value, then
        // calculate the next number from the repository's background executor.
        deviceNameInput.setText("Устройство 1");
        deviceRepository.getAllAsync(existingDevices -> {
            if (isFinishing() || isDestroyed()) return;
            if ("Устройство 1".contentEquals(deviceNameInput.getText())) {
                deviceNameInput.setText(nextDeviceName(existingDevices));
            }
        });
        triggerRemoteShutdownLayoutVisibility(false);
    }

    private String nextDeviceName(List<Device> devices) {
        int next = 1;
        Pattern pattern = Pattern.compile("^Устройство\\s+(\\d+)$", Pattern.CASE_INSENSITIVE);
        for (Device existing : devices) {
            if (existing.name == null) continue;
            Matcher matcher = pattern.matcher(existing.name.trim());
            if (matcher.matches()) {
                try { next = Math.max(next, Integer.parseInt(matcher.group(1)) + 1); }
                catch (NumberFormatException ignored) { }
            }
        }
        return "Устройство " + next;
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.add_device_menu, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == R.id.add_device_menu_save) {
            checkAndPersistDevice();
            return true;
        }

        return super.onOptionsItemSelected(item);

    }

    @Override
    protected void persistDevice(Device device) {
        deviceRepository.insertAllAsync(device);
    }

    @Override
    protected Device buildDeviceFromInputs() {
        Device device = new Device();
        device.name = getDeviceNameInputText();
        device.statusIp = getDeviceStatusIpText();
        device.macAddress = getDeviceMacInputText();
        device.broadcastAddress = getDeviceBroadcastAddressText();
        device.port = getPort();
        device.wanIp = getDeviceWanAddressText();
        device.wanPort = getDeviceWanPort();
        device.secureOnPassword = getDeviceSecureOnPassword();
        device.remoteShutdownEnabled = getDeviceRemoteShutdownEnabled();
        device.sshAddress = getDeviceSshAddress();
        device.sshPort = getDeviceSshPort();
        device.sshUsername = getDeviceSshUsername();
        device.sshPassword = getDeviceSshPassword();
        device.sshCommand = getDeviceSshCommand();
        device.shutdownOs = getSelectedOs(deviceSshOsSpinner);
        device.sshAuthType = getDeviceSshAuthType();
        device.sshKeyAlias = getDeviceSshKeyAlias();
        String group = getDeviceGroupInputText();
        device.groupName = group.isEmpty() ? null : group;

        return device;
    }

    @Override
    protected boolean inputsHaveNotChanged() {
        return getDeviceNameInputText().isEmpty() && getDeviceMacInputText().isEmpty()
                && getPort() == 9
                && getDeviceBroadcastAddressText().isEmpty() && getDeviceStatusIpText().isEmpty()
                && getDeviceWanAddressText().isEmpty() && getDeviceWanPort() == null
                && getDeviceSecureOnPassword().isEmpty() && !getDeviceRemoteShutdownEnabled() &&
                getDeviceSshAddress().isEmpty() && getDeviceSshPort() == -1 && getDeviceSshUsername().isEmpty() &&
                getDeviceSshPassword().isEmpty() && getDeviceSshCommand().isEmpty() &&
                "password".equals(getDeviceSshAuthType()) && getDeviceSshKeyAlias() == null
                && getDeviceGroupInputText().isEmpty();
    }
}
