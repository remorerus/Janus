package com.ymid.wakeonlan.persistence;

import android.content.Context;

import com.ymid.wakeonlan.persistence.entities.WindowsAgentDeviceEntity;
import java.util.List;

/** Persistence facade for independently paired Windows Agent devices. */
public final class WindowsAgentDeviceRegistry {
    private final WindowsAgentDeviceDao dao;
    private final Context context;

    public WindowsAgentDeviceRegistry(Context context) {
        this.context = context.getApplicationContext();
        dao = DatabaseInstanceManager.getInstance(this.context).windowsAgentDeviceDao();
    }

    public List<WindowsAgentDeviceEntity> getAll() { return dao.getAll(); }
    public WindowsAgentDeviceEntity get(String deviceId) { return dao.getByDeviceId(deviceId); }
    public WindowsAgentDeviceEntity getDefault() { return dao.getDefault(); }

    public synchronized void addPairedDevice(WindowsAgentDeviceEntity device, boolean makeDefault) {
        if (device == null || device.deviceId == null || device.deviceId.trim().isEmpty())
            throw new IllegalArgumentException("У устройства должен быть постоянный ID.");
        if (device.baseUrl == null || device.baseUrl.trim().isEmpty()
                || device.clientSecret == null || device.clientSecret.trim().isEmpty())
            throw new IllegalArgumentException("Для устройства не сохранены адрес и ключ сопряжения.");
        if (makeDefault || dao.getDefault() == null) {
            dao.clearDefault();
            device.isDefault = true;
        }
        dao.insert(device);
        com.ymid.wakeonlan.voice.WindowsAgentClient.syncDeviceRegistryToAgents(context);
    }

    public synchronized void update(WindowsAgentDeviceEntity device) {
        if (device == null || device.deviceId == null || device.deviceId.trim().isEmpty())
            throw new IllegalArgumentException("Не указан ID устройства.");
        dao.update(device);
    }

    public synchronized void setDefault(String deviceId) {
        if (dao.getByDeviceId(deviceId) == null)
            throw new IllegalArgumentException("Устройство не найдено.");
        dao.clearDefault();
        dao.setDefault(deviceId);
        com.ymid.wakeonlan.voice.WindowsAgentClient.syncDeviceRegistryToAgents(context);
    }

    public void remove(String deviceId) {
        dao.deleteByDeviceId(deviceId);
        com.ymid.wakeonlan.voice.WindowsAgentClient.syncDeviceRegistryToAgents(context);
    }
}
