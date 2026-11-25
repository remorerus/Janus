package com.ymid.wakeonlan.persistence;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Update;

import com.ymid.wakeonlan.persistence.entities.WindowsAgentDeviceEntity;
import java.util.List;

@Dao
public interface WindowsAgentDeviceDao {
    @Query("SELECT * FROM WindowsAgentDevices ORDER BY isDefault DESC, displayName COLLATE NOCASE ASC")
    List<WindowsAgentDeviceEntity> getAll();

    @Query("SELECT * FROM WindowsAgentDevices WHERE deviceId = :deviceId LIMIT 1")
    WindowsAgentDeviceEntity getByDeviceId(String deviceId);

    @Query("SELECT * FROM WindowsAgentDevices WHERE isDefault = 1 AND enabled = 1 LIMIT 1")
    WindowsAgentDeviceEntity getDefault();

    @Query("SELECT * FROM WindowsAgentDevices WHERE enabled = 1")
    List<WindowsAgentDeviceEntity> getEnabled();

    @Insert(onConflict = OnConflictStrategy.ABORT)
    void insert(WindowsAgentDeviceEntity device);

    @Update
    void update(WindowsAgentDeviceEntity device);

    @Query("UPDATE WindowsAgentDevices SET isDefault = 0")
    void clearDefault();

    @Query("UPDATE WindowsAgentDevices SET isDefault = 1 WHERE deviceId = :deviceId")
    void setDefault(String deviceId);

    @Query("DELETE FROM WindowsAgentDevices WHERE deviceId = :deviceId")
    void deleteByDeviceId(String deviceId);
}
