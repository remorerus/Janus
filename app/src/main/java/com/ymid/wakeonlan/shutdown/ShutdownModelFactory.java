package com.ymid.wakeonlan.shutdown;

import androidx.annotation.Nullable;

import com.google.common.base.Strings;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;

import com.ymid.wakeonlan.persistence.models.Device;
import com.ymid.wakeonlan.network.DeviceNetworkProfiles;
import com.ymid.wakeonlan.ui.list.status.DeviceStatusBoard;

public class ShutdownModelFactory {

    private static final int DEFAULT_SSH_PORT = 22;

    public static Optional<ShutdownModel> fromDevice(Device device) {
        boolean shutdownEnabled = device.remoteShutdownEnabled;
        LinkedHashSet<String> addresses = new LinkedHashSet<>();

        // Prefer the endpoint that most recently answered on the active Android route.
        String activeAddress = DeviceStatusBoard.getInstance().getLastActiveAddress(device.id);
        addAddress(addresses, activeAddress);
        addAddress(addresses, device.sshAddress);
        addresses.addAll(DeviceNetworkProfiles.statusAddresses(device));

        List<String> candidates = new ArrayList<>(addresses);
        String username = getValueOrFallback(device.sshUsername, null);
        String command = getValueOrFallback(device.sshCommand, null);
        String authType = "key";
        String keyAlias = device.sshKeyAlias;

        if (allRequiredFieldsSet(shutdownEnabled, candidates, username, command, keyAlias)) {
            return Optional.of(new ShutdownModel(candidates, getSshPortOrFallback(device.sshPort),
                    username, null, command, authType, keyAlias));
        }

        return Optional.empty();
    }

    private static void addAddress(LinkedHashSet<String> addresses, String value) {
        if (!Strings.isNullOrEmpty(value)) addresses.add(value.trim());
    }

    private static boolean allRequiredFieldsSet(boolean shutdownEnabled, List<String> addresses, String username,
                                                 String command, String keyAlias) {
        return shutdownEnabled && addresses != null && !addresses.isEmpty()
                && username != null && command != null && keyAlias != null && !keyAlias.isEmpty();
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
