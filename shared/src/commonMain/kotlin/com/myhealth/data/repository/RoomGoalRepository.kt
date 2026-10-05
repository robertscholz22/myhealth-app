package com.myhealth.data.repository

import com.myhealth.data.db.dao.GoalDao
import com.myhealth.data.mapper.toDomain
import com.myhealth.data.mapper.toEntity
import com.myhealth.domain.model.Goal
import com.myhealth.domain.model.GoalStatus
import com.myhealth.domain.repository.GoalRepository
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
 * Room-backed [GoalRepository] (PLAN §2.2.4, P6.1) over `goal`.
 *
 * The one invariant that cannot live in the schema is enforced here: **only one goal may have
 * `priority = 1`**. Both [setPrimary] and an [upsert] that arrives with `priority = 1` demote every
 * other primary to 2 in the same write, so the periodization engine's
 * `goals.firstOrNull { … }.minByOrNull { priority }` (§3.5.2) can never see two primaries.
 *
 * Writes are read-modify-upsert so `createdAtMillis` survives an edit and `updatedAtMillis` always
 * reflects the [PlatformClock].
 */
class RoomGoalRepository(
    private val goalDao: GoalDao,
    private val clock: PlatformClock,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : GoalRepository {

    override fun observeAll(): Flow<List<Goal>> =
        goalDao.observeAll().map { rows -> rows.map { it.toDomain() } }

    override fun observeByStatus(status: GoalStatus): Flow<List<Goal>> =
        goalDao.observeByStatus(status).map { rows -> rows.map { it.toDomain() } }

    override fun observePrimary(): Flow<Goal?> = goalDao.observePrimary().map { it?.toDomain() }

    override suspend fun getById(id: Long): Goal? =
        withContext(ioDispatcher) { goalDao.getById(id)?.toDomain() }

    override suspend fun upsert(goal: Goal): Outcome<Long> = withContext(ioDispatcher) {
        runCatchingApp {
            val now = clock.millis()
            val existing = if (goal.id != 0L) goalDao.getById(goal.id) else null
            val entity = goal.copy(
                priority = goal.priority.coerceAtLeast(1),
                createdAtMillis = existing?.createdAtMillis
                    ?: goal.createdAtMillis.takeIf { it != 0L }
                    ?: now,
                updatedAtMillis = now,
            ).toEntity()
            val newId = goalDao.upsert(entity)
            val id = if (entity.id != 0L) entity.id else newId
            if (entity.priority == PRIMARY_PRIORITY) {
                goalDao.demoteOtherPrimaries(keepGoalId = id, updatedAtMillis = now)
            }
            id
        }
    }

    override suspend fun setStatus(id: Long, status: GoalStatus): Outcome<Unit> =
        withContext(ioDispatcher) {
            runCatchingApp { goalDao.updateStatus(id, status, clock.millis()) }
        }

    override suspend fun setPrimary(id: Long): Outcome<Unit> = withContext(ioDispatcher) {
        runCatchingApp {
            val now = clock.millis()
            goalDao.updatePriority(id, PRIMARY_PRIORITY, now)
            goalDao.demoteOtherPrimaries(keepGoalId = id, updatedAtMillis = now)
        }
    }

    override suspend fun delete(id: Long): Outcome<Unit> = withContext(ioDispatcher) {
        runCatchingApp { goalDao.deleteById(id) }
    }

    private companion object {
        /** `1 = primary` (§2.2.4); the demoted rank is 2. */
        const val PRIMARY_PRIORITY = 1
    }
}
