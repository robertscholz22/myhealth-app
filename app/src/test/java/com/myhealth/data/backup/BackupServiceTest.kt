package com.myhealth.data.backup

import com.google.common.truth.Truth.assertThat
import com.myhealth.data.backup.BackupContentSource
import com.myhealth.domain.model.RideBestKind
import com.myhealth.domain.repository.BackupMode
import com.myhealth.domain.util.AppError
import com.myhealth.domain.util.Outcome
import com.myhealth.testutil.Fixtures
import kotlinx.coroutines.test.runTest
import okio.Buffer
import okio.ForwardingSink
import okio.Sink
import okio.Source
import org.junit.Test

/** Export and both import modes of PLAN P8.4, against an in-memory database. */
class BackupServiceTest {

    private val clock = Fixtures.fixedClock("2026-09-12T10:00:00Z")
    private val dao = FakeBackupDao()
    private val content = FakeBackupContent()
    private val service = BackupService(
        dao = dao,
        transaction = { block -> block() },
        content = content,
        appVersion = "1.0",
        clock = clock,
    )

    private fun seed() {
        dao.profile += BackupFixtures.profile()
        dao.bodyMeasurement += BackupFixtures.body()
        dao.activitySession += BackupFixtures.activity()
        dao.activityStream += BackupFixtures.stream()
        dao.dailyLoad += BackupFixtures.dailyLoad()
        dao.ingredient += BackupFixtures.ingredient()
        dao.mealLog += BackupFixtures.mealLog()
        dao.mealLogItem += BackupFixtures.mealLogItem()
        dao.runningBest += BackupFixtures.runningBest()
        dao.rideBest += BackupFixtures.rideBest()
        dao.strengthWorkout += BackupFixtures.strengthWorkout()
        dao.strengthWorkoutExercise += BackupFixtures.strengthWorkoutExercise()
        dao.strengthSetLog += BackupFixtures.strengthSetLog()
        dao.exerciseProgress += BackupFixtures.exerciseProgress()
    }

    @Test
    fun export_writes_every_table_and_reports_the_counts() = runTest {
        seed()

        val summary = (service.export(URI) as Outcome.Ok).value

        assertThat(summary.totalRows).isEqualTo(14)
        assertThat(summary.rowsWritten).isEqualTo(14)
        assertThat(summary.appVersion).isEqualTo("1.0")
        assertThat(summary.exportedAtMillis).isEqualTo(clock.millis())
        assertThat(summary.schemaVersion).isEqualTo(BackupFile.CURRENT_SCHEMA_VERSION)
        assertThat(content.text()).contains("\"activitySession\"")
        assertThat(content.text()).contains("Spiel")
    }

    @Test
    fun replace_wipes_the_device_and_restores_the_backup_verbatim() = runTest {
        seed()
        service.export(URI)
        dao.activitySession.clear()
        dao.activitySession += BackupFixtures.activity(id = 99L, startAtMillis = 1L, title = "Stale")
        dao.ingredient += BackupFixtures.ingredient(id = 42L, name = "Stale food")

        val summary = (service.import(URI, BackupMode.REPLACE) as Outcome.Ok).value

        assertThat(summary.rowsWritten).isEqualTo(14)
        assertThat(dao.activitySession.map { it.id to it.title }).containsExactly(1L to "Spiel")
        assertThat(dao.ingredient.map { it.name }).containsExactly("Haferflocken")
        assertThat(dao.activityStream.single().activityId).isEqualTo(1L)
    }

    @Test
    fun merge_into_an_empty_database_inserts_everything_and_remaps_foreign_keys() = runTest {
        seed()
        service.export(URI)
        clearDevice()

        val summary = (service.import(URI, BackupMode.MERGE) as Outcome.Ok).value

        assertThat(summary.rowsWritten).isEqualTo(14)
        val activityId = dao.activitySession.single().id
        assertThat(activityId).isNotEqualTo(1L)
        assertThat(dao.activityStream.single().activityId).isEqualTo(activityId)
        assertThat(dao.runningBest.single().activityId).isEqualTo(activityId)
        val mealLogId = dao.mealLog.single().id
        assertThat(dao.mealLogItem.single().mealLogId).isEqualTo(mealLogId)
        assertThat(dao.mealLogItem.single().ingredientId).isEqualTo(dao.ingredient.single().id)
    }

    /**
     * P12: `ride_best` merges on `kind|value|day`, not on its primary key, and its `activityId`
     * is remapped onto the ride this merge actually created — so a restored 20-minute best still
     * points at the right ride, and importing a backup that only differs by row id inserts nothing.
     */
    @Test
    fun bike08_backup_ride_best_merges_by_natural_key() = runTest {
        seed()
        service.export(URI)
        clearDevice()

        service.import(URI, BackupMode.MERGE)

        val activityId = dao.activitySession.single().id
        val best = dao.rideBest.single()
        assertThat(activityId).isNotEqualTo(1L)
        assertThat(best.activityId).isEqualTo(activityId)
        assertThat(best.kind).isEqualTo(RideBestKind.POWER_20MIN)
        assertThat(best.value).isEqualTo(300.0)

        // The same effort arriving again under a different id is recognised and skipped…
        content.bytes = BackupSerializer
            .encodeToString(BackupFixtures.file().copy(rideBest = listOf(BackupFixtures.rideBest(id = 77L))))
            .toByteArray()
        service.import(URI, BackupMode.MERGE)
        assertThat(dao.rideBest).hasSize(1)

        // …while a genuinely different kind is a new row.
        content.bytes = BackupSerializer
            .encodeToString(
                BackupFixtures.file().copy(
                    rideBest = listOf(
                        BackupFixtures.rideBest(id = 5L, kind = RideBestKind.TIME_40K, value = 4_478.0),
                    ),
                ),
            )
            .toByteArray()
        service.import(URI, BackupMode.MERGE)
        assertThat(dao.rideBest.map { it.kind })
            .containsExactly(RideBestKind.POWER_20MIN, RideBestKind.TIME_40K)
        assertThat(dao.rideBest.first { it.kind == RideBestKind.TIME_40K }.activityId)
            .isEqualTo(activityId)
    }

    /**
     * P14: `strength_workout` merges on its `templateId` (a user's own workout on its name), its
     * exercise rows ride along as owned children under the id the merge handed out, and a set log
     * merges on `completedAtMillis|exerciseId|setIndex` — so restoring a backup twice leaves one
     * "Upper A" with one row, not two.
     */
    @Test
    fun strength_workout_and_set_log_merge_by_natural_key() = runTest {
        seed()
        service.export(URI)
        clearDevice()

        service.import(URI, BackupMode.MERGE)

        val workoutId = dao.strengthWorkout.single().id
        assertThat(workoutId).isNotEqualTo(1L)
        assertThat(dao.strengthWorkout.single().templateId).isEqualTo("UPPER_A")
        assertThat(dao.strengthWorkoutExercise.single().workoutId).isEqualTo(workoutId)
        assertThat(dao.strengthSetLog.single().exerciseId).isEqualTo("BARBELL_BENCH_PRESS")

        // The same workout and the same logged set arriving again under new ids change nothing.
        content.bytes = BackupSerializer
            .encodeToString(
                BackupFixtures.file().copy(
                    strengthWorkout = listOf(BackupFixtures.strengthWorkout(id = 77L)),
                    strengthSetLog = listOf(BackupFixtures.strengthSetLog(id = 88L)),
                ),
            )
            .toByteArray()
        service.import(URI, BackupMode.MERGE)

        assertThat(dao.strengthWorkout).hasSize(1)
        assertThat(dao.strengthWorkout.single().id).isEqualTo(workoutId)
        assertThat(dao.strengthSetLog).hasSize(1)
        assertThat(dao.strengthWorkoutExercise).hasSize(1)
        assertThat(dao.exerciseProgress.single().reps).isEqualTo(8)
    }

    /** The smoke test in code: importing the same file twice must not double anything. */
    @Test
    fun merging_the_same_backup_twice_inserts_nothing_the_second_time() = runTest {
        seed()
        service.export(URI)
        clearDevice()
        service.import(URI, BackupMode.MERGE)
        val afterFirst = dao.activitySession.size to dao.mealLogItem.size

        val second = (service.import(URI, BackupMode.MERGE) as Outcome.Ok).value

        assertThat(second.rowsWritten).isEqualTo(0)
        assertThat(dao.activitySession.size to dao.mealLogItem.size).isEqualTo(afterFirst)
        assertThat(dao.activitySession).hasSize(1)
        assertThat(dao.ingredient).hasSize(1)
        assertThat(dao.runningBest).hasSize(1)
    }

    @Test
    fun merge_never_overwrites_a_row_the_device_already_has() = runTest {
        seed()
        service.export(URI)
        dao.profile.clear()
        dao.profile += BackupFixtures.profile(name = "Newer profile")
        dao.dailyLoad.clear()
        dao.dailyLoad += BackupFixtures.dailyLoad(ctl = 99.0)

        service.import(URI, BackupMode.MERGE)

        assertThat(dao.profile.single().displayName).isEqualTo("Newer profile")
        assertThat(dao.dailyLoad.single().ctl).isEqualTo(99.0)
    }

    @Test
    fun a_backup_from_a_newer_schema_is_refused_without_touching_the_database() = runTest {
        seed()
        content.bytes = BackupSerializer
            .encodeToString(BackupFixtures.file().copy(schemaVersion = 99))
            .toByteArray()

        val outcome = service.import(URI, BackupMode.REPLACE)

        assertThat(outcome).isInstanceOf(Outcome.Err::class.java)
        assertThat((outcome as Outcome.Err).error).isInstanceOf(AppError.Validation::class.java)
        assertThat(dao.activitySession).hasSize(1)
        assertThat(dao.ingredient).hasSize(1)
    }

    private fun clearDevice() {
        dao.profile.clear()
        dao.bodyMeasurement.clear()
        dao.activitySession.clear()
        dao.activityStream.clear()
        dao.dailyLoad.clear()
        dao.ingredient.clear()
        dao.mealLog.clear()
        dao.mealLogItem.clear()
        dao.runningBest.clear()
        dao.rideBest.clear()
        dao.strengthWorkout.clear()
        dao.strengthWorkoutExercise.clear()
        dao.strengthSetLog.clear()
        dao.exerciseProgress.clear()
    }

    private companion object {
        const val URI = "content://docs/backup.json"
    }
}

/** A document provider backed by one byte array. */
private class FakeBackupContent : BackupContentSource {

    var bytes: ByteArray = ByteArray(0)

    fun text(): String = String(bytes)

    override suspend fun openInput(uri: String): Source = Buffer().write(bytes)

    override suspend fun openOutput(uri: String): Sink = object : ForwardingSink(Buffer()) {
        override fun close() {
            super.close()
            bytes = (delegate as Buffer).readByteArray()
        }
    }
}
