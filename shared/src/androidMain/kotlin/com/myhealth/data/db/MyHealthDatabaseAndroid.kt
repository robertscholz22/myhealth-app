package com.myhealth.data.db

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import com.myhealth.data.db.migration.Migrations
import com.myhealth.data.repository.TransactionRunner

/**
 * Opens the database (Room `SupportSQLite` mode on the framework SQLite, as before P20.2).
 *
 * [allowDestructiveMigration] mirrors the user setting of the same name and only has any effect
 * when [debug] is true (§2.2) — in a release build the flag is ignored and a missing migration
 * throws, which is the intended behaviour.
 */
fun buildMyHealthDatabase(
    context: Context,
    debug: Boolean,
    allowDestructiveMigration: Boolean = false,
): MyHealthDatabase =
    Room.databaseBuilder(context.applicationContext, MyHealthDatabase::class.java, MyHealthDatabase.NAME)
        .addMigrations(*Migrations.ALL)
        .apply {
            if (debug && allowDestructiveMigration) {
                fallbackToDestructiveMigration(true)
            }
        }
        .build()

/** [TransactionRunner] over Room's `withTransaction`. */
class RoomTransactionRunner(private val db: MyHealthDatabase) : TransactionRunner {
    override suspend fun <T> invoke(block: suspend () -> T): T = db.withTransaction(block)
}
