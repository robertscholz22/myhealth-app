package com.myhealth.domain.repository

import com.myhealth.domain.model.ActivitySession
import com.myhealth.domain.model.ActivitySource
import com.myhealth.domain.model.ActivitySourceRecord
import com.myhealth.domain.model.ActivitySummary
import com.myhealth.domain.model.LoadMethod
import com.myhealth.domain.model.SportGroup
import com.myhealth.domain.model.SportType
import com.myhealth.domain.util.Outcome
import kotlinx.coroutines.flow.Flow

/**
 * Counts returned by [ActivityRepository.ingest] (P2.5). Declared here rather than in
 * `domain/model` because it is part of this contract only — `domain/` may not reference
 * `data/`, so the ingestion result type has to live on the domain side of the boundary.
 */
data class IngestResult(val inserted: Int, val merged: Int, val duplicate: Int) {
    operator fun plus(other: IngestResult): IngestResult = IngestResult(
        inserted = inserted + other.inserted,
        merged = merged + other.merged,
        duplicate = duplicate + other.duplicate,
    )

    companion object {
        val EMPTY = IngestResult(inserted = 0, merged = 0, duplicate = 0)
    }
}

/**
 * One arrival to ingest: the `activity_source_record` row that makes ingestion idempotent, plus
 * the **normalized candidate session** decoded from that record's payload by the source's own
 * mapper (`HcMapper`, the FIT mapper of P7, …).
 *
 * Both halves are needed: the repository cannot decode `payloadJson` itself — the payload schema
 * belongs to the source — and the de-dup/merge engine (§2.4) works on field values, not on JSON.
 */
data class ActivityIngestItem(
    val record: ActivitySourceRecord,
    val session: ActivitySession,
)

/**
 * The canonical, merged `activity_session` (PLAN §2.2.2). List reads return the light
 * [ActivitySummary]; detail reads return the "full" [ActivitySession] with streams and laps (§2.3).
 *
 * [ingest] is the single write path for synced/imported data: it de-duplicates and merges per
 * §2.4 and is idempotent on `(source, externalId)`. User edits go through the named setters so
 * `userEditedFields` can be maintained — a merge must never overwrite them.
 */
interface ActivityRepository {

    fun observeRange(fromDay: Long, toDay: Long): Flow<List<ActivitySummary>>

    fun observeRecent(limit: Int): Flow<List<ActivitySummary>>

    fun observeBySportGroup(group: SportGroup, fromDay: Long): Flow<List<ActivitySummary>>

    fun observeFullById(id: Long): Flow<ActivitySession?>

    suspend fun getById(id: Long): ActivitySession?

    suspend fun getByDay(day: Long): List<ActivitySummary>

    /** One-shot range read for the load recompute worker (P5.5), which needs streams. */
    suspend fun getRange(fromDay: Long, toDay: Long): List<ActivitySession>

    suspend fun ingest(items: List<ActivityIngestItem>): Outcome<IngestResult>

    /**
     * Drops one source's view of an activity (a Health Connect `DeletionChange`, P2.6). The
     * canonical row is re-merged from the sources that remain, or deleted when none do.
     */
    suspend fun removeSourceRecord(source: ActivitySource, externalId: String): Outcome<Unit>

    suspend fun upsert(session: ActivitySession): Outcome<Long>

    suspend fun setRpe(id: Long, rpe: Int?): Outcome<Unit>

    suspend fun setNote(id: Long, note: String?): Outcome<Unit>

    /**
     * Corrects the sport of an activity — a user-level edit, so the field is pinned in
     * `userEditedFields` and a later merge can never revert it (§2.4). Used by the calendar when
     * a `SOCCER_MATCH` event is linked to an activity (P3.2/P3.7).
     */
    suspend fun setSportType(id: Long, sportType: SportType): Outcome<Unit>

    /** Written by the load recompute worker after `TrimpCalculator` runs (§3.2, P5.5). */
    suspend fun setTrimp(id: Long, trimp: Double?, method: LoadMethod?): Outcome<Unit>

    suspend fun delete(id: Long): Outcome<Unit>
}
