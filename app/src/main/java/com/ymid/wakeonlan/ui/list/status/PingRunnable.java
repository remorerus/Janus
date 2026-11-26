package com.ymid.wakeonlan.ui.list.status;

import android.util.Log;

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

    private boolean skipRunningExecutionResults = false;

    public PingRunnable(Device device, StatusTestItem statusTestItem) {
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
                ping.run();

                if (completed[0] && latency[0] >= 0) {
                    notifyDeviceStatusListeners(new DeviceStatusSnapshot(DeviceStatus.ONLINE, latency[0]));
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
