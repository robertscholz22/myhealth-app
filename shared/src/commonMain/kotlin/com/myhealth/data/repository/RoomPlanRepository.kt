package com.myhealth.data.repository

import com.myhealth.data.db.dao.PlanDao
import com.myhealth.data.db.entity.PlannedSessionEntity
import com.myhealth.data.mapper.toDomain
import com.myhealth.data.mapper.toEntity
import com.myhealth.domain.model.PlanStatus
import com.myhealth.domain.model.PlannedSession
import com.myhealth.domain.model.PlannedStatus
import com.myhealth.domain.model.TrainingPlan
import com.myhealth.domain.repository.PlanRepository
import com.myhealth.domain.util.Outcome
import com.myhealth.domain.util.runCatchingApp
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import com.myhealth.data.time.PlatformClock

/**
 * Room-backed [PlanRepository] (PLAN §2.2.4, P3.2/P6.5) over `training_plan` and
 * `planned_session`.
 *
 * The one invariant that cannot live in the schema is enforced here: **at most one `ACTIVE`
 * plan** — upserting a plan with `status = ACTIVE` archives every other active plan in the same
 * write. Session writes go through read-modify-upsert so `updatedAtMillis` and the untouched
 * columns are preserved.
 */
class RoomPlanRepository(
    private val planDao: PlanDao,
    private val clock: PlatformClock,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : PlanRepository {

    override fun observeActivePlan(): Flow<TrainingPlan?> =
        planDao.observeActivePlan().map { it?.toDomain() }

    override fun observeAllPlans(): Flow<List<TrainingPlan>> =
        planDao.observeAllPlans().map { rows -> rows.map { it.toDomain() } }

    override fun observeSessions(fromDay: Long, toDay: Long): Flow<List<PlannedSession>> =
        planDao.observeSessionRange(fromDay, toDay).map { rows -> rows.map { it.toDomain() } }

    override fun observeSessionsForPlan(planId: Long): Flow<List<PlannedSession>> =
        planDao.observeSessionsForPlan(planId).map { rows -> rows.map { it.toDomain() } }

    override suspend fun getPlan(id: Long): TrainingPlan? =
        withContext(ioDispatcher) { planDao.getById(id)?.toDomain() }

    override suspend fun getSession(id: Long): PlannedSession? =
        withContext(ioDispatcher) { planDao.getSessionById(id)?.toDomain() }

    override suspend fun getSessions(fromDay: Long, toDay: Long): List<PlannedSession> =
        withContext(ioDispatcher) { planDao.getSessionRange(fromDay, toDay).map { it.toDomain() } }

    override suspend fun getReplaceableSessions(fromDay: Long, toDay: Long): List<PlannedSession> =
        withContext(ioDispatcher) {
            planDao.getReplaceableSessions(fromDay, toDay).map { it.toDomain() }
        }

    /** Activating a plan archives the others — the "at most one ACTIVE plan" rule of §2.2.4. */
    override suspend fun upsertPlan(plan: TrainingPlan): Outcome<Long> =
        withContext(ioDispatcher) {
            runCatchingApp {
                val now = clock.millis()
                val entity = plan.copy(
                    createdAtMillis = if (plan.createdAtMillis == 0L) now else plan.createdAtMillis,
                    updatedAtMillis = now,
                ).toEntity()
                val newId = planDao.upsert(entity)
                val id = if (entity.id != 0L) entity.id else newId
                if (plan.status == PlanStatus.ACTIVE) {
                    planDao.archiveOtherActivePlans(keepPlanId = id, updatedAtMillis = now)
                }
                id
            }
        }

    override suspend fun deletePlan(id: Long): Outcome<Unit> =
        withContext(ioDispatcher) { runCatchingApp { planDao.deleteById(id) } }

    override suspend fun upsertSession(session: PlannedSession): Outcome<Long> =
        withContext(ioDispatcher) {
            runCatchingApp {
                val entity = stamped(session)
                val newId = planDao.upsertSession(entity)
                if (entity.id != 0L) entity.id else newId
            }
        }

    override suspend fun upsertSessions(sessions: List<PlannedSession>): Outcome<Unit> =
        withContext(ioDispatcher) {
            runCatchingApp { planDao.upsertSessions(sessions.map { stamped(it) }) }
        }

    override suspend fun setSessionStatus(id: Long, status: PlannedStatus): Outcome<Unit> =
        withContext(ioDispatcher) {
            runCatchingApp { planDao.updateSessionStatus(id, status, clock.millis()) }
        }

    override suspend fun setSessionLocked(id: Long, locked: Boolean): Outcome<Unit> =
        editSession(id) { it.copy(locked = locked) }

    override suspend fun linkActivity(sessionId: Long, activityId: Long?): Outcome<Unit> =
        editSession(sessionId) { it.copy(linkedActivityId = activityId) }

    override suspend fun deleteSession(id: Long): Outcome<Unit> =
        withContext(ioDispatcher) { runCatchingApp { planDao.deleteSessionById(id) } }

    private fun stamped(session: PlannedSession): PlannedSessionEntity {
        val now = clock.millis()
        return session.copy(
            createdAtMillis = if (session.createdAtMillis == 0L) now else session.createdAtMillis,
            updatedAtMillis = now,
        ).toEntity()
    }

    private suspend fun editSession(
        id: Long,
        change: (PlannedSessionEntity) -> PlannedSessionEntity,
    ): Outcome<Unit> = withContext(ioDispatcher) {
        runCatchingApp {
            val row = planDao.getSessionById(id)
            if (row != null) {
                planDao.upsertSession(change(row).copy(updatedAtMillis = clock.millis()))
            }
        }
    }
}
