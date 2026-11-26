package com.ymid.wakeonlan.ui.scan;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import com.ymid.wakeonlan.ui.scan.model.NetworkScanDevice;

/**
 * In-memory snapshot of the latest RE:Vision LAN scan.
 * Voice routing can resolve the spoken display name to the discovered host
 * without asking the user to type an IP address or add the host manually.
 */
public final class NetworkScanCache {
    private static final ConcurrentHashMap<String, NetworkScanDevice> DEVICES = new ConcurrentHashMap<>();

    private NetworkScanCache() {}

    public static void clear() {
        DEVICES.clear();
    }

    public static void put(String ipAddress, String hostName) {
        if (ipAddress == null || ipAddress.trim().isEmpty()) return;
        NetworkScanDevice device = new NetworkScanDevice();
        device.setIpAddress(ipAddress.trim());
        if (hostName != null && !hostName.trim().isEmpty()
                && !ipAddress.trim().equalsIgnoreCase(hostName.trim())) {
            device.setName(hostName.trim());
        }
        DEVICES.put(ipAddress.trim(), device);
    }

    public static List<NetworkScanDevice> snapshot() {
        return new ArrayList<>(DEVICES.values());
    }
}
