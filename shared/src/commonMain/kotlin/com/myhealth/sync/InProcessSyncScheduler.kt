package com.myhealth.sync

import com.myhealth.domain.model.ImportKind
import com.myhealth.domain.model.ImportProgress
import com.myhealth.domain.repository.ActivityImporter
import com.myhealth.domain.util.Outcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

/**
 * The health-store work of `HealthSyncWorker` (P22): incremental sync, a backfill from a day, and
 * the re-read of recent sessions' detail streams. Each returns the service's [Outcome].
 */
class HealthJobs(
    val sync: suspend () -> Outcome<*>,
    val backfill: suspend (fromDay: Long) -> Outcome<*>,
    val reread: suspend (days: Long) -> Outcome<*>,
)

/**
 * [SyncScheduler] without a platform job service (P21): the work runs in [scope] while the app
 * process lives (the iOS shell; P22.4 adds `BGTaskScheduler` on top). Without [health] the
 * health-store calls are no-ops that stay [SyncWorkState.Idle].
 *
 * The request semantics follow `WorkManagerSyncScheduler`:
 * - target recompute: debounced by [targetDebounce]; a new request restarts the wait (`REPLACE`).
 * - load recompute: debounced by [loadDebounce]; requests arriving while one runs are folded into
 *   a single follow-up run from the **earliest** requested day, so a running historical recompute
 *   is never cancelled by a later, shorter one (BUG-14, `APPEND_OR_REPLACE`).
 * - import: one slot; a new import cancels the running one (`REPLACE`).
 * - the daily schedules run once now and then every [dailyPeriod] while the process lives.
 * - health sync: "sync now" is ignored while one runs (`KEEP`), a backfill replaces a running
 *   backfill (`REPLACE`), the periodic sync runs now and then every interval; all health work is
 *   serialised by one lock. A successful run requests the target recompute and a load recompute
 *   from the earliest touched day, exactly like `HealthSyncWorker`.
 *
 * Requests may come from any thread; all bookkeeping runs on [serial] (one thread at a time), the
 * work itself on [work].
 */
class InProcessSyncScheduler(
    private val scope: CoroutineScope,
    private val importer: () -> ActivityImporter,
    private val recomputeLoad: suspend (fromDay: Long) -> Outcome<Unit>,
    private val recomputeTargets: suspend () -> Boolean,
    private val today: () -> Long,
    private val health: HealthJobs? = null,
    private val targetDebounce: Duration = 30.seconds,
    private val loadDebounce: Duration = 10.seconds,
    private val dailyPeriod: Duration = 24.hours,
    private val serial: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1),
    private val work: CoroutineDispatcher = Dispatchers.Default,
) : SyncScheduler {

    // ---- health store (P22) ------------------------------------------------------------------

    private val syncState = MutableStateFlow<SyncWorkState>(SyncWorkState.Idle)
    private val backfillState = MutableStateFlow<SyncWorkState>(SyncWorkState.Idle)
    private val healthLock = Mutex()
    private var syncJob: Job? = null
    private var backfillJob: Job? = null
    private var rereadJob: Job? = null
    private var periodicJob: Job? = null

    override fun schedulePeriodic(intervalHours: Int) {
        val jobs = health ?: return
        scope.launch(serial) {
            periodicJob?.cancel()
            periodicJob = scope.launch(serial) {
                while (true) {
                    launchSync(jobs)
                    delay(intervalHours.coerceAtLeast(1).hours)
                }
            }
        }
    }

    override fun syncNow() {
        val jobs = health ?: return
        scope.launch(serial) { launchSync(jobs) }
    }

    private fun launchSync(jobs: HealthJobs) {
        if (syncJob?.isActive == true) return
        syncState.value = SyncWorkState.Running
        syncJob = scope.launch(serial) {
            syncState.value = runHealth(fromDay = null) { jobs.sync() }
        }
    }

    override fun backfill(fromDay: Long) {
        val jobs = health ?: return
        scope.launch(serial) {
            backfillJob?.cancel()
            backfillState.value = SyncWorkState.Running
            backfillJob = scope.launch(serial) {
                backfillState.value = runHealth(fromDay = fromDay) { jobs.backfill(fromDay) }
            }
        }
    }

    override fun rereadExerciseDetail(days: Long) {
        val jobs = health ?: return
        scope.launch(serial) {
            rereadJob?.cancel()
            rereadJob = scope.launch(serial) { runHealth(fromDay = today() - days) { jobs.reread(days) } }
        }
    }

    /** One health run under the lock, with `HealthSyncWorker`'s verdict and follow-ups. */
    private suspend fun runHealth(fromDay: Long?, block: suspend () -> Outcome<*>): SyncWorkState = try {
        val outcome = healthLock.withLock { withContext(work) { block() } }
        when (val verdict = mapOutcome(outcome)) {
            WorkerVerdict.Success -> {
                requestTargetRecompute()
                requestLoadRecompute(loadRecomputeDay(outcome, fromDay, today()))
                SyncWorkState.Idle
            }
            WorkerVerdict.Retry -> SyncWorkState.Failed(HEALTH_UNAVAILABLE)
            is WorkerVerdict.Failure -> SyncWorkState.Failed(verdict.reason)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        SyncWorkState.Failed(e.message)
    }

    override fun observeState(): Flow<SyncWorkState> = syncState

    override fun observeBackfillState(): Flow<SyncWorkState> = backfillState

    // ---- nutrition targets ----------------------------------------------------------------

    private val targetState = MutableStateFlow<SyncWorkState>(SyncWorkState.Idle)
    private var targetJob: Job? = null
    private var targetDaily: Job? = null

    override fun requestTargetRecompute() {
        scope.launch(serial) {
            targetJob?.cancel()
            targetState.value = SyncWorkState.Running
            targetJob = scope.launch(serial) {
                delay(targetDebounce)
                runTargets()
            }
        }
    }

    private suspend fun runTargets() {
        targetState.value = SyncWorkState.Running
        targetState.value = try {
            if (withContext(work) { recomputeTargets() }) SyncWorkState.Idle else SyncWorkState.Failed(null)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            SyncWorkState.Failed(e.message)
        }
    }

    override fun scheduleDailyTargetRecompute() {
        scope.launch(serial) {
            if (targetDaily?.isActive == true) return@launch
            targetDaily = scope.launch(serial) {
                while (true) {
                    runTargets()
                    delay(dailyPeriod)
                }
            }
        }
    }

    override fun observeTargetRecomputeState(): Flow<SyncWorkState> = targetState

    // ---- training load ---------------------------------------------------------------------

    private val loadState = MutableStateFlow<SyncWorkState>(SyncWorkState.Idle)
    private var pendingFromDay: Long? = null
    private var loadJob: Job? = null
    private var loadDaily: Job? = null

    override fun requestLoadRecompute(fromDay: Long) {
        scope.launch(serial) {
            pendingFromDay = pendingFromDay?.let { minOf(it, fromDay) } ?: fromDay
            loadState.value = SyncWorkState.Running
            if (loadJob?.isActive == true) return@launch
            loadJob = scope.launch(serial) {
                delay(loadDebounce)
                while (true) {
                    val from = pendingFromDay ?: break
                    pendingFromDay = null
                    runLoad(from)
                }
            }
        }
    }

    private suspend fun runLoad(fromDay: Long) {
        loadState.value = SyncWorkState.Running
        loadState.value = try {
            when (val outcome = withContext(work) { recomputeLoad(fromDay) }) {
                is Outcome.Ok -> SyncWorkState.Idle
                is Outcome.Err -> SyncWorkState.Failed(outcome.error.toString())
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            SyncWorkState.Failed(e.message)
        }
    }

    override fun scheduleDailyLoadRecompute() {
        scope.launch(serial) {
            if (loadDaily?.isActive == true) return@launch
            loadDaily = scope.launch(serial) {
                while (true) {
                    delay(dailyPeriod)
                    requestLoadRecompute(today())
                }
            }
        }
    }

    override fun observeLoadRecomputeState(): Flow<SyncWorkState> = loadState

    // ---- import ----------------------------------------------------------------------------

    private val importState = MutableStateFlow(ImportWorkState())
    private var importJob: Job? = null

    override fun startImport(uri: String, kind: String, force: Boolean) {
        scope.launch(serial) { launchImport(uri, kind, force) }
    }

    private fun launchImport(uri: String, kind: String, force: Boolean) {
        importJob?.cancel()
        val importKind = ImportKind.entries.firstOrNull { it.name == kind }
        if (importKind == null) {
            importState.value = ImportWorkState(ImportWorkState.Stage.FAILED, message = "Unsupported file type.")
            return
        }
        importState.value = ImportWorkState(ImportWorkState.Stage.RUNNING)
        importJob = scope.launch(serial) {
            var terminal: ImportProgress? = null
            try {
                importer().import(uri, importKind, force).flowOn(work).collect { progress ->
                    when (progress) {
                        is ImportProgress.Working -> importState.value = ImportWorkState(
                            stage = ImportWorkState.Stage.RUNNING,
                            parsed = progress.counts.parsed,
                            inserted = progress.counts.inserted + progress.counts.merged,
                            duplicate = progress.counts.duplicate,
                            failed = progress.counts.failed,
                            currentItem = progress.currentItem,
                        )
                        is ImportProgress.Started -> Unit
                        else -> terminal = progress
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                importState.value = ImportWorkState(ImportWorkState.Stage.FAILED, message = e.message)
                return@launch
            }
            importState.value = when (val end = terminal) {
                is ImportProgress.Finished -> ImportWorkState(
                    stage = ImportWorkState.Stage.DONE,
                    parsed = end.counts.parsed,
                    inserted = end.counts.inserted + end.counts.merged,
                    duplicate = end.counts.duplicate,
                    failed = end.counts.failed,
                )
                is ImportProgress.AlreadyImported -> ImportWorkState(
                    stage = ImportWorkState.Stage.ALREADY_IMPORTED,
                    message = end.previous.fileName,
                )
                is ImportProgress.Failed -> ImportWorkState(ImportWorkState.Stage.FAILED, message = end.error.toString())
                else -> ImportWorkState(ImportWorkState.Stage.FAILED, message = "The import produced no result.")
            }
        }
    }

    override fun observeImportState(): Flow<ImportWorkState> = importState

    override fun clearImportState() {
        scope.launch(serial) {
            importJob?.cancel()
            importState.value = ImportWorkState()
        }
    }

    private companion object {
        const val HEALTH_UNAVAILABLE = "The health store is not available right now."
    }
}
