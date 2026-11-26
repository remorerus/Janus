package com.ymid.wakeonlan.persistence;

import androidx.room.Database;
import androidx.room.RoomDatabase;

import com.ymid.wakeonlan.persistence.entities.ActionLogEntity;
import com.ymid.wakeonlan.persistence.entities.DeviceEntity;
import com.ymid.wakeonlan.persistence.entities.WindowsAgentDeviceEntity;

@Database(entities = {DeviceEntity.class, ActionLogEntity.class, WindowsAgentDeviceEntity.class}, version = 11, exportSchema = false)
public abstract class AppDatabase extends RoomDatabase {
    public abstract DeviceDao deviceDao();
    public abstract ActionLogDao actionLogDao();
    public abstract WindowsAgentDeviceDao windowsAgentDeviceDao();
}
