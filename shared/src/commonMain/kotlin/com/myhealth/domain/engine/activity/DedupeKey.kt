package com.myhealth.domain.engine.activity

import com.myhealth.domain.model.SportGroup

/**
 * The de-duplication bucket key of PLAN §2.4: `"${sportGroup}|${startAtMillis / 300_000}"`.
 *
 * The bucket exists purely to make candidate lookup an indexed equality query instead of a range
 * scan. It is deliberately coarser than the 3-minute start tolerance of [ActivityMatcher]: two
 * activities 1 ms apart can still land in different buckets, so a lookup must always ask for the
 * neighbouring buckets too ([neighbours]) and then apply the real predicate.
 */
object DedupeKey {

    /** Bucket width — 5 minutes (§2.4). */
    const val BUCKET_MILLIS: Long = 300_000L

    /** Bucket index of an instant. Epoch millis are non-negative, so truncation is floor. */
    fun bucketIndex(startAtMillis: Long): Long = startAtMillis / BUCKET_MILLIS

    /** The bucket an activity starting at [startAtMillis] belongs to. */
    fun of(sportGroup: SportGroup, startAtMillis: Long): String =
        "$sportGroup|${bucketIndex(startAtMillis)}"

    /** Buckets `n-1, n, n+1` — the candidate lookup set (§2.4). */
    fun neighbours(sportGroup: SportGroup, startAtMillis: Long): List<String> {
        val n = bucketIndex(startAtMillis)
        return listOf("$sportGroup|${n - 1}", "$sportGroup|$n", "$sportGroup|${n + 1}")
    }
}
