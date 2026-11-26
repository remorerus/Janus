package com.ymid.wakeonlan.persistence.migrations;

import androidx.annotation.NonNull;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;

/** Adds alternate LAN routes without changing existing pairings or device records. */
public final class MigrationFrom10To11 extends Migration {
    public MigrationFrom10To11() { super(10, 11); }
    @Override public void migrate(@NonNull SupportSQLiteDatabase database) {
        database.execSQL("ALTER TABLE WindowsAgentDevices ADD COLUMN endpointUrlsJson TEXT NOT NULL DEFAULT '[]'");
    }
}
