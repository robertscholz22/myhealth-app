package com.myhealth.sync

import com.google.common.truth.Truth.assertThat
import com.myhealth.data.healthconnect.BackfillResult
import com.myhealth.data.healthconnect.SyncSummary
import com.myhealth.domain.util.AppError
import com.myhealth.domain.util.Outcome
import org.junit.Test

/**
 * Pure unit tests for [mapOutcome] (PLAN P2.7) — the [androidx.work.ListenableWorker.Result]
 * mapping itself needs WorkManager and is exercised manually / via `HealthSyncWorker.doWork`.
 */
class HealthSyncWorkerTest {

    private companion object {
        /** Any epoch day; only the offsets matter. */
        const val TODAY: Long = 20_708L
    }

    @Test
    fun ok_outcome_maps_to_success() {
        val verdict = mapOutcome(Outcome.Ok(Unit))

        assertThat(verdict).isEqualTo(WorkerVerdict.Success)
    }

    @Test
    fun health_connect_unavailable_maps_to_retry() {
        val verdict = mapOutcome(Outcome.Err(AppError.HealthConnectUnavailable))

        assertThat(verdict).isEqualTo(WorkerVerdict.Retry)
    }

    @Test
    fun load_recompute_starts_at_the_oldest_ingested_activity_day() {
        val summary = SyncSummary(activitiesInserted = 38, minAffectedDay = TODAY - 40)

        val day = loadRecomputeDay(Outcome.Ok(summary), backfillFromDay = null, today = TODAY)

        assertThat(day).isEqualTo(TODAY - 40)
    }

    @Test
    fun load_recompute_falls_back_to_today_when_no_activity_was_touched() {
        val day = loadRecomputeDay(Outcome.Ok(SyncSummary()), backfillFromDay = null, today = TODAY)

        assertThat(day).isEqualTo(TODAY)
    }

    @Test
    fun backfill_load_recompute_never_starts_later_than_its_own_start_day() {
        val result = BackfillResult(windows = 3, minAffectedDay = TODAY - 100)

        val day = loadRecomputeDay(Outcome.Ok(result), backfillFromDay = TODAY - 365, today = TODAY)

        assertThat(day).isEqualTo(TODAY - 365)
    }

    @Test
    fun permission_denied_maps_to_failure_with_a_reason() {
        val verdict = mapOutcome(Outcome.Err(AppError.HealthConnectPermissionDenied))

        assertThat(verdict).isInstanceOf(WorkerVerdict.Failure::class.java)
        assertThat((verdict as WorkerVerdict.Failure).reason).isEqualTo("Health Connect permission denied")
    }
}
