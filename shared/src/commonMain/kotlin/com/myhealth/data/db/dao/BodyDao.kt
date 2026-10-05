package com.myhealth.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.myhealth.data.db.entity.BodyMeasurementEntity
import com.myhealth.domain.model.ActivitySource
import kotlinx.coroutines.flow.Flow

/** DAO for `body_measurement` (PLAN §2.2.1). */
@Dao
interface BodyDao {

    @Upsert
    suspend fun upsert(entity: BodyMeasurementEntity): Long

    @Upsert
    suspend fun upsertAll(entities: List<BodyMeasurementEntity>)

    /**
     * Synced rows always arrive with `id = 0`. `@Upsert` resolves a conflict by falling back to an
     * update **by primary key**, which a fresh `id = 0` never matches — so a corrected value from
     * the source would otherwise be silently dropped instead of updating the row that already
     * collides on the unique `(source, externalId)` index. Each row's existing id is resolved
     * first, in one transaction, so the upsert lands on the right primary key.
     */
    @Transaction
    suspend fun upsertAllResolvingIds(entities: List<BodyMeasurementEntity>) {
        val resolved = entities.map { entity ->
            val externalId = entity.externalId
            val existing = if (externalId != null) getBySourceExternalId(entity.source, externalId) else null
            if (existing != null) entity.copy(id = existing.id) else entity
        }
        upsertAll(resolved)
    }

    @Query("SELECT * FROM body_measurement WHERE id = :id")
    suspend fun getById(id: Long): BodyMeasurementEntity?

    @Query("DELETE FROM body_measurement WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT * FROM body_measurement ORDER BY measuredAtMillis DESC LIMIT 1")
    fun observeLatest(): Flow<BodyMeasurementEntity?>

    @Query(
        "SELECT * FROM body_measurement WHERE day BETWEEN :fromDay AND :toDay " +
            "ORDER BY measuredAtMillis ASC",
    )
    fun observeRange(fromDay: Long, toDay: Long): Flow<List<BodyMeasurementEntity>>

    /** Most recent row carrying a weight at or after [sinceMillis] — the §3.1.1 weight ladder. */
    @Query(
        "SELECT * FROM body_measurement WHERE weightKg IS NOT NULL AND measuredAtMillis >= :sinceMillis " +
            "ORDER BY measuredAtMillis DESC LIMIT 1",
    )
    suspend fun latestWeightSince(sinceMillis: Long): BodyMeasurementEntity?

    /** Most recent row carrying a body-fat percentage — selects Katch–McArdle over Mifflin (§3.1.1). */
    @Query(
        "SELECT * FROM body_measurement WHERE bodyFatPercent IS NOT NULL AND measuredAtMillis >= :sinceMillis " +
            "ORDER BY measuredAtMillis DESC LIMIT 1",
    )
    suspend fun latestBodyFatSince(sinceMillis: Long): BodyMeasurementEntity?

    @Query("SELECT * FROM body_measurement WHERE source = :source AND externalId = :externalId")
    suspend fun getBySourceExternalId(source: ActivitySource, externalId: String): BodyMeasurementEntity?
}
