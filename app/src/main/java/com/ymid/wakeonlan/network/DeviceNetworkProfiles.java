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
        add(values, device == null ? null : device.statusIp);
        read(device, values, "statusIp");
        if (values.isEmpty() && device != null) add(values, device.sshAddress);
        return new ArrayList<>(values);
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
