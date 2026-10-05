package com.myhealth.data.repository

import com.google.common.truth.Truth.assertThat
import com.myhealth.data.db.dao.BodyDao
import com.myhealth.data.db.entity.BodyMeasurementEntity
import com.myhealth.domain.model.ActivitySource
import com.myhealth.domain.model.BodyMeasurement
import com.myhealth.domain.util.AppError
import com.myhealth.domain.util.Outcome
import com.myhealth.testutil.Fixtures
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.io.IOException

/**
 * Unit tests for [RoomBodyRepository] against an in-memory fake DAO (PLAN P1.7). "Now" is pinned
 * to 2026-09-12T12:00Z so the look-back windows of the weight ladder (§3.1.1) are exact.
 */
class RoomBodyRepositoryTest {

    private val dao = FakeBodyDao()
    private val clock = Fixtures.fixedClock("2026-09-12T12:00:00Z")
    private val repo = RoomBodyRepository(dao, clock, Dispatchers.Unconfined)

    @Test
    fun observe_latest_returns_the_most_recently_measured_row() = runTest {
        repo.insert(measurement("2026-09-10T07:00:00Z", weightKg = 80.0))
        repo.insert(measurement("2026-09-12T07:00:00Z", weightKg = 79.4))
        repo.insert(measurement("2026-09-11T07:00:00Z", weightKg = 79.9))

        assertThat(repo.observeLatest().first()?.weightKg).isEqualTo(79.4)
    }

    @Test
    fun observe_latest_emits_null_when_nothing_is_stored() = runTest {
        assertThat(repo.observeLatest().first()).isNull()
    }

    @Test
    fun observe_range_filters_by_day_and_sorts_oldest_first() = runTest {
        repo.insert(measurement("2026-09-12T07:00:00Z", weightKg = 79.4))
        repo.insert(measurement("2026-09-01T07:00:00Z", weightKg = 81.0))
        repo.insert(measurement("2026-08-20T07:00:00Z", weightKg = 82.0))

        val range = repo.observeRange(
            fromDay = Fixtures.epochDay("2026-09-01"),
            toDay = Fixtures.epochDay("2026-09-12"),
        ).first()

        assertThat(range.map { it.weightKg }).containsExactly(81.0, 79.4).inOrder()
    }

    @Test
    fun latest_with_body_fat_returns_the_newest_row_inside_the_window() = runTest {
        repo.insert(measurement("2026-09-02T07:00:00Z", weightKg = 80.0, bodyFatPercent = 18.0))
        repo.insert(measurement("2026-09-09T07:00:00Z", weightKg = 79.5, bodyFatPercent = 17.2))

        assertThat(repo.latestWithBodyFat(withinDays = 30)?.bodyFatPercent).isEqualTo(17.2)
    }

    @Test
    fun latest_with_body_fat_ignores_rows_older_than_the_window() = runTest {
        repo.insert(measurement("2026-07-01T07:00:00Z", weightKg = 83.0, bodyFatPercent = 21.0))

        assertThat(repo.latestWithBodyFat(withinDays = 30)).isNull()
        assertThat(repo.latestWithBodyFat(withinDays = 120)?.bodyFatPercent).isEqualTo(21.0)
    }

    @Test
    fun latest_with_body_fat_ignores_rows_that_only_carry_a_weight() = runTest {
        repo.insert(measurement("2026-09-11T07:00:00Z", weightKg = 79.0))

        assertThat(repo.latestWithBodyFat(withinDays = 30)).isNull()
        assertThat(repo.latestWeight(withinDays = 30)?.weightKg).isEqualTo(79.0)
    }

    @Test
    fun latest_weight_applies_the_same_window() = runTest {
        repo.insert(measurement("2026-06-01T07:00:00Z", weightKg = 85.0))

        assertThat(repo.latestWeight(withinDays = 30)).isNull()
    }

    @Test
    fun insert_returns_the_new_row_id() = runTest {
        val first = repo.insert(measurement("2026-09-11T07:00:00Z", weightKg = 79.0))
        val second = repo.insert(measurement("2026-09-12T07:00:00Z", weightKg = 78.8))

        assertThat((first as Outcome.Ok).value).isEqualTo(1L)
        assertThat((second as Outcome.Ok).value).isEqualTo(2L)
    }

    @Test
    fun upsert_all_stores_every_measurement() = runTest {
        val result = repo.upsertAll(
            listOf(
                measurement("2026-09-11T07:00:00Z", weightKg = 79.0),
                measurement("2026-09-12T07:00:00Z", weightKg = 78.8),
            ),
        )

        assertThat(result).isInstanceOf(Outcome.Ok::class.java)
        assertThat(dao.rows.value).hasSize(2)
    }

    @Test
    fun upsertAll_updates_existing_row_by_external_id() = runTest {
        repo.insert(
            measurement("2026-09-11T07:00:00Z", weightKg = 79.0).copy(
                source = ActivitySource.HEALTH_CONNECT,
                externalId = "hc-1",
            ),
        )

        val corrected = repo.upsertAll(
            listOf(
                measurement("2026-09-11T07:00:00Z", weightKg = 79.6).copy(
                    source = ActivitySource.HEALTH_CONNECT,
                    externalId = "hc-1",
                ),
            ),
        )

        assertThat(corrected).isInstanceOf(Outcome.Ok::class.java)
        assertThat(dao.rows.value).hasSize(1)
        assertThat(dao.rows.value.single().weightKg).isEqualTo(79.6)
    }

    @Test
    fun delete_removes_the_measurement() = runTest {
        val id = (repo.insert(measurement("2026-09-12T07:00:00Z", weightKg = 78.8)) as Outcome.Ok).value

        assertThat(repo.delete(id)).isInstanceOf(Outcome.Ok::class.java)
        assertThat(repo.observeLatest().first()).isNull()
    }

    @Test
    fun a_failing_write_is_reported_as_a_storage_error() = runTest {
        dao.failWith = IOException("disk full")

        val result = repo.insert(measurement("2026-09-12T07:00:00Z", weightKg = 78.8))

        assertThat(result).isInstanceOf(Outcome.Err::class.java)
        assertThat((result as Outcome.Err).error).isInstanceOf(AppError.Storage::class.java)
    }

    private fun measurement(
        at: String,
        weightKg: Double? = null,
        bodyFatPercent: Double? = null,
    ) = BodyMeasurement(
        measuredAtMillis = Fixtures.millis(at),
        day = Fixtures.epochDay(at.substringBefore('T')),
        weightKg = weightKg,
        bodyFatPercent = bodyFatPercent,
        muscleMassKg = null,
        boneMassKg = null,
        bodyWaterPercent = null,
        source = ActivitySource.MANUAL,
    )
}

/** In-memory [BodyDao] reproducing the ordering and filtering the real queries do in SQL. */
private class FakeBodyDao : BodyDao {

    val rows = MutableStateFlow<List<BodyMeasurementEntity>>(emptyList())
    private var nextId = 1L
    var failWith: Throwable? = null

    override suspend fun upsert(entity: BodyMeasurementEntity): Long {
        failWith?.let { throw it }
        val id = if (entity.id == 0L) nextId++ else entity.id
        rows.value = rows.value.filterNot { it.id == id } + entity.copy(id = id)
        return id
    }

    override suspend fun upsertAll(entities: List<BodyMeasurementEntity>) {
        entities.forEach { upsert(it) }
    }

    override suspend fun getById(id: Long): BodyMeasurementEntity? = rows.value.firstOrNull { it.id == id }

    override suspend fun deleteById(id: Long) {
        rows.value = rows.value.filterNot { it.id == id }
    }

    override fun observeLatest(): Flow<BodyMeasurementEntity?> =
        rows.map { list -> list.maxByOrNull { it.measuredAtMillis } }

    override fun observeRange(fromDay: Long, toDay: Long): Flow<List<BodyMeasurementEntity>> =
        rows.map { list -> list.filter { it.day in fromDay..toDay }.sortedBy { it.measuredAtMillis } }

    override suspend fun latestWeightSince(sinceMillis: Long): BodyMeasurementEntity? =
        rows.value.filter { it.weightKg != null && it.measuredAtMillis >= sinceMillis }
            .maxByOrNull { it.measuredAtMillis }

    override suspend fun latestBodyFatSince(sinceMillis: Long): BodyMeasurementEntity? =
        rows.value.filter { it.bodyFatPercent != null && it.measuredAtMillis >= sinceMillis }
            .maxByOrNull { it.measuredAtMillis }

    override suspend fun getBySourceExternalId(
        source: ActivitySource,
        externalId: String,
    ): BodyMeasurementEntity? =
        rows.value.firstOrNull { it.source == source && it.externalId == externalId }
}
