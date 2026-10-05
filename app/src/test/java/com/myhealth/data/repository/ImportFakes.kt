package com.myhealth.data.repository

import com.myhealth.data.db.dao.ImportDao
import com.myhealth.data.db.entity.ImportRecordEntity
import com.myhealth.domain.model.ActivitySession
import com.myhealth.domain.model.ActivitySource
import com.myhealth.domain.model.ImportRecord
import com.myhealth.domain.model.ImportUndoSummary
import com.myhealth.domain.model.LoadMethod
import com.myhealth.domain.model.SportGroup
import com.myhealth.domain.model.SportType
import com.myhealth.domain.repository.ActivityIngestItem
import com.myhealth.domain.repository.ActivityRepository
import com.myhealth.domain.repository.ImportRepository
import com.myhealth.domain.repository.IngestResult
import com.myhealth.domain.util.AppError
import com.myhealth.domain.util.Outcome
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import okio.Buffer
import okio.Source

/** Hands [ImportService] a fixed byte array instead of a `content://` document. */
class FakeImportContentSource(
    private val fileName: String,
    private val bytes: ByteArray,
) : ImportContentSource {
    var opened: Int = 0
        private set

    override suspend fun displayName(uri: String): String = fileName

    override suspend fun openSource(uri: String): Source {
        opened++
        return Buffer().write(bytes)
    }
}

/** In-memory `import_record`, including the unique file-hash index. */
class FakeImportRepository : ImportRepository {

    private val records = MutableStateFlow<List<ImportRecord>>(emptyList())
    private var nextId = 1L

    fun seed(record: ImportRecord) {
        records.value = records.value + record.copy(id = nextId++)
    }

    fun all(): List<ImportRecord> = records.value

    override fun observeRecent(limit: Int): Flow<List<ImportRecord>> =
        records.map { list -> list.sortedByDescending { it.importedAtMillis }.take(limit) }

    override suspend fun getById(id: Long): ImportRecord? = records.value.firstOrNull { it.id == id }

    override suspend fun getByHash(fileHashSha256: String): ImportRecord? =
        records.value.firstOrNull { it.fileHashSha256 == fileHashSha256 }

    /** `@Upsert` semantics: a non-zero id updates that row in place, `0` inserts a new one. */
    override suspend fun record(record: ImportRecord): Outcome<Long> {
        val id = if (record.id != 0L) record.id else nextId++
        records.value = records.value
            .filterNot { it.id == id || it.fileHashSha256 == record.fileHashSha256 } +
            record.copy(id = id)
        return Outcome.Ok(id)
    }

    override suspend fun delete(id: Long): Outcome<Unit> {
        records.value = records.value.filterNot { it.id == id }
        return Outcome.Ok(Unit)
    }

    /** Set by the test to the real undo; unset it stays a no-op the pipeline never calls. */
    var undoDelegate: (suspend (Long) -> Outcome<ImportUndoSummary>)? = null

    override suspend fun undo(importId: Long): Outcome<ImportUndoSummary> =
        checkNotNull(undoDelegate) { "No undo delegate set on FakeImportRepository" }(importId)

    override suspend fun repairOversizedErrors(): Outcome<Int> = Outcome.Ok(0)

    /** Set by a test that exercises the orphan-cleanup path; otherwise never called (BUG-12b). */
    var removeOrphanedImportDataDelegate: (suspend () -> Outcome<ImportUndoSummary>)? = null

    override suspend fun removeOrphanedImportData(): Outcome<ImportUndoSummary> =
        checkNotNull(removeOrphanedImportDataDelegate) {
            "No removeOrphanedImportData delegate set on FakeImportRepository"
        }()
}

/**
 * In-memory `import_record`, modelling Room's `@Upsert` (id 0 inserts, a known id updates) and the
 * unique `fileHashSha256` index. Lets the **real** [RoomImportRepository] — including its undo —
 * be exercised without Room.
 */
class FakeImportDao : ImportDao {

    private val rows = MutableStateFlow<List<ImportRecordEntity>>(emptyList())
    private var nextId = 1L

    fun all(): List<ImportRecordEntity> = rows.value

    override suspend fun upsert(entity: ImportRecordEntity): Long {
        val id = if (entity.id != 0L) entity.id else nextId++
        rows.value = rows.value.filterNot { it.id == id || it.fileHashSha256 == entity.fileHashSha256 } +
            entity.copy(id = id)
        return id
    }

    override suspend fun getById(id: Long): ImportRecordEntity? = rows.value.firstOrNull { it.id == id }

    override suspend fun deleteById(id: Long) {
        rows.value = rows.value.filterNot { it.id == id }
    }

    override suspend fun getByHash(hash: String): ImportRecordEntity? =
        rows.value.firstOrNull { it.fileHashSha256 == hash }

    override fun observeRecent(limit: Int): Flow<List<ImportRecordEntity>> =
        rows.map { list -> list.sortedByDescending { it.importedAtMillis }.take(limit) }

    override suspend fun replaceOversizedErrors(maxChars: Int, replacement: String): Int {
        val oversized = rows.value.filter { (it.errorsJson?.length ?: 0) > maxChars }
        rows.value = rows.value.map { if (it in oversized) it.copy(errorsJson = replacement) else it }
        return oversized.size
    }
}

/**
 * Wraps a real [ActivityRepository] and records the size of every [ingest] call, so the chunking
 * guarantee of P7.5 can be asserted without reimplementing the merge engine.
 */
class RecordingActivityRepository(
    private val delegate: ActivityRepository,
    private val failOn: (ActivityIngestItem) -> Boolean = { false },
) : ActivityRepository by delegate {

    val chunkSizes = mutableListOf<Int>()

    override suspend fun ingest(items: List<ActivityIngestItem>): Outcome<IngestResult> {
        chunkSizes += items.size
        if (items.any(failOn)) return Outcome.Err(AppError.Parse("db", "boom"))
        return delegate.ingest(items)
    }
}

/** A Health-Connect-shaped session, for the "FIT merges into an existing HC activity" case. */
fun hcSession(
    startAtMillis: Long,
    durationSec: Int,
    distanceMeters: Double?,
    day: Long,
    sportType: SportType = SportType.RUN_OUTDOOR,
    totalEnergyKcal: Double? = 400.0,
): ActivitySession = ActivitySession(
    id = 0L,
    startAtMillis = startAtMillis,
    endAtMillis = startAtMillis + durationSec * 1000L,
    day = day,
    sportType = sportType,
    sportGroup = sportType.group,
    title = "Health Connect run",
    durationSec = durationSec,
    elapsedSec = durationSec,
    distanceMeters = distanceMeters,
    activeEnergyKcal = null,
    totalEnergyKcal = totalEnergyKcal,
    avgHr = 150,
    maxHr = 175,
    avgSpeedMps = null,
    maxSpeedMps = null,
    avgCadenceSpm = null,
    elevationGainM = null,
    trimp = null,
    loadMethod = null as LoadMethod?,
    rpe = null,
    note = null,
    primarySource = ActivitySource.HEALTH_CONNECT,
    mergedSources = listOf(ActivitySource.HEALTH_CONNECT),
    dedupeBucket = "${SportGroup.RUN}|${startAtMillis / 300_000L}",
    userEditedFields = emptyList(),
    hasStreams = false,
    streams = null,
    laps = emptyList(),
    createdAtMillis = startAtMillis,
    updatedAtMillis = startAtMillis,
)
