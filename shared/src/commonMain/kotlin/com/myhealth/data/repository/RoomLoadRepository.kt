package com.myhealth.data.repository

import com.myhealth.data.db.dao.LoadDao
import com.myhealth.data.mapper.toDomain
import com.myhealth.data.mapper.toEntity
import com.myhealth.domain.model.DailyLoad
import com.myhealth.domain.repository.LoadRepository
import com.myhealth.domain.util.Outcome
import com.myhealth.domain.util.runCatchingApp
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * Room-backed [LoadRepository] over `daily_load` (PLAN §2.2.6, P5.5).
 *
 * [recomputeFrom] does not depend on [com.myhealth.data.repository.LoadRecomputeService] directly:
 * that service itself depends on this repository (to write the rows it computes), so a direct
 * reference here would be a circular `by lazy` initialization in `AppGraph`. Instead the actual
 * recompute logic is handed in as a suspend lambda, which `AppGraph` wires to
 * `loadRecomputeService.recompute(fromDay)` — that lambda is only *invoked* once both lazy values
 * already exist, so there is no ordering problem, only a deferred reference.
 */
class RoomLoadRepository(
    private val loadDao: LoadDao,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val recompute: suspend (Long) -> Outcome<Unit>,
) : LoadRepository {

    override fun observeRange(fromDay: Long, toDay: Long): Flow<List<DailyLoad>> =
        loadDao.observeRange(fromDay, toDay).map { rows -> rows.map { it.toDomain() } }

    override fun observeLatest(): Flow<DailyLoad?> = loadDao.observeLatest().map { it?.toDomain() }

    override suspend fun getRange(fromDay: Long, toDay: Long): List<DailyLoad> =
        withContext(ioDispatcher) { loadDao.getRange(fromDay, toDay).map { it.toDomain() } }

    override suspend fun getLatest(): DailyLoad? =
        withContext(ioDispatcher) { loadDao.getLatest()?.toDomain() }

    override suspend fun recomputeFrom(fromDay: Long): Outcome<Unit> = recompute(fromDay)

    override suspend fun upsertAll(days: List<DailyLoad>): Outcome<Unit> = withContext(ioDispatcher) {
        runCatchingApp { loadDao.upsertAll(days.map { it.toEntity() }) }
    }

    override suspend fun deleteBefore(beforeDay: Long): Outcome<Unit> = withContext(ioDispatcher) {
        runCatchingApp { loadDao.deleteBefore(beforeDay) }
    }
}
