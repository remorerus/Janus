package com.ymid.wakeonlan.persistence.entities;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.ColumnInfo;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/** One independently paired Windows Agent. IP addresses are endpoints, never device identity. */
@Entity(tableName = "WindowsAgentDevices", indices = {@Index(value = {"displayName"}, name = "index_WindowsAgentDevices_display_name")})
public class WindowsAgentDeviceEntity {
    @PrimaryKey
    @NonNull
    public String deviceId = "";

    @NonNull
    public String displayName = "";

    /** JSON array of voice aliases; parsed and validated by the registry layer. */
    @NonNull
    @ColumnInfo(defaultValue = "'[]'")
    public String voiceAliasesJson = "[]";

    @NonNull
    public String baseUrl = "";

    /** Known LAN routes for this stable deviceId; IP changes never create duplicate devices. */
    @NonNull
    @ColumnInfo(defaultValue = "'[]'")
    public String endpointUrlsJson = "[]";

    /** Per-device HMAC secret; protected at rest by the SQLCipher-encrypted Room database. */
    @NonNull
    public String clientSecret = "";

    public long createdAt;
    public long lastSeenAt;
    @ColumnInfo(defaultValue = "0")
    public boolean isDefault;
    @ColumnInfo(defaultValue = "1")
    public boolean enabled = true;
}
