package com.myhealth.data.repository

import com.google.common.truth.Truth.assertThat
import com.myhealth.domain.engine.activity.ActivityFixtures
import com.myhealth.domain.model.ActivitySession
import com.myhealth.domain.model.ActivitySource
import com.myhealth.domain.model.ActivitySourceRecord
import com.myhealth.domain.model.SportType
import com.myhealth.domain.repository.ActivityIngestItem
import com.myhealth.testutil.Fixtures
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * The ingestion pipeline of PLAN P2.5 against an in-memory [FakeActivityDao]: idempotency on
 * `(source, externalId)`, candidate lookup by dedupe bucket, the §2.4 merge, and the deletion
 * path that P2.6 drives from a Health Connect `DeletionChange`.
 */
class ActivityIngestorTest {

    private val dao = FakeActivityDao()
    private val clock = Fixtures.fixedClock("2026-09-12T12:00:00Z")
    private val ingestor = ActivityIngestor(dao, DirectTransactionRunner, clock)

    private val hcSession = ActivityFixtures.session(
        source = ActivitySource.HEALTH_CONNECT,
        startIso = "2026-09-12T06:00:00Z",
        durationSec = 3600,
        sportType = SportType.RUN_OUTDOOR,
        distanceMeters = 9_940.0,
        title = "Running",
        activeEnergyKcal = 720.0,
        avgHr = 148,
        streams = ActivityFixtures.streams(140, 150),
    )

    private val fitSession = ActivityFixtures.session(
        source = ActivitySource.FIT_IMPORT,
        startIso = "2026-09-12T05:59:40Z",
        durationSec = 3612,
        sportType = SportType.RUN_TRAIL,
        distanceMeters = 10_000.0,
        title = "Morning trail",
        activeEnergyKcal = 690.0,
        avgHr = 151,
        maxHr = 178,
        elevationGainM = 240.0,
        streams = ActivityFixtures.streams(141, 149, 152),
        laps = listOf(ActivityFixtures.lap(0, 5_000.0), ActivityFixtures.lap(1, 5_000.0)),
    )

    private val hc = item(hcSession, ActivitySource.HEALTH_CONNECT, "hc-1")
    private val fit = item(fitSession, ActivitySource.FIT_IMPORT, "fit-1")

    @Test
    fun a_new_activity_is_inserted_with_its_source_record_and_streams() = runTest {
        val result = ingestor.ingest(listOf(hc))

        assertThat(result.inserted).isEqualTo(1)
        assertThat(result.merged).isEqualTo(0)
        assertThat(result.duplicate).isEqualTo(0)

        val row = dao.rows().single()
        assertThat(row.id).isEqualTo(1L)
        assertThat(row.distanceMeters).isEqualTo(9_940.0)
        assertThat(row.primarySource).isEqualTo(ActivitySource.HEALTH_CONNECT)
        assertThat(row.mergedSourcesCsv).isEqualTo("HEALTH_CONNECT")
        assertThat(row.hasStreams).isTrue()
        assertThat(dao.streams).containsKey(1L)
        assertThat(dao.sourceRecords.single().activityId).isEqualTo(1L)
    }

    @Test
    fun re_ingesting_the_same_record_creates_no_duplicate() = runTest {
        ingestor.ingest(listOf(hc))
        val again = ingestor.ingest(listOf(hc))

        assertThat(again.duplicate).isEqualTo(1)
        assertThat(again.inserted).isEqualTo(0)
        assertThat(dao.rows()).hasSize(1)
        assertThat(dao.sourceRecords).hasSize(1)
    }

    @Test
    fun a_changed_payload_for_a_known_record_is_refreshed_in_place() = runTest {
        ingestor.ingest(listOf(hc))
        val corrected = hc.copy(record = hc.record.copy(payloadJson = """{"v":2}"""))

        ingestor.ingest(listOf(corrected))

        assertThat(dao.sourceRecords).hasSize(1)
        assertThat(dao.sourceRecords.single().payloadJson).isEqualTo("""{"v":2}""")
    }

    @Test
    fun health_connect_then_fit_merges_into_one_activity() = runTest {
        ingestor.ingest(listOf(hc))
        val second = ingestor.ingest(listOf(fit))

        assertThat(second.merged).isEqualTo(1)
        assertThat(second.inserted).isEqualTo(0)

        val row = dao.rows().single()
        assertThat(row.distanceMeters).isEqualTo(10_000.0)
        assertThat(row.activeEnergyKcal).isEqualTo(720.0)
        assertThat(row.title).isEqualTo("Morning trail")
        assertThat(row.sportType).isEqualTo(SportType.RUN_TRAIL)
        assertThat(row.primarySource).isEqualTo(ActivitySource.FIT_IMPORT)
        assertThat(row.mergedSourcesCsv).isEqualTo("HEALTH_CONNECT,FIT_IMPORT")
        assertThat(dao.sourceRecords.map { it.activityId }).containsExactly(1L, 1L)
        assertThat(dao.getLaps(1L)).hasSize(2)
    }

    @Test
    fun fit_then_health_connect_produces_the_same_activity() = runTest {
        ingestor.ingest(listOf(fit))
        ingestor.ingest(listOf(hc))
        val fitFirst = dao.rows().single()

        val other = FakeActivityDao()
        val otherIngestor = ActivityIngestor(other, DirectTransactionRunner, clock)
        otherIngestor.ingest(listOf(hc))
        otherIngestor.ingest(listOf(fit))
        val hcFirst = other.rows().single()

        assertThat(other.rows()).hasSize(1)
        assertThat(fitFirst.copy(createdAtMillis = 0L))
            .isEqualTo(hcFirst.copy(createdAtMillis = 0L))
        assertThat(fitFirst.distanceMeters).isEqualTo(10_000.0)
        assertThat(fitFirst.activeEnergyKcal).isEqualTo(720.0)
    }

    @Test
    fun an_unrelated_activity_in_the_same_bucket_is_not_merged() = runTest {
        val ride = ActivityFixtures.session(
            source = ActivitySource.HEALTH_CONNECT,
            startIso = "2026-09-12T06:00:00Z",
            sportType = SportType.CYCLING,
            distanceMeters = 40_000.0,
        )
        ingestor.ingest(listOf(hc))

        val result = ingestor.ingest(listOf(item(ride, ActivitySource.HEALTH_CONNECT, "hc-2")))

        assertThat(result.inserted).isEqualTo(1)
        assertThat(dao.rows()).hasSize(2)
    }

    @Test
    fun deleting_the_only_source_removes_the_canonical_row() = runTest {
        ingestor.ingest(listOf(hc))

        ingestor.removeSourceRecord(ActivitySource.HEALTH_CONNECT, "hc-1")

        assertThat(dao.rows()).isEmpty()
        assertThat(dao.sourceRecords).isEmpty()
        assertThat(dao.streams).isEmpty()
    }

    @Test
    fun deleting_one_of_two_sources_keeps_the_activity_and_re_merges_it() = runTest {
        ingestor.ingest(listOf(hc, fit))

        ingestor.removeSourceRecord(ActivitySource.FIT_IMPORT, "fit-1")

        val row = dao.rows().single()
        assertThat(row.mergedSourcesCsv).isEqualTo("HEALTH_CONNECT")
        assertThat(row.primarySource).isEqualTo(ActivitySource.HEALTH_CONNECT)
        assertThat(dao.sourceRecords.single().source).isEqualTo(ActivitySource.HEALTH_CONNECT)
    }

    @Test
    fun deleting_an_unknown_record_is_a_no_op() = runTest {
        ingestor.ingest(listOf(hc))

        ingestor.removeSourceRecord(ActivitySource.FIT_IMPORT, "missing")

        assertThat(dao.rows()).hasSize(1)
    }

    private fun item(
        session: ActivitySession,
        source: ActivitySource,
        externalId: String,
    ) = ActivityIngestItem(
        record = ActivitySourceRecord(
            id = 0,
            activityId = null,
            source = source,
            externalId = externalId,
            payloadJson = """{"externalId":"$externalId"}""",
            receivedAtMillis = ActivityFixtures.NOW,
        ),
        session = session,
    )
}
