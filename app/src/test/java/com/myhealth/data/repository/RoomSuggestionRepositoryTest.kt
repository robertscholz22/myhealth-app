package com.myhealth.data.repository

import com.google.common.truth.Truth.assertThat
import com.myhealth.data.db.entity.SuggestedSessionEntity
import com.myhealth.data.db.entity.SuggestionBatchEntity
import com.myhealth.domain.engine.strength.ProgressionEngine
import com.myhealth.domain.engine.suggest.IntervalBuilder
import com.myhealth.domain.engine.suggest.IntervalContext
import com.myhealth.domain.engine.suggest.SuggestFixtures
import com.myhealth.domain.engine.suggest.SuggestionEngine
import com.myhealth.domain.model.Exercise
import com.myhealth.domain.model.ExercisePrescription
import com.myhealth.domain.model.ExerciseProgress
import com.myhealth.domain.model.Intensity
import com.myhealth.domain.model.PlanStatus
import com.myhealth.domain.model.PlannedSession
import com.myhealth.domain.model.PlannedStatus
import com.myhealth.domain.model.RunningBest
import com.myhealth.domain.model.SessionType
import com.myhealth.domain.model.SportType
import com.myhealth.domain.model.StrengthSetLog
import com.myhealth.domain.model.StrengthWorkout
import com.myhealth.domain.model.StrengthWorkoutKind
import com.myhealth.domain.model.SuggestionStatus
import com.myhealth.domain.model.TrainingPhase
import com.myhealth.domain.model.WorkoutStructureCodec
import com.myhealth.domain.repository.StrengthRepository
import com.myhealth.domain.util.Outcome
import com.myhealth.testutil.Fixtures
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * [RoomSuggestionRepository] against in-memory fakes (PLAN P6.5). The two behaviours that are not
 * in the schema and not in the engine live here: a new batch supersedes the previous `PROPOSED`
 * one, and accepting a suggestion copies it into `planned_session` (creating the default `ACTIVE`
 * plan when the owner has none).
 */
class RoomSuggestionRepositoryTest {

    private val clock = Fixtures.fixedClock("2026-09-14T06:00:00Z")
    private val today = SuggestFixtures.TODAY_DAY

    private val suggestionDao = FakeSuggestionDao()
    private val planDao = FakePlanDao()
    private val planRepo = RoomPlanRepository(planDao, clock, Dispatchers.Unconfined)
    private val goalRepo = RoomGoalRepository(FakeGoalDao(), clock, Dispatchers.Unconfined)
    private val profileRepo = FakeTargetProfileRepository(SuggestFixtures.profile())
    private val calendarRepo = FakeTargetCalendarRepository()
    private val loadRepo = FakeLoadRepository()
    private val activityDao = FakeActivityDao()
    private val activityRepo = RoomActivityRepository(
        activityDao,
        ActivityIngestor(activityDao, DirectTransactionRunner, clock),
        clock,
        ioDispatcher = Dispatchers.Unconfined,
    )

    private var recomputeRequests = 0

    private val settingsRepo = FakeLoadSettingsRepository()

    private val strengthRepo = InMemoryStrengthRepository()

    private val repo = RoomSuggestionRepository(
        suggestionDao = suggestionDao,
        planRepo = planRepo,
        goalRepo = goalRepo,
        profileRepo = profileRepo,
        calendarRepo = calendarRepo,
        loadRepo = loadRepo,
        activityRepo = activityRepo,
        settingsRepo = settingsRepo,
        engine = SuggestionEngine(clock),
        clock = clock,
        strengthRepo = strengthRepo,
        strengthSeeder = StrengthWorkoutSeeder(strengthRepo, clock),
        onPlanChanged = { recomputeRequests++ },
        ioDispatcher = Dispatchers.Unconfined,
    )

    @Test
    fun generate_supersedes_the_previous_proposed_batch() = runTest {
        seedLoadHistory()

        val first = (repo.generate(7) as Outcome.Ok).value
        val second = (repo.generate(7) as Outcome.Ok).value

        assertThat(second.id).isNotEqualTo(first.id)
        assertThat(suggestionDao.getById(first.id)?.status).isEqualTo(SuggestionStatus.SUPERSEDED)
        assertThat(suggestionDao.getById(second.id)?.status).isEqualTo(SuggestionStatus.PROPOSED)
        assertThat(repo.observeLatestBatch().first()?.id).isEqualTo(second.id)
        // The engine actually produced a week: the batch is worth reviewing, not an empty shell.
        assertThat(repo.observeSessions(second.id).first()).isNotEmpty()
        assertThat(second.horizonStartDay).isEqualTo(today)
        assertThat(second.horizonEndDay).isEqualTo(today + 7)
    }

    @Test
    fun accept_copies_suggestions_into_planned_sessions_and_creates_the_default_plan() = runTest {
        val batchId = suggestionDao.upsert(
            SuggestionBatchEntity(
                id = 0L,
                generatedAtMillis = clock.millis(),
                horizonStartDay = today,
                horizonEndDay = today + 7,
                phase = TrainingPhase.BASE,
                weeklyLoadTarget = 400.0,
                inputsHash = "hash",
            ),
        )
        suggestionDao.upsertSessions(
            listOf(
                suggested(batchId, day = today + 1, sessionType = SessionType.EASY_RUN),
                suggested(batchId, day = today + 3, sessionType = SessionType.TEMPO_RUN),
            ),
        )
        val proposed = suggestionDao.getSessionsForBatch(batchId)

        val accepted = proposed.take(1).map { it.id }
        assertThat(repo.accept(accepted)).isInstanceOf(Outcome.Ok::class.java)

        val plan = planRepo.observeActivePlan().first()
        assertThat(plan).isNotNull()
        assertThat(plan!!.name).isEqualTo(RoomSuggestionRepository.DEFAULT_PLAN_NAME)
        assertThat(plan.status).isEqualTo(PlanStatus.ACTIVE)
        assertThat(plan.startDay).isEqualTo(today)

        val planned = planRepo.getSessions(today, today + 7)
        assertThat(planned).hasSize(1)
        assertThat(planned.single().sourceSuggestionId).isEqualTo(accepted.single())
        assertThat(planned.single().planId).isEqualTo(plan.id)
        assertThat(planned.single().status).isEqualTo(PlannedStatus.PLANNED)
        assertThat(planned.single().day).isEqualTo(today + 1)
        assertThat(planned.single().sessionType).isEqualTo(SessionType.EASY_RUN)

        assertThat(suggestionDao.getSessionById(accepted.single())?.status)
            .isEqualTo(SuggestionStatus.ACCEPTED)
        assertThat(recomputeRequests).isEqualTo(1)

        // The untouched suggestion is still open, and rejecting it closes it.
        val rejected = proposed.drop(1).map { it.id }
        repo.reject(rejected)
        assertThat(suggestionDao.getSessionById(rejected.single())?.status)
            .isEqualTo(SuggestionStatus.REJECTED)
        assertThat(planRepo.getSessions(today, today + 7)).hasSize(1)

        // POLISH-9: the batch is closed once nothing in it is `PROPOSED` any more.
        assertThat(suggestionDao.getById(batchId)?.status).isEqualTo(SuggestionStatus.ACCEPTED)
    }

    @Test
    fun accept_replaces_unlocked_planned_sessions_in_the_horizon_and_keeps_locked_and_completed_ones() = runTest {
        val batchId = suggestionDao.upsert(
            SuggestionBatchEntity(
                id = 0L,
                generatedAtMillis = clock.millis(),
                horizonStartDay = today,
                horizonEndDay = today + 6,
                phase = TrainingPhase.BASE,
                weeklyLoadTarget = 400.0,
                inputsHash = "hash",
            ),
        )
        suggestionDao.upsertSessions(listOf(suggested(batchId, day = today + 2, sessionType = SessionType.EASY_RUN)))
        val proposed = suggestionDao.getSessionsForBatch(batchId).map { it.id }

        // Existing week: an unlocked planned session (replaceable), a locked one and a completed one
        // inside the horizon, plus an unlocked one outside it.
        fun existing(
            day: Long,
            locked: Boolean = false,
            status: PlannedStatus = PlannedStatus.PLANNED,
            fromSuggestion: Boolean = true,
        ) = PlannedSession(
            id = 0L, planId = null, day = day, startMinuteOfDay = null,
            sportType = SportType.RUN_OUTDOOR, sessionType = SessionType.EASY_RUN, intensity = Intensity.LOW,
            targetDurationMin = 40, targetDistanceMeters = null, targetPaceSecPerKm = null, estimatedTrimp = 50.0,
            description = null, rationale = null, status = status, locked = locked, linkedActivityId = null,
            sourceSuggestionId = if (fromSuggestion) 999L else null,
            createdAtMillis = clock.millis(), updatedAtMillis = clock.millis(),
        )
        planRepo.upsertSession(existing(today + 1))
        planRepo.upsertSession(existing(today + 3, locked = true))
        planRepo.upsertSession(existing(today + 4, status = PlannedStatus.COMPLETED))
        // BUG-15: a session the user planned by hand is not an earlier proposal — it stays.
        planRepo.upsertSession(existing(today + 5, fromSuggestion = false))
        planRepo.upsertSession(existing(today + 10))
        assertThat(repo.countReplaceableSessions(batchId)).isEqualTo(1)

        assertThat(repo.accept(proposed)).isInstanceOf(Outcome.Ok::class.java)

        val inHorizon = planRepo.getSessions(today, today + 6)
        assertThat(inHorizon.map { it.day to it.locked }).containsExactly(
            (today + 2) to false, // the accepted suggestion
            (today + 3) to true,  // locked stays
            (today + 4) to false, // completed stays
            (today + 5) to false, // manual stays (BUG-15)
        )
        assertThat(inHorizon.single { it.day == today + 4 }.status).isEqualTo(PlannedStatus.COMPLETED)
        assertThat(planRepo.getSessions(today + 7, today + 14)).hasSize(1) // outside the horizon untouched
        assertThat(repo.countReplaceableSessions(batchId)).isEqualTo(1) // only the newly accepted one is now replaceable
    }

    @Test
    fun a_partly_reviewed_batch_stays_proposed_until_every_session_is_decided() = runTest {
        val batchId = seedBatch()
        val rows = suggestionDao.getSessionsForBatch(batchId)

        repo.accept(rows.take(1).map { it.id })

        // One session is still open, so the batch is still worth reviewing.
        assertThat(suggestionDao.getById(batchId)?.status).isEqualTo(SuggestionStatus.PROPOSED)

        repo.reject(rows.drop(1).map { it.id })
        assertThat(suggestionDao.getById(batchId)?.status).isEqualTo(SuggestionStatus.ACCEPTED)
    }

    @Test
    fun a_batch_whose_sessions_are_all_rejected_becomes_rejected() = runTest {
        val batchId = seedBatch()
        val rows = suggestionDao.getSessionsForBatch(batchId)

        repo.reject(rows.map { it.id })

        assertThat(suggestionDao.getById(batchId)?.status).isEqualTo(SuggestionStatus.REJECTED)
        assertThat(planRepo.getSessions(today, today + 7)).isEmpty()
    }

    // ---- POLISH-8: the staleness flag ----------------------------------------------------------

    @Test
    fun a_calendar_change_marks_an_open_proposed_batch_stale_until_it_is_regenerated() = runTest {
        seedLoadHistory()
        assertThat(repo.observeStale().first()).isFalse()

        repo.generate(7)
        assertThat(repo.observeStale().first()).isFalse()

        repo.markProposedStale()
        assertThat(settingsRepo.settings.first().suggestionsStale).isTrue()
        assertThat(repo.observeStale().first()).isTrue()

        // Regenerating clears it again.
        repo.generate(7)
        assertThat(repo.observeStale().first()).isFalse()
    }

    @Test
    fun marking_stale_does_nothing_when_no_batch_is_awaiting_review() = runTest {
        val batchId = seedBatch()
        repo.reject(suggestionDao.getSessionsForBatch(batchId).map { it.id })

        repo.markProposedStale()

        assertThat(settingsRepo.settings.first().suggestionsStale).isFalse()
        assertThat(repo.observeStale().first()).isFalse()
    }

    @Test
    fun sug35_structure_survives_accept() = runTest {
        val batchId = suggestionDao.upsert(
            SuggestionBatchEntity(
                id = 0L,
                generatedAtMillis = clock.millis(),
                horizonStartDay = today,
                horizonEndDay = today + 7,
                phase = TrainingPhase.PEAK,
                weeklyLoadTarget = 400.0,
                inputsHash = "hash",
            ),
        )
        val structureJson = WorkoutStructureCodec.encode(
            IntervalBuilder.buildFor(
                candidate = SuggestFixtures.candidate(SessionType.INTERVAL_RUN, today + 2, minutes = 55),
                ctx = IntervalContext(phase = TrainingPhase.BUILD, vdot = 50.0),
            )!!,
        )
        suggestionDao.upsertSessions(
            listOf(
                suggested(batchId, day = today + 2, sessionType = SessionType.INTERVAL_RUN)
                    .copy(structureJson = structureJson, targetPaceSecPerKm = 234),
            ),
        )
        val proposed = suggestionDao.getSessionsForBatch(batchId)

        assertThat(repo.accept(proposed.map { it.id })).isInstanceOf(Outcome.Ok::class.java)

        val planned = planRepo.getSessions(today, today + 7).single()
        assertThat(planned.structureJson).isEqualTo(structureJson)
        assertThat(planned.targetPaceSecPerKm).isEqualTo(234)
        assertThat(WorkoutStructureCodec.decode(planned.structureJson)?.templateId)
            .isEqualTo("RUN_1000_I")
    }

    @Test
    fun accepting_a_strength_suggestion_materialises_its_workout() = runTest {
        // P14.5 (§3.12.3): the suggestion names a built-in; `accept` seeds it and writes the row id
        // onto `planned_session.workoutId`, so the plan card can open the actual exercise list.
        val batchId = suggestionDao.upsert(
            SuggestionBatchEntity(
                id = 0L,
                generatedAtMillis = clock.millis(),
                horizonStartDay = today,
                horizonEndDay = today + 7,
                phase = TrainingPhase.IN_SEASON,
                weeklyLoadTarget = 400.0,
                inputsHash = "hash",
            ),
        )
        suggestionDao.upsertSessions(
            listOf(
                suggested(batchId, day = today + 1, sessionType = SessionType.STRENGTH_UPPER)
                    .copy(sportType = SportType.STRENGTH, workoutTemplateId = "UPPER_A"),
            ),
        )

        assertThat(repo.accept(suggestionDao.getSessionsForBatch(batchId).map { it.id }))
            .isInstanceOf(Outcome.Ok::class.java)

        val planned = planRepo.getSessions(today, today + 7).single()
        val seeded = requireNotNull(strengthRepo.getByTemplateId("UPPER_A"))
        assertThat(planned.workoutId).isEqualTo(seeded.id)
        assertThat(seeded.exercises).hasSize(6)
        // Seeding is idempotent: accepting the same template again reuses the row.
        // Nine rows since P17.1 — the six lifting templates plus the three mobility routines.
        assertThat(strengthRepo.stored.value).hasSize(9)
    }

    @Test
    fun p19_accept_uses_the_pool_workout_and_falls_back_to_the_built_in_when_it_was_deleted() = runTest {
        val mine = (strengthRepo.upsertWorkout(
            StrengthWorkout(id = 0L, name = "My upper", kind = StrengthWorkoutKind.UPPER, createdAtMillis = 0L, updatedAtMillis = 0L),
        ) as Outcome.Ok).value
        val batchId = seedBatch()
        suggestionDao.upsertSessions(
            listOf(
                suggested(batchId, day = today + 2, sessionType = SessionType.STRENGTH_UPPER)
                    .copy(sportType = SportType.STRENGTH, workoutTemplateId = null, workoutId = mine),
                suggested(batchId, day = today + 4, sessionType = SessionType.STRENGTH_UPPER)
                    .copy(sportType = SportType.STRENGTH, workoutTemplateId = "UPPER_A", workoutId = 999L),
            ),
        )
        val strength = suggestionDao.getSessionsForBatch(batchId).filter { it.sessionType == SessionType.STRENGTH_UPPER }
        assertThat(repo.accept(strength.map { it.id })).isInstanceOf(Outcome.Ok::class.java)

        val planned = planRepo.getSessions(today, today + 7).associateBy { it.day }
        assertThat(planned.getValue(today + 2).workoutId).isEqualTo(mine)
        assertThat(planned.getValue(today + 4).workoutId)
            .isEqualTo(requireNotNull(strengthRepo.getByTemplateId("UPPER_A")).id)
    }

    @Test
    fun p19_unchecking_every_workout_removes_strength_from_the_generated_week() = runTest {
        seedLoadHistory()
        profileRepo.profiles.value = SuggestFixtures.profile(preferredSportsJson = """{"RUN":2,"STRENGTH":3}""")
        val checked = (repo.generate(7) as Outcome.Ok).value
        strengthRepo.stored.value.keys.forEach { strengthRepo.setUseInSuggestions(it, false) }
        val unchecked = (repo.generate(7) as Outcome.Ok).value

        assertThat(unchecked.inputsHash).isNotEqualTo(checked.inputsHash)
        assertThat(repo.observeSessions(unchecked.id).first().map { it.sessionType })
            .containsNoneOf(SessionType.STRENGTH_FULL, SessionType.STRENGTH_UPPER, SessionType.STRENGTH_LOWER)
    }

    @Test
    fun p19_an_old_pr_alone_gets_a_benchmark_run_and_a_recent_effort_does_not() = runTest {
        seedLoadHistory()
        val bests = FakeRunningBestRepository()
        val withBests = RoomSuggestionRepository(
            suggestionDao = suggestionDao,
            planRepo = planRepo,
            goalRepo = goalRepo,
            profileRepo = profileRepo,
            calendarRepo = calendarRepo,
            loadRepo = loadRepo,
            activityRepo = activityRepo,
            settingsRepo = settingsRepo,
            engine = SuggestionEngine(clock),
            clock = clock,
            runningBestRepo = bests,
            ioDispatcher = Dispatchers.Unconfined,
        )
        goalRepo.upsert(SuggestFixtures.raceGoal(today + 108).copy(id = 0L))
        bests.byActivity[1L] = listOf(best(id = 1L, day = today - 988, timeSec = 1214))

        val stale = (withBests.generate(7) as Outcome.Ok).value
        assertThat(withBests.observeSessions(stale.id).first().map { it.sessionType }).contains(SessionType.TIME_TRIAL)

        bests.byActivity[2L] = listOf(best(id = 2L, day = today - 9, timeSec = 1318))
        val fresh = (withBests.generate(7) as Outcome.Ok).value
        assertThat(withBests.observeSessions(fresh.id).first().map { it.sessionType })
            .doesNotContain(SessionType.TIME_TRIAL)
    }

    private fun best(id: Long, day: Long, timeSec: Int) = RunningBest(
        id = id,
        distanceMeters = 5000.0,
        timeSec = timeSec,
        activityId = id,
        day = day,
        method = "FULL_ACTIVITY",
        isEstimated = false,
        paceSecPerKm = timeSec / 5,
        createdAtMillis = 0L,
    )

    // ---- helpers -------------------------------------------------------------------------------

    /** A `PROPOSED` batch with two open sessions — the shape the review screen works on. */
    private suspend fun seedBatch(): Long {
        val batchId = suggestionDao.upsert(
            SuggestionBatchEntity(
                id = 0L,
                generatedAtMillis = clock.millis(),
                horizonStartDay = today,
                horizonEndDay = today + 7,
                phase = TrainingPhase.BASE,
                weeklyLoadTarget = 400.0,
                inputsHash = "hash",
            ),
        )
        suggestionDao.upsertSessions(
            listOf(
                suggested(batchId, day = today + 1, sessionType = SessionType.EASY_RUN),
                suggested(batchId, day = today + 3, sessionType = SessionType.TEMPO_RUN),
            ),
        )
        return batchId
    }

    /** 42 days at 60 AU/day so `Periodization` has a CTL to build a real weekly budget from. */
    private fun seedLoadHistory() {
        (1L..42L).forEach { back ->
            val day = today - back
            loadRepo.rows[day] = SuggestFixtures.load(day = day, trimp = 60.0, atl = 60.0, ctl = 55.0)
        }
    }

    private fun suggested(
        batchId: Long,
        day: Long,
        sessionType: SessionType,
    ): SuggestedSessionEntity = SuggestedSessionEntity(
        id = 0L,
        batchId = batchId,
        day = day,
        sportType = SportType.RUN_OUTDOOR,
        sessionType = sessionType,
        intensity = Intensity.LOW,
        targetDurationMin = 45,
        targetDistanceMeters = null,
        estimatedTrimp = 54.0,
        score = 0.8,
        rationaleJson = """[{"ruleId":"PHASE_BASE","text":"Base phase: easy volume."}]""",
        status = SuggestionStatus.PROPOSED,
    )

    /** In-memory [StrengthRepository] — only what [StrengthWorkoutSeeder] touches is modelled. */
    private class InMemoryStrengthRepository : StrengthRepository {
        val stored = MutableStateFlow<Map<Long, StrengthWorkout>>(emptyMap())
        private var nextId = 1L

        val progress: MutableMap<String, ExerciseProgress> = mutableMapOf()

        override fun observeAll(): Flow<List<StrengthWorkout>> =
            stored.map { all -> all.values.sortedBy { it.name } }

        override fun observe(id: Long): Flow<StrengthWorkout?> = stored.map { it[id] }

        override suspend fun getById(id: Long): StrengthWorkout? = stored.value[id]

        override suspend fun getByTemplateId(templateId: String): StrengthWorkout? =
            stored.value.values.firstOrNull { it.templateId == templateId }

        override suspend fun upsertWorkout(workout: StrengthWorkout): Outcome<Long> {
            val id = if (workout.id == 0L) nextId++ else workout.id
            stored.value = stored.value + (id to workout.copy(id = id))
            return Outcome.Ok(id)
        }

        override suspend fun deleteWorkout(id: Long): Outcome<Unit> {
            stored.value = stored.value - id
            return Outcome.Ok(Unit)
        }

        override suspend fun insertSetLogs(logs: List<StrengthSetLog>): Outcome<Unit> = Outcome.Ok(Unit)

        override fun observeSetLogsByDay(day: Long): Flow<List<StrengthSetLog>> =
            MutableStateFlow(emptyList())

        override suspend fun getSetLogsOfPlannedSession(plannedSessionId: Long): List<StrengthSetLog> =
            emptyList()

        override suspend fun deleteSetLog(id: Long): Outcome<Unit> = Outcome.Ok(Unit)

        override suspend fun getRecentSetLogs(exerciseId: String, limit: Int): List<StrengthSetLog> =
            emptyList()

        override suspend fun saveSetLogs(
            logs: List<StrengthSetLog>,
        ): Outcome<Map<String, ExerciseProgress>> = Outcome.Ok(emptyMap())

        override fun observeProgress(exerciseId: String): Flow<ExerciseProgress?> =
            MutableStateFlow(progress[exerciseId])

        override suspend fun getProgress(exerciseId: String): ExerciseProgress? = progress[exerciseId]

        override suspend fun getAllProgress(): List<ExerciseProgress> = progress.values.toList()

        override suspend fun upsertProgress(progress: ExerciseProgress): Outcome<Unit> {
            this.progress[progress.exerciseId] = progress
            return Outcome.Ok(Unit)
        }

        override suspend fun prescriptionFor(
            exercise: Exercise,
            bodyWeightKg: Double,
        ): ExercisePrescription =
            ProgressionEngine.prescription(exercise, progress[exercise.id], bodyWeightKg)
    }
}
