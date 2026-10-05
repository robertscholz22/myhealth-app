package com.myhealth.di

import com.myhealth.data.backup.BackupContentSource
import com.myhealth.data.backup.BackupService
import com.myhealth.data.db.MyHealthDatabase
import com.myhealth.data.fit.GarminCsvParser
import com.myhealth.data.off.OffClient
import com.myhealth.data.off.OffThrottle
import com.myhealth.data.repository.ActivityIngestor
import com.myhealth.data.repository.ImportContentSource
import com.myhealth.data.repository.ImportService
import com.myhealth.data.repository.LoadRecomputeService
import com.myhealth.data.repository.RoomActivityRepository
import com.myhealth.data.repository.RoomBodyRepository
import com.myhealth.data.repository.RoomCalendarRepository
import com.myhealth.data.repository.RoomCycleRepository
import com.myhealth.data.repository.RoomGoalRepository
import com.myhealth.data.repository.RoomHealthRepository
import com.myhealth.data.repository.RoomImportRepository
import com.myhealth.data.repository.RoomIngredientRepository
import com.myhealth.data.repository.RoomLoadRepository
import com.myhealth.data.repository.RoomMealRepository
import com.myhealth.data.repository.RoomNutritionRepository
import com.myhealth.data.repository.RoomPlanRepository
import com.myhealth.data.repository.RoomProfileRepository
import com.myhealth.data.repository.RoomRideBestRepository
import com.myhealth.data.repository.RoomRunningBestRepository
import com.myhealth.data.repository.RoomStrengthRepository
import com.myhealth.data.repository.RoomSuggestionRepository
import com.myhealth.data.repository.RoomSyncStateRepository
import com.myhealth.data.repository.StrengthWorkoutSeeder
import com.myhealth.data.repository.TransactionRunner
import com.myhealth.data.time.PlatformClock
import com.myhealth.data.time.timeZone
import com.myhealth.data.time.toKotlinClock
import com.myhealth.data.time.todayEpochDay
import com.myhealth.domain.engine.calendar.EventActivityLinker
import com.myhealth.domain.engine.nutrition.NutritionTargetEngine
import com.myhealth.domain.engine.strength.EquipmentSetCodec
import com.myhealth.domain.engine.strength.ProgressionDefaults
import com.myhealth.domain.engine.suggest.SuggestionEngine
import com.myhealth.domain.repository.ActivityImporter
import com.myhealth.domain.repository.ActivityRepository
import com.myhealth.domain.repository.BackupRepository
import com.myhealth.domain.repository.BodyRepository
import com.myhealth.domain.repository.CalendarRepository
import com.myhealth.domain.repository.CycleRepository
import com.myhealth.domain.repository.GoalRepository
import com.myhealth.domain.repository.HealthRepository
import com.myhealth.domain.repository.ImportRepository
import com.myhealth.domain.repository.IngredientRepository
import com.myhealth.domain.repository.LoadRepository
import com.myhealth.domain.repository.MealRepository
import com.myhealth.domain.repository.NutritionRepository
import com.myhealth.domain.repository.PlanRepository
import com.myhealth.domain.repository.ProfileRepository
import com.myhealth.domain.repository.RideBestRepository
import com.myhealth.domain.repository.RunningBestRepository
import com.myhealth.domain.repository.StrengthRepository
import com.myhealth.domain.repository.SuggestionRepository
import com.myhealth.domain.repository.SyncStateRepository
import com.myhealth.sync.TargetRecomputeService
import com.myhealth.ui.camera.DraftStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/**
 * The platform-neutral half of the dependency container (P21): every repository, engine and
 * service the two apps share, wired exactly as `AppGraph` wired them up to 0.9.3. A plain class
 * with `by lazy` properties — no annotation processors, no reflection (§1.3).
 *
 * Each platform graph (`AppGraph` on Android, `IosAppGraph` on iOS) supplies what only it can
 * open: the database, the settings file, document access, Health Connect / HealthKit and the
 * background scheduler.
 */
abstract class CoreGraph : UiGraph {

    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    abstract val db: MyHealthDatabase

    /** One Room transaction around a block (`withTransaction` on Android). */
    protected abstract val transactionRunner: TransactionRunner

    /** Reads `.fit`/`.csv`/`.zip` documents handed to the import (SAF on Android). */
    protected abstract val importContent: ImportContentSource

    /** Reads and writes backup documents (SAF on Android). */
    protected abstract val backupContent: BackupContentSource

    /** Written into every backup's header. */
    protected abstract val appVersion: String

    /** App-start work, run once per process by the platform shell before the first screen. */
    fun start() {
        // BUG-19: shrink oversized import error lists (≤ 0.9.1) before any screen reads them.
        appScope.launch { importRepo.repairOversizedErrors() }

        // Schedule (and re-schedule on change) the periodic sync at the user's configured
        // interval (P2.7) — an app-lifetime subscription, not a one-shot background task.
        settings.settings
            .map { it.syncIntervalHours }
            .distinctUntilChanged()
            .onEach { hours -> syncScheduler.schedulePeriodic(hours) }
            .launchIn(appScope)

        // Nutrition targets for [today - 1, today + 7], daily at 03:00 local (P4.12).
        syncScheduler.scheduleDailyTargetRecompute()

        // TRIMP / ACWR / recovery for the last 28 days, daily at 04:00 local (P5.5).
        syncScheduler.scheduleDailyLoadRecompute()

        // BUG-18: one recompute per app start. It runs the EWMAs over the whole history and repairs
        // any stored row that disagrees (zero-seeded rows from ≤ 0.9.0), so a training plan generated
        // right after an update already sees the right CTL. Cheap: one pass over the daily sums.
        syncScheduler.requestLoadRecompute(clock.todayEpochDay())
    }

    override val profileRepo: ProfileRepository by lazy { RoomProfileRepository(db.profileDao()) }

    override val bodyRepo: BodyRepository by lazy { RoomBodyRepository(db.bodyDao(), clock) }

    override val syncStateRepo: SyncStateRepository by lazy { RoomSyncStateRepository(db.syncStateDao()) }

    override val healthRepo: HealthRepository by lazy { RoomHealthRepository(db.healthDao(), db.sleepDao()) }

    /** `ingredient` search/CRUD, archive-vs-delete on removal (§2.2.5, P4.2). */
    override val ingredientRepo: IngredientRepository by lazy {
        RoomIngredientRepository(db.ingredientDao(), db.mealDao(), clock)
    }

    /** `meal_log`/`meal_template` CRUD; the snapshot-on-log rule of §2.2.5 lives in its writer
     * (P4.4/P4.5). */
    override val mealRepo: MealRepository by lazy {
        RoomMealRepository(db.mealDao(), db.ingredientDao(), clock)
    }

    /**
     * `cycle_entry` + the P11.1 forecast engine. `isTrackingEnabled` ors the settings flag with
     * `profile.sex == FEMALE`, and it is the only gate the suggestion and nutrition repositories
     * consult before they read any cycle data.
     */
    override val cycleRepo: CycleRepository by lazy {
        RoomCycleRepository(
            cycleDao = db.cycleDao(),
            profileRepo = profileRepo,
            settingsRepo = settings,
            clock = clock,
        )
    }

    /** The nutrition target engine (§3.1, P4.11) — stateless; only the clock's zone is used. */
    val nutritionTargetEngine: NutritionTargetEngine by lazy { NutritionTargetEngine(clock.timeZone) }

    /**
     * `nutrition_target_snapshot` + `water_log`. `ensureTarget` gathers its inputs from the
     * profile/body/health/activity/plan/calendar repositories and recomputes only when the
     * `inputsHash` changed (P4.12).
     */
    override val nutritionRepo: NutritionRepository by lazy {
        RoomNutritionRepository(
            nutritionDao = db.nutritionDao(),
            profileRepo = profileRepo,
            bodyRepo = bodyRepo,
            healthRepo = healthRepo,
            activityRepo = activityRepo,
            planRepo = planRepo,
            calendarRepo = calendarRepo,
            engine = nutritionTargetEngine,
            clock = clock,
            cycleRepo = cycleRepo,
        )
    }

    /** De-dup + merge ingestion of activities (§2.4, P2.5) — one Room transaction per arrival. */
    val ingestor: ActivityIngestor by lazy { ActivityIngestor(db.activityDao(), transactionRunner, clock) }

    override val activityRepo: ActivityRepository by lazy {
        RoomActivityRepository(db.activityDao(), ingestor, clock)
    }

    override val runningBestRepo: RunningBestRepository by lazy { RoomRunningBestRepository(db.runningBestDao()) }

    /** `ride_best` (P12); read by the Bike screen and the FTP estimate. */
    override val rideBestRepo: RideBestRepository by lazy { RoomRideBestRepository(db.rideBestDao()) }

    /**
     * The body weight `ProgressionEngine`'s initial estimate is built on (P16.1/P16.2): the latest
     * measurement within a year, falling back to 75 kg when there is none. Shared by
     * [strengthRepo]'s own writer and every UI caller of `StrengthRepository.prescriptionFor`
     * (`ExercisesViewModel`, `TrainingViewModel`) so the two never disagree on "today's" weight.
     */
    override val currentBodyWeightKg: suspend () -> Double = {
        bodyRepo.latestWeight(ProgressionDefaults.BODY_WEIGHT_MAX_AGE_DAYS)?.weightKg
            ?: ProgressionDefaults.FALLBACK_BODY_WEIGHT_KG
    }

    /**
     * The three strength tables (§2.2.7, P14): workouts, their rows and the per-set log — plus
     * `exercise_progress` (P16.1).
     */
    override val strengthRepo: StrengthRepository by lazy {
        RoomStrengthRepository(
            dao = db.strengthDao(),
            bodyWeightKg = currentBodyWeightKg,
            clock = clock,
        )
    }

    /**
     * Materialises the six built-in workouts of `StrengthTemplates` (§3.12.3, P14.4), rewritten
     * against the profile's "my equipment" set (P16.1). Idempotent on `templateId`, so every
     * caller may simply seed before it reads.
     */
    override val strengthWorkoutSeeder: StrengthWorkoutSeeder by lazy {
        StrengthWorkoutSeeder(strengthRepo, clock) {
            EquipmentSetCodec.decode(profileRepo.getProfile()?.availableEquipmentJson)
        }
    }

    /**
     * `daily_load` (§2.2.6, P5.5). `RoomLoadRepository.recomputeFrom` is wired to
     * [loadRecomputeService] through a lambda rather than a direct reference, since that service
     * depends on this repository in turn — a direct reference would be a circular `by lazy`.
     */
    override val loadRepo: LoadRepository by lazy {
        RoomLoadRepository(db.loadDao()) { day -> loadRecomputeService.recompute(day) }
    }

    /** Nutrition targets for a window of days (P4.12); run by the platform scheduler. */
    val targetRecomputeService: TargetRecomputeService by lazy { TargetRecomputeService(nutritionRepo, clock) }

    /** TRIMP / ATL-CTL-ACWR / recovery / running-PR recompute pipeline (§3.2–§3.4, P5.5). */
    val loadRecomputeService: LoadRecomputeService by lazy {
        LoadRecomputeService(
            activityDao = db.activityDao(),
            activityRepo = activityRepo,
            loadRepo = loadRepo,
            runningBestRepo = runningBestRepo,
            rideBestRepo = rideBestRepo,
            profileRepo = profileRepo,
            healthRepo = healthRepo,
            settingsRepo = settings,
            clock = clock,
            planRepo = planRepo,
        )
    }

    /** `goal`; enforces the single-primary-goal rule of P6.1. */
    override val goalRepo: GoalRepository by lazy { RoomGoalRepository(db.goalDao(), clock) }

    /** `training_plan` + `planned_session`; enforces the single-`ACTIVE`-plan rule (P3.2). */
    override val planRepo: PlanRepository by lazy { RoomPlanRepository(db.planDao(), clock) }

    /** The `CalendarDay` aggregate of §2.3 plus event/override CRUD and linking (P3.2). */
    override val calendarRepo: CalendarRepository by lazy {
        RoomCalendarRepository(
            eventDao = db.eventDao(),
            planDao = db.planDao(),
            activityDao = db.activityDao(),
            mealDao = db.mealDao(),
            nutritionDao = db.nutritionDao(),
            loadDao = db.loadDao(),
            sleepDao = db.sleepDao(),
            activityRepo = activityRepo,
            clock = clock,
            onPlanChanged = { suggestionRepo.markProposedStale() },
        )
    }

    /** Event ↔ activity auto-link engine (P3.3) — stateless, the zone comes from the caller. */
    val linker: EventActivityLinker by lazy { EventActivityLinker }

    /** The training-suggestion engine (§3.5, P6.4) — the clock only stamps the batch. */
    val suggestionEngine: SuggestionEngine by lazy { SuggestionEngine(clock.toKotlinClock()) }

    /**
     * `suggestion_batch` + `suggested_session` (P6.5): gathers the §3.5.1 inputs, runs
     * [suggestionEngine], and copies accepted suggestions into `planned_session`. Accepting
     * changes a day's nutrition target, hence the `requestTargetRecompute` hook.
     */
    override val suggestionRepo: SuggestionRepository by lazy {
        RoomSuggestionRepository(
            suggestionDao = db.suggestionDao(),
            planRepo = planRepo,
            goalRepo = goalRepo,
            profileRepo = profileRepo,
            calendarRepo = calendarRepo,
            loadRepo = loadRepo,
            activityRepo = activityRepo,
            settingsRepo = settings,
            engine = suggestionEngine,
            clock = clock,
            cycleRepo = cycleRepo,
            runningBestRepo = runningBestRepo,
            rideBestRepo = rideBestRepo,
            healthRepo = healthRepo,
            strengthRepo = strengthRepo,
            strengthSeeder = strengthWorkoutSeeder,
            onPlanChanged = { syncScheduler.requestTargetRecompute() },
        )
    }

    // ---- FIT / CSV / ZIP import (P7.5) --------------------------------------------------

    /** `import_record` (§2.2.6): the file-hash guard against importing the same export twice. */
    override val importRepo: ImportRepository by lazy {
        RoomImportRepository(
            importDao = db.importDao(),
            activityDao = db.activityDao(),
            ingestor = ingestor,
            onUndone = { day -> syncScheduler.requestLoadRecompute(day) },
        )
    }

    /**
     * The import pipeline. It feeds [activityRepo]`.ingest` — the same seam the health sync uses —
     * so a FIT file merges with an already-synced activity under the §2.4 precedence, and it asks
     * for a load recompute from the earliest day it touched.
     */
    override val importService: ActivityImporter by lazy {
        ImportService(
            content = importContent,
            activityRepo = activityRepo,
            importRepo = importRepo,
            csvParser = GarminCsvParser(clock.timeZone),
            clock = clock,
            onImported = { day -> syncScheduler.requestLoadRecompute(day) },
        )
    }

    /**
     * A `.fit`/`.csv`/`.zip` handed to the app from the share sheet or a file manager, waiting for
     * the Import screen to pick it up (P7.6). Set by the platform shell, cleared by the screen
     * once it has started the import.
     */
    override val pendingImportUri: MutableStateFlow<String?> = MutableStateFlow(null)

    // ---- JSON backup (P8.4) --------------------------------------------------------------

    /**
     * Whole-database JSON export/import. It deliberately bypasses the repositories: a backup is
     * about the rows as they are stored, not about the domain view of them, and REPLACE has to be
     * able to write ids the domain models do not even expose.
     */
    override val backupRepo: BackupRepository by lazy {
        BackupService(
            dao = db.backupDao(),
            transaction = { block -> transactionRunner { block() } },
            content = backupContent,
            appVersion = appVersion,
            clock = clock,
            onImported = { syncScheduler.requestLoadRecompute(clock.todayEpochDay()) },
        )
    }

    // ---- scanning: Open Food Facts (P4.10) -------------------------------------------------

    /** The one-slot Scan → OcrReview → IngredientEdit hand-off (P4.8). */
    override val draftStore: DraftStore by lazy { DraftStore() }

    /** Open Food Facts product reads, throttled to 15/minute off the app [clock] (P4.10). */
    val offClient: OffClient by lazy { OffClient(settings, OffThrottle(clock)) }

    /** Barcode → prefilled draft, shared by the scan path and the editor's manual lookup (P4.10). */
    override val offLookup: OffLookup by lazy { OffProductLookup(offClient) }
}
