package com.ymid.wakeonlan.shutdown;

import androidx.annotation.Nullable;

import com.google.common.base.Strings;

import java.util.Optional;

import com.ymid.wakeonlan.persistence.models.Device;
import com.ymid.wakeonlan.network.DeviceNetworkProfiles;

public class ShutdownModelFactory {

    private static final int DEFAULT_SSH_PORT = 22;

    public static Optional<ShutdownModel> fromDevice(Device device) {
        boolean shutdownEnabled = device.remoteShutdownEnabled;
        String profileFallback = DeviceNetworkProfiles.statusAddresses(device).stream().findFirst().orElse(null);
        String address = getValueOrFallback(device.sshAddress, profileFallback);
        int port = getSshPortOrFallback(device.sshPort);
        String username = getValueOrFallback(device.sshUsername, null);
        String command = getValueOrFallback(device.sshCommand, null);
        String authType = "key";
        String keyAlias = device.sshKeyAlias;


        if (allRequiredFieldsSet(shutdownEnabled, address, username, command, keyAlias)) {
            return Optional.of(new ShutdownModel(address, port, username, null, command, authType, keyAlias));
        }

        return Optional.empty();
    }

    private static boolean allRequiredFieldsSet(boolean shutdownEnabled, String address, String username,
                                                 String command, String keyAlias) {
        return shutdownEnabled && address != null && username != null && command != null
                && keyAlias != null && !keyAlias.isEmpty();
    }

    @Nullable
    private static String getValueOrFallback(@Nullable String value, @Nullable String fallback) {
        if (!Strings.isNullOrEmpty(value)) return value;
        if (!Strings.isNullOrEmpty(fallback)) return fallback;
        return null;
    }

    private static Integer getSshPortOrFallback(@Nullable Integer value) {
        if (value != null && value > 0) return value;
        return DEFAULT_SSH_PORT;
    }
}
