package com.myhealth.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.myhealth.data.db.entity.CycleEntryEntity
import kotlinx.coroutines.flow.Flow

/**
 * DAO for `cycle_entry` (PLAN §5 P11.1). Everything is ordered by `periodStartDay` so the engine
 * never has to sort, and `getByStartDay` exists because the unique index means "log this start
 * again" is an edit, not an insert.
 */
@Dao
interface CycleDao {

    @Upsert
    suspend fun upsert(entity: CycleEntryEntity): Long

    @Query("SELECT * FROM cycle_entry ORDER BY periodStartDay ASC")
    fun observeAll(): Flow<List<CycleEntryEntity>>

    @Query("SELECT * FROM cycle_entry ORDER BY periodStartDay ASC")
    suspend fun getAll(): List<CycleEntryEntity>

    @Query("SELECT * FROM cycle_entry WHERE id = :id")
    suspend fun getById(id: Long): CycleEntryEntity?

    @Query("SELECT * FROM cycle_entry WHERE periodStartDay = :periodStartDay")
    suspend fun getByStartDay(periodStartDay: Long): CycleEntryEntity?

    @Query("SELECT * FROM cycle_entry ORDER BY periodStartDay DESC LIMIT 1")
    suspend fun getLatest(): CycleEntryEntity?

    @Query("DELETE FROM cycle_entry WHERE id = :id")
    suspend fun deleteById(id: Long)
}
