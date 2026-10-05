package com.myhealth.domain.repository

import com.myhealth.domain.model.ActivitySource
import com.myhealth.domain.model.BodyMeasurement
import com.myhealth.domain.util.Outcome
import kotlinx.coroutines.flow.Flow

/**
 * `body_measurement` (PLAN §2.2.1). [latestWeight] and [latestWithBodyFat] implement the weight
 * ladder and the Katch–McArdle/Mifflin selection of the nutrition target engine (§3.1.1): both
 * look back at most [withinDays] days and return `null` when nothing recent enough exists.
 */
interface BodyRepository {

    fun observeLatest(): Flow<BodyMeasurement?>

    fun observeRange(fromDay: Long, toDay: Long): Flow<List<BodyMeasurement>>

    /** Most recent measurement carrying a weight, within [withinDays] days of now. */
    suspend fun latestWeight(withinDays: Long): BodyMeasurement?

    /** Most recent measurement carrying a body-fat percentage, within [withinDays] days of now. */
    suspend fun latestWithBodyFat(withinDays: Long): BodyMeasurement?

    /** Returns the row id of the inserted (or replaced) measurement. */
    suspend fun insert(measurement: BodyMeasurement): Outcome<Long>

    /** Bulk path for Health Connect / import ingestion (P2.3, P7.5). */
    suspend fun upsertAll(measurements: List<BodyMeasurement>): Outcome<Unit>

    suspend fun delete(id: Long): Outcome<Unit>

    /** Health Connect deleted the record behind this row (P2.6); a no-op when it is unknown. */
    suspend fun deleteByExternalId(source: ActivitySource, externalId: String): Outcome<Unit>
}
