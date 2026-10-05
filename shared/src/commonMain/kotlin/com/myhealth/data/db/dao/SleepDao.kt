package com.myhealth.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.myhealth.data.db.entity.SleepSessionEntity
import com.myhealth.domain.model.ActivitySource
import kotlinx.coroutines.flow.Flow

/**
 * DAO for `sleep_session` (PLAN §2.2.3). `night` is unique: the mapper merges two sessions that
 * land on the same night before insert, so the upsert here is effectively "upsert by night".
 */
@Dao
interface SleepDao {

    @Upsert
    suspend fun upsert(entity: SleepSessionEntity): Long

    @Upsert
    suspend fun upsertAll(entities: List<SleepSessionEntity>)

    @Query("SELECT * FROM sleep_session WHERE id = :id")
    suspend fun getById(id: Long): SleepSessionEntity?

    @Query("DELETE FROM sleep_session WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT * FROM sleep_session WHERE night = :night")
    suspend fun getByNight(night: Long): SleepSessionEntity?

    @Query("SELECT * FROM sleep_session WHERE source = :source AND externalId = :externalId")
    suspend fun getBySourceExternalId(source: ActivitySource, externalId: String): SleepSessionEntity?

    @Query("SELECT * FROM sleep_session WHERE night BETWEEN :fromNight AND :toNight ORDER BY night ASC")
    fun observeRange(fromNight: Long, toNight: Long): Flow<List<SleepSessionEntity>>

    @Query("SELECT * FROM sleep_session ORDER BY night DESC LIMIT 1")
    fun observeLatest(): Flow<SleepSessionEntity?>
}
