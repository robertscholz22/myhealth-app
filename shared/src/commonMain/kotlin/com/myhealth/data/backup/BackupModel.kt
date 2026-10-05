package com.myhealth.data.backup

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
import kotlinx.serialization.Serializable

/**
 * The whole database as one JSON document (PLAN P8.4).
 *
 * [schemaVersion] is the **Room database version** the export was taken from, not a format
 * version of its own: a backup can only be restored by an app whose schema is at least as new,
 * because an older app has no migration that could bring the rows forward. A *newer* backup is
 * therefore rejected outright ([BackupSerializer.decode]); an older one is accepted and Room's
 * own migrations do the rest once the rows are in.
 *
 * Every table of §2.2 has one list here, carrying the Room entity itself — one representation,
 * no DTO layer to drift out of step with the schema. `ingredient_fts` is deliberately absent: it
 * is an index over `ingredient`, rebuilt by the migration and kept by the content-sync triggers.
 *
 * What is **never** in a backup: the DataStore settings and anything in `EncryptedSharedPreferences`
 * (Garmin credentials, tokens). A backup file is plain text the owner may e-mail to themselves.
 */
@Serializable
data class BackupFile(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val exportedAtMillis: Long = 0L,
    val appVersion: String = "",
    // §2.2.1 profile & body
    val profile: List<ProfileEntity> = emptyList(),
    val bodyMeasurement: List<BodyMeasurementEntity> = emptyList(),
    // §2.2.2 activities
    val activitySession: List<ActivitySessionEntity> = emptyList(),
    val activitySourceRecord: List<ActivitySourceRecordEntity> = emptyList(),
    val activityStream: List<ActivityStreamEntity> = emptyList(),
    val activityLap: List<ActivityLapEntity> = emptyList(),
    // §2.2.3 daily health
    val dailyHealthSummary: List<DailyHealthSummaryEntity> = emptyList(),
    val sleepSession: List<SleepSessionEntity> = emptyList(),
    val syncState: List<SyncStateEntity> = emptyList(),
    // §2.2.4 calendar & plan
    val calendarEvent: List<CalendarEventEntity> = emptyList(),
    val eventOverride: List<EventOverrideEntity> = emptyList(),
    val trainingPlan: List<TrainingPlanEntity> = emptyList(),
    val plannedSession: List<PlannedSessionEntity> = emptyList(),
    val suggestionBatch: List<SuggestionBatchEntity> = emptyList(),
    val suggestedSession: List<SuggestedSessionEntity> = emptyList(),
    val goal: List<GoalEntity> = emptyList(),
    // §2.2.5 nutrition
    val ingredient: List<IngredientEntity> = emptyList(),
    val mealTemplate: List<MealTemplateEntity> = emptyList(),
    val mealTemplateItem: List<MealTemplateItemEntity> = emptyList(),
    val mealLog: List<MealLogEntity> = emptyList(),
    val mealLogItem: List<MealLogItemEntity> = emptyList(),
    val nutritionTargetSnapshot: List<NutritionTargetSnapshotEntity> = emptyList(),
    val waterLog: List<WaterLogEntity> = emptyList(),
    // §2.2.6 derived / cache
    val dailyLoad: List<DailyLoadEntity> = emptyList(),
    val runningBest: List<RunningBestEntity> = emptyList(),
    val rideBest: List<RideBestEntity> = emptyList(),
    val importRecord: List<ImportRecordEntity> = emptyList(),
    // §5 P11.1 cycle
    val cycleEntry: List<CycleEntryEntity> = emptyList(),
    // §2.2.7 strength (P14)
    val strengthWorkout: List<StrengthWorkoutEntity> = emptyList(),
    val strengthWorkoutExercise: List<StrengthWorkoutExerciseEntity> = emptyList(),
    val strengthSetLog: List<StrengthSetLogEntity> = emptyList(),
    // §P16 P16.1 load progression
    val exerciseProgress: List<ExerciseProgressEntity> = emptyList(),
) {

    /** Rows per SQLite table name, empty tables omitted — what the screen reports after a run. */
    fun rowsPerTable(): Map<String, Int> = mapOf(
        "profile" to profile.size,
        "body_measurement" to bodyMeasurement.size,
        "activity_session" to activitySession.size,
        "activity_source_record" to activitySourceRecord.size,
        "activity_stream" to activityStream.size,
        "activity_lap" to activityLap.size,
        "daily_health_summary" to dailyHealthSummary.size,
        "sleep_session" to sleepSession.size,
        "sync_state" to syncState.size,
        "calendar_event" to calendarEvent.size,
        "event_override" to eventOverride.size,
        "training_plan" to trainingPlan.size,
        "planned_session" to plannedSession.size,
        "suggestion_batch" to suggestionBatch.size,
        "suggested_session" to suggestedSession.size,
        "goal" to goal.size,
        "ingredient" to ingredient.size,
        "meal_template" to mealTemplate.size,
        "meal_template_item" to mealTemplateItem.size,
        "meal_log" to mealLog.size,
        "meal_log_item" to mealLogItem.size,
        "nutrition_target_snapshot" to nutritionTargetSnapshot.size,
        "water_log" to waterLog.size,
        "daily_load" to dailyLoad.size,
        "running_best" to runningBest.size,
        "ride_best" to rideBest.size,
        "import_record" to importRecord.size,
        "cycle_entry" to cycleEntry.size,
        "strength_workout" to strengthWorkout.size,
        "strength_workout_exercise" to strengthWorkoutExercise.size,
        "strength_set_log" to strengthSetLog.size,
        "exercise_progress" to exerciseProgress.size,
    ).filterValues { it > 0 }

    val totalRows: Int get() = rowsPerTable().values.sum()

    companion object {
        /**
         * Must track `@Database(version = …)` of `MyHealthDatabase`. It stood at 3 while the
         * database moved to 4 (the "undo import" column) — a pre-existing drift corrected with
         * the P12 bump to 5. P14.1 raises it to **6** (zones + the three strength tables), so a
         * 0.4.0 export declares 6 and an older app refuses it, which is exactly the guarantee the
         * field exists for. P16.1 raises it to **7** ("my equipment", the set-log feedback and
         * `exercise_progress`).
         */
        const val CURRENT_SCHEMA_VERSION: Int = 8
    }
}
