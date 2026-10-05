package com.myhealth.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.myhealth.data.db.entity.PlannedSessionEntity
import com.myhealth.data.db.entity.TrainingPlanEntity
import com.myhealth.domain.model.PlannedStatus
import kotlinx.coroutines.flow.Flow

/** DAO for `training_plan` and `planned_session` (PLAN §2.2.4). */
@Dao
interface PlanDao {

    @Upsert
    suspend fun upsert(entity: TrainingPlanEntity): Long

    @Query("SELECT * FROM training_plan WHERE id = :id")
    suspend fun getById(id: Long): TrainingPlanEntity?

    @Query("DELETE FROM training_plan WHERE id = :id")
    suspend fun deleteById(id: Long)

    /** At most one `ACTIVE` plan exists (repository invariant); this returns it. */
    @Query("SELECT * FROM training_plan WHERE status = 'ACTIVE' ORDER BY startDay DESC LIMIT 1")
    fun observeActivePlan(): Flow<TrainingPlanEntity?>

    @Query("SELECT * FROM training_plan ORDER BY startDay DESC")
    fun observeAllPlans(): Flow<List<TrainingPlanEntity>>

    /** Enforces "at most one ACTIVE plan" (§2.2.4): activating [keepPlanId] archives the rest. */
    @Query(
        "UPDATE training_plan SET status = 'ARCHIVED', updatedAtMillis = :updatedAtMillis " +
            "WHERE status = 'ACTIVE' AND id != :keepPlanId",
    )
    suspend fun archiveOtherActivePlans(keepPlanId: Long, updatedAtMillis: Long)

    // ---- planned sessions ------------------------------------------------------------------

    @Upsert
    suspend fun upsertSession(entity: PlannedSessionEntity): Long

    @Upsert
    suspend fun upsertSessions(entities: List<PlannedSessionEntity>)

    @Query("SELECT * FROM planned_session WHERE id = :id")
    suspend fun getSessionById(id: Long): PlannedSessionEntity?

    @Query("DELETE FROM planned_session WHERE id = :id")
    suspend fun deleteSessionById(id: Long)

    @Query("SELECT * FROM planned_session WHERE day = :day ORDER BY startMinuteOfDay ASC")
    suspend fun getSessionsByDay(day: Long): List<PlannedSessionEntity>

    @Query(
        "SELECT * FROM planned_session WHERE day BETWEEN :fromDay AND :toDay " +
            "ORDER BY day ASC, startMinuteOfDay ASC",
    )
    suspend fun getSessionRange(fromDay: Long, toDay: Long): List<PlannedSessionEntity>

    @Query(
        "SELECT * FROM planned_session WHERE day BETWEEN :fromDay AND :toDay " +
            "ORDER BY day ASC, startMinuteOfDay ASC",
    )
    fun observeSessionRange(fromDay: Long, toDay: Long): Flow<List<PlannedSessionEntity>>

    @Query("SELECT * FROM planned_session WHERE planId = :planId ORDER BY day ASC")
    fun observeSessionsForPlan(planId: Long): Flow<List<PlannedSessionEntity>>

    /** Unlocked, still-`PLANNED` sessions in the horizon — the rows the suggester may replace. */
    @Query(
        "SELECT * FROM planned_session WHERE day BETWEEN :fromDay AND :toDay " +
            "AND locked = 0 AND status = 'PLANNED' AND sourceSuggestionId IS NOT NULL ORDER BY day ASC",
    )
    /**
     * The sessions a newly accepted proposal may replace (BUG-10): unlocked, still PLANNED, and
     * — BUG-15 — created by an earlier *suggestion*. A session the user planned by hand
     * (`sourceSuggestionId IS NULL`) is never replaced.
     */
    suspend fun getReplaceableSessions(fromDay: Long, toDay: Long): List<PlannedSessionEntity>

    @Query("UPDATE planned_session SET status = :status, updatedAtMillis = :updatedAtMillis WHERE id = :id")
    suspend fun updateSessionStatus(id: Long, status: PlannedStatus, updatedAtMillis: Long)
}
