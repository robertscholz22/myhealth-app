package com.myhealth.data.repository

import com.myhealth.data.db.dao.SuggestionDao
import com.myhealth.data.mapper.toDomain
import com.myhealth.data.mapper.toEntity
import com.myhealth.data.time.PlatformClock
import com.myhealth.data.time.todayEpochDay
import com.myhealth.domain.engine.running.RiegelPredictor
import com.myhealth.domain.engine.suggest.GoalFormInputs
import com.myhealth.domain.engine.suggest.SuggestionEngine
import com.myhealth.domain.engine.suggest.SuggestionInput
import com.myhealth.domain.model.DailyLoad
import com.myhealth.domain.model.GoalStatus
import com.myhealth.domain.model.PlanStatus
import com.myhealth.domain.model.PlannedSession
import com.myhealth.domain.model.PlannedStatus
import com.myhealth.domain.model.Profile
import com.myhealth.domain.model.RecoveryState
import com.myhealth.domain.model.SessionType
import com.myhealth.domain.model.SportGroup
import com.myhealth.domain.model.StrengthWorkout
import com.myhealth.domain.model.SuggestedSession
import com.myhealth.domain.model.SuggestionBatch
import com.myhealth.domain.model.SuggestionStatus
import com.myhealth.domain.model.TrainingPlan
import com.myhealth.domain.repository.ActivityRepository
import com.myhealth.domain.repository.CalendarRepository
import com.myhealth.domain.repository.CycleRepository
import com.myhealth.domain.repository.GoalRepository
import com.myhealth.domain.repository.HealthRepository
import com.myhealth.domain.repository.LoadRepository
import com.myhealth.domain.repository.PlanRepository
import com.myhealth.domain.repository.ProfileRepository
import com.myhealth.domain.repository.RideBestRepository
import com.myhealth.domain.repository.RunningBestRepository
import com.myhealth.domain.repository.SettingsRepository
import com.myhealth.domain.repository.StrengthRepository
import com.myhealth.domain.repository.SuggestionRepository
import com.myhealth.domain.util.AppError
import com.myhealth.domain.util.Outcome
import com.myhealth.domain.util.epochDayDate
import com.myhealth.domain.util.runCatchingApp
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * Room-backed [SuggestionRepository] over `suggestion_batch` / `suggested_session` (PLAN §2.2.4,
 * P6.5) — the persistence half of the §3.5 suggestion engine.
 *
 * [generate] is the only place the engine's [SuggestionInput] is assembled, exactly per §3.5.1:
 * today, the profile, the `ACTIVE` goals by priority, the expanded occurrences of the horizon
 * **+ 3 days** (C1/C2 look ahead), the locked planned sessions inside the horizon, 42 days of
 * `daily_load`, the recovery state cached on the newest `daily_load` row, 14 days of activities
 * and the `ACTIVE` plan's `startDay` (the `RECOVERY_WEEK` override of §3.5.2). Writing the new
 * batch marks any previous `PROPOSED` one `SUPERSEDED`, so exactly one batch is ever reviewable.
 *
 * [accept] copies the chosen rows into `planned_session` with `status = PLANNED`,
 * `sourceSuggestionId` set and — since P14.5 — a `workoutId` materialised from the suggestion's
 * `workoutTemplateId` by [StrengthWorkoutSeeder]; a planned session changes the day's nutrition
 * target, so the write is followed by [onPlanChanged] (wired to `SyncScheduler.requestTargetRecompute`, P4.12). Accepting
 * into an empty database creates the default `ACTIVE` plan the sessions hang off — the owner never
 * has to build a plan by hand before the suggester is useful.
 */
class RoomSuggestionRepository(
    private val suggestionDao: SuggestionDao,
    private val planRepo: PlanRepository,
    private val goalRepo: GoalRepository,
    private val profileRepo: ProfileRepository,
    private val calendarRepo: CalendarRepository,
    private val loadRepo: LoadRepository,
    private val activityRepo: ActivityRepository,
    private val settingsRepo: SettingsRepository,
    private val engine: SuggestionEngine,
    private val clock: PlatformClock,
    /**
     * P11.2: `null` wherever cycle tracking is not wired (and in the tests that predate it). The
     * horizon's cycle statuses are read only while `isTrackingEnabled()` is true, so the four
     * `CYCLE_*` rules stay inert for everybody else.
     */
    private val cycleRepo: CycleRepository? = null,
    /**
     * P14.3: the three sources of `SuggestionInput.vdot` / `paceBands` / `ftpWatts` (§3.11). `null`
     * wherever they are not wired (and in the P6.5 tests): the engine then builds zone-only
     * structures and the week is exactly the pre-P14 one.
     */
    private val runningBestRepo: RunningBestRepository? = null,
    private val rideBestRepo: RideBestRepository? = null,
    private val healthRepo: HealthRepository? = null,
    /**
     * P14.5: the muscle-load layer (§3.12.4/§3.12.5). `null` wherever strength is not wired — the
     * engine then sees `muscleLoad = null`, `C15` never fires and the week is the pre-P14.5 one.
     * [strengthSeeder] is what turns an accepted `workoutTemplateId` into a real `workoutId`.
     */
    private val strengthRepo: StrengthRepository? = null,
    private val strengthSeeder: StrengthWorkoutSeeder? = null,
    private val onPlanChanged: () -> Unit = {},
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : SuggestionRepository {

    override fun observeLatestBatch(): Flow<SuggestionBatch?> =
        suggestionDao.observeLatestBatch().map { it?.toDomain() }

    /** POLISH-8: the DataStore flag, and-ed with "there is actually an open batch to regenerate". */
    override fun observeStale(): Flow<Boolean> = combine(
        settingsRepo.settings.map { it.suggestionsStale },
        observeLatestBatch(),
    ) { stale, batch -> stale && batch?.status == SuggestionStatus.PROPOSED }

    override fun observeSessions(batchId: Long): Flow<List<SuggestedSession>> =
        suggestionDao.observeSessionsForBatch(batchId).map { rows -> rows.map { it.toDomain() } }

    override suspend fun getBatch(id: Long): SuggestionBatch? =
        withContext(ioDispatcher) { suggestionDao.getById(id)?.toDomain() }

    override suspend fun generate(horizonDays: Int): Outcome<SuggestionBatch> =
        withContext(ioDispatcher) {
            val profile = profileRepo.getProfile()
                ?: return@withContext Outcome.Err(
                    AppError.Validation("profile", "Complete onboarding to get training suggestions."),
                )
            val days = horizonDays.coerceIn(MIN_HORIZON_DAYS, MAX_HORIZON_DAYS)
            runCatchingApp {
                val result = engine.generate(gatherInput(profile, days))
                suggestionDao.supersedeProposedBatches()
                settingsRepo.update { it.copy(suggestionsStale = false) }
                val batchId = suggestionDao.upsert(result.batch.copy(id = 0L).toEntity())
                suggestionDao.upsertSessions(
                    result.sessions.map { it.copy(id = 0L, batchId = batchId).toEntity() },
                )
                result.batch.copy(id = batchId)
            }
        }

    override suspend fun countReplaceableSessions(batchId: Long): Int = withContext(ioDispatcher) {
        val batch = suggestionDao.getById(batchId) ?: return@withContext 0
        planRepo.getReplaceableSessions(batch.horizonStartDay, batch.horizonEndDay).size
    }

    override suspend fun accept(sessionIds: List<Long>): Outcome<Unit> = withContext(ioDispatcher) {
        if (sessionIds.isEmpty()) return@withContext Outcome.Ok(Unit)
        val outcome = runCatchingApp {
            val rows = suggestionDao.getSessionsByIds(sessionIds).map { it.toDomain() }
            if (rows.isNotEmpty()) {
                val planId = activePlanId()
                // BUG-10: a proposal replaces the week — drop the unlocked PLANNED sessions inside
                // each accepted batch's horizon (locked / COMPLETED / SKIPPED sessions stay).
                rows.map { it.batchId }.distinct().forEach { batchId ->
                    suggestionDao.getById(batchId)?.let { batch ->
                        planRepo.getReplaceableSessions(batch.horizonStartDay, batch.horizonEndDay)
                            .forEach { planRepo.deleteSession(it.id) }
                    }
                }
                planRepo.upsertSessions(
                    rows.map { it.toPlannedSession(planId, workoutIdFor(it)) },
                )
                suggestionDao.updateSessionStatuses(rows.map { it.id }, SuggestionStatus.ACCEPTED)
                closeBatches(rows.map { it.batchId }.distinct())
            }
        }
        if (outcome is Outcome.Ok) onPlanChanged()
        outcome
    }

    override suspend fun reject(sessionIds: List<Long>): Outcome<Unit> = withContext(ioDispatcher) {
        if (sessionIds.isEmpty()) return@withContext Outcome.Ok(Unit)
        runCatchingApp {
            val batchIds = suggestionDao.getSessionsByIds(sessionIds).map { it.batchId }.distinct()
            suggestionDao.updateSessionStatuses(sessionIds, SuggestionStatus.REJECTED)
            closeBatches(batchIds)
        }
    }

    override suspend fun supersedeProposed(): Outcome<Unit> = withContext(ioDispatcher) {
        runCatchingApp { suggestionDao.supersedeProposedBatches() }
    }

    override suspend fun markProposedStale(): Outcome<Unit> = withContext(ioDispatcher) {
        runCatchingApp {
            if (suggestionDao.getLatestBatch()?.status == SuggestionStatus.PROPOSED) {
                settingsRepo.update { it.copy(suggestionsStale = true) }
            }
        }
    }

    /**
     * POLISH-9: once the review has been saved the batch is no longer a proposal. A batch with at
     * least one `ACCEPTED` session becomes `ACCEPTED`; one whose sessions are *all* `REJECTED`
     * becomes `REJECTED`. A batch still holding `PROPOSED` sessions (a partial review) is left
     * alone, so the rest can still be reviewed. `observeLatestBatch` keeps returning the row — the
     * Training phase badge reads it — but the Today card and the review screen only propose from
     * `PROPOSED` batches.
     */
    private suspend fun closeBatches(batchIds: List<Long>) {
        batchIds.forEach { batchId ->
            val sessions = suggestionDao.getSessionsForBatch(batchId)
            if (sessions.isEmpty()) return@forEach
            val status = when {
                sessions.any { it.status == SuggestionStatus.PROPOSED } -> null
                sessions.any { it.status == SuggestionStatus.ACCEPTED } -> SuggestionStatus.ACCEPTED
                sessions.all { it.status == SuggestionStatus.REJECTED } -> SuggestionStatus.REJECTED
                else -> null
            }
            if (status != null) {
                suggestionDao.updateBatchStatus(batchId, status)
                settingsRepo.update { it.copy(suggestionsStale = false) }
            }
        }
    }

    // ---- engine inputs (§3.5.1) ------------------------------------------------------------

    private val muscleResolver = SuggestionMuscleResolver(
        activityRepo = activityRepo,
        planRepo = planRepo,
        strengthRepo = strengthRepo,
    )

    private val paceResolver = SuggestionPaceResolver(
        activityRepo = activityRepo,
        settingsRepo = settingsRepo,
        runningBestRepo = runningBestRepo,
        rideBestRepo = rideBestRepo,
        healthRepo = healthRepo,
    )

    private suspend fun gatherInput(profile: Profile, horizonDays: Int): SuggestionInput {
        val todayDay = clock.todayEpochDay()
        val today = todayDay.epochDayDate()
        val horizonEnd = todayDay + horizonDays
        val latestLoad = loadRepo.getLatest()
        val paces = paceResolver.resolve(profile, todayDay)
        val muscle = muscleResolver.resolve(todayDay, latestLoad?.ctl ?: 0.0)
        val lockedPlanned = planRepo.getSessions(todayDay, horizonEnd - 1)
            .filter { it.locked || it.sourceSuggestionId == null }
        return SuggestionInput(
            today = today,
            horizonDays = horizonDays,
            profile = profile,
            goals = goalRepo.observeByStatus(GoalStatus.ACTIVE).first().sortedBy { it.priority },
            events = calendarRepo
                .observeOccurrences(todayDay, horizonEnd + EVENT_LOOKAHEAD_DAYS)
                .first(),
            // BUG-15: a session the user planned by hand is as fixed as a locked one — it is never
            // replaced (PlanDao.getReplaceableSessions) and the engine must plan around it.
            lockedPlanned = lockedPlanned,
            recentLoad = loadRepo.getRange(todayDay - LOAD_WINDOW_DAYS, todayDay),
            recovery = latestLoad.toRecoveryState(),
            recentActivities = activityRepo
                .observeRange(todayDay - ACTIVITY_WINDOW_DAYS, todayDay)
                .first(),
            planStartDay = planRepo.observeActivePlan().first()?.startDay,
            cycleStatusByDay = cycleStatuses(todayDay, horizonEnd),
            vdot = paces.vdot,
            paceBands = paces.paceBands,
            ftpWatts = paces.ftpWatts,
            muscleLoad = muscle.muscleLoad,
            lastAcceptedTemplateByKind = muscle.lastAcceptedTemplateByKind,
            goalForm = goalForm(todayDay, lockedPlanned),
            strengthPool = strengthPool(),
            lastWorkoutIdBySessionType = muscle.lastWorkoutIdBySessionType,
        )
    }

    /**
     * P19 (§P19 item 4): the goal layer's facts, or `null` where running bests are not wired (the
     * P6.5 tests), which keeps that whole layer off.
     *
     * A time trial counts as "already planned" when it is in the last seven days (unless skipped),
     * or fixed inside the horizon (locked, hand-planned or completed). An *unlocked* suggested one
     * in the horizon does not count: accepting this batch replaces it, so the batch must carry its
     * own.
     */
    private suspend fun goalForm(todayDay: Long, lockedPlanned: List<PlannedSession>): GoalFormInputs? {
        val bests = runningBestRepo ?: return null
        val recent = bests.observeSince(todayDay - RiegelPredictor.MAX_SOURCE_AGE_DAYS).first()
        val runs = activityRepo.observeRange(todayDay - LONG_RUN_WINDOW_DAYS, todayDay).first()
            .filter { it.sportGroup == SportGroup.RUN }
        val pastTrials = planRepo.getSessions(todayDay - BENCHMARK_LOOKBACK_DAYS, todayDay - 1)
            .filter { it.sessionType == SessionType.TIME_TRIAL && it.status != PlannedStatus.SKIPPED }
        val fixedTrials = lockedPlanned.filter { it.sessionType == SessionType.TIME_TRIAL }
        return GoalFormInputs(
            vdotSourceDay = RiegelPredictor.pickSource(recent, todayDay)?.day,
            longestRunMeters28d = runs.maxOfOrNull { it.distanceMeters ?: 0.0 } ?: 0.0,
            timeTrialDays = (pastTrials + fixedTrials).map { it.day }.toSet(),
        )
    }

    /**
     * P19 (§P19 item 9): the workouts checked "Use in suggestions", or `null` where strength is
     * not wired. The built-ins are seeded first (idempotent): without that, an athlete who never
     * opened the Workouts screen would have an empty pool and no strength days at all.
     */
    private suspend fun strengthPool(): List<StrengthWorkout>? {
        val repo = strengthRepo ?: return null
        strengthSeeder?.seed()
        return repo.observeAll().first().filter { it.useInSuggestions }
    }

    /** The horizon's cycle statuses, or an empty map when tracking is off (P11.2). */
    private suspend fun cycleStatuses(fromDay: Long, toDayExclusive: Long) = cycleRepo
        ?.takeIf { it.isTrackingEnabled().first() }
        ?.statusesFor(fromDay, toDayExclusive)
        .orEmpty()

    /**
     * The recovery half of the newest `daily_load` row read back as a [RecoveryState]: §3.5.1 asks
     * for one, and `daily_load` is where `RecoveryEngine`'s verdict is cached (§2.2.6). A row that
     * carries no score at all yields `null` — the engine's documented "unknown recovery" case.
     */
    private fun DailyLoad?.toRecoveryState(): RecoveryState? {
        val row = this ?: return null
        if (row.recoveryScore == null && row.recoveryBand == null) return null
        return RecoveryState(
            day = row.day,
            score = row.recoveryScore,
            band = row.recoveryBand,
            confidence = row.recoveryConfidence,
            components = emptyList(),
            flags = row.flags,
            warnings = emptyList(),
        )
    }

    // ---- accept ------------------------------------------------------------------------------

    /** The `ACTIVE` plan's id, creating the default plan on first accept. */
    private suspend fun activePlanId(): Long? {
        planRepo.observeActivePlan().first()?.let { return it.id }
        val today = clock.todayEpochDay()
        val now = clock.millis()
        val created = planRepo.upsertPlan(
            TrainingPlan(
                id = 0L,
                name = DEFAULT_PLAN_NAME,
                startDay = today,
                endDay = today + DEFAULT_PLAN_LENGTH_DAYS,
                status = PlanStatus.ACTIVE,
                primaryGoalId = null,
                notes = null,
                createdAtMillis = now,
                updatedAtMillis = now,
            ),
        )
        return (created as? Outcome.Ok)?.value
    }

    /**
     * P14.5: the `workoutTemplateId` a strength suggestion proposes, materialised into a real
     * `strength_workout` row (§3.12.3). The seeder writes the six built-ins the first time one is
     * needed and is idempotent afterwards, so accepting the same template twice reuses the row —
     * including the user's own edits to it.
     */
    private suspend fun workoutIdFor(templateId: String?): Long? {
        val id = templateId ?: return null
        return strengthSeeder?.workoutFor(id)?.id
    }

    /**
     * P19: the pool's concrete workout when it still exists (it may have been deleted between
     * generating and accepting — `planned_session.workoutId` is a real FK), else the built-in.
     */
    private suspend fun workoutIdFor(session: SuggestedSession): Long? {
        session.workoutId?.let { id -> if (strengthRepo?.getById(id) != null) return id }
        return workoutIdFor(session.workoutTemplateId)
    }

    private fun SuggestedSession.toPlannedSession(
        planId: Long?,
        workoutId: Long? = null,
    ): PlannedSession {
        val now = clock.millis()
        return PlannedSession(
            id = 0L,
            planId = planId,
            day = day,
            startMinuteOfDay = null,
            sportType = sportType,
            sessionType = sessionType,
            intensity = intensity,
            targetDurationMin = targetDurationMin,
            targetDistanceMeters = targetDistanceMeters,
            targetPaceSecPerKm = targetPaceSecPerKm,
            estimatedTrimp = estimatedTrimp,
            description = null,
            rationale = rationale.joinToString(RATIONALE_SEPARATOR) { it.text }.takeIf { it.isNotBlank() },
            status = PlannedStatus.PLANNED,
            locked = false,
            linkedActivityId = null,
            sourceSuggestionId = id,
            createdAtMillis = now,
            updatedAtMillis = now,
            structureJson = structureJson,
            workoutId = workoutId,
        )
    }

    companion object {
        /** §3.5.1: the events list spans the horizon **+ 3 days** so C1/C2 can look ahead. */
        const val EVENT_LOOKAHEAD_DAYS: Long = 3L

        /** §3.5.1: 42 days of `daily_load`, 14 days of activities. */
        const val LOAD_WINDOW_DAYS: Long = 42L
        const val ACTIVITY_WINDOW_DAYS: Long = 14L

        /** P19: the long-run build-up grows from the longest run of the last four weeks. */
        const val LONG_RUN_WINDOW_DAYS: Long = 28L

        /** P19: a time trial planned or run in the last week means no new benchmark yet. */
        const val BENCHMARK_LOOKBACK_DAYS: Long = 7L

        const val MIN_HORIZON_DAYS: Int = 1
        const val MAX_HORIZON_DAYS: Int = 28

        /** The plan [accept] creates when the owner has never made one. */
        const val DEFAULT_PLAN_NAME: String = "My plan"
        const val DEFAULT_PLAN_LENGTH_DAYS: Long = 364L

        /** A suggestion's rationale bullets flattened into `planned_session.rationale` (TEXT). */
        const val RATIONALE_SEPARATOR: String = " · "
    }
}
