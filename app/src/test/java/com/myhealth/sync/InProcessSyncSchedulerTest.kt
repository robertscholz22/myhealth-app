package com.myhealth.sync

import com.google.common.truth.Truth.assertThat
import com.myhealth.domain.model.ImportCounts
import com.myhealth.domain.model.ImportKind
import com.myhealth.domain.model.ImportProgress
import com.myhealth.domain.model.ImportRecord
import com.myhealth.domain.repository.ActivityImporter
import com.myhealth.domain.util.AppError
import com.myhealth.domain.util.Outcome
import com.myhealth.data.healthconnect.BackfillResult
import com.myhealth.data.healthconnect.SyncSummary
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

/** P21: the iOS shell's [InProcessSyncScheduler] keeps the WorkManager request semantics. */
@OptIn(ExperimentalCoroutinesApi::class)
class InProcessSyncSchedulerTest {

    private val loadRuns = mutableListOf<Long>()
    private var targetRuns = 0
    private var targetsSucceed = true
    private var loadGate: CompletableDeferred<Unit>? = null
    private var importer: ActivityImporter = importerOf(flow { })

    private val healthRuns = mutableListOf<String>()
    private var healthGate: CompletableDeferred<Unit>? = null
    private var syncOutcome: Outcome<*> = Outcome.Ok(SyncSummary(minAffectedDay = 19_990L))

    private val healthJobs = HealthJobs(
        sync = {
            healthRuns += "sync"
            healthGate?.await()
            syncOutcome
        },
        backfill = { from ->
            healthRuns += "backfill:$from"
            healthGate?.await()
            Outcome.Ok(BackfillResult(finished = true))
        },
        reread = { days ->
            healthRuns += "reread:$days"
            Outcome.Ok(SyncSummary())
        },
    )

    private fun TestScope.scheduler(health: HealthJobs? = null): InProcessSyncScheduler {
        val dispatcher = StandardTestDispatcher(testScheduler)
        return InProcessSyncScheduler(
            scope = backgroundScope,
            importer = { importer },
            recomputeLoad = { from ->
                loadRuns += from
                loadGate?.await()
                Outcome.Ok(Unit)
            },
            recomputeTargets = {
                targetRuns++
                targetsSucceed
            },
            today = { 20_000L },
            health = health,
            serial = dispatcher,
            work = dispatcher,
        )
    }

    /** `advanceUntilIdle` ignores `backgroundScope` work, so time is advanced explicitly. */
    private fun TestScope.settle() {
        advanceTimeBy(60.seconds)
        runCurrent()
    }

    private fun importerOf(progress: Flow<ImportProgress>) = object : ActivityImporter {
        override fun import(uri: String, kind: ImportKind, force: Boolean): Flow<ImportProgress> = progress
    }

    private fun record(name: String) = ImportRecord(7L, ImportKind.FIT_FILE, name, "ab", 0L, 1, 1, 0, null)

    @Test
    fun ips01_load_burst_runs_once_from_the_earliest_day_after_the_debounce() = runTest {
        val s = scheduler()
        s.requestLoadRecompute(100)
        s.requestLoadRecompute(50)
        s.requestLoadRecompute(80)
        advanceTimeBy(9.seconds)
        runCurrent()
        assertThat(loadRuns).isEmpty()
        assertThat(s.observeLoadRecomputeState().first()).isEqualTo(SyncWorkState.Running)
        settle()
        assertThat(loadRuns).containsExactly(50L)
        assertThat(s.observeLoadRecomputeState().first()).isEqualTo(SyncWorkState.Idle)
    }

    @Test
    fun ips02_requests_during_a_run_do_not_cancel_it_and_follow_from_their_earliest_day() = runTest {
        val gate = CompletableDeferred<Unit>().also { loadGate = it }
        val s = scheduler()
        s.requestLoadRecompute(10)
        advanceTimeBy(11.seconds)
        runCurrent()
        assertThat(loadRuns).containsExactly(10L)
        s.requestLoadRecompute(300)
        s.requestLoadRecompute(200)
        runCurrent()
        gate.complete(Unit)
        settle()
        assertThat(loadRuns).containsExactly(10L, 200L).inOrder()
    }

    @Test
    fun ips03_target_requests_are_debounced_and_failures_reported() = runTest {
        val s = scheduler()
        repeat(3) {
            s.requestTargetRecompute()
            advanceTimeBy(20.seconds)
        }
        assertThat(targetRuns).isEqualTo(0)
        settle()
        assertThat(targetRuns).isEqualTo(1)
        assertThat(s.observeTargetRecomputeState().first()).isEqualTo(SyncWorkState.Idle)

        targetsSucceed = false
        s.requestTargetRecompute()
        settle()
        assertThat(s.observeTargetRecomputeState().first()).isInstanceOf(SyncWorkState.Failed::class.java)
    }

    @Test
    fun ips04_daily_schedules_run_now_and_every_day_once_however_often_scheduled() = runTest {
        val s = scheduler()
        s.scheduleDailyTargetRecompute()
        s.scheduleDailyTargetRecompute()
        s.scheduleDailyLoadRecompute()
        s.scheduleDailyLoadRecompute()
        runCurrent()
        assertThat(targetRuns).isEqualTo(1)
        assertThat(loadRuns).isEmpty()
        advanceTimeBy(24.hours + 11.seconds)
        runCurrent()
        assertThat(targetRuns).isEqualTo(2)
        assertThat(loadRuns).containsExactly(20_000L)
    }

    @Test
    fun ips05_import_progress_and_result_are_mapped_like_the_import_worker() = runTest {
        val gate = CompletableDeferred<Unit>()
        importer = importerOf(
            flow {
                emit(ImportProgress.Started("a.fit", ImportKind.FIT_FILE))
                emit(ImportProgress.Working(ImportCounts(parsed = 3, inserted = 1, merged = 1, duplicate = 1), "x.fit"))
                gate.await()
                emit(ImportProgress.Finished(record("a.fit"), ImportCounts(parsed = 4, inserted = 2, merged = 1, duplicate = 1, failed = 0)))
            },
        )
        val s = scheduler()
        s.startImport("file:///a.fit", ImportKind.FIT_FILE.name, force = false)
        runCurrent()
        assertThat(s.observeImportState().first()).isEqualTo(
            ImportWorkState(ImportWorkState.Stage.RUNNING, parsed = 3, inserted = 2, duplicate = 1, currentItem = "x.fit"),
        )
        gate.complete(Unit)
        settle()
        assertThat(s.observeImportState().first()).isEqualTo(
            ImportWorkState(ImportWorkState.Stage.DONE, parsed = 4, inserted = 3, duplicate = 1),
        )
        s.clearImportState()
        runCurrent()
        assertThat(s.observeImportState().first()).isEqualTo(ImportWorkState())
    }

    @Test
    fun ips06_already_imported_failed_and_unknown_kind() = runTest {
        val s = scheduler()
        importer = importerOf(flow { emit(ImportProgress.AlreadyImported(record("old.zip"))) })
        s.startImport("file:///b.zip", ImportKind.GARMIN_ZIP.name, force = false)
        settle()
        assertThat(s.observeImportState().first())
            .isEqualTo(ImportWorkState(ImportWorkState.Stage.ALREADY_IMPORTED, message = "old.zip"))

        importer = importerOf(flow { emit(ImportProgress.Failed(AppError.Parse("csv", "bad header"))) })
        s.startImport("file:///c.csv", ImportKind.GARMIN_CSV.name, force = true)
        settle()
        assertThat(s.observeImportState().first().stage).isEqualTo(ImportWorkState.Stage.FAILED)
        assertThat(s.observeImportState().first().message).contains("bad header")

        s.startImport("file:///d.txt", "TXT", force = false)
        settle()
        assertThat(s.observeImportState().first())
            .isEqualTo(ImportWorkState(ImportWorkState.Stage.FAILED, message = "Unsupported file type."))
    }

    @Test
    fun ips07_a_new_import_replaces_the_running_one() = runTest {
        val never = CompletableDeferred<Unit>()
        importer = importerOf(
            flow {
                emit(ImportProgress.Working(ImportCounts(parsed = 1), "slow.fit"))
                never.await()
                emit(ImportProgress.Finished(record("slow.fit"), ImportCounts(parsed = 99)))
            },
        )
        val s = scheduler()
        s.startImport("file:///slow.fit", ImportKind.FIT_FILE.name, force = false)
        runCurrent()
        importer = importerOf(flow { emit(ImportProgress.Finished(record("b.fit"), ImportCounts(parsed = 2, inserted = 2))) })
        s.startImport("file:///b.fit", ImportKind.FIT_FILE.name, force = false)
        never.complete(Unit)
        settle()
        assertThat(s.observeImportState().first())
            .isEqualTo(ImportWorkState(ImportWorkState.Stage.DONE, parsed = 2, inserted = 2))
    }

    @Test
    fun ips08_health_calls_are_inert_without_health_jobs() = runTest {
        val s = scheduler()
        s.schedulePeriodic(6)
        s.syncNow()
        s.backfill(19_000)
        s.rereadExerciseDetail(30)
        settle()
        assertThat(s.observeState().first()).isEqualTo(SyncWorkState.Idle)
        assertThat(s.observeBackfillState().first()).isEqualTo(SyncWorkState.Idle)
    }

    @Test
    fun ips09_sync_now_keeps_a_running_sync_and_triggers_the_recomputes() = runTest {
        val gate = CompletableDeferred<Unit>().also { healthGate = it }
        val s = scheduler(healthJobs)
        s.syncNow()
        runCurrent()
        s.syncNow()
        runCurrent()
        assertThat(healthRuns).containsExactly("sync")
        assertThat(s.observeState().first()).isEqualTo(SyncWorkState.Running)
        gate.complete(Unit)
        settle()
        assertThat(s.observeState().first()).isEqualTo(SyncWorkState.Idle)
        assertThat(targetRuns).isEqualTo(1)
        assertThat(loadRuns).containsExactly(19_990L)
    }

    @Test
    fun ips10_failures_are_reported_and_unavailable_is_named() = runTest {
        val s = scheduler(healthJobs)
        syncOutcome = Outcome.Err(AppError.HealthConnectUnavailable)
        s.syncNow()
        settle()
        assertThat(s.observeState().first())
            .isEqualTo(SyncWorkState.Failed("The health store is not available right now."))
        syncOutcome = Outcome.Err(AppError.HealthConnectPermissionDenied)
        s.syncNow()
        settle()
        assertThat(s.observeState().first()).isInstanceOf(SyncWorkState.Failed::class.java)
        assertThat(targetRuns).isEqualTo(0)
        assertThat(loadRuns).isEmpty()
    }

    @Test
    fun ips11_periodic_sync_runs_now_and_every_interval_and_backfill_replaces() = runTest {
        val s = scheduler(healthJobs)
        s.schedulePeriodic(6)
        runCurrent()
        assertThat(healthRuns).containsExactly("sync")
        advanceTimeBy(6.hours + 1.seconds)
        runCurrent()
        assertThat(healthRuns).containsExactly("sync", "sync")

        healthRuns.clear()
        val gate = CompletableDeferred<Unit>().also { healthGate = it }
        s.backfill(19_000)
        runCurrent()
        s.backfill(18_500)
        runCurrent()
        gate.complete(Unit)
        settle()
        assertThat(healthRuns).containsExactly("backfill:19000", "backfill:18500").inOrder()
        assertThat(s.observeBackfillState().first()).isEqualTo(SyncWorkState.Idle)
        // the backfill re-derives the load from its start day
        assertThat(loadRuns).contains(18_500L)

        s.rereadExerciseDetail(30)
        settle()
        assertThat(healthRuns.last()).isEqualTo("reread:30")
    }

    @Test
    fun ips12_background_run_awaits_sync_and_both_recomputes() = runTest {
        val s = scheduler(healthJobs)
        assertThat(s.syncAndRecomputeNow()).isTrue()
        assertThat(healthRuns).containsExactly("sync")
        assertThat(targetRuns).isEqualTo(1)
        assertThat(loadRuns).containsExactly(19_990L)

        syncOutcome = Outcome.Err(AppError.HealthConnectUnavailable)
        assertThat(s.syncAndRecomputeNow()).isFalse()
        assertThat(s.observeState().first())
            .isEqualTo(SyncWorkState.Failed("The health store is not available right now."))
        // the recomputes still run for today
        assertThat(loadRuns.last()).isEqualTo(20_000L)

        assertThat(scheduler().syncAndRecomputeNow()).isTrue()
    }
}
