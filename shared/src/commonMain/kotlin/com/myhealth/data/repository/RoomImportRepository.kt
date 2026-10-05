package com.myhealth.data.repository

import com.myhealth.data.db.dao.ActivityDao
import com.myhealth.data.db.dao.ImportDao
import com.myhealth.data.db.entity.ActivitySourceRecordEntity
import com.myhealth.data.mapper.toDomain
import com.myhealth.data.mapper.toEntity
import com.myhealth.domain.model.ActivitySource
import com.myhealth.domain.model.ImportKind
import com.myhealth.domain.model.ImportRecord
import com.myhealth.domain.model.ImportUndoSummary
import com.myhealth.domain.repository.ImportRepository
import com.myhealth.domain.util.Outcome
import com.myhealth.domain.util.runCatchingApp
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * Room-backed [ImportRepository] over `import_record` (PLAN §2.2.6, P7.5).
 *
 * `fileHashSha256` is uniquely indexed, so [getByHash] is the duplicate-import guard the pipeline
 * consults before it parses anything.
 *
 * [undo] is the other side of that guard: it hands every source record the import wrote back to
 * [ActivityIngestor.removeSourceRecord] — the same seam a Health Connect deletion uses, so an
 * activity two sources know survives with the other source's data — and then drops the audit row,
 * which is what makes the checksum forgotten and the file importable again. [onUndone] is the hook
 * the DI graph points at `SyncScheduler.requestLoadRecompute`; `data/` never imports `sync/`.
 *
 * BUG-12 (hotfix 1.0.3): imports written before DB v4 left their `activity_source_record` rows
 * with a null `importRecordId`, so [undo] found nothing to remove. [undo] now falls back to
 * [ActivityDao.getUnstampedSourceRecordsInWindow] — unstamped records of the import's source kind
 * received within ±15 minutes of `importedAtMillis` — and stamps them before removing them, so a
 * retried undo (or a crash mid-way) still finds them. [removeOrphanedImportData] sweeps up the
 * same kind of row across all imports at once, for files whose `import_record` may itself be gone.
 */
class RoomImportRepository(
    private val importDao: ImportDao,
    private val activityDao: ActivityDao,
    private val ingestor: ActivityIngestor,
    private val onUndone: suspend (Long) -> Unit = {},
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ImportRepository {

    override fun observeRecent(limit: Int): Flow<List<ImportRecord>> =
        importDao.observeRecent(limit).map { rows -> rows.map { it.toDomain() } }

    override suspend fun getById(id: Long): ImportRecord? =
        withContext(ioDispatcher) { importDao.getById(id)?.toDomain() }

    override suspend fun getByHash(fileHashSha256: String): ImportRecord? =
        withContext(ioDispatcher) { importDao.getByHash(fileHashSha256)?.toDomain() }

    override suspend fun record(record: ImportRecord): Outcome<Long> =
        withContext(ioDispatcher) { runCatchingApp { importDao.upsert(record.toEntity()) } }

    override suspend fun delete(id: Long): Outcome<Unit> =
        withContext(ioDispatcher) { runCatchingApp { importDao.deleteById(id) } }

    override suspend fun undo(importId: Long): Outcome<ImportUndoSummary> = withContext(ioDispatcher) {
        var minAffectedDay: Long? = null
        val outcome = runCatchingApp {
            val records = recordsToUndo(importId)
            val (summary, minDay) = removeAndSummarize(importId, records)
            minAffectedDay = minDay
            importDao.deleteById(importId)
            summary
        }
        if (outcome is Outcome.Ok) minAffectedDay?.let { onUndone(it) }
        outcome
    }

    override suspend fun repairOversizedErrors(): Outcome<Int> = withContext(ioDispatcher) {
        runCatchingApp { importDao.replaceOversizedErrors(MAX_ERRORS_JSON_CHARS, OVERSIZED_ERRORS_JSON) }
    }

    override suspend fun removeOrphanedImportData(): Outcome<ImportUndoSummary> = withContext(ioDispatcher) {
        var minAffectedDay: Long? = null
        val outcome = runCatchingApp {
            val records = activityDao.getAllUnstampedSourceRecords(FILE_IMPORT_SOURCES)
            val (summary, minDay) = removeAndSummarize(importId = 0L, records)
            minAffectedDay = minDay
            summary
        }
        if (outcome is Outcome.Ok) minAffectedDay?.let { onUndone(it) }
        outcome
    }

    /**
     * The records "Undo import" removes: the ones stamped with [importId], or — BUG-12 — when
     * there are none, unstamped records of the import's source kind received within ±15 minutes
     * of its `importedAtMillis`, stamped with [importId] first so the link survives a retry.
     */
    private suspend fun recordsToUndo(importId: Long): List<ActivitySourceRecordEntity> {
        val stamped = activityDao.getSourceRecordsOfImport(importId)
        if (stamped.isNotEmpty()) return stamped
        val importRecord = importDao.getById(importId) ?: return emptyList()
        val sources = fileImportSourcesOf(importRecord.kind)
        if (sources.isEmpty()) return emptyList()
        val fallback = activityDao.getUnstampedSourceRecordsInWindow(
            sources = sources,
            fromMillis = importRecord.importedAtMillis - UNDO_FALLBACK_WINDOW_MILLIS,
            toMillis = importRecord.importedAtMillis + UNDO_FALLBACK_WINDOW_MILLIS,
        )
        fallback.forEach { activityDao.stampImportRecordId(it.id, importId) }
        return fallback
    }

    /** Removes every [records] row through [ActivityIngestor.removeSourceRecord] and tallies the result. */
    private suspend fun removeAndSummarize(
        importId: Long,
        records: List<ActivitySourceRecordEntity>,
    ): Pair<ImportUndoSummary, Long?> {
        val activityIds = records.mapNotNull { it.activityId }.distinct()
        // The days have to be read before the rows go, or the recompute has nothing to start at.
        val minDay = activityIds.mapNotNull { activityDao.getById(it)?.day }.minOrNull()
        for (record in records) {
            ingestor.removeSourceRecord(record.source, record.externalId)
        }
        val deleted = activityIds.count { activityDao.getById(it) == null }
        val summary = ImportUndoSummary(
            importId = importId,
            sourceRecordsRemoved = records.size,
            activitiesDeleted = deleted,
            activitiesKept = activityIds.size - deleted,
        )
        return summary to minDay
    }

    companion object {
        /** The undo fallback's time window (BUG-12): Garmin's own export timestamps this loosely. */
        private const val UNDO_FALLBACK_WINDOW_MILLIS = 15 * 60 * 1000L

        /**
         * BUG-19: an `errorsJson` above this many characters is replaced. Since 0.9.2 an import
         * lists at most `ImportService.MAX_LISTED_ERRORS` errors (well under 100 000 characters);
         * even 200 000 non-ASCII characters stay far below the 2 MB cursor-window row limit.
         */
        const val MAX_ERRORS_JSON_CHARS = 200_000

        /** What a repaired row's `errorsJson` says instead (the `ItemErrorDto` list format). */
        const val OVERSIZED_ERRORS_JSON =
            """[{"item":"…","message":"The error list was too long to keep and was removed."}]"""

        private val FILE_IMPORT_SOURCES = listOf(ActivitySource.CSV_IMPORT, ActivitySource.FIT_IMPORT)

        /** Which [ActivitySource] an import kind's arrivals are stamped with (§2.4). */
        private fun fileImportSourcesOf(kind: ImportKind): List<ActivitySource> = when (kind) {
            ImportKind.GARMIN_CSV -> listOf(ActivitySource.CSV_IMPORT)
            ImportKind.FIT_FILE, ImportKind.GARMIN_ZIP -> listOf(ActivitySource.FIT_IMPORT)
            ImportKind.JSON_BACKUP -> emptyList()
        }
    }
}
