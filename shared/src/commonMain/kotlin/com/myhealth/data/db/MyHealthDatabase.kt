package com.myhealth.data.db

import androidx.room.Database
import androidx.room.ConstructedBy
import androidx.room.RoomDatabase
import androidx.room.RoomDatabaseConstructor
import androidx.room.TypeConverters
import com.myhealth.data.db.converter.Converters
import com.myhealth.data.db.dao.ActivityDao
import com.myhealth.data.db.dao.BackupDao
import com.myhealth.data.db.dao.BodyDao
import com.myhealth.data.db.dao.CycleDao
import com.myhealth.data.db.dao.EventDao
import com.myhealth.data.db.dao.GoalDao
import com.myhealth.data.db.dao.HealthDao
import com.myhealth.data.db.dao.ImportDao
import com.myhealth.data.db.dao.IngredientDao
import com.myhealth.data.db.dao.LoadDao
import com.myhealth.data.db.dao.MealDao
import com.myhealth.data.db.dao.NutritionDao
import com.myhealth.data.db.dao.PlanDao
import com.myhealth.data.db.dao.ProfileDao
import com.myhealth.data.db.dao.RideBestDao
import com.myhealth.data.db.dao.RunningBestDao
import com.myhealth.data.db.dao.SleepDao
import com.myhealth.data.db.dao.StrengthDao
import com.myhealth.data.db.dao.SuggestionDao
import com.myhealth.data.db.dao.SyncStateDao
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
import com.myhealth.data.db.entity.IngredientFtsEntity
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
 * The single Room database (PLAN §2.2): 26 tables plus the P8.5 `ingredient_fts` index, the
 * P11.1 `cycle_entry` table, the P12 `ride_best` table, the three P14 strength tables and the
 * P16.1 `exercise_progress` table,
 * `exportSchema = true`, schemas in `shared/schemas`.
 *
 * P20.2: the class lives in the shared module so the iPhone app opens the same schema; each platform
 * builds it in its own source set (`buildMyHealthDatabase` on Android, `MyHealthDatabaseIos` on iOS).
 *
 * Every version bump ships an explicit `Migration` in [Migrations] plus a row in `docs/PLAN.md`
 * §6.4. `fallbackToDestructiveMigration` is forbidden except behind the debug-only flag handled in
 * `buildMyHealthDatabase`, so a schema mistake can never silently wipe a user's history in a release build.
 */
@Database(
    entities = [
        // §2.2.1 profile & body
        ProfileEntity::class,
        BodyMeasurementEntity::class,
        // §2.2.2 activities
        ActivitySessionEntity::class,
        ActivitySourceRecordEntity::class,
        ActivityStreamEntity::class,
        ActivityLapEntity::class,
        // §2.2.3 daily health
        DailyHealthSummaryEntity::class,
        SleepSessionEntity::class,
        SyncStateEntity::class,
        // §2.2.4 calendar & plan
        CalendarEventEntity::class,
        EventOverrideEntity::class,
        TrainingPlanEntity::class,
        PlannedSessionEntity::class,
        SuggestionBatchEntity::class,
        SuggestedSessionEntity::class,
        GoalEntity::class,
        // §2.2.5 nutrition
        IngredientEntity::class,
        IngredientFtsEntity::class,
        MealTemplateEntity::class,
        MealTemplateItemEntity::class,
        MealLogEntity::class,
        MealLogItemEntity::class,
        NutritionTargetSnapshotEntity::class,
        WaterLogEntity::class,
        // §2.2.6 derived / cache
        DailyLoadEntity::class,
        RunningBestEntity::class,
        RideBestEntity::class,
        ImportRecordEntity::class,
        // §5 P11.1 cycle
        CycleEntryEntity::class,
        // §2.2.7 strength (P14)
        StrengthWorkoutEntity::class,
        StrengthWorkoutExerciseEntity::class,
        StrengthSetLogEntity::class,
        // §P16 P16.1 per-exercise load progression
        ExerciseProgressEntity::class,
    ],
    version = 8,
    exportSchema = true,
)
@TypeConverters(Converters::class)
@ConstructedBy(MyHealthDatabaseConstructor::class)
abstract class MyHealthDatabase : RoomDatabase() {

    abstract fun profileDao(): ProfileDao

    abstract fun bodyDao(): BodyDao

    abstract fun activityDao(): ActivityDao

    abstract fun healthDao(): HealthDao

    abstract fun sleepDao(): SleepDao

    abstract fun syncStateDao(): SyncStateDao

    abstract fun eventDao(): EventDao

    abstract fun planDao(): PlanDao

    abstract fun goalDao(): GoalDao

    abstract fun suggestionDao(): SuggestionDao

    abstract fun ingredientDao(): IngredientDao

    abstract fun mealDao(): MealDao

    abstract fun nutritionDao(): NutritionDao

    abstract fun loadDao(): LoadDao

    abstract fun runningBestDao(): RunningBestDao

    /** `ride_best` (P12). */
    abstract fun rideBestDao(): RideBestDao

    abstract fun importDao(): ImportDao

    /** `cycle_entry` (P11.1). */
    abstract fun cycleDao(): CycleDao

    /** The three strength tables (P14) plus `exercise_progress` (P16.1). */
    abstract fun strengthDao(): StrengthDao

    /** Whole-table access for the JSON backup (P8.4). */
    abstract fun backupDao(): BackupDao

    companion object {
        const val NAME: String = "myhealth.db"
    }
}

/** Generated by Room's KSP processor for every target (Room KMP). */
@Suppress("KotlinNoActualForExpect")
expect object MyHealthDatabaseConstructor : RoomDatabaseConstructor<MyHealthDatabase> {
    override fun initialize(): MyHealthDatabase
}
