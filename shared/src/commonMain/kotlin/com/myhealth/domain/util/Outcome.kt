package com.myhealth.domain.util

import okio.IOException

/**
 * Result type for anything that can fail (PLAN §1.5). Flows of cached data never use this — they
 * emit last-known data and expose a separate `syncStatus` — only one-shot repository/engine calls do.
 */
sealed interface Outcome<out T> {
    data class Ok<T>(val value: T) : Outcome<T>
    data class Err(val error: AppError) : Outcome<Nothing>
}

/**
 * Runs [block], catching any [Exception] and mapping it to an [Outcome]. `IOException` (and
 * subtypes, e.g. Room/disk failures) map to [AppError.Storage]; anything else maps to
 * [AppError.Unexpected]. Programmer errors (`require`/`check` on caller-controlled invariants)
 * are expected to be `Error`s or otherwise not caught here.
 */
inline fun <T> runCatchingApp(block: () -> T): Outcome<T> = try {
    Outcome.Ok(block())
} catch (e: IOException) {
    Outcome.Err(AppError.Storage(e))
} catch (e: Exception) {
    Outcome.Err(AppError.Unexpected(e))
}
