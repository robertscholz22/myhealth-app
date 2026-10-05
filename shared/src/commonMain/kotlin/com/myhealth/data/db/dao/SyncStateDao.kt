package com.myhealth.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.myhealth.data.db.entity.SyncStateEntity
import kotlinx.coroutines.flow.Flow

/** DAO for `sync_state` (PLAN §2.2.3); the key is a channel name such as `hc.exercise`. */
@Dao
interface SyncStateDao {

    @Upsert
    suspend fun upsert(entity: SyncStateEntity)

    @Query("SELECT * FROM sync_state WHERE `key` = :key")
    suspend fun getById(key: String): SyncStateEntity?

    @Query("DELETE FROM sync_state WHERE `key` = :key")
    suspend fun deleteById(key: String)

    @Query("SELECT * FROM sync_state ORDER BY `key` ASC")
    fun observeAll(): Flow<List<SyncStateEntity>>

    @Query("SELECT * FROM sync_state WHERE `key` = :key")
    fun observe(key: String): Flow<SyncStateEntity?>

    @Query("UPDATE sync_state SET changesToken = :token WHERE `key` = :key")
    suspend fun updateToken(key: String, token: String?)
}
