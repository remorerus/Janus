package com.ymid.wakeonlan.ui.modify;

import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.common.base.Strings;

import java.util.Objects;

import com.ymid.wakeonlan.R;
import com.ymid.wakeonlan.persistence.models.Device;
import com.ymid.wakeonlan.security.TofuHostKeyVerifier;
import com.ymid.wakeonlan.security.SshKeyManager;
import com.ymid.wakeonlan.voice.WindowsAgentClient;
import org.json.JSONObject;

public class EditDeviceActivity extends ModifyDeviceActivity {

    public static final String DEVICE_PARCELABLE_KEY = "machine";
    private Device device;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        populateInputs();
        configureSshConnectionRequest();
        configureAgentConnectionControls();
    }

    private void configureSshConnectionRequest() {
        sshConnectButton.setVisibility(android.view.View.VISIBLE);
        sshConnectStatus.setVisibility(android.view.View.VISIBLE);
        sshConnectButton.setOnClickListener(v -> {
            String username = deviceSshUsernameInput.getText() == null
                    ? "" : deviceSshUsernameInput.getText().toString().trim();
            if (username.isEmpty()) {
                sshConnectStatus.setText("Сначала укажи имя пользователя Windows в этой карточке.");
                return;
            }
            String alias = sshKeyAlias;
            if (Strings.isNullOrEmpty(alias)) {
                alias = SshKeyManager.generateKeyPair();
                sshKeyAlias = alias;
                updateSshPublicKeyUi();
            } else if (SshKeyManager.getOpenSshPublicKey(alias) == null) {
                sshConnectStatus.setText("Сохранённый ключ не найден в Android Keystore. Существующая привязка не изменена.");
                return;
            }

            final String selectedAlias = alias;
            final Device target = buildDeviceFromInputs();
            target.sshKeyAlias = selectedAlias;
            sshConnectButton.setEnabled(false);
            sshConnectStatus.setText(getString(R.string.ssh_key_searching));

            new Thread(() -> {
                try {
                    JSONObject response = WindowsAgentClient.requestSshKeyApproval(
                            EditDeviceActivity.this, target, username, selectedAlias);
                    target.sshUsername = username;
                    if (Strings.isNullOrEmpty(target.sshAddress)) {
                        target.sshAddress = response.optString("agentAddress", "");
                    }
                    if (target.sshPort == null || target.sshPort < 1) target.sshPort = 22;
                    deviceRepository.updateAsync(target);
                    runOnUiThread(() -> {
                        device = target;
                        sshKeyAlias = selectedAlias;
                        updateSshPublicKeyUi();
                        sshConnectStatus.setText(getString(R.string.ssh_key_request_sent));
                        sshConnectButton.setEnabled(true);
                        Toast.makeText(this, "Запрос отправлен: " +
                                response.optString("agentName", "Windows PC"), Toast.LENGTH_LONG).show();
                    });
                } catch (Exception error) {
                    runOnUiThread(() -> {
                        sshConnectStatus.setText(error.getMessage() == null
                                ? "Не удалось отправить запрос SSH-подключения." : error.getMessage());
                        sshConnectButton.setEnabled(true);
                    });
                }
            }, "revision-ssh-key-request").start();
        });
    }

    private void configureAgentConnectionControls() {
        agentConnectStatus.setVisibility(android.view.View.VISIBLE);
        agentConnectStatus.setText("Проверка сохранённого сопряжения Windows Agent…");
        agentConnectButton.setOnClickListener(v -> changeAgentConnection(true));
        agentDisconnectButton.setOnClickListener(v -> changeAgentConnection(false));
        new Thread(() -> {
            boolean paired = WindowsAgentClient.hasPairedDeviceForDevice(EditDeviceActivity.this, device);
            int state = paired ? WindowsAgentClient.probeForDevice(EditDeviceActivity.this, device, device.statusIp) : -1;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (!paired) {
                    agentConnectButton.setVisibility(android.view.View.GONE);
                    agentDisconnectButton.setVisibility(android.view.View.GONE);
                    agentConnectStatus.setText("Нет сохранённого сопряжения с этим компьютером. Первичное сопряжение выполняется отдельно; эти кнопки не сбрасывают и не создают pairing.");
                    return;
                }
                agentConnectButton.setVisibility(android.view.View.VISIBLE);
                agentDisconnectButton.setVisibility(android.view.View.VISIBLE);
                agentConnectButton.setEnabled(state != 1);
                agentDisconnectButton.setEnabled(state == 1 || state == 2 || state == 3);
                agentConnectStatus.setText(state == 1 ? "Windows Agent подключён и проверен сохранённым ключом."
                        : "Сопряжение сохранено. Можно повторно подключиться без pairing code.");
            });
        }, "revision-agent-settings-probe").start();
    }

    private void changeAgentConnection(boolean connect) {
        agentConnectButton.setEnabled(false);
        agentDisconnectButton.setEnabled(false);
        agentConnectStatus.setText(connect ? "Подключение с сохранённым ключом…" : "Отключение RE:Vision от Windows Agent…");
        new Thread(() -> {
            String result;
            try {
                result = WindowsAgentClient.setConnectionEnabledForDevice(this, device, connect);
            } catch (Exception error) {
                result = error.getMessage() == null ? "Не удалось изменить состояние Windows Agent." : error.getMessage();
            }
            final String message = result;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                agentConnectStatus.setText(message);
                agentConnectButton.setEnabled(true);
                agentDisconnectButton.setEnabled(true);
            });
        }, "revision-agent-settings-action").start();
    }

    private void populateInputs() {
        Bundle extras = getIntent().getExtras();
        if (extras != null) {
            device = extras.getParcelable(DEVICE_PARCELABLE_KEY);
            if (device == null) {
                Toast.makeText(this, R.string.edit_machine_error_loading, Toast.LENGTH_SHORT).show();
                finish();
                return;
            }

            deviceNameInput.setText(device.name);
            deviceGroupInput.setText(com.google.common.base.Strings.nullToEmpty(device.groupName));
            deviceStatusIpInput.setRealText(device.statusIp);
            deviceMacInput.setRealText(device.macAddress);
            // Legacy profiles without their own MAC inherit the device's original MAC.
            loadAdditionalNetworkProfiles(device.networkProfilesJson);
            deviceBroadcastInput.setRealText(device.broadcastAddress);
            devicePorts.setText(String.valueOf(device.port));
            deviceWanAddressInput.setRealText(device.wanIp);
            deviceWanPortInput.setText(device.wanPort == null ? "" : String.valueOf(device.wanPort));
            deviceSecureOnPassword.setText(device.secureOnPassword);

            deviceEnableRemoteShutdown.setChecked(device.remoteShutdownEnabled);
            triggerRemoteShutdownLayoutVisibility(device.remoteShutdownEnabled);
            deviceSshAddressInput.setRealText(device.sshAddress);
            deviceSshPortInput.setText(getSshPortFallback());
            deviceSshUsernameInput.setText(device.sshUsername);
            deviceSshCommandInput.setText(device.sshCommand);
            sshKeyAlias = device.sshKeyAlias;
            updateSshAuthUi();
            // set spinner to saved OS
            if (device.shutdownOs != null) {
                if (deviceSshOsSpinner.getAdapter() != null) {
                    int count = deviceSshOsSpinner.getAdapter().getCount();
                    for (int i = 0; i < count; i++) {
                        Object item = deviceSshOsSpinner.getAdapter().getItem(i);
                        if (item != null && item.toString().equalsIgnoreCase(device.shutdownOs)) {
                            deviceSshOsSpinner.setSelection(i);
                            break;
                        }
                    }
                }
            }
        }
    }

    @NonNull
    private String getSshPortFallback() {
        return device.sshPort == null || device.sshPort < 0 ? "" : String.valueOf(device.sshPort);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.edit_device_menu, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == R.id.edit_machine_menu_save) {
            checkAndPersistDevice();
            return true;
        } else if (item.getItemId() == R.id.edit_machine_menu_delete) {
            new MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.edit_device_delete_title)
                    .setMessage(R.string.edit_device_delete_message)
                    .setPositiveButton(android.R.string.yes, (dialog, which) -> {
                        deviceRepository.deleteAsync(device);
                        finish();
                    })
                    .setNegativeButton(android.R.string.no, (dialog, which) -> {
                    })
                    .show();
            return true;
        }

        return super.onOptionsItemSelected(item);
    }

    @Override
    protected boolean inputsHaveNotChanged() {
        return Strings.nullToEmpty(device.name).equals(getDeviceNameInputText()) &&
                Strings.nullToEmpty(device.broadcastAddress).equals(getDeviceBroadcastAddressText()) &&
                Strings.nullToEmpty(device.macAddress).equals(getDeviceMacInputText()) &&
                Strings.nullToEmpty(device.statusIp).equals(getDeviceStatusIpText()) &&
                Strings.nullToEmpty(device.wanIp).equals(getDeviceWanAddressText()) &&
                Objects.equals(device.wanPort, getDeviceWanPort()) &&
                device.port == getPort() &&
                Strings.nullToEmpty(device.secureOnPassword).equals(getDeviceSecureOnPassword()) &&
                device.remoteShutdownEnabled == getDeviceRemoteShutdownEnabled() &&
                Strings.nullToEmpty(device.sshAddress).equals(getDeviceSshAddress()) &&
                Objects.equals(device.sshPort == null ? -1 : device.sshPort, getDeviceSshPort()) &&
                Strings.nullToEmpty(device.sshUsername).equals(getDeviceSshUsername()) &&
                Strings.nullToEmpty(device.sshCommand).equals(getDeviceSshCommand()) &&
                Strings.nullToEmpty(device.sshAuthType == null ? "password" : device.sshAuthType).equals(getDeviceSshAuthType()) &&
                Strings.nullToEmpty(device.sshKeyAlias).equals(Strings.nullToEmpty(getDeviceSshKeyAlias())) &&
                Strings.nullToEmpty(device.groupName).equals(getDeviceGroupInputText()) &&
                Strings.nullToEmpty(device.networkProfilesJson).equals(getAdditionalNetworkProfilesJson());

    }

    @Override
    protected void persistDevice(Device device) {
        deviceRepository.updateAsync(device);
        forgetPinnedHostKey(device);
    }

    private void forgetPinnedHostKey(Device device) {
        if (Strings.isNullOrEmpty(device.sshAddress)) return;

        int sshPort = device.sshPort != null && device.sshPort > 0 ? device.sshPort : 22;
        TofuHostKeyVerifier.forget(this, device.sshAddress, sshPort);
    }

    @Override
    protected Device buildDeviceFromInputs() {
        device.name = getDeviceNameInputText();
        device.statusIp = getDeviceStatusIpText();
        device.networkProfilesJson = getAdditionalNetworkProfilesJson();
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
        device.sshPassword = null;
        device.sshCommand = getDeviceSshCommand();
        device.shutdownOs = getSelectedOs(deviceSshOsSpinner);
        device.sshAuthType = getDeviceSshAuthType();
        device.sshKeyAlias = getDeviceSshKeyAlias();
        String group = getDeviceGroupInputText();
        device.groupName = group.isEmpty() ? null : group;

        return device;
    }

}
