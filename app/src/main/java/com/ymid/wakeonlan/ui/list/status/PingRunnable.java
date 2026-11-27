package com.ymid.wakeonlan.ui.list.status;

import android.util.Log;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.RouteInfo;

import java.net.InetAddress;
import java.util.List;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

import com.ymid.wakeonlan.persistence.models.Device;
import com.ymid.wakeonlan.persistence.models.DeviceStatus;
import com.ymid.wakeonlan.network.DeviceNetworkProfiles;
import com.ymid.wakeonlan.ping.Ping;
import com.ymid.wakeonlan.ui.list.status.pool.StatusTestItem;

public class PingRunnable implements Runnable {

    private final Context context;
    private final Device device;
    private final StatusTestItem statusTestItem;

    private boolean skipRunningExecutionResults = false;

    public PingRunnable(Device device, StatusTestItem statusTestItem) {
        this(null, device, statusTestItem);
    }

    public PingRunnable(Context context, Device device, StatusTestItem statusTestItem) {
        this.context = context == null ? null : context.getApplicationContext();
        this.device = device;
        this.statusTestItem = statusTestItem;
    }

    public void cancelStatusUpdates() {
        skipRunningExecutionResults = true;
    }

    @Override
    public void run() {
        List<String> candidates = DeviceNetworkProfiles.statusAddresses(device);
        if (candidates.isEmpty() && device.sshAddress != null && !device.sshAddress.trim().isEmpty()) {
            candidates = java.util.Collections.singletonList(device.sshAddress.trim());
        }
        if (candidates.isEmpty()) {
            notifyDeviceStatusListeners(new DeviceStatusSnapshot(DeviceStatus.UNKNOWN, null));
            return;
        }

        Network activeNetwork = getActiveNetwork();
        candidates = orderForActiveNetwork(candidates, activeNetwork);

        for (String host : candidates) {
            if (skipRunningExecutionResults) return;
            try {
                final long[] latency = {-1L};
                final boolean[] completed = {false};
                final InetAddress dest = InetAddress.getByName(host);
                final Ping ping = new Ping(dest, new Ping.PingListener() {
                    @Override
                    public void onPing(final long timeMs) {
                        completed[0] = true;
                        latency[0] = timeMs;
                    }

                    @Override
                    public void onPingException(final Exception e) {
                        completed[0] = true;
                        latency[0] = -1L;
                        Log.w(getClass().getSimpleName(), "Ping failed for profile address " + host, e);
                    }
                });
                ping.setTimeoutMs(1000);
                // Pin every ICMP probe to Android's currently active network. Without this,
                // stale addresses from another saved Wi-Fi profile can win via a different route.
                if (activeNetwork != null) ping.setNetwork(activeNetwork);
                ping.run();

                if (completed[0] && latency[0] >= 0) {
                    notifyDeviceStatusListeners(new DeviceStatusSnapshot(DeviceStatus.ONLINE, latency[0], host));
                    return;
                }
            } catch (Throwable t) {
                Log.w(getClass().getSimpleName(), "Error while pinging profile address " + host, t);
            }
        }

        notifyDeviceStatusListeners(new DeviceStatusSnapshot(DeviceStatus.OFFLINE, null));
    }

    private Network getActiveNetwork() {
        if (context == null) return null;
        try {
            ConnectivityManager manager = (ConnectivityManager)
                    context.getSystemService(Context.CONNECTIVITY_SERVICE);
            return manager == null ? null : manager.getActiveNetwork();
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * Prefer endpoints reachable through the most-specific route of the active Android network.
     * A directly connected Wi-Fi subnet therefore wins over a stale profile reachable only
     * through the default gateway. Sorting is stable when route specificity is equal.
     */
    private List<String> orderForActiveNetwork(List<String> candidates, Network activeNetwork) {
        if (context == null || activeNetwork == null || candidates.size() < 2) return candidates;
        try {
            ConnectivityManager manager = (ConnectivityManager)
                    context.getSystemService(Context.CONNECTIVITY_SERVICE);
            LinkProperties properties = manager == null ? null : manager.getLinkProperties(activeNetwork);
            if (properties == null || properties.getRoutes().isEmpty()) return candidates;

            Map<String, Integer> specificity = new HashMap<>();
            for (String host : candidates) {
                int bestPrefix = -1;
                try {
                    InetAddress address = InetAddress.getByName(host);
                    for (RouteInfo route : properties.getRoutes()) {
                        if (route.matches(address)) {
                            bestPrefix = Math.max(bestPrefix,
                                    route.getDestination().getPrefixLength());
                        }
                    }
                } catch (Exception ignored) { }
                specificity.put(host, bestPrefix);
            }

            List<String> ordered = new ArrayList<>(candidates);
            ordered.sort((left, right) -> Integer.compare(
                    specificity.getOrDefault(right, -1),
                    specificity.getOrDefault(left, -1)));
            return ordered;
        } catch (Exception ignored) {
            return candidates;
        }
    }

    private void notifyDeviceStatusListeners(DeviceStatusSnapshot statusSnapshot) {
        if (!skipRunningExecutionResults) {
            statusTestItem.forAllListeners(deviceStatusListener -> deviceStatusListener.onStatusSnapshotAvailable(statusSnapshot));
        }
    }

}
