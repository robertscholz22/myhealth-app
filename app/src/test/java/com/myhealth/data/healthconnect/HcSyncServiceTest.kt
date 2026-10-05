package com.myhealth.data.healthconnect

import androidx.health.connect.client.records.ExerciseSessionRecord
import com.google.common.truth.Truth.assertThat
import com.myhealth.domain.model.ActivitySource
import com.myhealth.domain.repository.SyncKeys
import com.myhealth.domain.util.AppError
import com.myhealth.domain.util.Outcome
import com.myhealth.testutil.Fixtures
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * Token lifecycle, change application and resumable backfill of PLAN P2.6 (amendment A6), driven
 * by a fake [HcReader] and fake repositories — no Health Connect provider and no device.
 */
class HcSyncServiceTest {

    private val zone: ZoneId = ZoneId.of("UTC")
    private val clock = Fixtures.fixedClock("2026-09-12T12:00:00Z", zone)
    private val today = LocalDate.of(2026, 9, 12).toEpochDay()

    private val reader = FakeHcReader()
    private val activityRepo = FakeActivityRepository()
    private val healthRepo = FakeHealthRepository()
    private val bodyRepo = FakeBodyRepository()
    private val syncState = FakeSyncStateRepository()

    private val sync = HcSyncService(
        reader = reader,
        mapper = HealthConnectMapper(),
        activityRepo = activityRepo,
        healthRepo = healthRepo,
        bodyRepo = bodyRepo,
        syncStateRepo = syncState,
        clock = clock,
        zone = TimeZone.UTC,
    )

    // ---- first run ------------------------------------------------------------------------------

    @Test
    fun first_run_reads_the_bounded_window_and_acquires_a_token_per_channel() = runTest {
        reader.exercises = listOf(exercise("hc-1", "2026-09-11T06:00:00Z"))
        reader.daily = listOf(HcDailySummary(day = today, steps = 8_000))

        val outcome = sync.syncIncremental()

        assertThat(outcome).isInstanceOf(Outcome.Ok::class.java)
        val summary = (outcome as Outcome.Ok).value
        assertThat(summary.fullReads).isEqualTo(4)
        assertThat(summary.activitiesInserted).isEqualTo(1)
        assertThat(summary.daysUpdated).isEqualTo(1)

        // Exactly one 30-day window per channel, and no getChanges call at all.
        assertThat(reader.exerciseWindows).hasSize(1)
        assertThat(reader.dailyWindows.single().first).isEqualTo(LocalDate.ofEpochDay(today - 29))
        assertThat(reader.changesCalls).isEmpty()

        // Every channel now holds a token and a success timestamp.
        val keys = listOf(SyncKeys.HC_EXERCISE, SyncKeys.HC_DAILY, SyncKeys.HC_SLEEP, SyncKeys.HC_BODY)
        assertThat(syncState.states.keys).containsAtLeastElementsIn(keys)
        keys.forEach { key ->
            assertThat(syncState.states.getValue(key).changesToken).isNotNull()
            assertThat(syncState.states.getValue(key).lastSuccessAtMillis).isEqualTo(clock.millis())
        }
        assertThat(reader.tokenRequests).hasSize(4)
        assertThat(reader.tokenRequests.first()).containsExactly(HcRecordKind.EXERCISE)
    }

    // ---- incremental ----------------------------------------------------------------------------

    @Test
    fun incremental_upsert_ingests_the_changed_session_without_a_full_read() = runTest {
        givenTokens()
        reader.pages["exercise-token"] = HcChanges(
            upserts = listOf(HcRecordDto.Exercise(exercise("hc-9", "2026-09-12T06:00:00Z"))),
            nextToken = "exercise-token-2",
        )

        val summary = (sync.syncIncremental() as Outcome.Ok).value

        assertThat(summary.activitiesInserted).isEqualTo(1)
        assertThat(summary.fullReads).isEqualTo(0)
        assertThat(reader.exerciseWindows).isEmpty()
        assertThat(activityRepo.ingested.single().record.externalId).isEqualTo("hc-9")
        assertThat(activityRepo.ingested.single().session.sportGroup.name).isEqualTo("RUN")
        assertThat(syncState.states.getValue(SyncKeys.HC_EXERCISE).changesToken)
            .isEqualTo("exercise-token-2")
    }

    @Test
    fun paging_continues_while_has_more_and_persists_every_next_token() = runTest {
        givenTokens()
        reader.pages["exercise-token"] = HcChanges(
            upserts = listOf(HcRecordDto.Exercise(exercise("hc-a", "2026-09-12T06:00:00Z"))),
            nextToken = "page-2",
            hasMore = true,
        )
        reader.pages["page-2"] = HcChanges(
            upserts = listOf(HcRecordDto.Exercise(exercise("hc-b", "2026-09-12T08:00:00Z"))),
            nextToken = "page-3",
        )

        val summary = (sync.syncIncremental() as Outcome.Ok).value

        assertThat(summary.activitiesInserted).isEqualTo(2)
        assertThat(reader.changesCalls).containsAtLeast("exercise-token", "page-2").inOrder()
        assertThat(syncState.states.getValue(SyncKeys.HC_EXERCISE).changesToken).isEqualTo("page-3")
    }

    @Test
    fun deletion_change_removes_the_source_record() = runTest {
        givenTokens()
        reader.pages["exercise-token"] = HcChanges(
            deletedIds = listOf("hc-gone"),
            nextToken = "exercise-token-2",
        )
        reader.pages["sleep-token"] = HcChanges(
            deletedIds = listOf("sleep-gone"),
            nextToken = "sleep-token-2",
        )
        reader.pages["body-token"] = HcChanges(
            deletedIds = listOf("body-gone"),
            nextToken = "body-token-2",
        )

        val summary = (sync.syncIncremental() as Outcome.Ok).value

        assertThat(summary.activitiesDeleted).isEqualTo(1)
        assertThat(activityRepo.removed)
            .containsExactly(ActivitySource.HEALTH_CONNECT to "hc-gone")
        assertThat(healthRepo.deletedSleep).containsExactly("sleep-gone")
        assertThat(bodyRepo.deleted).containsExactly("body-gone")
    }

    // ---- token expiry ---------------------------------------------------------------------------

    @Test
    fun expired_token_triggers_exactly_one_full_re_read_and_a_fresh_token() = runTest {
        givenTokens()
        reader.exercises = listOf(exercise("hc-old", "2026-09-01T06:00:00Z"))
        reader.pages["exercise-token"] = HcChanges(expired = true, nextToken = "")

        val summary = (sync.syncIncremental() as Outcome.Ok).value

        // Exactly one full read, exactly one getChanges call: the expiry path never loops.
        assertThat(summary.fullReads).isEqualTo(1)
        assertThat(reader.exerciseWindows).hasSize(1)
        assertThat(reader.changesCalls.count { it == "exercise-token" }).isEqualTo(1)
        assertThat(activityRepo.ingested.single().record.externalId).isEqualTo("hc-old")

        val state = syncState.states.getValue(SyncKeys.HC_EXERCISE)
        assertThat(state.changesToken).isEqualTo("tok-1")
        assertThat(state.lastError).contains("expired")
        assertThat(state.lastErrorAtMillis).isEqualTo(clock.millis())
        assertThat(state.lastSuccessAtMillis).isEqualTo(clock.millis())
    }

    @Test
    fun a_failing_channel_is_recorded_and_the_others_still_sync() = runTest {
        givenTokens()
        activityRepo.ingestError = AppError.Storage(RuntimeException("disk full"))
        reader.pages["exercise-token"] = HcChanges(
            upserts = listOf(HcRecordDto.Exercise(exercise("hc-x", "2026-09-12T06:00:00Z"))),
            nextToken = "exercise-token-2",
        )

        val summary = (sync.syncIncremental() as Outcome.Ok).value

        assertThat(summary.errors).hasSize(1)
        assertThat(summary.errors.single()).contains(SyncKeys.HC_EXERCISE)
        assertThat(syncState.states.getValue(SyncKeys.HC_EXERCISE).lastError).contains("disk full")
        assertThat(syncState.states.getValue(SyncKeys.HC_SLEEP).lastSuccessAtMillis)
            .isEqualTo(clock.millis())
    }

    // ---- backfill -------------------------------------------------------------------------------

    @Test
    fun backfill_is_refused_without_the_history_permission() = runTest {
        val backfill = backfill(granted = emptySet())

        val outcome = backfill.run(fromDay = today - 40)

        assertThat(outcome).isEqualTo(Outcome.Err(AppError.HealthConnectPermissionDenied))
        assertThat(reader.exerciseWindows).isEmpty()
        assertThat(syncState.states.getValue(SyncKeys.HC_EXERCISE).lastError)
            .contains("READ_HEALTH_DATA_HISTORY")
    }

    @Test
    fun backfill_reads_newest_to_oldest_in_fourteen_day_windows() = runTest {
        val outcome = backfill().run(fromDay = today - 41)

        val result = (outcome as Outcome.Ok).value
        assertThat(result.windows).isEqualTo(3)
        assertThat(result.finished).isTrue()
        assertThat(result.completeDay).isEqualTo(today - 41)
        assertThat(reader.dailyWindows.map { it.first.toEpochDay() to it.second.toEpochDay() })
            .containsExactly(
                (today - 13) to today,
                (today - 27) to (today - 14),
                (today - 41) to (today - 28),
            )
            .inOrder()
    }

    @Test
    fun backfill_resumes_from_the_backfill_complete_day_after_an_interruption() = runTest {
        reader.failExerciseFromDay = today - 27

        val first = backfill().run(fromDay = today - 41)

        assertThat(first).isInstanceOf(Outcome.Err::class.java)
        assertThat(syncState.states.getValue(SyncKeys.HC_EXERCISE).backfillCompleteDay)
            .isEqualTo(today - 13)

        // The provider recovers; the rerun must not read the window that already succeeded.
        reader.failExerciseFromDay = null
        reader.dailyWindows.clear()

        val second = (backfill().run(fromDay = today - 41) as Outcome.Ok).value

        assertThat(second.windows).isEqualTo(2)
        assertThat(second.completeDay).isEqualTo(today - 41)
        assertThat(reader.dailyWindows.map { it.first.toEpochDay() })
            .containsExactly(today - 27, today - 41)
            .inOrder()
    }

    // ---- helpers --------------------------------------------------------------------------------

    private fun backfill(granted: Set<String> = setOf(HcPermissions.HISTORY)) = HcBackfill(
        sync = sync,
        syncStateRepo = syncState,
        historyGranted = { HcPermissions.HISTORY in granted },
        clock = clock,
        windowDelayMillis = 250L,
    )

    /** Pre-seeds a stored changes token per channel, so sync takes the incremental path. */
    private fun givenTokens() {
        listOf(
            SyncKeys.HC_EXERCISE to "exercise-token",
            SyncKeys.HC_DAILY to "daily-token",
            SyncKeys.HC_SLEEP to "sleep-token",
            SyncKeys.HC_BODY to "body-token",
        ).forEach { (key, token) ->
            syncState.states[key] = com.myhealth.domain.model.SyncState(
                key = key,
                changesToken = token,
                lastSuccessAtMillis = null,
                lastErrorAtMillis = null,
                lastError = null,
                backfillCompleteDay = null,
            )
        }
    }

    // ---- load recompute window / empty-day cleanup ----------------------------------------------

    @Test
    fun first_run_reports_the_oldest_ingested_activity_day_as_min_affected() = runTest {
        reader.exercises = listOf(
            exercise("hc-old", "2026-08-03T06:00:00Z"),
            exercise("hc-new", "2026-09-11T06:00:00Z"),
        )

        val summary = (sync.syncIncremental() as Outcome.Ok).value

        // 2026-08-03 is 40 days before the fixed "today" of 2026-09-12.
        assertThat(summary.minAffectedDay).isEqualTo(today - 40)
        assertThat(activityRepo.ingested).hasSize(2)
    }

    @Test
    fun sync_without_any_activity_reports_no_min_affected_day() = runTest {
        reader.daily = listOf(HcDailySummary(day = today, steps = 8_000))

        val summary = (sync.syncIncremental() as Outcome.Ok).value

        assertThat(summary.minAffectedDay).isNull()
    }

    @Test
    fun sync_deletes_daily_rows_that_only_carry_the_synthetic_energy_baseline() = runTest {
        reader.daily = listOf(
            HcDailySummary(day = today, steps = 8_000, totalEnergyKcal = 2_400.0),
            HcDailySummary(day = today - 1, totalEnergyKcal = 1_564.5),
        )

        val summary = (sync.syncIncremental() as Outcome.Ok).value

        assertThat(summary.emptyDaysRemoved).isEqualTo(1)
        assertThat(healthRepo.summaries.value.keys).containsExactly(today)
    }

    private fun exercise(externalId: String, startIso: String) = HcExercise(
        externalId = externalId,
        packageName = "com.garmin.android.apps.connectmobile",
        startMillis = Fixtures.millis(startIso, zone),
        endMillis = Fixtures.millis(startIso, zone) + 3_600_000L,
        exerciseType = ExerciseSessionRecord.EXERCISE_TYPE_RUNNING,
        title = null,
        notes = null,
        distanceMeters = 10_000.0,
        activeEnergyKcal = 700.0,
    )
}
