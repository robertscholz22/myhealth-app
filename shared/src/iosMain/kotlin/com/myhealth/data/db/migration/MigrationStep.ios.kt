package com.myhealth.data.db.migration

import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

actual fun MigrationStep.toRoomMigration(): Migration = object : Migration(from, to) {
    override fun migrate(connection: SQLiteConnection) {
        statements.forEach { connection.execSQL(it) }
    }
}
