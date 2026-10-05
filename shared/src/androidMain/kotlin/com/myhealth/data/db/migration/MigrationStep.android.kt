package com.myhealth.data.db.migration

import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.execSQL

/** The Android database runs in Room's `SupportSQLite` mode (framework SQLite, no driver). */
actual fun MigrationStep.toRoomMigration(): Migration = object : Migration(from, to) {
    override fun migrate(db: SupportSQLiteDatabase) {
        statements.forEach(db::execSQL)
    }

    override fun migrate(connection: SQLiteConnection) {
        statements.forEach { connection.execSQL(it) }
    }
}
