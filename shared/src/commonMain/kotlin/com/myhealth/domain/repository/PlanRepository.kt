package com.myhealth.domain.repository

import com.myhealth.domain.model.PlannedSession
import com.myhealth.domain.model.PlannedStatus
import com.myhealth.domain.model.TrainingPlan
import com.myhealth.domain.util.Outcome
import kotlinx.coroutines.flow.Flow

/**
 * `training_plan` + `planned_session` (PLAN §2.2.4, P6.5).
 *
 * Invariant enforced by the implementation: at most one `ACTIVE` [TrainingPlan] — activating a
 * plan archives the previous one. [getReplaceableSessions] returns the unlocked, still-`PLANNED`
 * rows the suggester is allowed to replace (constraint C6, §3.5.3).
 */
interface PlanRepository {

    fun observeActivePlan(): Flow<TrainingPlan?>

    fun observeAllPlans(): Flow<List<TrainingPlan>>

    fun observeSessions(fromDay: Long, toDay: Long): Flow<List<PlannedSession>>

    fun observeSessionsForPlan(planId: Long): Flow<List<PlannedSession>>

    suspend fun getPlan(id: Long): TrainingPlan?

    suspend fun getSession(id: Long): PlannedSession?

    suspend fun getSessions(fromDay: Long, toDay: Long): List<PlannedSession>

    suspend fun getReplaceableSessions(fromDay: Long, toDay: Long): List<PlannedSession>

    suspend fun upsertPlan(plan: TrainingPlan): Outcome<Long>

    suspend fun deletePlan(id: Long): Outcome<Unit>

    suspend fun upsertSession(session: PlannedSession): Outcome<Long>

    suspend fun upsertSessions(sessions: List<PlannedSession>): Outcome<Unit>

    suspend fun setSessionStatus(id: Long, status: PlannedStatus): Outcome<Unit>

    suspend fun setSessionLocked(id: Long, locked: Boolean): Outcome<Unit>

    /** Completion linking (P6.8); `activityId = null` unlinks. */
    suspend fun linkActivity(sessionId: Long, activityId: Long?): Outcome<Unit>

    suspend fun deleteSession(id: Long): Outcome<Unit>
}
