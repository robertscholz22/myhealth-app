package com.myhealth.data.db

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.myhealth.data.db.entity.ActivitySourceRecordEntity
import com.myhealth.data.db.entity.CycleEntryEntity
import com.myhealth.data.db.entity.ExerciseProgressEntity
import com.myhealth.data.db.entity.IngredientEntity
import com.myhealth.data.db.entity.RideBestEntity
import com.myhealth.data.db.entity.StrengthSetLogEntity
import com.myhealth.data.db.entity.StrengthWorkoutEntity
import com.myhealth.data.db.entity.StrengthWorkoutExerciseEntity
import com.myhealth.data.db.migration.Migrations
import com.myhealth.domain.model.ActivitySource
import com.myhealth.domain.model.Feedback
import com.myhealth.domain.model.MeasureBasis
import com.myhealth.domain.model.RideBestKind
import com.myhealth.domain.model.SessionType
import com.myhealth.domain.model.StrengthWorkoutKind
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Replays every real upgrade from the exported schema JSONs (PLAN §6.4 step 4): a database is
 * created at version N with rows in it, `runMigrationsAndValidate` brings it to N+1 and compares
 * the result against `N+1.json` column for column and index for index, and the DAOs then prove the
 * rows survived and the new shape works.
 *
 * Instrumented — there is no emulator on the build machine (§0.3), so this compiles in CI and is
 * only executed when a device is attached.
 */
@RunWith(AndroidJUnit4::class)
class MyHealthMigrationTest {

    @get:Rule
    val migrations = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        MyHealthDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    /**
     * P8.5: an existing v1 database upgrades in place, the FTS index is rebuilt from the rows it
     * already held, and a row inserted afterwards is indexed by the content-sync triggers.
     */
    @Test
    fun migration_1_to_2_builds_the_ingredient_fts_index_over_existing_rows() {
        migrations.createDatabase(MIGRATION_DB, 1).use { v1 ->
            v1.execSQL(
                "INSERT INTO ingredient (id, name, brand, basis, kcal, isFavorite, source, " +
                    "useCount, archived, createdAtMillis, updatedAtMillis) VALUES " +
                    "(1, 'Hafermilch', 'Oatly', 'PER_100ML', 46.0, 0, 'MANUAL', 0, 0, 1, 1)",
            )
        }

        migrations.runMigrationsAndValidate(MIGRATION_DB, 2, true, *Migrations.ALL)

        val migrated = Room.databaseBuilder(
            ApplicationProvider.getApplicationContext(),
            MyHealthDatabase::class.java,
            MIGRATION_DB,
        ).addMigrations(*Migrations.ALL).build()
        try {
            runTest {
                val existing = migrated.ingredientDao().searchFts("hafer*", limit = 10).first()
                assertThat(existing.map { it.name }).containsExactly("Hafermilch")

                migrated.ingredientDao().upsert(
                    IngredientEntity(
                        name = "Sojamilch",
                        basis = MeasureBasis.PER_100ML,
                        kcal = 39.0,
                        source = "MANUAL",
                        createdAtMillis = 2L,
                        updatedAtMillis = 2L,
                    ),
                )
                val inserted = migrated.ingredientDao().searchFts("soja*", limit = 10).first()
                assertThat(inserted.map { it.name }).containsExactly("Sojamilch")
            }
        } finally {
            migrated.close()
        }
    }

    /**
     * P11.1: an existing v2 database gains the `cycle_entry` table in place — the rows it already
     * held survive, the new table's unique index over `periodStartDay` is really there, and the
     * DAO reads and writes it afterwards.
     */
    @Test
    fun migration_2_to_3_adds_cycle_entry_with_a_unique_period_start() {
        migrations.createDatabase(MIGRATION_DB, 2).use { v2 ->
            v2.execSQL(
                "INSERT INTO ingredient (id, name, brand, basis, kcal, isFavorite, source, " +
                    "useCount, archived, createdAtMillis, updatedAtMillis) VALUES " +
                    "(1, 'Hafermilch', 'Oatly', 'PER_100ML', 46.0, 0, 'MANUAL', 0, 0, 1, 1)",
            )
        }

        migrations.runMigrationsAndValidate(MIGRATION_DB, 3, true, *Migrations.ALL)

        val migrated = Room.databaseBuilder(
            ApplicationProvider.getApplicationContext(),
            MyHealthDatabase::class.java,
            MIGRATION_DB,
        ).addMigrations(*Migrations.ALL).build()
        try {
            runTest {
                // Nothing was lost on the way up.
                assertThat(migrated.ingredientDao().searchFts("hafer*", limit = 10).first())
                    .hasSize(1)

                val dao = migrated.cycleDao()
                val id = dao.upsert(
                    CycleEntryEntity(
                        periodStartDay = 20_800L,
                        periodEndDay = 20_804L,
                        createdAtMillis = 1L,
                        updatedAtMillis = 1L,
                    ),
                )
                assertThat(dao.getAll()).hasSize(1)
                assertThat(dao.getByStartDay(20_800L)?.id).isEqualTo(id)

                // The unique index is what keeps one cycle per start day: Room's @Upsert resolves
                // the UNIQUE conflict by updating by primary key (id 0 matches nothing), so the
                // duplicate is dropped without an exception and the original row survives.
                runCatching {
                    dao.upsert(
                        CycleEntryEntity(
                            periodStartDay = 20_800L,
                            createdAtMillis = 2L,
                            updatedAtMillis = 2L,
                        ),
                    )
                }
                assertThat(dao.getAll()).hasSize(1)
                assertThat(dao.getByStartDay(20_800L)?.id).isEqualTo(id)
                assertThat(dao.getByStartDay(20_800L)?.periodEndDay).isEqualTo(20_804L)
            }
        } finally {
            migrated.close()
        }
    }

    /**
     * "Undo import": an existing v3 database gains `activity_source_record.importRecordId` in
     * place. The rows it already held survive with `NULL` in the new column (they predate undo),
     * and a record written afterwards can be found by its import.
     */
    @Test
    fun migration_3_to_4_adds_import_record_id_to_activity_source_record() {
        migrations.createDatabase(MIGRATION_DB, 3).use { v3 ->
            v3.execSQL(
                "INSERT INTO activity_source_record (id, activityId, source, externalId, " +
                    "payloadJson, receivedAtMillis) VALUES " +
                    "(1, NULL, 'HEALTH_CONNECT', 'hc-1', '{}', 1000)",
            )
        }

        migrations.runMigrationsAndValidate(MIGRATION_DB, 4, true, *Migrations.ALL)

        val migrated = Room.databaseBuilder(
            ApplicationProvider.getApplicationContext(),
            MyHealthDatabase::class.java,
            MIGRATION_DB,
        ).addMigrations(*Migrations.ALL).build()
        try {
            runTest {
                val dao = migrated.activityDao()
                // The pre-v4 arrival survived and is not attributed to any import.
                val existing = dao.getSourceRecord(ActivitySource.HEALTH_CONNECT, "hc-1")
                assertThat(existing?.importRecordId).isNull()
                assertThat(dao.getSourceRecordsOfImport(7L)).isEmpty()

                dao.upsertSourceRecord(
                    ActivitySourceRecordEntity(
                        source = ActivitySource.CSV_IMPORT,
                        externalId = "csv-1",
                        payloadJson = "{}",
                        receivedAtMillis = 2_000L,
                        importRecordId = 7L,
                    ),
                )
                assertThat(dao.getSourceRecordsOfImport(7L).map { it.externalId })
                    .containsExactly("csv-1")
            }
        } finally {
            migrated.close()
        }
    }

    /**
     * P12: an existing v4 database gains the three power columns on `activity_session`, the
     * `powerWJson` channel on `activity_stream`, the FTP override plus trainer flag on `profile`,
     * and the new `ride_best` table — in place, with rows already in the two tables that change.
     */
    @Test
    fun migration_4_to_5_adds_power_columns_and_ride_best() {
        migrations.createDatabase(MIGRATION_DB, 4).use { v4 ->
            v4.execSQL(
                "INSERT INTO activity_session (id, startAtMillis, endAtMillis, day, sportType, " +
                    "sportGroup, title, durationSec, elapsedSec, primarySource, mergedSourcesCsv, " +
                    "dedupeBucket, userEditedFieldsCsv, hasStreams, createdAtMillis, updatedAtMillis) " +
                    "VALUES (1, 1000, 4600, 19662, 'CYCLING', 'CYCLE', 'Zwift', 3600, 3600, " +
                    "'CSV_IMPORT', 'CSV_IMPORT', 'CYCLE|0', '', 0, 1, 1)",
            )
            v4.execSQL(
                "INSERT INTO profile (id, displayName, sex, birthDay, heightCm, neatLevel, " +
                    "goalPaceKgPerWeek, sleepTargetHours, preferredSportsJson, mobilityOnRestDays, " +
                    "createdAtMillis, updatedAtMillis) VALUES " +
                    "(1, 'Robert', 'MALE', 5000, 180.0, 'LIGHT_ACTIVE', 0.0, 8.0, '{}', 1, 1, 1)",
            )
        }

        migrations.runMigrationsAndValidate(MIGRATION_DB, 5, true, *Migrations.ALL)

        val migrated = Room.databaseBuilder(
            ApplicationProvider.getApplicationContext(),
            MyHealthDatabase::class.java,
            MIGRATION_DB,
        ).addMigrations(*Migrations.ALL).build()
        try {
            runTest {
                // The pre-v5 ride survived and simply has no power — nothing before 1.1.0 read one.
                val ride = checkNotNull(migrated.activityDao().getById(1L))
                assertThat(ride.title).isEqualTo("Zwift")
                assertThat(ride.avgPowerW).isNull()
                assertThat(ride.maxPowerW).isNull()
                assertThat(ride.normalizedPowerW).isNull()

                // The profile row stayed valid: the new flag took its `DEFAULT 0`.
                val profile = checkNotNull(migrated.profileDao().observeProfile().first())
                assertThat(profile.ftpWattsManual).isNull()
                assertThat(profile.indoorTrainerAvailable).isFalse()

                val dao = migrated.rideBestDao()
                dao.upsertAll(
                    listOf(
                        RideBestEntity(
                            kind = RideBestKind.POWER_20MIN,
                            value = 300.0,
                            activityId = 1L,
                            day = 19_662L,
                            createdAtMillis = 1L,
                        ),
                        RideBestEntity(
                            kind = RideBestKind.TIME_40K,
                            value = 4_478.0,
                            activityId = null,
                            day = 19_662L,
                            isEstimated = true,
                            createdAtMillis = 1L,
                        ),
                    ),
                )
                assertThat(dao.getByActivity(1L).single().kind).isEqualTo(RideBestKind.POWER_20MIN)
                val bests = dao.observeBestPerKind().first().associateBy { it.kind }
                assertThat(bests.getValue(RideBestKind.POWER_20MIN).value).isEqualTo(300.0)
                assertThat(bests.getValue(RideBestKind.TIME_40K).isEstimated).isTrue()

                // `uq_ride_best_activity_kind` keeps one row per (ride, kind).
                runCatching {
                    dao.upsert(
                        RideBestEntity(
                            kind = RideBestKind.POWER_20MIN,
                            value = 280.0,
                            activityId = 1L,
                            day = 19_662L,
                            createdAtMillis = 2L,
                        ),
                    )
                }
                assertThat(dao.getByActivity(1L)).hasSize(1)

                // The FK is SET_NULL: deleting the ride keeps the effort, drops the link.
                migrated.activityDao().deleteById(1L)
                assertThat(dao.getSince(0L).map { it.activityId }).containsExactly(null, null)
            }
        } finally {
            migrated.close()
        }
    }

    /**
     * P14.1: an existing v5 database gains the three strength tables and the new zone/prescription
     * columns **in place**. The rows the database already held — a profile, a plan with a planned
     * session, a suggestion batch with a suggested session — survive with `NULL` in every new
     * column, the added foreign key on `planned_session.workoutId` really is a foreign key
     * (deleting the workout nulls the link rather than orphaning it), and the set log written
     * afterwards reads back through its day index.
     */
    @Test
    fun migration_5_to_6_adds_strength_tables_and_session_columns() {
        migrations.createDatabase(MIGRATION_DB, 5).use { v5 ->
            v5.execSQL(
                "INSERT INTO profile (id, displayName, sex, birthDay, heightCm, neatLevel, " +
                    "goalPaceKgPerWeek, sleepTargetHours, preferredSportsJson, mobilityOnRestDays, " +
                    "indoorTrainerAvailable, createdAtMillis, updatedAtMillis) VALUES " +
                    "(1, 'Robert', 'MALE', 5000, 180.0, 'LIGHT_ACTIVE', 0.0, 8.0, '{}', 1, 0, 1, 1)",
            )
            v5.execSQL(
                "INSERT INTO training_plan (id, name, startDay, endDay, status, createdAtMillis, " +
                    "updatedAtMillis) VALUES (1, 'Autumn', 19660, 19700, 'ACTIVE', 1, 1)",
            )
            v5.execSQL(
                "INSERT INTO planned_session (id, planId, day, sportType, sessionType, intensity, " +
                    "targetDurationMin, status, locked, createdAtMillis, updatedAtMillis) VALUES " +
                    "(1, 1, 19662, 'STRENGTH', 'STRENGTH_UPPER', 'MODERATE', 45, 'PLANNED', 0, 1, 1)",
            )
            v5.execSQL(
                "INSERT INTO suggestion_batch (id, generatedAtMillis, horizonStartDay, " +
                    "horizonEndDay, phase, weeklyLoadTarget, inputsHash, status) VALUES " +
                    "(1, 1000, 19662, 19669, 'BUILD', 420.0, 'hash-1', 'PROPOSED')",
            )
            v5.execSQL(
                "INSERT INTO suggested_session (id, batchId, day, sportType, sessionType, " +
                    "intensity, targetDurationMin, estimatedTrimp, score, rationaleJson, status) " +
                    "VALUES (1, 1, 19663, 'RUN_OUTDOOR', 'INTERVAL_RUN', 'HIGH', 55, 120.0, 0.8, " +
                    "'[]', 'PROPOSED')",
            )
        }

        migrations.runMigrationsAndValidate(MIGRATION_DB, 6, true, *Migrations.ALL)

        val migrated = Room.databaseBuilder(
            ApplicationProvider.getApplicationContext(),
            MyHealthDatabase::class.java,
            MIGRATION_DB,
        ).addMigrations(*Migrations.ALL).build()
        try {
            runTest {
                // Everything that was there is still there, with no zones and no structure —
                // nothing before 0.4.0 knew either.
                val profile = checkNotNull(migrated.profileDao().observeProfile().first())
                assertThat(profile.hrZoneBoundsJson).isNull()
                assertThat(profile.lactateThresholdHrManual).isNull()
                val planned = checkNotNull(migrated.planDao().getSessionById(1L))
                assertThat(planned.sessionType).isEqualTo(SessionType.STRENGTH_UPPER)
                assertThat(planned.structureJson).isNull()
                assertThat(planned.workoutId).isNull()
                val suggested = checkNotNull(migrated.suggestionDao().getSessionById(1L))
                assertThat(suggested.targetPaceSecPerKm).isNull()
                assertThat(suggested.structureJson).isNull()
                assertThat(suggested.workoutTemplateId).isNull()

                // The three new tables work, including the ordered children and the day index.
                val dao = migrated.strengthDao()
                val workoutId = dao.upsertWorkout(
                    StrengthWorkoutEntity(
                        name = "Upper A",
                        kind = StrengthWorkoutKind.UPPER,
                        templateId = "UPPER_A",
                        isBuiltIn = true,
                        createdAtMillis = 1L,
                        updatedAtMillis = 1L,
                    ),
                )
                dao.upsertExercises(
                    listOf(
                        StrengthWorkoutExerciseEntity(
                            workoutId = workoutId,
                            orderIndex = 0,
                            exerciseId = "BARBELL_BENCH_PRESS",
                            sets = 3,
                            reps = 10,
                            loadKg = 60.0,
                        ),
                        StrengthWorkoutExerciseEntity(
                            workoutId = workoutId,
                            orderIndex = 1,
                            exerciseId = "PLANK",
                            sets = 3,
                            seconds = 45,
                            isBodyweight = true,
                        ),
                    ),
                )
                assertThat(checkNotNull(dao.getByTemplateId("UPPER_A")).exercises).hasSize(2)

                dao.insertSetLogs(
                    listOf(
                        StrengthSetLogEntity(
                            day = 19_662L,
                            plannedSessionId = 1L,
                            exerciseId = "BARBELL_BENCH_PRESS",
                            setIndex = 1,
                            reps = 10,
                            loadKg = 60.0,
                            completedAtMillis = 2L,
                        ),
                    ),
                )
                assertThat(dao.observeSetLogsByDay(19_662L).first()).hasSize(1)
                assertThat(dao.getSetLogsOfPlannedSession(1L)).hasSize(1)

                // The FK added by `ALTER TABLE … ADD COLUMN … REFERENCES` really is enforced:
                // linking the workout and then deleting it nulls the link (SET_NULL), and the
                // exercise rows go with the workout (CASCADE).
                migrated.planDao().upsertSession(planned.copy(workoutId = workoutId))
                assertThat(checkNotNull(migrated.planDao().getSessionById(1L)).workoutId)
                    .isEqualTo(workoutId)

                dao.deleteWorkout(workoutId)
                assertThat(checkNotNull(migrated.planDao().getSessionById(1L)).workoutId).isNull()
                assertThat(dao.getByTemplateId("UPPER_A")).isNull()
                // The set log survives its workout — it records what actually happened.
                assertThat(dao.observeSetLogsByDay(19_662L).first()).hasSize(1)
            }
        } finally {
            migrated.close()
        }
    }

    /**
     * P16.1: an existing v6 database gains `profile.availableEquipmentJson`,
     * `strength_set_log.feedback` and the `exercise_progress` table **in place**. The rows it
     * already held survive with `NULL` in both new columns — which is exactly §P16's invariant
     * that the feature is inert until the owner uses it — and the new table round-trips through
     * the DAO, keyed on the catalog id rather than on an autoincrementing row id.
     */
    @Test
    fun migration_6_to_7_adds_progress_table_and_columns() {
        migrations.createDatabase(MIGRATION_DB, 6).use { v6 ->
            v6.execSQL(
                "INSERT INTO profile (id, displayName, sex, birthDay, heightCm, neatLevel, " +
                    "goalPaceKgPerWeek, sleepTargetHours, preferredSportsJson, mobilityOnRestDays, " +
                    "indoorTrainerAvailable, createdAtMillis, updatedAtMillis) VALUES " +
                    "(1, 'Robert', 'MALE', 5000, 180.0, 'LIGHT_ACTIVE', 0.0, 8.0, '{}', 1, 0, 1, 1)",
            )
            v6.execSQL(
                "INSERT INTO strength_set_log (id, day, plannedSessionId, activityId, exerciseId, " +
                    "setIndex, reps, seconds, loadKg, rpe, completedAtMillis) VALUES " +
                    "(1, 19662, NULL, NULL, 'BARBELL_BACK_SQUAT', 1, 5, NULL, 60.0, 8, 2000)",
            )
        }

        migrations.runMigrationsAndValidate(MIGRATION_DB, 7, true, *Migrations.ALL)

        val migrated = Room.databaseBuilder(
            ApplicationProvider.getApplicationContext(),
            MyHealthDatabase::class.java,
            MIGRATION_DB,
        ).addMigrations(*Migrations.ALL).build()
        try {
            runTest {
                // Nothing before 0.5.0 restricted equipment or gave feedback.
                val profile = checkNotNull(migrated.profileDao().observeProfile().first())
                assertThat(profile.availableEquipmentJson).isNull()
                val dao = migrated.strengthDao()
                val logged = dao.observeSetLogsByDay(19_662L).first().single()
                assertThat(logged.exerciseId).isEqualTo("BARBELL_BACK_SQUAT")
                assertThat(logged.feedback).isNull()

                // The new column takes a feedback from now on …
                dao.insertSetLogs(
                    listOf(
                        StrengthSetLogEntity(
                            day = 19_663L,
                            exerciseId = "BARBELL_BACK_SQUAT",
                            setIndex = 1,
                            reps = 8,
                            loadKg = 60.0,
                            completedAtMillis = 3_000L,
                            feedback = Feedback.TOO_EASY,
                        ),
                    ),
                )
                assertThat(dao.observeSetLogsByDay(19_663L).first().single().feedback)
                    .isEqualTo(Feedback.TOO_EASY)

                // … and `exercise_progress` stores one state per exercise, replaced in place.
                dao.upsertProgress(
                    ExerciseProgressEntity(
                        exerciseId = "BARBELL_BACK_SQUAT",
                        loadKg = 60.0,
                        reps = 5,
                        isEstimated = true,
                        updatedDay = 19_663L,
                    ),
                )
                dao.upsertProgress(
                    ExerciseProgressEntity(
                        exerciseId = "BARBELL_BACK_SQUAT",
                        loadKg = 62.5,
                        reps = 5,
                        lastFeedback = Feedback.TOO_EASY,
                        isEstimated = false,
                        updatedDay = 19_664L,
                    ),
                )
                val state = checkNotNull(dao.getProgress("BARBELL_BACK_SQUAT"))
                assertThat(state.loadKg).isEqualTo(62.5)
                assertThat(state.lastFeedback).isEqualTo(Feedback.TOO_EASY)
                assertThat(state.isEstimated).isFalse()
                assertThat(dao.getAllProgress()).hasSize(1)
                assertThat(dao.observeProgress("BARBELL_BACK_SQUAT").first()?.updatedDay)
                    .isEqualTo(19_664L)
                assertThat(dao.getProgress("PLANK")).isNull()
            }
        } finally {
            migrated.close()
        }
    }

    /**
     * P19: an existing v7 database keeps its goals as races and its workouts in the suggestion
     * pool (`DEFAULT 1`), and a stored suggestion has no concrete workout yet.
     */
    @Test
    fun migration_7_to_8_keeps_goals_races_and_workouts_in_the_pool() {
        migrations.createDatabase(MIGRATION_DB, 7).use { v7 ->
            v7.execSQL(
                "INSERT INTO goal (id, type, title, targetDay, targetDistanceMeters, targetTimeSec, " +
                    "priority, status, createdAtMillis, updatedAtMillis) VALUES " +
                    "(1, 'RACE_TIME', 'Berlin Half', 20547, 21097.5, 5100, 1, 'ACTIVE', 1, 1)",
            )
            v7.execSQL(
                "INSERT INTO strength_workout (id, name, kind, templateId, isBuiltIn, notes, " +
                    "createdAtMillis, updatedAtMillis) VALUES (1, 'Upper A', 'UPPER', 'UPPER_A', 1, NULL, 1, 1)",
            )
        }

        migrations.runMigrationsAndValidate(MIGRATION_DB, 8, true, *Migrations.ALL)

        val migrated = Room.databaseBuilder(
            ApplicationProvider.getApplicationContext(),
            MyHealthDatabase::class.java,
            MIGRATION_DB,
        ).addMigrations(*Migrations.ALL).build()
        try {
            runTest {
                val goal = checkNotNull(migrated.goalDao().getById(1L))
                assertThat(goal.isRace).isTrue()
                val dao = migrated.strengthDao()
                assertThat(checkNotNull(dao.getById(1L)).workout.useInSuggestions).isTrue()
                dao.setUseInSuggestions(1L, false, 2L)
                assertThat(checkNotNull(dao.getById(1L)).workout.useInSuggestions).isFalse()
            }
        } finally {
            migrated.close()
        }
    }

    private companion object {
        const val MIGRATION_DB = "migration-test.db"
    }
}
