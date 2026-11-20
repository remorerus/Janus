package com.ymid.wakeonlan.persistence.migrations;

import androidx.annotation.NonNull;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;

/** Renames saved devices to stable numbered labels while preserving all connection settings. */
public class MigrationFrom8To9 extends Migration {
    public MigrationFrom8To9() {
        super(8, 9);
    }

    @Override
    public void migrate(@NonNull SupportSQLiteDatabase database) {
        database.execSQL(
                "UPDATE Devices SET name = 'Устройство ' || " +
                "(SELECT COUNT(*) FROM Devices AS previous WHERE previous.id <= Devices.id)"
        );
    }
}
