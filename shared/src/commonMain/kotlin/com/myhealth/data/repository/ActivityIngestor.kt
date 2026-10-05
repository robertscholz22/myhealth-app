package com.myhealth.data.repository

import com.myhealth.data.db.dao.ActivityDao
import com.myhealth.data.db.entity.ActivitySessionEntity
import com.myhealth.data.mapper.toDomain
import com.myhealth.data.mapper.toEntity
import com.myhealth.domain.engine.activity.ActivityMatcher
import com.myhealth.domain.engine.activity.ActivityMerger
import com.myhealth.domain.engine.activity.DedupeKey
import com.myhealth.domain.engine.activity.toMatchKey
import com.myhealth.domain.model.ActivitySession
import com.myhealth.domain.model.ActivitySource
import com.myhealth.domain.repository.ActivityIngestItem
import com.myhealth.domain.repository.IngestResult
import com.myhealth.data.time.PlatformClock

/**
 * The single write path for synced and imported activities (PLAN P2.5), implementing §2.4.
 *
 * Per arrival, inside one transaction:
 * 1. the `activity_source_record` row is inserted with `IGNORE` on `(source, externalId)` — the
 *    idempotency key. A record that already existed is **still re-merged**, because the source
 *    may have corrected its payload since; the stored `payloadJson` is refreshed.
 * 2. candidates are looked up by `dedupeBucket` `n-1, n, n+1` and filtered with [ActivityMatcher].
 * 3. the canonical row is merged by [ActivityMerger] and upserted together with its streams and
 *    laps.
 */
class ActivityIngestor(
    private val activityDao: ActivityDao,
    private val transaction: TransactionRunner,
    private val clock: PlatformClock,
) {

    suspend fun ingest(items: List<ActivityIngestItem>): IngestResult {
        var result = IngestResult.EMPTY
        for (item in items) {
            result += transaction { ingestOne(item) }
        }
        return result
    }

    /**
     * Drops one source's view of an activity and re-merges what is left (P2.6 deletions).
     *
     * The canonical row is deleted when the removed record was its last source. When other
     * sources remain the row is kept and its `mergedSourcesCsv`/`primarySource` are recomputed:
     * the stored payloads cannot be decoded back into field values here (the payload schema
     * belongs to the source), so the field values are corrected by the next ingest of any
     * remaining source.
     */
    suspend fun removeSourceRecord(source: ActivitySource, externalId: String) {
        transaction { unlink(source, externalId) }
    }

    private suspend fun unlink(source: ActivitySource, externalId: String) {
        val record = activityDao.getSourceRecord(source, externalId) ?: return
        activityDao.deleteSourceRecord(source, externalId)
        val activityId = record.activityId ?: return
        val remaining = activityDao.getSourceRecordsFor(activityId)
        if (remaining.isEmpty()) {
            activityDao.deleteById(activityId)
            return
        }
        val row = activityDao.getById(activityId) ?: return
        val sources = remaining.map { it.source }.distinct().sortedBy { it.ordinal }
        activityDao.upsert(
            row.copy(
                primarySource = ActivityMerger.primarySourceOf(sources),
                mergedSourcesCsv = sources.joinToString(",") { it.name },
                updatedAtMillis = clock.millis(),
            ),
        )
    }

    private suspend fun ingestOne(item: ActivityIngestItem): IngestResult {
        val now = clock.millis()
        val record = item.record
        val known = activityDao.getSourceRecord(record.source, record.externalId)
        val existing = findExisting(item.session, known?.activityId)

        val merged = ActivityMerger.merge(listOf(item.session), existing, now)
        val entity = merged.toEntity()
        val insertedId = activityDao.upsert(entity)
        val activityId = if (entity.id != 0L) entity.id else insertedId

        writeStreamsAndLaps(activityId, merged)
        linkSourceRecord(item, activityId, known != null)

        return when {
            known != null -> IngestResult(inserted = 0, merged = 0, duplicate = 1)
            existing != null -> IngestResult(inserted = 0, merged = 1, duplicate = 0)
            else -> IngestResult(inserted = 1, merged = 0, duplicate = 0)
        }
    }

    /** Bucket `n-1, n, n+1` plus the §2.4 predicate; falls back to the record's known activity. */
    private suspend fun findExisting(
        candidate: ActivitySession,
        knownActivityId: Long?,
    ): ActivitySession? {
        val buckets = DedupeKey.neighbours(candidate.sportGroup, candidate.startAtMillis)
        val key = candidate.toMatchKey()
        val row = activityDao.getByBuckets(buckets)
            .firstOrNull { ActivityMatcher.matches(it.toDomain().toMatchKey(), key) }
            ?: knownActivityId?.let { activityDao.getById(it) }
            ?: return null
        return loadFull(row)
    }

    private suspend fun loadFull(row: ActivitySessionEntity): ActivitySession = row.toDomain(
        streams = activityDao.getStream(row.id)?.toDomain(),
        laps = activityDao.getLaps(row.id).map { it.toDomain() },
    )

    private suspend fun writeStreamsAndLaps(activityId: Long, merged: ActivitySession) {
        merged.streams?.let { activityDao.upsertStream(it.toEntity(activityId)) }
        if (merged.laps.isNotEmpty()) {
            activityDao.deleteLaps(activityId)
            activityDao.upsertLaps(
                merged.laps.mapIndexed { index, lap ->
                    lap.copy(id = 0L, activityId = activityId, lapIndex = index).toEntity()
                },
            )
        }
    }

    private suspend fun linkSourceRecord(
        item: ActivityIngestItem,
        activityId: Long,
        alreadyKnown: Boolean,
    ) {
        val record = item.record
        if (!alreadyKnown) {
            val rowId = activityDao.insertSourceRecordIgnoring(
                record.copy(id = 0L, activityId = activityId).toEntity(),
            )
            if (rowId != -1L) return
        }
        activityDao.updateSourceRecord(
            source = record.source,
            externalId = record.externalId,
            payloadJson = record.payloadJson,
            activityId = activityId,
            receivedAtMillis = record.receivedAtMillis,
            importRecordId = record.importRecordId,
        )
    }
}
