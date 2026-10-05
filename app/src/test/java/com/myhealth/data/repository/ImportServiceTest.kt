package com.myhealth.data.repository

import com.garmin.fit.DateTime
import com.garmin.fit.FileEncoder
import com.garmin.fit.FileIdMesg
import com.garmin.fit.Fit
import com.google.common.truth.Truth.assertThat
import com.myhealth.data.fit.GarminCsvParser
import com.myhealth.data.fit.RunFixtureEncoder
import com.myhealth.data.fit.zipOf
import com.myhealth.domain.model.ActivitySource
import com.myhealth.domain.model.ActivitySourceRecord
import com.myhealth.domain.model.ImportItemError
import com.myhealth.domain.model.ImportKind
import com.myhealth.domain.model.ImportProgress
import com.myhealth.domain.repository.ActivityIngestItem
import com.myhealth.domain.repository.ActivityRepository
import com.myhealth.domain.repository.ImportKinds
import com.myhealth.domain.util.Outcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * PLAN P7.5 with fakes for the file source and `import_record`, but the **real** ingestion stack
 * (`ActivityIngestor` over an in-memory `ActivityDao`), so the de-dup/merge behaviour asserted
 * here is the behaviour the app has.
 */
class ImportServiceTest {

    private val zone = ZoneId.of("Europe/Berlin")
    private val clock = Clock.fixed(Instant.ofEpochMilli(NOW), zone)
    private val dao = FakeActivityDao()
    private val ingestor = ActivityIngestor(dao, DirectTransactionRunner, clock)
    private val activityRepo: ActivityRepository =
        RoomActivityRepository(dao, ingestor, clock, Dispatchers.Unconfined)
    private val importRepo = FakeImportRepository()
    private val recomputeDays = mutableListOf<Long>()

    private fun service(
        content: FakeImportContentSource,
        repo: ActivityRepository = activityRepo,
        chunkSize: Int = ImportService.CHUNK_SIZE,
    ) = ImportService(
        content = content,
        activityRepo = repo,
        importRepo = importRepo,
        csvParser = GarminCsvParser(TimeZone.of("Europe/Berlin")),
        clock = clock,
        onImported = { day -> recomputeDays += day },
        ioDispatcher = Dispatchers.Unconfined,
        chunkSize = chunkSize,
    )

    private fun fitContent() = FakeImportContentSource("run_5k.fit", RunFixtureEncoder.ensure().readBytes())

    @Test
    fun a_file_whose_hash_is_already_recorded_short_circuits() = runTest {
        val content = fitContent()
        val first = service(content).import("content://doc", ImportKind.FIT_FILE).toList()
        val finished = first.filterIsInstance<ImportProgress.Finished>().single()
        assertThat(finished.counts.inserted).isEqualTo(1)
        assertThat(dao.rows()).hasSize(1)

        val second = service(content).import("content://doc", ImportKind.FIT_FILE).toList()

        val duplicate = second.filterIsInstance<ImportProgress.AlreadyImported>().single()
        assertThat(duplicate.previous.fileHashSha256).isEqualTo(finished.record.fileHashSha256)
        assertThat(second.filterIsInstance<ImportProgress.Working>()).isEmpty()
        assertThat(dao.rows()).hasSize(1)
        assertThat(importRepo.all()).hasSize(1)

        // `force` bypasses the guard and re-ingests idempotently (same (source, externalId)).
        val forced = service(content).import("content://doc", ImportKind.FIT_FILE, force = true).toList()
        val redone = forced.filterIsInstance<ImportProgress.Finished>().single()
        assertThat(redone.counts.duplicate).isEqualTo(1)
        assertThat(redone.counts.inserted).isEqualTo(0)
        assertThat(dao.rows()).hasSize(1)
    }

    @Test
    fun a_corrupt_entry_fails_alone_and_is_recorded_in_the_import_record() = runTest {
        val archive = zipOf(
            "DI_CONNECT/uploads/good.fit" to RunFixtureEncoder.ensure().readBytes(),
            "DI_CONNECT/uploads/broken.fit" to ByteArray(200) { 0x5 },
            "DI_CONNECT/activities.csv" to loadEnglishCsv().toByteArray(),
            "../escape.fit" to ByteArray(10),
        )
        val content = FakeImportContentSource("export.zip", archive)

        val progress = service(content).import("content://zip", ImportKind.GARMIN_ZIP).toList()

        val finished = progress.filterIsInstance<ImportProgress.Finished>().single()
        // One FIT activity plus the five CSV rows parsed; the broken FIT and the escaping entry
        // are errors, not a failed import.
        assertThat(finished.counts.parsed).isEqualTo(6)
        assertThat(finished.counts.failed).isEqualTo(1)
        assertThat(finished.errors.map { it.item })
            .containsAtLeast("DI_CONNECT/uploads/broken.fit", "../escape.fit")
        assertThat(finished.record.errorsJson).isNotNull()
        assertThat(finished.record.itemsParsed).isEqualTo(6)
        assertThat(finished.record.kind).isEqualTo(ImportKind.GARMIN_ZIP)
        // Five canonical rows, not six: the CSV's first row is the same run as the FIT file and
        // the two are merged by §2.4 on the way in.
        assertThat(dao.rows()).hasSize(5)
        assertThat(dao.rows().first().mergedSourcesCsv).isEqualTo("FIT_IMPORT,CSV_IMPORT")
    }

    @Test
    fun bug19a_fit_files_without_a_session_are_skipped_inside_an_archive_only() = runTest {
        val wellness = monitoringFit()
        val archive = zipOf(
            "DI_CONNECT/uploads/run.fit" to RunFixtureEncoder.ensure().readBytes(),
            "DI_CONNECT/uploads/monitor_1.fit" to wellness,
            "DI_CONNECT/uploads/monitor_2.fit" to wellness,
        )
        val zipped = service(FakeImportContentSource("export.zip", archive))
            .import("content://zip", ImportKind.GARMIN_ZIP).toList()
            .filterIsInstance<ImportProgress.Finished>().single()
        assertThat(zipped.counts.parsed).isEqualTo(1)
        assertThat(zipped.counts.inserted).isEqualTo(1)
        assertThat(zipped.counts.failed).isEqualTo(0)
        assertThat(zipped.errors).isEmpty()
        assertThat(zipped.record.errorsJson).isNull()

        // A single file the user picked must be an activity: still an error.
        val single = service(FakeImportContentSource("monitor.fit", wellness))
            .import("content://fit", ImportKind.FIT_FILE).toList()
            .filterIsInstance<ImportProgress.Finished>().single()
        assertThat(single.counts.failed).isEqualTo(1)
        assertThat(single.errors.single().message).isEqualTo("fit: no session message in the file")
    }

    @Test
    fun bug19b_only_the_first_two_hundred_errors_are_listed_and_the_rest_is_counted() = runTest {
        val broken = (1..250).map { "DI_CONNECT/uploads/broken_$it.fit" to ByteArray(40) { 7 } }
        val archive = zipOf(*broken.toTypedArray())
        val finished = service(FakeImportContentSource("export.zip", archive))
            .import("content://zip", ImportKind.GARMIN_ZIP).toList()
            .filterIsInstance<ImportProgress.Finished>().single()
        assertThat(finished.counts.failed).isEqualTo(250)
        assertThat(finished.errors).hasSize(ImportService.MAX_LISTED_ERRORS + 1)
        assertThat(finished.errors.first().item).isEqualTo("DI_CONNECT/uploads/broken_1.fit")
        assertThat(finished.errors.last())
            .isEqualTo(ImportItemError(ImportService.MORE_ERRORS_ITEM, "50 more errors not listed"))
        val stored = Json.parseToJsonElement(finished.record.errorsJson!!).jsonArray
        assertThat(stored).hasSize(ImportService.MAX_LISTED_ERRORS + 1)
    }

    /** A FIT file with only a file id, like the wellness/monitoring files of a Garmin export. */
    private fun monitoringFit(): ByteArray {
        val file = java.io.File.createTempFile("monitor", ".fit").apply { deleteOnExit() }
        val encoder = FileEncoder(file, Fit.ProtocolVersion.V2_0)
        encoder.write(
            FileIdMesg().apply {
                type = com.garmin.fit.File.MONITORING_B
                manufacturer = 1
                product = 3121
                serialNumber = 42L
                timeCreated = DateTime(1_100_000_000L)
            },
        )
        encoder.close()
        return file.readBytes()
    }

    @Test
    fun activities_are_ingested_in_chunks_of_fifty() = runTest {
        val content = FakeImportContentSource("activities.csv", syntheticCsv(rows = 120).toByteArray())
        val recording = RecordingActivityRepository(activityRepo)

        val progress = service(content, recording).import("content://csv", ImportKind.GARMIN_CSV).toList()

        assertThat(recording.chunkSizes).containsExactly(50, 50, 20).inOrder()
        val finished = progress.filterIsInstance<ImportProgress.Finished>().single()
        assertThat(finished.counts.parsed).isEqualTo(120)
        assertThat(finished.counts.inserted).isEqualTo(120)
        assertThat(dao.rows()).hasSize(120)
        // Progress is reported while the import runs, not only at the end.
        assertThat(progress.filterIsInstance<ImportProgress.Working>().size).isAtLeast(3)
        // The load recompute is requested once, from the earliest day the import touched.
        assertThat(recomputeDays).containsExactly(LocalDate.of(2026, 1, 1).toEpochDay())
    }

    @Test
    fun a_failing_chunk_is_accounted_for_without_aborting_the_import() = runTest {
        val content = FakeImportContentSource("activities.csv", syntheticCsv(rows = 60).toByteArray())
        val recording = RecordingActivityRepository(activityRepo, failOn = { it.session.day % 2 == 0L })

        val finished = service(content, recording)
            .import("content://csv", ImportKind.GARMIN_CSV)
            .toList()
            .filterIsInstance<ImportProgress.Finished>()
            .single()

        assertThat(finished.counts.parsed).isEqualTo(60)
        assertThat(finished.counts.failed).isGreaterThan(0)
        assertThat(finished.counts.inserted + finished.counts.failed).isEqualTo(60)
        assertThat(finished.record.errorsJson).contains("db")
    }

    @Test
    fun a_fit_import_merges_into_an_existing_health_connect_activity() = runTest {
        val day = LocalDate.of(2026, 5, 10).toEpochDay()
        val existing = hcSession(
            startAtMillis = RunFixtureEncoder.START_MILLIS,
            durationSec = RunFixtureEncoder.TOTAL_SECONDS,
            distanceMeters = 4_980.0,
            day = day,
        )
        val seeded = activityRepo.upsert(existing)
        assertThat(seeded).isInstanceOf(Outcome.Ok::class.java)
        assertThat(dao.rows()).hasSize(1)

        service(fitContent()).import("content://doc", ImportKind.FIT_FILE).toList()

        val row = dao.rows().single()
        assertThat(row.mergedSourcesCsv).isEqualTo("HEALTH_CONNECT,FIT_IMPORT")
        assertThat(row.primarySource).isEqualTo(ActivitySource.FIT_IMPORT)
        // Streams and laps come from the FIT file (§2.4: FIT_IMPORT > HEALTH_CONNECT).
        assertThat(row.hasStreams).isTrue()
        assertThat(dao.streams[row.id]).isNotNull()
        assertThat(dao.laps.filter { it.activityId == row.id }).hasSize(2)
        // Distance is FIT's, calories stay Health Connect's (it carries the device-calibrated kcal).
        assertThat(checkNotNull(row.distanceMeters)).isWithin(1.0).of(5_000.0)
        assertThat(row.totalEnergyKcal).isEqualTo(400.0)
        assertThat(row.activeEnergyKcal).isEqualTo(355.0)
        assertThat(recomputeDays).containsExactly(day)
    }

    /**
     * "Undo import" (BUG-11 recovery): every source record this import wrote goes, the canonical
     * activity Health Connect also knows survives with HC's data, the CSV-only activity is
     * deleted, and the `import_record` — the file's checksum — is forgotten, so the same file can
     * be imported again.
     */
    @Test
    fun undo_removes_only_this_imports_source_records_and_forgets_the_file() = runTest {
        val runDay = LocalDate.of(2026, 5, 10).toEpochDay()
        seedHealthConnectRun(runDay)
        val importDao = FakeImportDao()
        val undoableRepo = RoomImportRepository(
            importDao = importDao,
            activityDao = dao,
            ingestor = ingestor,
            onUndone = { day -> recomputeDays += day },
            ioDispatcher = Dispatchers.Unconfined,
        )
        val content = FakeImportContentSource("activities.csv", UNDO_CSV.toByteArray())
        fun run() = ImportService(
            content = content,
            activityRepo = activityRepo,
            importRepo = undoableRepo,
            csvParser = GarminCsvParser(TimeZone.of("Europe/Berlin")),
            clock = clock,
            onImported = { day -> recomputeDays += day },
            ioDispatcher = Dispatchers.Unconfined,
        ).import("content://csv", ImportKind.GARMIN_CSV)

        val finished = run().toList().filterIsInstance<ImportProgress.Finished>().single()
        assertThat(finished.counts.parsed).isEqualTo(2)
        assertThat(dao.rows()).hasSize(2)
        // Both CSV arrivals are stamped with the import that wrote them; the HC one is not.
        assertThat(dao.sourceRecords.filter { it.importRecordId == finished.record.id }).hasSize(2)
        assertThat(dao.sourceRecords.single { it.source == ActivitySource.HEALTH_CONNECT }.importRecordId)
            .isNull()

        val undone = undoableRepo.undo(finished.record.id)

        val summary = (undone as Outcome.Ok).value
        assertThat(summary.sourceRecordsRemoved).isEqualTo(2)
        assertThat(summary.activitiesDeleted).isEqualTo(1)
        assertThat(summary.activitiesKept).isEqualTo(1)

        // The merged run is kept and falls back to Health Connect alone; the CSV-only ride is gone.
        val kept = dao.rows().single()
        assertThat(kept.mergedSourcesCsv).isEqualTo("HEALTH_CONNECT")
        assertThat(kept.primarySource).isEqualTo(ActivitySource.HEALTH_CONNECT)
        assertThat(kept.totalEnergyKcal).isEqualTo(400.0)
        assertThat(dao.sourceRecords.map { it.source }).containsExactly(ActivitySource.HEALTH_CONNECT)
        assertThat(recomputeDays).contains(runDay)

        // The checksum is forgotten, so the very same file imports again.
        assertThat(importDao.all()).isEmpty()
        val again = run().toList()
        assertThat(again.filterIsInstance<ImportProgress.AlreadyImported>()).isEmpty()
        assertThat(again.filterIsInstance<ImportProgress.Finished>().single().counts.parsed).isEqualTo(2)
        assertThat(dao.rows()).hasSize(2)
    }

    /** One Health Connect arrival, ingested through the real seam so it has a source record. */
    private suspend fun seedHealthConnectRun(day: Long) {
        val outcome = activityRepo.ingest(
            listOf(
                ActivityIngestItem(
                    record = ActivitySourceRecord(
                        id = 0L,
                        activityId = null,
                        source = ActivitySource.HEALTH_CONNECT,
                        externalId = "hc-run",
                        payloadJson = "{}",
                        receivedAtMillis = NOW,
                    ),
                    session = hcSession(
                        startAtMillis = RUN_START,
                        durationSec = 1_500,
                        distanceMeters = 4_980.0,
                        day = day,
                    ),
                ),
            ),
        )
        assertThat(outcome).isInstanceOf(Outcome.Ok::class.java)
    }

    @Test
    fun an_unreadable_document_fails_the_whole_import() = runTest {
        val content = object : ImportContentSource {
            override suspend fun displayName(uri: String) = "gone.fit"
            override suspend fun openSource(uri: String): okio.Source = throw java.io.IOException("no such file")
        }
        val service = ImportService(
            content = content,
            activityRepo = activityRepo,
            importRepo = importRepo,
            csvParser = GarminCsvParser(TimeZone.of("Europe/Berlin")),
            clock = clock,
            ioDispatcher = Dispatchers.Unconfined,
        )

        val progress = service.import("content://gone", ImportKind.FIT_FILE).toList()

        assertThat(progress.filterIsInstance<ImportProgress.Failed>()).hasSize(1)
        assertThat(importRepo.all()).isEmpty()
    }

    @Test
    fun the_file_name_decides_the_import_kind() {
        assertThat(ImportKinds.forFileName("2026-05-10.fit")).isEqualTo(ImportKind.FIT_FILE)
        assertThat(ImportKinds.forFileName("Activities.CSV")).isEqualTo(ImportKind.GARMIN_CSV)
        assertThat(ImportKinds.forFileName("export.zip")).isEqualTo(ImportKind.GARMIN_ZIP)
        assertThat(ImportKinds.forFileName("notes.txt")).isNull()
    }

    private companion object {
        const val NOW = 1_800_000_000_000L

        /** 2026-05-10T09:00 Europe/Berlin. */
        const val RUN_START = 1_778_396_400_000L

        /** Row 1 is the run Health Connect already knows; row 2 exists only in this file. */
        val UNDO_CSV = """
            Activity Type,Date,Title,Distance,Calories,Time,Avg HR
            Running,2026-05-10 09:00:00,Morning run,5.00,355,00:25:00,152
            Cycling,2026-05-11 17:30:00,Evening ride,30.25,700,1:05:30,138
        """.trimIndent()
    }
}

/** 120 synthetic rows, one per day from 2026-01-01, to exercise the chunk boundary. */
internal fun syntheticCsv(rows: Int): String = buildString {
    appendLine("Activity Type,Date,Title,Distance,Calories,Time,Avg HR")
    repeat(rows) { index ->
        val date = LocalDate.of(2026, 1, 1).plusDays(index.toLong())
        appendLine("Running,$date 09:00:00,Run $index,5.00,350,00:25:00,150")
    }
}

/** The English CSV fixture, reused as a zip entry. */
internal fun loadEnglishCsv(): String =
    checkNotNull(ImportServiceTest::class.java.classLoader)
        .getResource("fixtures/csv/garmin_en.csv")!!
        .readText()
