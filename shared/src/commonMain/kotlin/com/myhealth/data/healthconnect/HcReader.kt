package com.myhealth.data.healthconnect

import com.myhealth.domain.util.Outcome
import kotlinx.datetime.TimeZone

/**
 * Everything the app reads out of Health Connect (PLAN P2.2), expressed in the plain DTOs of
 * `HcDto.kt`. An interface so the sync pipeline (P2.6) can be driven by a fake in tests — the
 * real implementation needs a device with a Health Connect provider. P20.2: the platform seam of
 * the shared sync — `HealthConnectReader` on Android, a HealthKit reader on iOS (P22); times are
 * epoch millis and epoch days instead of `java.time` types.
 *
 * Every method returns an [Outcome] instead of throwing, and every read is bounded by an
 * explicit time range.
 */
interface HcReader {

    /** Exercise sessions overlapping `[fromMillis, toMillis)`, each with its own per-session series. */
    suspend fun readExerciseSessions(fromMillis: Long, toMillis: Long): Outcome<List<HcExercise>>

    /** One [HcDailySummary] per local day (epoch day) in `[fromDay, toDay]` that has any data at all. */
    suspend fun readDailySummaries(
        fromDay: Long,
        toDay: Long,
        zone: TimeZone,
    ): Outcome<List<HcDailySummary>>

    /** Sleep sessions overlapping `[fromMillis, toMillis)`, one per record (not yet merged). */
    suspend fun readSleep(fromMillis: Long, toMillis: Long): Outcome<List<HcSleep>>

    /** Weight and body-fat records in `[fromMillis, toMillis)`, one [HcBody] per record. */
    suspend fun readBody(fromMillis: Long, toMillis: Long): Outcome<List<HcBody>>

    /**
     * A fresh changes token covering exactly the record types of [kinds] (amendment A6). Changes
     * are reported from the moment the token is issued, never retroactively.
     */
    suspend fun getChangesToken(kinds: Set<HcRecordKind>): Outcome<String>

    /** One page of changes for [token]; loop while [HcChanges.hasMore] is true. */
    suspend fun getChanges(token: String): Outcome<HcChanges>
}
