package com.myhealth.data.repository

import com.myhealth.data.db.dao.CycleDao
import com.myhealth.data.mapper.toDomain
import com.myhealth.data.mapper.toEntity
import com.myhealth.domain.engine.cycle.CycleEngine
import com.myhealth.domain.model.CycleEntry
import com.myhealth.domain.model.CycleForecast
import com.myhealth.domain.model.CycleStatus
import com.myhealth.domain.model.Sex
import com.myhealth.domain.repository.CycleRepository
import com.myhealth.domain.repository.ProfileRepository
import com.myhealth.domain.repository.SettingsRepository
import com.myhealth.domain.util.AppError
import com.myhealth.domain.util.Outcome
import com.myhealth.domain.util.runCatchingApp
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import com.myhealth.data.time.PlatformClock

/**
 * Room-backed [CycleRepository] over `cycle_entry` (PLAN §5 P11.1) — the storage half of
 * [CycleEngine], which stays a pure function of the logged entries.
 *
 * Two invariants live here rather than in the schema:
 * - **one entry per start day**: the table's unique index would reject a second row, so an upsert
 *   whose `periodStartDay` is already taken by a *different* row is folded into that row instead
 *   of failing (re-logging today's start is an edit, which is what the screen's "Log period start"
 *   button does when it is tapped twice);
 * - **an end never precedes its start**: a "Period ended" tap with an earlier day is a validation
 *   error, not a negative period length.
 *
 * Writes are read-modify-upsert so `createdAtMillis` survives an edit and `updatedAtMillis` always
 * reflects the [PlatformClock].
 */
class RoomCycleRepository(
    private val cycleDao: CycleDao,
    private val profileRepo: ProfileRepository,
    private val settingsRepo: SettingsRepository,
    private val clock: PlatformClock,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : CycleRepository {

    override fun observeAll(): Flow<List<CycleEntry>> =
        cycleDao.observeAll().map { rows -> rows.map { it.toDomain() } }

    override fun observeStatus(today: Long): Flow<CycleStatus?> =
        observeAll().map { CycleEngine.statusFor(today, it) }

    override fun observeForecast(today: Long): Flow<CycleForecast> =
        observeAll().map { CycleEngine.forecast(it, today) }

    /** The §P11 gate: the settings flag, or simply being the user the feature was asked for. */
    override fun isTrackingEnabled(): Flow<Boolean> = combine(
        settingsRepo.settings.map { it.cycleTrackingEnabled },
        profileRepo.observeProfile(),
    ) { flag, profile -> flag || profile?.sex == Sex.FEMALE }

    override suspend fun getAll(): List<CycleEntry> =
        withContext(ioDispatcher) { cycleDao.getAll().map { it.toDomain() } }

    override suspend fun getById(id: Long): CycleEntry? =
        withContext(ioDispatcher) { cycleDao.getById(id)?.toDomain() }

    override suspend fun statusFor(day: Long): CycleStatus? =
        withContext(ioDispatcher) { CycleEngine.statusFor(day, getAll()) }

    override suspend fun statusesFor(fromDay: Long, toDayExclusive: Long): Map<Long, CycleStatus> =
        withContext(ioDispatcher) { CycleEngine.statusesFor(fromDay, toDayExclusive, getAll()) }

    override suspend fun upsert(entry: CycleEntry): Outcome<Long> = withContext(ioDispatcher) {
        val end = entry.periodEndDay
        if (end != null && end < entry.periodStartDay) {
            return@withContext Outcome.Err(
                AppError.Validation("periodEndDay", "A period cannot end before it started."),
            )
        }
        runCatchingApp {
            val byStart = cycleDao.getByStartDay(entry.periodStartDay)
            val existing = if (entry.id != 0L) cycleDao.getById(entry.id) else null
            // Re-logging a start that is already on file edits that row; the unique index would
            // otherwise reject the insert and the user would only see a failure.
            val targetId = existing?.id ?: byStart?.id ?: 0L
            val now = clock.millis()
            val createdAt = existing?.createdAtMillis
                ?: byStart?.createdAtMillis
                ?: entry.createdAtMillis.takeIf { it != 0L }
                ?: now
            val row = entry
                .copy(id = targetId, createdAtMillis = createdAt, updatedAtMillis = now)
                .toEntity()
            val newId = cycleDao.upsert(row)
            if (targetId != 0L) targetId else newId
        }
    }

    override suspend fun delete(id: Long): Outcome<Unit> =
        withContext(ioDispatcher) { runCatchingApp { cycleDao.deleteById(id) } }
}
