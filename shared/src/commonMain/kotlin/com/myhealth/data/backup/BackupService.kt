package com.myhealth.data.backup

import com.myhealth.data.db.dao.BackupDao
import com.myhealth.data.time.PlatformClock
import com.myhealth.domain.repository.BackupMode
import com.myhealth.domain.repository.BackupRepository
import com.myhealth.domain.repository.BackupSummary
import com.myhealth.domain.util.Outcome
import com.myhealth.domain.util.runCatchingApp
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext

/**
 * JSON backup export/import (PLAN P8.4).
 *
 * Export reads every table through [BackupDao] and streams one [BackupFile] into the document the
 * user picked. Import decodes it, refuses a newer schema, and applies it inside **one**
 * `withTransaction` block so a failure half-way leaves the database exactly as it was:
 *
 * - [BackupMode.REPLACE] empties every table child-first and re-inserts the backup's rows with
 *   their original ids, so the restored database is byte-identical to the exported one.
 * - [BackupMode.MERGE] inserts only rows whose natural key is not present yet — see [BackupMerge].
 *
 * Neither mode touches the DataStore settings or any credential store.
 */
class BackupService(
    private val dao: BackupDao,
    /** Runs the whole import atomically; in the app this is `db.withTransaction { … }`. */
    private val transaction: suspend (suspend () -> Int) -> Int,
    private val content: BackupContentSource,
    private val appVersion: String,
    private val clock: PlatformClock,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    /**
     * BUG-18: runs after a successful import. The app requests a load recompute here — restored
     * `daily_load` rows are whatever the exporting version computed, including zero-seeded ones.
     */
    private val onImported: () -> Unit = {},
) : BackupRepository {

    override suspend fun export(uri: String): Outcome<BackupSummary> = withContext(ioDispatcher) {
        runCatchingApp {
            val file = readAll().copy(exportedAtMillis = clock.millis(), appVersion = appVersion)
            BackupSerializer.encode(file, content.openOutput(uri))
            file.summaryOf(rowsWritten = file.totalRows)
        }
    }

    override suspend fun import(uri: String, mode: BackupMode): Outcome<BackupSummary> =
        withContext(ioDispatcher) {
            val decoded = runCatchingApp { BackupSerializer.decode(content.openInput(uri)) }
            val file = when (decoded) {
                is Outcome.Err -> return@withContext decoded
                is Outcome.Ok -> when (val inner = decoded.value) {
                    is Outcome.Err -> return@withContext inner
                    is Outcome.Ok -> inner.value
                }
            }
            runCatchingApp {
                val written = transaction {
                    when (mode) {
                        BackupMode.REPLACE -> replaceAll(file)
                        BackupMode.MERGE -> BackupMerge(dao).merge(file, readAll())
                    }
                }
                file.summaryOf(rowsWritten = written)
            }.also { if (it is Outcome.Ok) onImported() }
        }

    /** Every table, in one snapshot. Also the "existing rows" side of a MERGE. */
    suspend fun readAll(): BackupFile = BackupFile(
        schemaVersion = BackupFile.CURRENT_SCHEMA_VERSION,
        profile = dao.allProfile(),
        bodyMeasurement = dao.allBodyMeasurement(),
        activitySession = dao.allActivitySession(),
        activitySourceRecord = dao.allActivitySourceRecord(),
        activityStream = dao.allActivityStream(),
        activityLap = dao.allActivityLap(),
        dailyHealthSummary = dao.allDailyHealthSummary(),
        sleepSession = dao.allSleepSession(),
        syncState = dao.allSyncState(),
        calendarEvent = dao.allCalendarEvent(),
        eventOverride = dao.allEventOverride(),
        trainingPlan = dao.allTrainingPlan(),
        plannedSession = dao.allPlannedSession(),
        suggestionBatch = dao.allSuggestionBatch(),
        suggestedSession = dao.allSuggestedSession(),
        goal = dao.allGoal(),
        ingredient = dao.allIngredient(),
        mealTemplate = dao.allMealTemplate(),
        mealTemplateItem = dao.allMealTemplateItem(),
        mealLog = dao.allMealLog(),
        mealLogItem = dao.allMealLogItem(),
        nutritionTargetSnapshot = dao.allNutritionTargetSnapshot(),
        waterLog = dao.allWaterLog(),
        dailyLoad = dao.allDailyLoad(),
        runningBest = dao.allRunningBest(),
        rideBest = dao.allRideBest(),
        importRecord = dao.allImportRecord(),
        cycleEntry = dao.allCycleEntry(),
        strengthWorkout = dao.allStrengthWorkout(),
        strengthWorkoutExercise = dao.allStrengthWorkoutExercise(),
        strengthSetLog = dao.allStrengthSetLog(),
        exerciseProgress = dao.allExerciseProgress(),
    )

    /** Child-first wipe, then parent-first insert with the backup's own ids. */
    private suspend fun replaceAll(file: BackupFile): Int {
        deleteAllChildFirst()
        return insertAllParentFirst(file)
    }

    private suspend fun deleteAllChildFirst() {
        // P14: the set log hangs off `planned_session` and `activity_session`, the exercise rows
        // off `strength_workout` — all three go before any of their parents.
        dao.deleteExerciseProgress()
        dao.deleteStrengthSetLog()
        dao.deleteStrengthWorkoutExercise()
        dao.deleteCycleEntry()
        dao.deleteImportRecord()
        // `ride_best` and `running_best` are children of `activity_session`, so they go first.
        dao.deleteRideBest()
        dao.deleteRunningBest()
        dao.deleteDailyLoad()
        dao.deleteWaterLog()
        dao.deleteNutritionTargetSnapshot()
        dao.deleteMealLogItem()
        dao.deleteMealLog()
        dao.deleteMealTemplateItem()
        dao.deleteMealTemplate()
        dao.deleteGoal()
        dao.deleteSuggestedSession()
        dao.deleteSuggestionBatch()
        dao.deletePlannedSession()
        // After `planned_session`, which references it (`SET_NULL`).
        dao.deleteStrengthWorkout()
        dao.deleteTrainingPlan()
        dao.deleteEventOverride()
        dao.deleteCalendarEvent()
        dao.deleteSyncState()
        dao.deleteSleepSession()
        dao.deleteDailyHealthSummary()
        dao.deleteBodyMeasurement()
        dao.deleteActivityLap()
        dao.deleteActivityStream()
        dao.deleteActivitySourceRecord()
        dao.deleteActivitySession()
        dao.deleteIngredient()
        dao.deleteProfile()
    }

    private suspend fun insertAllParentFirst(file: BackupFile): Int {
        var written = 0
        written += dao.insertProfile(file.profile).size
        written += dao.insertIngredient(file.ingredient).size
        written += dao.insertActivitySession(file.activitySession).size
        written += dao.insertActivitySourceRecord(file.activitySourceRecord).size
        written += dao.insertActivityStream(file.activityStream).size
        written += dao.insertActivityLap(file.activityLap).size
        written += dao.insertBodyMeasurement(file.bodyMeasurement).size
        written += dao.insertDailyHealthSummary(file.dailyHealthSummary).size
        written += dao.insertSleepSession(file.sleepSession).size
        written += dao.insertSyncState(file.syncState).size
        written += dao.insertCalendarEvent(file.calendarEvent).size
        written += dao.insertEventOverride(file.eventOverride).size
        written += dao.insertTrainingPlan(file.trainingPlan).size
        // Before `planned_session`, whose `workoutId` points at it.
        written += dao.insertStrengthWorkout(file.strengthWorkout).size
        written += dao.insertStrengthWorkoutExercise(file.strengthWorkoutExercise).size
        written += dao.insertPlannedSession(file.plannedSession).size
        written += dao.insertSuggestionBatch(file.suggestionBatch).size
        written += dao.insertSuggestedSession(file.suggestedSession).size
        written += dao.insertGoal(file.goal).size
        written += dao.insertMealTemplate(file.mealTemplate).size
        written += dao.insertMealTemplateItem(file.mealTemplateItem).size
        written += dao.insertMealLog(file.mealLog).size
        written += dao.insertMealLogItem(file.mealLogItem).size
        written += dao.insertNutritionTargetSnapshot(file.nutritionTargetSnapshot).size
        written += dao.insertWaterLog(file.waterLog).size
        written += dao.insertDailyLoad(file.dailyLoad).size
        written += dao.insertRunningBest(file.runningBest).size
        written += dao.insertRideBest(file.rideBest).size
        written += dao.insertImportRecord(file.importRecord).size
        written += dao.insertCycleEntry(file.cycleEntry).size
        written += dao.insertStrengthSetLog(file.strengthSetLog).size
        // P16.1: `exercise_progress` references nothing — the catalog is code, not a table.
        written += dao.insertExerciseProgress(file.exerciseProgress).size
        return written
    }
}

/** The counts a screen reports for a finished export or import. */
internal fun BackupFile.summaryOf(rowsWritten: Int): BackupSummary = BackupSummary(
    schemaVersion = schemaVersion,
    exportedAtMillis = exportedAtMillis,
    appVersion = appVersion,
    rowsPerTable = rowsPerTable(),
    rowsWritten = rowsWritten,
)
