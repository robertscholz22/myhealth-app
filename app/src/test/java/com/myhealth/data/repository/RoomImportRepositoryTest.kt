package com.myhealth.data.repository

import com.google.common.truth.Truth.assertThat
import com.myhealth.domain.model.ActivitySource
import com.myhealth.domain.model.ActivitySourceRecord
import com.myhealth.domain.model.ImportKind
import com.myhealth.domain.model.ImportRecord
import com.myhealth.domain.repository.ActivityIngestItem
import com.myhealth.domain.util.Outcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

/**
 * `import_record` over fake DAOs (PLAN §2.2.6), with the **real** [ActivityIngestor] behind
 * [RoomImportRepository.undo] so the re-merge/delete decision asserted here is the app's.
 */
class RoomImportRepositoryTest {

    private val zone = ZoneId.of("Europe/Berlin")
    private val clock = Clock.fixed(Instant.ofEpochMilli(NOW), zone)
    private val activityDao = FakeActivityDao()
    private val importDao = FakeImportDao()
    private val ingestor = ActivityIngestor(activityDao, DirectTransactionRunner, clock)
    private val recomputeDays = mutableListOf<Long>()
    private val repo = RoomImportRepository(
        importDao = importDao,
        activityDao = activityDao,
        ingestor = ingestor,
        onUndone = { day -> recomputeDays += day },
        ioDispatcher = Dispatchers.Unconfined,
    )

    @Test
    fun bug19_only_error_lists_above_the_limit_are_replaced() = runTest {
        repo.record(record("export.zip", "big").copy(errorsJson = "x".repeat(RoomImportRepository.MAX_ERRORS_JSON_CHARS + 1)))
        repo.record(record("edge.zip", "edge").copy(errorsJson = "x".repeat(RoomImportRepository.MAX_ERRORS_JSON_CHARS)))
        repo.record(record("a.csv", "ok"))

        assertThat(repo.repairOversizedErrors()).isEqualTo(Outcome.Ok(1))
        assertThat(repo.getByHash("big")?.errorsJson).isEqualTo(RoomImportRepository.OVERSIZED_ERRORS_JSON)
        assertThat(repo.getByHash("edge")?.errorsJson?.length).isEqualTo(RoomImportRepository.MAX_ERRORS_JSON_CHARS)
        assertThat(repo.repairOversizedErrors()).isEqualTo(Outcome.Ok(0))
    }

    @Test
    fun a_record_round_trips_by_id_hash_and_recency() = runTest {
        val id = (repo.record(record("a.csv", "hash-a")) as Outcome.Ok).value
        repo.record(record("b.csv", "hash-b", importedAtMillis = NOW + 1_000))

        assertThat(repo.getById(id)?.fileName).isEqualTo("a.csv")
        assertThat(repo.getByHash("hash-b")?.fileName).isEqualTo("b.csv")
        assertThat(repo.getByHash("missing")).isNull()
        assertThat(repo.observeRecent(10).first().map { it.fileName })
            .containsExactly("b.csv", "a.csv").inOrder()

        repo.delete(id)
        assertThat(repo.getById(id)).isNull()
    }

    /**
     * The undo contract: a canonical activity another source also knows is re-merged and kept, one
     * that only the import produced is deleted, and the audit row goes so the file is importable
     * again. The recompute is requested from the earliest day the undo touched.
     */
    @Test
    fun undo_deletes_csv_only_activities_keeps_shared_ones_and_drops_the_audit_row() = runTest {
        val importId = (repo.record(record("activities.csv", "hash-a")) as Outcome.Ok).value
        ingest(ActivitySource.HEALTH_CONNECT, "hc-1", start = START, day = DAY, importRecordId = null)
        ingest(ActivitySource.CSV_IMPORT, "csv-1", start = START, day = DAY, importRecordId = importId)
        ingest(ActivitySource.CSV_IMPORT, "csv-2", start = START + DAY_MILLIS, day = DAY + 1, importRecordId = importId)
        assertThat(activityDao.rows()).hasSize(2)

        val summary = (repo.undo(importId) as Outcome.Ok).value

        assertThat(summary.importId).isEqualTo(importId)
        assertThat(summary.sourceRecordsRemoved).isEqualTo(2)
        assertThat(summary.activitiesDeleted).isEqualTo(1)
        assertThat(summary.activitiesKept).isEqualTo(1)
        assertThat(activityDao.rows().single().mergedSourcesCsv).isEqualTo("HEALTH_CONNECT")
        assertThat(activityDao.sourceRecords.map { it.externalId }).containsExactly("hc-1")
        assertThat(repo.getByHash("hash-a")).isNull()
        assertThat(recomputeDays).containsExactly(DAY)
    }

    /**
     * BUG-12: imports written before DB v4 left their `activity_source_record` rows with a null
     * `importRecordId`, so the primary lookup in [undo] finds nothing. It falls back to unstamped
     * records of the import's source kind received within ±15 minutes of `importedAtMillis`.
     */
    @Test
    fun undo_falls_back_to_unstamped_records_in_the_import_time_window() = runTest {
        val importId = (repo.record(record("activities.csv", "hash-a")) as Outcome.Ok).value
        // Arrived 10 minutes before the import ran, but never stamped with it (pre-v4 arrival).
        ingest(
            ActivitySource.CSV_IMPORT,
            "csv-1",
            start = START,
            day = DAY,
            importRecordId = null,
            receivedAtMillis = NOW - 10 * 60_000L,
        )
        assertThat(activityDao.sourceRecords).hasSize(1)

        val summary = (repo.undo(importId) as Outcome.Ok).value

        assertThat(summary.sourceRecordsRemoved).isEqualTo(1)
        assertThat(summary.activitiesDeleted).isEqualTo(1)
        assertThat(summary.activitiesKept).isEqualTo(0)
        assertThat(activityDao.rows()).isEmpty()
        assertThat(activityDao.sourceRecords).isEmpty()
        assertThat(repo.getByHash("hash-a")).isNull()
        assertThat(recomputeDays).containsExactly(DAY)
    }

    /**
     * The fallback window and source-kind filter are both selective: a record outside ±15 minutes,
     * and one of a different source kind, survive an undo that finds nothing to remove.
     */
    @Test
    fun undo_fallback_ignores_records_outside_the_window_and_other_sources() = runTest {
        val importId = (repo.record(record("activities.csv", "hash-a")) as Outcome.Ok).value
        // 20 minutes outside the +/-15 minute window.
        ingest(
            ActivitySource.CSV_IMPORT,
            "csv-late",
            start = START,
            day = DAY,
            importRecordId = null,
            receivedAtMillis = NOW + 20 * 60_000L,
        )
        // Within the window, but the wrong source kind for a GARMIN_CSV import.
        ingest(
            ActivitySource.FIT_IMPORT,
            "fit-1",
            start = START + DAY_MILLIS,
            day = DAY + 1,
            importRecordId = null,
            receivedAtMillis = NOW,
        )

        val summary = (repo.undo(importId) as Outcome.Ok).value

        assertThat(summary.sourceRecordsRemoved).isEqualTo(0)
        assertThat(activityDao.sourceRecords.map { it.externalId })
            .containsExactly("csv-late", "fit-1")
        assertThat(recomputeDays).isEmpty()
    }

    /**
     * BUG-12b: the orphan cleanup sweeps every unstamped CSV_IMPORT/FIT_IMPORT record regardless
     * of when it arrived, but leaves stamped file imports and non-file sources (e.g. Health
     * Connect, which is legitimately unstamped) alone.
     */
    @Test
    fun remove_orphaned_import_data_removes_only_unstamped_file_imports() = runTest {
        val importId = (repo.record(record("kept.csv", "hash-kept")) as Outcome.Ok).value
        ingest(ActivitySource.CSV_IMPORT, "orphan-csv", start = START, day = DAY, importRecordId = null)
        ingest(
            ActivitySource.FIT_IMPORT,
            "orphan-fit",
            start = START + DAY_MILLIS,
            day = DAY + 1,
            importRecordId = null,
        )
        ingest(
            ActivitySource.CSV_IMPORT,
            "stamped-csv",
            start = START + 2 * DAY_MILLIS,
            day = DAY + 2,
            importRecordId = importId,
        )
        ingest(
            ActivitySource.HEALTH_CONNECT,
            "hc-1",
            start = START + 3 * DAY_MILLIS,
            day = DAY + 3,
            importRecordId = null,
        )

        val summary = (repo.removeOrphanedImportData() as Outcome.Ok).value

        assertThat(summary.sourceRecordsRemoved).isEqualTo(2)
        assertThat(summary.activitiesDeleted).isEqualTo(2)
        assertThat(activityDao.sourceRecords.map { it.externalId })
            .containsExactly("stamped-csv", "hc-1")
        assertThat(recomputeDays).containsExactly(DAY)
        // The orphan cleanup is not tied to one import, so its own record survives untouched.
        assertThat(repo.getByHash("hash-kept")).isNotNull()
    }

    @Test
    fun undoing_an_import_that_wrote_nothing_is_a_no_op_that_still_forgets_the_file() = runTest {
        val importId = (repo.record(record("empty.csv", "hash-empty")) as Outcome.Ok).value

        val summary = (repo.undo(importId) as Outcome.Ok).value

        assertThat(summary.sourceRecordsRemoved).isEqualTo(0)
        assertThat(summary.activitiesDeleted).isEqualTo(0)
        assertThat(summary.activitiesKept).isEqualTo(0)
        assertThat(repo.getByHash("hash-empty")).isNull()
        // Nothing was touched, so no recompute is asked for.
        assertThat(recomputeDays).isEmpty()
    }

    private suspend fun ingest(
        source: ActivitySource,
        externalId: String,
        start: Long,
        day: Long,
        importRecordId: Long?,
        receivedAtMillis: Long = NOW,
    ) {
        ingestor.ingest(
            listOf(
                ActivityIngestItem(
                    record = ActivitySourceRecord(
                        id = 0L,
                        activityId = null,
                        source = source,
                        externalId = externalId,
                        payloadJson = "{}",
                        receivedAtMillis = receivedAtMillis,
                        importRecordId = importRecordId,
                    ),
                    session = hcSession(
                        startAtMillis = start,
                        durationSec = 1_500,
                        distanceMeters = 5_000.0,
                        day = day,
                    ).copy(primarySource = source, mergedSources = listOf(source)),
                ),
            ),
        )
    }

    private fun record(
        fileName: String,
        hash: String,
        importedAtMillis: Long = NOW,
    ) = ImportRecord(
        id = 0L,
        kind = ImportKind.GARMIN_CSV,
        fileName = fileName,
        fileHashSha256 = hash,
        importedAtMillis = importedAtMillis,
        itemsParsed = 2,
        itemsInserted = 2,
        itemsDuplicate = 0,
        errorsJson = null,
    )

    private companion object {
        const val NOW = 1_800_000_000_000L

        /** 2026-05-10T09:00 Europe/Berlin. */
        const val START = 1_778_396_400_000L
        const val DAY = 20_583L
        const val DAY_MILLIS = 86_400_000L
    }
}
