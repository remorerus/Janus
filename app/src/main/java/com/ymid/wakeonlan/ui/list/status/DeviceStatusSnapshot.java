package com.ymid.wakeonlan.ui.list.status;

import androidx.annotation.Nullable;

import com.ymid.wakeonlan.persistence.models.DeviceStatus;

public class DeviceStatusSnapshot {

    private final DeviceStatus status;
    @Nullable
    private final Long latencyMs;
    private final long measuredAtMs;
    @Nullable
    private final String activeAddress;

    public DeviceStatusSnapshot(DeviceStatus status, @Nullable Long latencyMs) {
        this(status, latencyMs, null);
    }

    public DeviceStatusSnapshot(DeviceStatus status, @Nullable Long latencyMs, @Nullable String activeAddress) {
        this.status = status;
        this.latencyMs = latencyMs;
        this.activeAddress = activeAddress;
        this.measuredAtMs = System.currentTimeMillis();
    }

    public DeviceStatus getStatus() {
        return status;
    }

    @Nullable
    public Long getLatencyMs() {
        return latencyMs;
    }

    public long getMeasuredAtMs() {
        return measuredAtMs;
    }

    /** The endpoint that actually answered this measurement, not a configured or guessed address. */
    @Nullable
    public String getActiveAddress() {
        return activeAddress;
    }
}
