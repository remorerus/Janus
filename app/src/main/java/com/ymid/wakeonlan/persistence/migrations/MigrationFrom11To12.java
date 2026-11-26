package com.ymid.wakeonlan.persistence.migrations;

import androidx.annotation.NonNull;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;

/** Stores additional network profiles without changing existing device identities. */
public final class MigrationFrom11To12 extends Migration {
    public MigrationFrom11To12() { super(11, 12); }

    @Override
    public void migrate(@NonNull SupportSQLiteDatabase database) {
        database.execSQL("ALTER TABLE Devices ADD COLUMN network_profiles_json TEXT NOT NULL DEFAULT '[]'");
    }
}
