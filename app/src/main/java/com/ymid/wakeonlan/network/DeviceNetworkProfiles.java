package com.ymid.wakeonlan.network;

import com.ymid.wakeonlan.persistence.models.Device;
import android.net.LinkProperties;
import android.net.RouteInfo;
import org.json.JSONArray;
import org.json.JSONObject;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Comparator;
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
     * Orders candidate endpoints by the most specific route on Android's active network.
     * A host on the connected Wi-Fi subnet outranks a host reachable only through a default
     * route, so an old responding address on another interface is not selected first.
     */
    public static List<String> prioritizeForActiveRoutes(List<String> candidates, LinkProperties properties) {
        if (candidates == null || candidates.size() < 2 || properties == null) {
            return candidates == null ? new ArrayList<>() : new ArrayList<>(candidates);
        }
        final class ScoredAddress {
            final String host;
            final int score;
            final int originalIndex;
            ScoredAddress(String host, int score, int originalIndex) {
                this.host = host;
                this.score = score;
                this.originalIndex = originalIndex;
            }
        }
        List<ScoredAddress> scored = new ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) {
            String host = candidates.get(i);
            int bestPrefix = -1;
            try {
                InetAddress address = InetAddress.getByName(host);
                for (RouteInfo route : properties.getRoutes()) {
                    if (route != null && route.getDestination() != null
                            && route.getDestination().contains(address)) {
                        bestPrefix = Math.max(bestPrefix, route.getDestination().getPrefixLength());
                    }
                }
            } catch (Exception ignored) { }
            scored.add(new ScoredAddress(host, bestPrefix, i));
        }
        scored.sort(Comparator.comparingInt((ScoredAddress item) -> item.score).reversed()
                .thenComparingInt(item -> item.originalIndex));
        List<String> ordered = new ArrayList<>(scored.size());
        for (ScoredAddress item : scored) ordered.add(item.host);
        return ordered;
    }

    /**
     * Returns the MAC belonging to the profile whose configured status endpoint answered.
     * A default MAC is returned only for the primary profile; an unmatched address deliberately
     * returns null rather than displaying a MAC from a different network adapter.
     */
    public static String macAddressForStatusAddress(Device device, String address) {
        if (device == null || !hasText(address)) return null;
        String wanted = address.trim();
        String primaryStatus = hasText(device.statusIp) ? device.statusIp : device.broadcastAddress;
        if (sameAddress(primaryStatus, wanted)) return device.macAddress;

        if (device.networkProfilesJson == null) return null;
        try {
            JSONArray profiles = new JSONArray(device.networkProfilesJson);
            for (int i = 0; i < profiles.length(); i++) {
                JSONObject profile = profiles.optJSONObject(i);
                if (profile == null) continue;
                String status = profile.optString("statusIp", "");
                String broadcast = profile.optString("broadcastAddress", "");
                if (sameAddress(status, wanted) || sameAddress(broadcast, wanted)) {
                    String mac = profile.optString("macAddress", "").trim();
                    return hasText(mac) ? mac : null;
                }
            }
        } catch (Exception ignored) { }
        return null;
    }

    private static boolean sameAddress(String left, String right) {
        return hasText(left) && hasText(right) && left.trim().equalsIgnoreCase(right.trim());
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
