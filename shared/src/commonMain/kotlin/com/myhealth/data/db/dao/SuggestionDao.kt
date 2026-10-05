package com.myhealth.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.myhealth.data.db.entity.SuggestedSessionEntity
import com.myhealth.data.db.entity.SuggestionBatchEntity
import com.myhealth.domain.model.SuggestionStatus
import kotlinx.coroutines.flow.Flow

/** DAO for `suggestion_batch` and `suggested_session` (PLAN §2.2.4). */
@Dao
interface SuggestionDao {

    @Upsert
    suspend fun upsert(entity: SuggestionBatchEntity): Long

    @Query("SELECT * FROM suggestion_batch WHERE id = :id")
    suspend fun getById(id: Long): SuggestionBatchEntity?

    @Query("DELETE FROM suggestion_batch WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT * FROM suggestion_batch ORDER BY generatedAtMillis DESC, id DESC LIMIT 1")
    fun observeLatestBatch(): Flow<SuggestionBatchEntity?>

    @Query("SELECT * FROM suggestion_batch WHERE inputsHash = :inputsHash ORDER BY generatedAtMillis DESC, id DESC LIMIT 1")
    suspend fun getByInputsHash(inputsHash: String): SuggestionBatchEntity?

    @Query("UPDATE suggestion_batch SET status = :status WHERE id = :id")
    suspend fun updateBatchStatus(id: Long, status: SuggestionStatus)

    @Query("SELECT * FROM suggestion_batch ORDER BY generatedAtMillis DESC, id DESC LIMIT 1")
    suspend fun getLatestBatch(): SuggestionBatchEntity?

    /** P6.5: a new batch supersedes whatever was still on the review screen. */
    @Query("UPDATE suggestion_batch SET status = 'SUPERSEDED' WHERE status = 'PROPOSED'")
    suspend fun supersedeProposedBatches()

    // ---- suggested sessions -----------------------------------------------------------------

    @Upsert
    suspend fun upsertSessions(entities: List<SuggestedSessionEntity>)

    @Query("SELECT * FROM suggested_session WHERE batchId = :batchId ORDER BY day ASC, score DESC")
    fun observeSessionsForBatch(batchId: Long): Flow<List<SuggestedSessionEntity>>

    @Query("SELECT * FROM suggested_session WHERE id = :id")
    suspend fun getSessionById(id: Long): SuggestedSessionEntity?

    @Query("SELECT * FROM suggested_session WHERE batchId = :batchId ORDER BY day ASC, score DESC")
    suspend fun getSessionsForBatch(batchId: Long): List<SuggestedSessionEntity>

    @Query("SELECT * FROM suggested_session WHERE id IN (:ids) ORDER BY day ASC")
    suspend fun getSessionsByIds(ids: List<Long>): List<SuggestedSessionEntity>

    @Query("UPDATE suggested_session SET status = :status WHERE id = :id")
    suspend fun updateSessionStatus(id: Long, status: SuggestionStatus)

    @Query("UPDATE suggested_session SET status = :status WHERE id IN (:ids)")
    suspend fun updateSessionStatuses(ids: List<Long>, status: SuggestionStatus)

    @Query("DELETE FROM suggested_session WHERE batchId = :batchId")
    suspend fun deleteSessionsForBatch(batchId: Long)
}
