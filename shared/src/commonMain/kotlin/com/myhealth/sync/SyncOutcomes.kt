package com.myhealth.sync

import com.myhealth.data.healthconnect.BackfillResult
import com.myhealth.data.healthconnect.SyncSummary
import com.myhealth.data.healthconnect.describe
import com.myhealth.domain.util.AppError
import com.myhealth.domain.util.Outcome

/**
 * The outcome of one worker run, decoupled from the platform's job result (`ListenableWorker.Result` on Android) so
 * [mapOutcome] is unit-testable without WorkManager (PLAN P2.7).
 */
sealed interface WorkerVerdict {
    data object Success : WorkerVerdict
    data object Retry : WorkerVerdict
    data class Failure(val reason: String) : WorkerVerdict
}

/**
 * `HealthConnectUnavailable` is treated as transient (the provider may still be starting up, or
 * temporarily unbound) and retried with the scheduler's exponential backoff; every other error is a
 * terminal failure carrying its description as the output data reason.
 */
fun mapOutcome(outcome: Outcome<*>): WorkerVerdict = when (outcome) {
    is Outcome.Ok -> WorkerVerdict.Success
    is Outcome.Err -> if (outcome.error == AppError.HealthConnectUnavailable) {
        WorkerVerdict.Retry
    } else {
        WorkerVerdict.Failure(outcome.error.describe())
    }
}

/**
 * The day [SyncScheduler.requestLoadRecompute] has to start from: the earliest
 * day this run touched an exercise record on, so an initial sync or a backfill re-derives TRIMP
 * for the whole ingested history and not only for the last 28 days (verification BUG-5).
 * The load recompute widens the window by the 28-day EWMA prefix itself.
 */
fun loadRecomputeDay(
    outcome: Outcome<*>,
    backfillFromDay: Long?,
    today: Long,
): Long {
    val reported = when (val value = (outcome as? Outcome.Ok)?.value) {
        is SyncSummary -> value.minAffectedDay
        is BackfillResult -> value.minAffectedDay
        else -> null
    }
    val fallback = backfillFromDay ?: today
    return minOf(reported ?: fallback, fallback)
}
