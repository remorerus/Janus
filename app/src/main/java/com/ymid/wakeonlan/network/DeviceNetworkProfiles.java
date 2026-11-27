package com.ymid.wakeonlan.network;

import com.ymid.wakeonlan.persistence.models.Device;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/** Reads the additional named routes while preserving the legacy primary fields. */
public final class DeviceNetworkProfiles {
    private DeviceNetworkProfiles() { }

    public static List<String> broadcastAddresses(Device device) {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        add(values, device == null ? null : device.broadcastAddress);
        read(device, values, "broadcastAddress");
        return new ArrayList<>(values);
    }

    public static List<String> statusAddresses(Device device) {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        if (device == null) return new ArrayList<>();
        // This app's existing Broadcast/IP field is also used by some setups as the PC's
        // unicast address. Use it for status checks when a dedicated Status IP is omitted.
        add(values, hasText(device.statusIp) ? device.statusIp : device.broadcastAddress);
        if (device.networkProfilesJson != null) {
            try {
                JSONArray profiles = new JSONArray(device.networkProfilesJson);
                for (int i = 0; i < profiles.length(); i++) {
                    JSONObject profile = profiles.optJSONObject(i);
                    if (profile == null) continue;
                    String status = profile.optString("statusIp", "");
                    String broadcast = profile.optString("broadcastAddress", "");
                    add(values, hasText(status) ? status : broadcast);
                }
            } catch (Exception ignored) {
                // Ignore malformed optional profile data; legacy device fields remain usable.
            }
        }
        return new ArrayList<>(values);
    }

    /**
     * Returns the MAC address belonging to the network profile whose status endpoint
     * actually answered. Additional profile MACs are stored in networkProfilesJson.
     */
    public static String macAddressFor(Device device, String activeAddress) {
        if (device == null) return null;
        if (!hasText(activeAddress)) return device.macAddress;

        if (sameAddress(activeAddress, device.statusIp)
                || sameAddress(activeAddress, device.broadcastAddress)) {
            return device.macAddress;
        }

        if (device.networkProfilesJson != null) {
            try {
                JSONArray profiles = new JSONArray(device.networkProfilesJson);
                for (int i = 0; i < profiles.length(); i++) {
                    JSONObject profile = profiles.optJSONObject(i);
                    if (profile == null) continue;
                    String status = profile.optString("statusIp", "");
                    String broadcast = profile.optString("broadcastAddress", "");
                    if (sameAddress(activeAddress, status) || sameAddress(activeAddress, broadcast)) {
                        String mac = profile.optString("macAddress", "");
                        return hasText(mac) ? mac.trim() : device.macAddress;
                    }
                }
            } catch (Exception ignored) {
                // Keep the primary MAC as a safe fallback for malformed optional profile data.
            }
        }
        return device.macAddress;
    }

    private static boolean sameAddress(String first, String second) {
        return hasText(first) && hasText(second) && first.trim().equalsIgnoreCase(second.trim());
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private static void read(Device device, LinkedHashSet<String> values, String key) {
        if (device == null || device.networkProfilesJson == null) return;
        try {
            JSONArray profiles = new JSONArray(device.networkProfilesJson);
            for (int i = 0; i < profiles.length(); i++) {
                JSONObject profile = profiles.optJSONObject(i);
                if (profile != null) add(values, profile.optString(key, null));
            }
        } catch (Exception ignored) {
            // Ignore malformed optional profile data; legacy device fields remain usable.
        }
    }

    private static void add(LinkedHashSet<String> values, String value) {
        if (value == null) return;
        String trimmed = value.trim();
        if (!trimmed.isEmpty()) values.add(trimmed);
    }
}
