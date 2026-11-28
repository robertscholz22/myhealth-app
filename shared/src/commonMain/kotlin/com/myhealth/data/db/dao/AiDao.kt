package com.myhealth.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import com.myhealth.data.db.entity.AiChangeEntity
import com.myhealth.data.db.entity.AiConfigEntity
import com.myhealth.data.db.entity.AiLinkEntity
import kotlinx.coroutines.flow.Flow

/** P28: the AI relay connection, the rows the AI created, and the AI changes log. */
@Dao
interface AiDao {

    @Query("SELECT * FROM ai_config WHERE id = 1")
    fun observeConfig(): Flow<AiConfigEntity?>

    @Query("SELECT * FROM ai_config WHERE id = 1")
    suspend fun getConfig(): AiConfigEntity?

    @Upsert
    suspend fun upsertConfig(row: AiConfigEntity)

    @Query("DELETE FROM ai_config")
    suspend fun deleteConfig()

    @Query("SELECT * FROM ai_link")
    suspend fun getLinks(): List<AiLinkEntity>

    @Query("SELECT * FROM ai_link")
    fun observeLinks(): Flow<List<AiLinkEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLink(row: AiLinkEntity)

    @Query("DELETE FROM ai_link WHERE kind = :kind AND localKey = :localKey")
    suspend fun deleteLink(kind: String, localKey: String)

    @Query("SELECT * FROM ai_change ORDER BY id DESC LIMIT :limit")
    fun observeChanges(limit: Int): Flow<List<AiChangeEntity>>

    @Query("SELECT * FROM ai_change WHERE batchId = :batchId ORDER BY id")
    suspend fun getBatch(batchId: String): List<AiChangeEntity>

    @Query("SELECT * FROM ai_change WHERE opId = :opId")
    suspend fun getChange(opId: String): AiChangeEntity?

    /** Ignores an op already logged (same [AiChangeEntity.opId]); returns -1 then. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertChange(row: AiChangeEntity): Long

    @Query("UPDATE ai_change SET result = :result WHERE id = :id")
    suspend fun setResult(id: Long, result: String)

    @Query("DELETE FROM ai_change")
    suspend fun deleteChanges()

    @Query("DELETE FROM ai_link")
    suspend fun deleteLinks()
}
