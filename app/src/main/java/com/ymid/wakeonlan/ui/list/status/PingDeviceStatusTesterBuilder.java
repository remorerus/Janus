package com.ymid.wakeonlan.ui.list.status;

import android.content.Context;

import com.ymid.wakeonlan.persistence.models.Device;
import com.ymid.wakeonlan.ui.list.status.pool.StatusTestItem;

public class PingDeviceStatusTesterBuilder implements DeviceStatusTesterBuilder {

    private final Context context;

    public PingDeviceStatusTesterBuilder() {
        this(null);
    }

    public PingDeviceStatusTesterBuilder(Context context) {
        this.context = context == null ? null : context.getApplicationContext();
    }

    public Runnable buildStatusTestCallable(Device device, StatusTestItem statusTestItem) {
        return new PingRunnable(context, device, statusTestItem);
    }

}
