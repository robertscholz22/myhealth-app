package com.myhealth.data.db.migration

import androidx.room.migration.Migration

/**
 * Schema migrations for [com.myhealth.data.db.MyHealthDatabase] (PLAN §2.2, §6.4).
 *
 * Convention — every schema change follows all five steps, in this order:
 *
 * 1. Change the entity/entities, then bump `@Database(version = N)` by exactly one.
 * 2. Add `private val MIGRATION_(N-1)_N = MigrationStep(N - 1, N) { db -> … }` below, using only
 *    `db.execSQL(...)`. Never reference an entity class or a DAO from a migration: migrations run
 *    against the schema as it was, not as the code now describes it.
 * 3. Append it to [ALL]. The array stays ordered by version, oldest first, and a migration that
 *    has shipped to the device is never reordered or edited in place.
 * 4. Build once so Room writes `shared/schemas/com.myhealth.data.db.MyHealthDatabase/N.json`, and
 *    keep that file — `MigrationTestHelper` replays real upgrades from it.
 * 5. Add a row to the migration table in `docs/PLAN.md` §6.4 (from, to, what changed, why).
 *
 * `fallbackToDestructiveMigration` is forbidden outside the debug-only escape hatch in
 * `buildMyHealthDatabase` (§2.2): losing a user's history is never an acceptable upgrade path.
 *
 * P20.2: a step only records its SQL; [toRoomMigration] turns it into a Room `Migration` for the
 * platform's database mode (Android: `SupportSQLiteDatabase`, iOS: the bundled driver's
 * `SQLiteConnection`), so both apps run the very same statements.
 */
object Migrations {

    /**
     * 1 → 2 (P8.5): the `ingredient_fts` FTS4 index over `ingredient(name, brand)`.
     *
     * The statements are Room's own, copied verbatim from
     * `app/schemas/com.myhealth.data.db.MyHealthDatabase/2.json` (`createSql` with `${'$'}{TABLE_NAME}`
     * resolved, plus the four `contentSyncTriggers`) — an external-content FTS table is only kept
     * in step by those triggers, and Room's schema validation compares them character for
     * character. The final `'rebuild'` command fills the index from the rows that already exist,
     * which the triggers alone would never do.
     */
    private val MIGRATION_1_2 = MigrationStep(1, 2) { db ->
        db.execSQL(
            "CREATE VIRTUAL TABLE IF NOT EXISTS `ingredient_fts` USING FTS4(" +
                "`name` TEXT NOT NULL, `brand` TEXT, content=`ingredient`)",
        )
        db.execSQL(
            "CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_ingredient_fts_BEFORE_UPDATE " +
                "BEFORE UPDATE ON `ingredient` BEGIN " +
                "DELETE FROM `ingredient_fts` WHERE `docid`=OLD.`rowid`; END",
        )
        db.execSQL(
            "CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_ingredient_fts_BEFORE_DELETE " +
                "BEFORE DELETE ON `ingredient` BEGIN " +
                "DELETE FROM `ingredient_fts` WHERE `docid`=OLD.`rowid`; END",
        )
        db.execSQL(
            "CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_ingredient_fts_AFTER_UPDATE " +
                "AFTER UPDATE ON `ingredient` BEGIN " +
                "INSERT INTO `ingredient_fts`(`docid`, `name`, `brand`) " +
                "VALUES (NEW.`rowid`, NEW.`name`, NEW.`brand`); END",
        )
        db.execSQL(
            "CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_ingredient_fts_AFTER_INSERT " +
                "AFTER INSERT ON `ingredient` BEGIN " +
                "INSERT INTO `ingredient_fts`(`docid`, `name`, `brand`) " +
                "VALUES (NEW.`rowid`, NEW.`name`, NEW.`brand`); END",
        )
        db.execSQL("INSERT INTO ingredient_fts(ingredient_fts) VALUES('rebuild')")
    }

    /**
     * 2 → 3 (P11.1): the `cycle_entry` table of the menstrual-cycle tracker, with the unique index
     * over `periodStartDay` that keeps one logged period per day (a duplicate start would corrupt
     * every interval `CycleEngine` averages). The statements are Room's own, copied verbatim from
     * `app/schemas/com.myhealth.data.db.MyHealthDatabase/3.json`, so `runMigrationsAndValidate`
     * compares them character for character.
     */
    private val MIGRATION_2_3 = MigrationStep(2, 3) { db ->
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `cycle_entry` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`periodStartDay` INTEGER NOT NULL, `periodEndDay` INTEGER, `note` TEXT, " +
                "`createdAtMillis` INTEGER NOT NULL, `updatedAtMillis` INTEGER NOT NULL)",
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `uq_cycle_entry_start` " +
                "ON `cycle_entry` (`periodStartDay`)",
        )
    }

    /**
     * 3 → 4 (BUG-11 follow-up, "Undo import"): `activity_source_record.importRecordId`, the
     * nullable back-link from a raw arrival to the `import_record` that wrote it, plus the index
     * the undo selects on. Existing rows keep `NULL`: they predate the column, so they were either
     * synced or imported before undo existed and are not undoable. The statements are Room's own,
     * copied from `app/schemas/com.myhealth.data.db.MyHealthDatabase/4.json`, so
     * `runMigrationsAndValidate` compares them character for character.
     */
    private val MIGRATION_3_4 = MigrationStep(3, 4) { db ->
        db.execSQL("ALTER TABLE `activity_source_record` ADD COLUMN `importRecordId` INTEGER")
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `idx_asr_import` ON `activity_source_record` (`importRecordId`)",
        )
    }

    /**
     * 4 → 5 (P12 "Bike & power"): cycling power on `activity_session` and `activity_stream`, the
     * FTP override and trainer flag on `profile`, and the new `ride_best` table.
     *
     * The three session columns and the stream column are nullable, so existing rows simply have
     * no power — which is the truth: nothing before 1.1.0 ever read a watt. `profile`'s flag is
     * `NOT NULL DEFAULT 0`, matching the entity default, so the single existing row stays valid
     * without a rewrite. The statements are Room's own, copied verbatim from
     * `app/schemas/com.myhealth.data.db.MyHealthDatabase/5.json`, so `runMigrationsAndValidate`
     * compares them character for character.
     */
    private val MIGRATION_4_5 = MigrationStep(4, 5) { db ->
        db.execSQL("ALTER TABLE `activity_session` ADD COLUMN `avgPowerW` INTEGER")
        db.execSQL("ALTER TABLE `activity_session` ADD COLUMN `maxPowerW` INTEGER")
        db.execSQL("ALTER TABLE `activity_session` ADD COLUMN `normalizedPowerW` INTEGER")
        db.execSQL("ALTER TABLE `activity_stream` ADD COLUMN `powerWJson` TEXT")
        db.execSQL("ALTER TABLE `profile` ADD COLUMN `ftpWattsManual` INTEGER")
        db.execSQL(
            "ALTER TABLE `profile` ADD COLUMN `indoorTrainerAvailable` INTEGER NOT NULL DEFAULT 0",
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `ride_best` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `kind` TEXT NOT NULL, " +
                "`value` REAL NOT NULL, `activityId` INTEGER, `day` INTEGER NOT NULL, " +
                "`isEstimated` INTEGER NOT NULL, `createdAtMillis` INTEGER NOT NULL, " +
                "FOREIGN KEY(`activityId`) REFERENCES `activity_session`(`id`) " +
                "ON UPDATE NO ACTION ON DELETE SET NULL )",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `idx_ride_best_kind_value` ON `ride_best` (`kind`, `value`)",
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `uq_ride_best_activity_kind` " +
                "ON `ride_best` (`activityId`, `kind`)",
        )
    }

    /**
     * 5 → 6 (P14.1 "Zones & strength"): the three strength tables, the two zone columns on
     * `profile`, three prescription columns on `suggested_session`, and `structureJson` +
     * `workoutId` on `planned_session`.
     *
     * Order matters: `strength_workout` is created **first**, because `planned_session.workoutId`
     * references it, and `strength_set_log` references `planned_session`, which already exists.
     *
     * `workoutId` is added with a `REFERENCES` clause on the `ALTER TABLE` itself — SQLite allows
     * that as long as the new column's default is `NULL`, which it is, and the constraint then
     * shows up in `PRAGMA foreign_key_list` exactly as Room's own `CREATE TABLE` would have
     * declared it (§6.4 row 6). `planned_session` is the most-referenced table in the app, so not
     * recreating it is worth a paragraph of explanation: the recreate-the-Room-way fallback stays
     * available if `runMigrationsAndValidate` ever disagrees.
     *
     * Every statement is Room's own, copied verbatim from
     * `app/schemas/com.myhealth.data.db.MyHealthDatabase/6.json`, so `runMigrationsAndValidate`
     * compares them character for character. Existing rows keep `NULL` in every new column:
     * nothing before 0.4.0 knew a muscle or a zone override.
     */
    private val MIGRATION_5_6 = MigrationStep(5, 6) { db ->
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `strength_workout` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, " +
                "`kind` TEXT NOT NULL, `templateId` TEXT, `isBuiltIn` INTEGER NOT NULL, " +
                "`notes` TEXT, `createdAtMillis` INTEGER NOT NULL, " +
                "`updatedAtMillis` INTEGER NOT NULL)",
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `uq_strength_workout_template` " +
                "ON `strength_workout` (`templateId`)",
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `strength_workout_exercise` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `workoutId` INTEGER NOT NULL, " +
                "`orderIndex` INTEGER NOT NULL, `exerciseId` TEXT NOT NULL, " +
                "`sets` INTEGER NOT NULL, `reps` INTEGER, `seconds` INTEGER, `loadKg` REAL, " +
                "`isBodyweight` INTEGER NOT NULL, `restSec` INTEGER, `note` TEXT, " +
                "FOREIGN KEY(`workoutId`) REFERENCES `strength_workout`(`id`) " +
                "ON UPDATE NO ACTION ON DELETE CASCADE )",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `idx_swe_workout` " +
                "ON `strength_workout_exercise` (`workoutId`)",
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `uq_swe_order` " +
                "ON `strength_workout_exercise` (`workoutId`, `orderIndex`)",
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `strength_set_log` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `day` INTEGER NOT NULL, " +
                "`plannedSessionId` INTEGER, `activityId` INTEGER, `exerciseId` TEXT NOT NULL, " +
                "`setIndex` INTEGER NOT NULL, `reps` INTEGER, `seconds` INTEGER, `loadKg` REAL, " +
                "`rpe` INTEGER, `completedAtMillis` INTEGER NOT NULL, " +
                "FOREIGN KEY(`plannedSessionId`) REFERENCES `planned_session`(`id`) " +
                "ON UPDATE NO ACTION ON DELETE SET NULL , " +
                "FOREIGN KEY(`activityId`) REFERENCES `activity_session`(`id`) " +
                "ON UPDATE NO ACTION ON DELETE SET NULL )",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `idx_ssl_day` ON `strength_set_log` (`day`)")
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `idx_ssl_planned` ON `strength_set_log` (`plannedSessionId`)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `idx_ssl_activity` ON `strength_set_log` (`activityId`)",
        )
        db.execSQL("ALTER TABLE `profile` ADD COLUMN `hrZoneBoundsJson` TEXT")
        db.execSQL("ALTER TABLE `profile` ADD COLUMN `lactateThresholdHrManual` INTEGER")
        db.execSQL("ALTER TABLE `suggested_session` ADD COLUMN `targetPaceSecPerKm` INTEGER")
        db.execSQL("ALTER TABLE `suggested_session` ADD COLUMN `structureJson` TEXT")
        db.execSQL("ALTER TABLE `suggested_session` ADD COLUMN `workoutTemplateId` TEXT")
        db.execSQL("ALTER TABLE `planned_session` ADD COLUMN `structureJson` TEXT")
        db.execSQL(
            "ALTER TABLE `planned_session` ADD COLUMN `workoutId` INTEGER " +
                "REFERENCES `strength_workout`(`id`) ON DELETE SET NULL",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `idx_planned_workout` ON `planned_session` (`workoutId`)",
        )
    }

    /**
     * 6 -> 7 (P16.1): "my equipment" and the load progression.
     *
     * Three additive changes, none of which touches an existing row: the nullable
     * `profile.availableEquipmentJson` (`null` = every piece of equipment, so the upgrade changes
     * nothing for the owner until they restrict the set), the nullable
     * `strength_set_log.feedback`, and the new `exercise_progress` table whose primary key is the
     * catalog id itself - a `TEXT` key, which is why the `CREATE TABLE` ends in
     * `PRIMARY KEY(exerciseId)` rather than an autoincrementing rowid.
     */
    private val MIGRATION_6_7 = MigrationStep(6, 7) { db ->
        db.execSQL("ALTER TABLE `profile` ADD COLUMN `availableEquipmentJson` TEXT")
        db.execSQL("ALTER TABLE `strength_set_log` ADD COLUMN `feedback` TEXT")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `exercise_progress` (" +
                "`exerciseId` TEXT NOT NULL, `loadKg` REAL, `reps` INTEGER, `seconds` INTEGER, " +
                "`lastFeedback` TEXT, `isEstimated` INTEGER NOT NULL, " +
                "`updatedDay` INTEGER NOT NULL, PRIMARY KEY(`exerciseId`))",
        )
    }

    /**
     * 7 -> 8 (P19.1): goal-driven training and the workout pool.
     *
     * Three additive columns, each with a default that keeps every existing row's behaviour:
     * `goal.isRace` (`1` — a dated goal stays a race until the owner calls it a deadline),
     * `strength_workout.useInSuggestions` (`1` — every workout stays in the rotation) and the
     * nullable `suggested_session.workoutId` (no FK: a proposal names a workout, the accepted
     * `planned_session.workoutId` is the enforced link).
     */
    private val MIGRATION_7_8 = MigrationStep(7, 8) { db ->
        db.execSQL("ALTER TABLE `goal` ADD COLUMN `isRace` INTEGER NOT NULL DEFAULT 1")
        db.execSQL(
            "ALTER TABLE `strength_workout` ADD COLUMN `useInSuggestions` INTEGER NOT NULL DEFAULT 1",
        )
        db.execSQL("ALTER TABLE `suggested_session` ADD COLUMN `workoutId` INTEGER")
    }

    /**
     * Every migration, oldest first. `.addMigrations(*ALL)` is the only call site, in each
     * platform's database builder, so adding a migration never changes it.
     */
    val ALL: Array<Migration>
        get() = STEPS.map { it.toRoomMigration() }.toTypedArray()

    /** The statements of every migration, oldest first. */
    val STEPS: List<MigrationStep> =
        listOf(
            MIGRATION_1_2,
            MIGRATION_2_3,
            MIGRATION_3_4,
            MIGRATION_4_5,
            MIGRATION_5_6,
            MIGRATION_6_7,
            MIGRATION_7_8,
        )
}

/**
 * One schema step: [from] → [to] and the statements it runs. The builder lambda keeps the
 * `db.execSQL(…)` shape of Room's `Migration { db -> … }`, which `MigrationSqlTest` reads.
 */
class MigrationStep(val from: Int, val to: Int, script: (SqlScript) -> Unit) {
    val statements: List<String> = SqlScript().also(script).statements
}

/** Records `execSQL` calls in order. */
class SqlScript {
    internal val statements = mutableListOf<String>()

    fun execSQL(sql: String) {
        statements += sql
    }
}

/** The platform's Room `Migration` running [MigrationStep.statements] in order. */
expect fun MigrationStep.toRoomMigration(): Migration
