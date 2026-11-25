package com.ymid.wakeonlan.persistence.migrations;

import androidx.annotation.NonNull;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;

/** Adds a multi-device Windows Agent registry without altering legacy device rows or settings. */
public class MigrationFrom9To10 extends Migration {
    public MigrationFrom9To10() { super(9, 10); }

    @Override
    public void migrate(@NonNull SupportSQLiteDatabase database) {
        database.execSQL("CREATE TABLE IF NOT EXISTS WindowsAgentDevices (" +
                "deviceId TEXT NOT NULL, " +
                "displayName TEXT NOT NULL, " +
                "voiceAliasesJson TEXT NOT NULL DEFAULT '[]', " +
                "baseUrl TEXT NOT NULL, " +
                "clientSecret TEXT NOT NULL, " +
                "createdAt INTEGER NOT NULL, " +
                "lastSeenAt INTEGER NOT NULL, " +
                "isDefault INTEGER NOT NULL DEFAULT 0, " +
                "enabled INTEGER NOT NULL DEFAULT 1, " +
                "PRIMARY KEY(deviceId))");
        database.execSQL("CREATE INDEX IF NOT EXISTS index_WindowsAgentDevices_display_name " +
                "ON WindowsAgentDevices(displayName)");
    }
}
