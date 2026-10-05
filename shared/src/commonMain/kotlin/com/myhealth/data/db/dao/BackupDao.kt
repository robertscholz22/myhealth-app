package com.myhealth.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.myhealth.data.db.entity.ActivityLapEntity
import com.myhealth.data.db.entity.ActivitySessionEntity
import com.myhealth.data.db.entity.ActivitySourceRecordEntity
import com.myhealth.data.db.entity.ActivityStreamEntity
import com.myhealth.data.db.entity.BodyMeasurementEntity
import com.myhealth.data.db.entity.CalendarEventEntity
import com.myhealth.data.db.entity.CycleEntryEntity
import com.myhealth.data.db.entity.DailyHealthSummaryEntity
import com.myhealth.data.db.entity.DailyLoadEntity
import com.myhealth.data.db.entity.EventOverrideEntity
import com.myhealth.data.db.entity.ExerciseProgressEntity
import com.myhealth.data.db.entity.GoalEntity
import com.myhealth.data.db.entity.ImportRecordEntity
import com.myhealth.data.db.entity.IngredientEntity
import com.myhealth.data.db.entity.MealLogEntity
import com.myhealth.data.db.entity.MealLogItemEntity
import com.myhealth.data.db.entity.MealTemplateEntity
import com.myhealth.data.db.entity.MealTemplateItemEntity
import com.myhealth.data.db.entity.NutritionTargetSnapshotEntity
import com.myhealth.data.db.entity.PlannedSessionEntity
import com.myhealth.data.db.entity.ProfileEntity
import com.myhealth.data.db.entity.RideBestEntity
import com.myhealth.data.db.entity.RunningBestEntity
import com.myhealth.data.db.entity.SleepSessionEntity
import com.myhealth.data.db.entity.StrengthSetLogEntity
import com.myhealth.data.db.entity.StrengthWorkoutEntity
import com.myhealth.data.db.entity.StrengthWorkoutExerciseEntity
import com.myhealth.data.db.entity.SuggestedSessionEntity
import com.myhealth.data.db.entity.SuggestionBatchEntity
import com.myhealth.data.db.entity.SyncStateEntity
import com.myhealth.data.db.entity.TrainingPlanEntity
import com.myhealth.data.db.entity.WaterLogEntity

/**
 * Whole-table access for the JSON backup (PLAN P8.4) — the one DAO that is allowed to speak in
 * "every row of every table", because that is exactly what an export is.
 *
 * Three families, one per table: `all…` reads it, `insert…` writes rows verbatim (ids included,
 * `REPLACE` on conflict so a restore is idempotent), and `delete…` empties it for a REPLACE
 * import. `ingredient_fts` has no methods: it is an index over `ingredient`, maintained by the
 * content-sync triggers that the inserts and deletes here fire.
 *
 * The delete order in `BackupService` is child-before-parent; the insert order is the reverse.
 */
@Dao
interface BackupDao {

    @Query("SELECT * FROM profile")
    suspend fun allProfile(): List<ProfileEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProfile(rows: List<ProfileEntity>): List<Long>

    @Query("DELETE FROM profile")
    suspend fun deleteProfile()

    @Query("SELECT * FROM body_measurement")
    suspend fun allBodyMeasurement(): List<BodyMeasurementEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBodyMeasurement(rows: List<BodyMeasurementEntity>): List<Long>

    @Query("DELETE FROM body_measurement")
    suspend fun deleteBodyMeasurement()

    @Query("SELECT * FROM activity_session")
    suspend fun allActivitySession(): List<ActivitySessionEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertActivitySession(rows: List<ActivitySessionEntity>): List<Long>

    @Query("DELETE FROM activity_session")
    suspend fun deleteActivitySession()

    @Query("SELECT * FROM activity_source_record")
    suspend fun allActivitySourceRecord(): List<ActivitySourceRecordEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertActivitySourceRecord(rows: List<ActivitySourceRecordEntity>): List<Long>

    @Query("DELETE FROM activity_source_record")
    suspend fun deleteActivitySourceRecord()

    @Query("SELECT * FROM activity_stream")
    suspend fun allActivityStream(): List<ActivityStreamEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertActivityStream(rows: List<ActivityStreamEntity>): List<Long>

    @Query("DELETE FROM activity_stream")
    suspend fun deleteActivityStream()

    @Query("SELECT * FROM activity_lap")
    suspend fun allActivityLap(): List<ActivityLapEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertActivityLap(rows: List<ActivityLapEntity>): List<Long>

    @Query("DELETE FROM activity_lap")
    suspend fun deleteActivityLap()

    @Query("SELECT * FROM daily_health_summary")
    suspend fun allDailyHealthSummary(): List<DailyHealthSummaryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDailyHealthSummary(rows: List<DailyHealthSummaryEntity>): List<Long>

    @Query("DELETE FROM daily_health_summary")
    suspend fun deleteDailyHealthSummary()

    @Query("SELECT * FROM sleep_session")
    suspend fun allSleepSession(): List<SleepSessionEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSleepSession(rows: List<SleepSessionEntity>): List<Long>

    @Query("DELETE FROM sleep_session")
    suspend fun deleteSleepSession()

    @Query("SELECT * FROM sync_state")
    suspend fun allSyncState(): List<SyncStateEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSyncState(rows: List<SyncStateEntity>): List<Long>

    @Query("DELETE FROM sync_state")
    suspend fun deleteSyncState()

    @Query("SELECT * FROM calendar_event")
    suspend fun allCalendarEvent(): List<CalendarEventEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCalendarEvent(rows: List<CalendarEventEntity>): List<Long>

    @Query("DELETE FROM calendar_event")
    suspend fun deleteCalendarEvent()

    @Query("SELECT * FROM event_override")
    suspend fun allEventOverride(): List<EventOverrideEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEventOverride(rows: List<EventOverrideEntity>): List<Long>

    @Query("DELETE FROM event_override")
    suspend fun deleteEventOverride()

    @Query("SELECT * FROM training_plan")
    suspend fun allTrainingPlan(): List<TrainingPlanEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTrainingPlan(rows: List<TrainingPlanEntity>): List<Long>

    @Query("DELETE FROM training_plan")
    suspend fun deleteTrainingPlan()

    @Query("SELECT * FROM planned_session")
    suspend fun allPlannedSession(): List<PlannedSessionEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlannedSession(rows: List<PlannedSessionEntity>): List<Long>

    @Query("DELETE FROM planned_session")
    suspend fun deletePlannedSession()

    @Query("SELECT * FROM suggestion_batch")
    suspend fun allSuggestionBatch(): List<SuggestionBatchEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSuggestionBatch(rows: List<SuggestionBatchEntity>): List<Long>

    @Query("DELETE FROM suggestion_batch")
    suspend fun deleteSuggestionBatch()

    @Query("SELECT * FROM suggested_session")
    suspend fun allSuggestedSession(): List<SuggestedSessionEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSuggestedSession(rows: List<SuggestedSessionEntity>): List<Long>

    @Query("DELETE FROM suggested_session")
    suspend fun deleteSuggestedSession()

    @Query("SELECT * FROM goal")
    suspend fun allGoal(): List<GoalEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertGoal(rows: List<GoalEntity>): List<Long>

    @Query("DELETE FROM goal")
    suspend fun deleteGoal()

    @Query("SELECT * FROM ingredient")
    suspend fun allIngredient(): List<IngredientEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertIngredient(rows: List<IngredientEntity>): List<Long>

    @Query("DELETE FROM ingredient")
    suspend fun deleteIngredient()

    @Query("SELECT * FROM meal_template")
    suspend fun allMealTemplate(): List<MealTemplateEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMealTemplate(rows: List<MealTemplateEntity>): List<Long>

    @Query("DELETE FROM meal_template")
    suspend fun deleteMealTemplate()

    @Query("SELECT * FROM meal_template_item")
    suspend fun allMealTemplateItem(): List<MealTemplateItemEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMealTemplateItem(rows: List<MealTemplateItemEntity>): List<Long>

    @Query("DELETE FROM meal_template_item")
    suspend fun deleteMealTemplateItem()

    @Query("SELECT * FROM meal_log")
    suspend fun allMealLog(): List<MealLogEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMealLog(rows: List<MealLogEntity>): List<Long>

    @Query("DELETE FROM meal_log")
    suspend fun deleteMealLog()

    @Query("SELECT * FROM meal_log_item")
    suspend fun allMealLogItem(): List<MealLogItemEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMealLogItem(rows: List<MealLogItemEntity>): List<Long>

    @Query("DELETE FROM meal_log_item")
    suspend fun deleteMealLogItem()

    @Query("SELECT * FROM nutrition_target_snapshot")
    suspend fun allNutritionTargetSnapshot(): List<NutritionTargetSnapshotEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertNutritionTargetSnapshot(rows: List<NutritionTargetSnapshotEntity>): List<Long>

    @Query("DELETE FROM nutrition_target_snapshot")
    suspend fun deleteNutritionTargetSnapshot()

    @Query("SELECT * FROM water_log")
    suspend fun allWaterLog(): List<WaterLogEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertWaterLog(rows: List<WaterLogEntity>): List<Long>

    @Query("DELETE FROM water_log")
    suspend fun deleteWaterLog()

    @Query("SELECT * FROM daily_load")
    suspend fun allDailyLoad(): List<DailyLoadEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDailyLoad(rows: List<DailyLoadEntity>): List<Long>

    @Query("DELETE FROM daily_load")
    suspend fun deleteDailyLoad()

    @Query("SELECT * FROM running_best")
    suspend fun allRunningBest(): List<RunningBestEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRunningBest(rows: List<RunningBestEntity>): List<Long>

    @Query("DELETE FROM running_best")
    suspend fun deleteRunningBest()

    // ---- ride_best (P12) ---------------------------------------------------------------------

    @Query("SELECT * FROM ride_best")
    suspend fun allRideBest(): List<RideBestEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRideBest(rows: List<RideBestEntity>): List<Long>

    @Query("DELETE FROM ride_best")
    suspend fun deleteRideBest()

    @Query("SELECT * FROM import_record")
    suspend fun allImportRecord(): List<ImportRecordEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertImportRecord(rows: List<ImportRecordEntity>): List<Long>

    @Query("DELETE FROM import_record")
    suspend fun deleteImportRecord()

    @Query("SELECT * FROM cycle_entry")
    suspend fun allCycleEntry(): List<CycleEntryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCycleEntry(rows: List<CycleEntryEntity>): List<Long>

    @Query("DELETE FROM cycle_entry")
    suspend fun deleteCycleEntry()

    @Query("SELECT * FROM strength_workout")
    suspend fun allStrengthWorkout(): List<StrengthWorkoutEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertStrengthWorkout(rows: List<StrengthWorkoutEntity>): List<Long>

    @Query("DELETE FROM strength_workout")
    suspend fun deleteStrengthWorkout()

    @Query("SELECT * FROM strength_workout_exercise")
    suspend fun allStrengthWorkoutExercise(): List<StrengthWorkoutExerciseEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertStrengthWorkoutExercise(rows: List<StrengthWorkoutExerciseEntity>): List<Long>

    @Query("DELETE FROM strength_workout_exercise")
    suspend fun deleteStrengthWorkoutExercise()

    @Query("SELECT * FROM strength_set_log")
    suspend fun allStrengthSetLog(): List<StrengthSetLogEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertStrengthSetLog(rows: List<StrengthSetLogEntity>): List<Long>

    @Query("DELETE FROM strength_set_log")
    suspend fun deleteStrengthSetLog()

    @Query("SELECT * FROM exercise_progress")
    suspend fun allExerciseProgress(): List<ExerciseProgressEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertExerciseProgress(rows: List<ExerciseProgressEntity>): List<Long>

    @Query("DELETE FROM exercise_progress")
    suspend fun deleteExerciseProgress()
}
