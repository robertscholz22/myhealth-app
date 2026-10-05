package com.myhealth.data.healthconnect

import com.myhealth.domain.repository.IngestResult
import com.myhealth.domain.util.AppError
import com.myhealth.domain.util.Outcome

/** Small mapping helpers shared by `HcSyncService` and `HcBackfill` (PLAN P2.6). */

/** The earlier of two optional epoch days, or whichever one is known. */
internal fun minOfDays(a: Long?, b: Long?): Long? = when {
    a == null -> b
    b == null -> a
    else -> minOf(a, b)
}

internal fun IngestResult.toSummary(minAffectedDay: Long? = null): SyncSummary = SyncSummary(
    activitiesInserted = inserted,
    activitiesMerged = merged,
    activitiesDuplicate = duplicate,
    minAffectedDay = minAffectedDay,
)

internal fun <T, R> Outcome<T>.map(transform: (T) -> R): Outcome<R> = when (this) {
    is Outcome.Ok -> Outcome.Ok(transform(value))
    is Outcome.Err -> this
}

fun AppError.describe(): String = when (this) {
    is AppError.Storage -> "storage: ${cause.message ?: cause::class.simpleName}"
    is AppError.Network -> "network: ${code ?: ""} ${cause?.message ?: ""}".trim()
    AppError.HealthConnectUnavailable -> "Health Connect unavailable"
    AppError.HealthConnectUpdateRequired -> "Health Connect update required"
    AppError.HealthConnectPermissionDenied -> "Health Connect permission denied"
    is AppError.Parse -> "parse $what: $detail"
    is AppError.Validation -> "invalid $field: $message"
    is AppError.Unexpected -> "unexpected: ${cause.message ?: cause::class.simpleName}"
}

/**
 * Health Connect has no typed "token expired" exception, so a failure whose message mentions the
 * token is treated like the `changesTokenExpired` flag: one full re-read, one fresh token.
 */
internal fun AppError.looksLikeTokenProblem(): Boolean {
    val message = (this as? AppError.Unexpected)?.cause?.message ?: return false
    return message.contains("token", ignoreCase = true)
}
