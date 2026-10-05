package com.myhealth.data.repository

import com.myhealth.data.db.dao.NutritionDao
import com.myhealth.data.mapper.toDomain
import com.myhealth.data.mapper.toEntity
import com.myhealth.data.time.PlatformClock
import com.myhealth.data.time.todayEpochDay
import com.myhealth.domain.engine.nutrition.DayTypeResolver
import com.myhealth.domain.engine.nutrition.NutritionDefaults
import com.myhealth.domain.engine.nutrition.NutritionTargetEngine
import com.myhealth.domain.engine.nutrition.NutritionTargetInput
import com.myhealth.domain.model.NutritionTarget
import com.myhealth.domain.model.PlannedStatus
import com.myhealth.domain.model.Profile
import com.myhealth.domain.model.WaterLog
import com.myhealth.domain.repository.ActivityRepository
import com.myhealth.domain.repository.BodyRepository
import com.myhealth.domain.repository.CalendarRepository
import com.myhealth.domain.repository.CycleRepository
import com.myhealth.domain.repository.HealthRepository
import com.myhealth.domain.repository.NutritionRepository
import com.myhealth.domain.repository.PlanRepository
import com.myhealth.domain.repository.ProfileRepository
import com.myhealth.domain.util.AppError
import com.myhealth.domain.util.Outcome
import com.myhealth.domain.util.epochDayDate
import com.myhealth.domain.util.runCatchingApp
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDate

/**
 * Room-backed [NutritionRepository] over `nutrition_target_snapshot` / `water_log` (PLAN §2.2.5).
 *
 * [ensureTarget] is the cache-invalidation entry point of P4.12: it gathers the engine inputs
 * (profile, the newest weight and body-fat measurement inside 30 days, the day's Health Connect
 * summary, the day's completed activities and still-planned sessions, and the events of
 * `day - 1 .. day + 1` for [DayTypeResolver]), hashes them together with the TDEE rung the ladder
 * of §3.1.3 lands on, and only recomputes + rewrites the snapshot when that hash differs from the
 * stored one. The BMR/TDEE half of the engine is evaluated to build the hash — it is pure and
 * cheap — while the macro split, the explanation string and the write are what the hash gates.
 */
class RoomNutritionRepository(
    private val nutritionDao: NutritionDao,
    private val profileRepo: ProfileRepository,
    private val bodyRepo: BodyRepository,
    private val healthRepo: HealthRepository,
    private val activityRepo: ActivityRepository,
    private val planRepo: PlanRepository,
    private val calendarRepo: CalendarRepository,
    private val engine: NutritionTargetEngine,
    private val clock: PlatformClock,
    /**
     * P11.2: `null` wherever cycle tracking is not wired (and in the tests that predate it). The
     * repository asks it for a day's [com.myhealth.domain.model.CycleStatus] only while
     * `isTrackingEnabled()` is true; the target itself never changes, only its explanation.
     */
    private val cycleRepo: CycleRepository? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : NutritionRepository {

    override fun observeTarget(day: Long): Flow<NutritionTarget?> =
        nutritionDao.observeTarget(day).map { it?.toDomain() }

    override fun observeTargets(fromDay: Long, toDay: Long): Flow<List<NutritionTarget>> =
        nutritionDao.observeTargets(fromDay, toDay).map { rows -> rows.map { it.toDomain() } }

    override suspend fun getTarget(day: Long): NutritionTarget? =
        withContext(ioDispatcher) { nutritionDao.getTarget(day)?.toDomain() }

    override suspend fun ensureTarget(day: Long): Outcome<NutritionTarget> = withContext(ioDispatcher) {
        val profile = profileRepo.getProfile()
            ?: return@withContext Outcome.Err(
                AppError.Validation("profile", "Complete onboarding to get nutrition targets."),
            )

        val date = day.epochDayDate()
        val input = gatherInput(date, profile)
        val energy = engine.energySummary(input)
        val hash = TargetInputsHash.of(hashFields(input, energy.tdeeSource.name, energy.tdeeKcal))

        val stored = nutritionDao.getTarget(day)?.toDomain()
        if (stored != null && stored.inputsHash == hash) return@withContext Outcome.Ok(stored)

        val computed = engine.compute(input).copy(inputsHash = hash, computedAtMillis = clock.millis())
        when (val write = runCatchingApp { nutritionDao.upsertTarget(computed.toEntity()) }) {
            is Outcome.Err -> write
            is Outcome.Ok -> Outcome.Ok(computed)
        }
    }

    override suspend fun upsertTarget(target: NutritionTarget): Outcome<Unit> = withContext(ioDispatcher) {
        runCatchingApp { nutritionDao.upsertTarget(target.toEntity()) }
    }

    override suspend fun deleteTarget(day: Long): Outcome<Unit> = withContext(ioDispatcher) {
        runCatchingApp { nutritionDao.deleteById(day) }
    }

    // ---- target inputs -----------------------------------------------------------------------

    private suspend fun gatherInput(
        date: LocalDate,
        profile: Profile,
    ): NutritionTargetInput {
        val day = date.toEpochDays()
        val window = NutritionDefaults.MEASUREMENT_MAX_AGE_DAYS
        val completed = activityRepo.getByDay(day)
        val planned = planRepo.getSessions(day, day).filter { it.status == PlannedStatus.PLANNED }
        val events = calendarRepo.observeOccurrences(day - 1, day + 1).first()
        val dayType = DayTypeResolver.resolve(
            DayTypeResolver.Input(
                date = date,
                events = events,
                plannedSessions = planned,
                completedSessions = completed,
            ),
        )
        return NutritionTargetInput(
            date = date,
            profile = profile,
            latestWeight = bodyRepo.latestWeight(window),
            latestBodyFat = bodyRepo.latestWithBodyFat(window),
            actualDailySummary = healthRepo.getDay(day),
            completedSessions = completed,
            plannedSessions = planned,
            dayType = dayType,
            isDayComplete = day < clock.todayEpochDay(),
            cycleStatus = cycleStatusFor(day),
        )
    }

    /** The day's cycle status, or `null` when there is no tracker or it is switched off (P11.2). */
    private suspend fun cycleStatusFor(day: Long) = cycleRepo
        ?.takeIf { it.isTrackingEnabled().first() }
        ?.statusFor(day)

    /** The `inputsHash` field set of P4.12. Keys are sorted by [TargetInputsHash]. */
    private fun hashFields(
        input: NutritionTargetInput,
        tdeeSource: String,
        tdeeValue: Double,
    ): Map<String, String?> = mapOf(
        "profileUpdatedAt" to input.profile.updatedAtMillis.toString(),
        "weight" to TargetInputsHash.num(input.latestWeight?.weightKg),
        "bodyFat" to TargetInputsHash.num(input.latestBodyFat?.bodyFatPercent),
        "goalWeight" to TargetInputsHash.num(input.profile.goalWeightKg),
        "pace" to TargetInputsHash.num(input.profile.goalPaceKgPerWeek),
        "neat" to input.profile.neatLevel.name,
        "dayType" to input.dayType.name,
        "plannedIds" to TargetInputsHash.ids(input.plannedSessions.map { it.id }),
        "completedIds" to TargetInputsHash.ids(input.completedSessions.map { it.id }),
        "tdeeSource" to tdeeSource,
        "tdeeValue" to TargetInputsHash.num(tdeeValue),
        // P11.2: the luteal note is part of the stored explanation, so the phase is part of the hash.
        "cyclePhase" to input.cycleStatus?.phase?.name,
    )

    // ---- water (P4.13) -----------------------------------------------------------------------

    override fun observeWaterTotalMl(day: Long): Flow<Int> = nutritionDao.observeWaterTotal(day)

    override fun observeWaterLogs(day: Long): Flow<List<WaterLog>> =
        nutritionDao.observeWaterDay(day).map { rows -> rows.map { it.toDomain() } }

    override suspend fun addWater(day: Long, ml: Int, atMinuteOfDay: Int?): Outcome<Long> =
        withContext(ioDispatcher) {
            if (ml <= 0) {
                return@withContext Outcome.Err(AppError.Validation("ml", "Amount must be positive."))
            }
            if (ml > MAX_WATER_ML) {
                return@withContext Outcome.Err(AppError.Validation("ml", "That is more than 5 litres."))
            }
            runCatchingApp {
                nutritionDao.upsertWater(
                    WaterLog(id = 0L, day = day, atMinuteOfDay = atMinuteOfDay, ml = ml).toEntity(),
                )
            }
        }

    override suspend fun deleteWater(id: Long): Outcome<Unit> =
        withContext(ioDispatcher) { runCatchingApp { nutritionDao.deleteWaterById(id) } }

    private companion object {
        /** A single entry above 5 l is a typo, not a drink. */
        const val MAX_WATER_ML = 5_000
    }
}
