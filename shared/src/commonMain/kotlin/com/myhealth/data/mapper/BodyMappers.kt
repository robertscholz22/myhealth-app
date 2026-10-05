package com.myhealth.data.mapper

import com.myhealth.data.db.entity.BodyMeasurementEntity
import com.myhealth.domain.model.BodyMeasurement

/** `body_measurement` ⇄ [BodyMeasurement] (PLAN §2.2.1 / P1.6). 1:1 field mapping. */
fun BodyMeasurementEntity.toDomain(): BodyMeasurement = BodyMeasurement(
    id = id,
    measuredAtMillis = measuredAtMillis,
    day = day,
    weightKg = weightKg,
    bodyFatPercent = bodyFatPercent,
    muscleMassKg = muscleMassKg,
    boneMassKg = boneMassKg,
    bodyWaterPercent = bodyWaterPercent,
    source = source,
    externalId = externalId,
    note = note,
)

fun BodyMeasurement.toEntity(): BodyMeasurementEntity = BodyMeasurementEntity(
    id = id,
    measuredAtMillis = measuredAtMillis,
    day = day,
    weightKg = weightKg,
    bodyFatPercent = bodyFatPercent,
    muscleMassKg = muscleMassKg,
    boneMassKg = boneMassKg,
    bodyWaterPercent = bodyWaterPercent,
    source = source,
    externalId = externalId,
    note = note,
)
