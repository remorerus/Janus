package com.ymid.wakeonlan.ui.list.status;

import android.util.Log;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;

import java.net.InetAddress;
import java.util.List;

import com.ymid.wakeonlan.persistence.models.Device;
import com.ymid.wakeonlan.persistence.models.DeviceStatus;
import com.ymid.wakeonlan.network.DeviceNetworkProfiles;
import com.ymid.wakeonlan.ping.Ping;
import com.ymid.wakeonlan.ui.list.status.pool.StatusTestItem;

public class PingRunnable implements Runnable {

    private final Device device;
    private final StatusTestItem statusTestItem;
    private final Context appContext;

    private boolean skipRunningExecutionResults = false;

    public PingRunnable(Device device, StatusTestItem statusTestItem, Context context) {
        this.device = device;
        this.statusTestItem = statusTestItem;
        this.appContext = context == null ? null : context.getApplicationContext();
    }

    public void cancelStatusUpdates() {
        skipRunningExecutionResults = true;
    }

    @Override
    public void run() {
        Network activeNetwork = null;
        LinkProperties activeLinkProperties = null;
        if (appContext != null) {
            try {
                ConnectivityManager manager = (ConnectivityManager)
                        appContext.getSystemService(Context.CONNECTIVITY_SERVICE);
                activeNetwork = manager == null ? null : manager.getActiveNetwork();
                activeLinkProperties = activeNetwork == null ? null : manager.getLinkProperties(activeNetwork);
            } catch (Exception error) {
                Log.w(getClass().getSimpleName(), "Could not read active Android network routes", error);
            }
        }

        List<String> candidates = DeviceNetworkProfiles.statusAddresses(device);
        if (candidates.isEmpty() && device.sshAddress != null && !device.sshAddress.trim().isEmpty()) {
            candidates = java.util.Collections.singletonList(device.sshAddress.trim());
        }
        // Put endpoints on the active Android network's most specific route first.
        candidates = DeviceNetworkProfiles.prioritizeForActiveRoutes(candidates, activeLinkProperties);
        if (candidates.isEmpty()) {
            notifyDeviceStatusListeners(new DeviceStatusSnapshot(DeviceStatus.UNKNOWN, null));
            return;
        }

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
                if (activeNetwork != null) ping.setNetwork(activeNetwork);
                ping.run();

                if (completed[0] && latency[0] >= 0) {
                    String activeMac = DeviceNetworkProfiles.macAddressForStatusAddress(device, host);
                    notifyDeviceStatusListeners(new DeviceStatusSnapshot(
                            DeviceStatus.ONLINE, latency[0], host, activeMac));
                    return;
                }
            } catch (Throwable t) {
                Log.w(getClass().getSimpleName(), "Error while pinging profile address " + host, t);
            }
        }

        notifyDeviceStatusListeners(new DeviceStatusSnapshot(DeviceStatus.OFFLINE, null));
    }

    private void notifyDeviceStatusListeners(DeviceStatusSnapshot statusSnapshot) {
        if (!skipRunningExecutionResults) {
            statusTestItem.forAllListeners(deviceStatusListener -> deviceStatusListener.onStatusSnapshotAvailable(statusSnapshot));
        }
    }

}
