package com.ymid.wakeonlan.ui.list.status;

import android.content.Context;

import com.ymid.wakeonlan.persistence.models.Device;
import com.ymid.wakeonlan.ui.list.status.pool.StatusTestItem;

public class PingDeviceStatusTesterBuilder implements DeviceStatusTesterBuilder {

    private volatile Context appContext;

    public PingDeviceStatusTesterBuilder(Context context) {
        setContext(context);
    }

    public void setContext(Context context) {
        appContext = context == null ? null : context.getApplicationContext();
    }

    public Runnable buildStatusTestCallable(Device device, StatusTestItem statusTestItem) {
        return new PingRunnable(device, statusTestItem, appContext);
    }

}
