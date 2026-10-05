package com.myhealth.data.mapper

import com.myhealth.data.db.entity.DailyLoadEntity
import com.myhealth.data.db.entity.ImportRecordEntity
import com.myhealth.data.db.dao.RideBestRow
import com.myhealth.data.db.entity.RideBestEntity
import com.myhealth.data.db.entity.RunningBestEntity
import com.myhealth.domain.model.DailyLoad
import com.myhealth.domain.model.ImportRecord
import com.myhealth.domain.model.RideBest
import com.myhealth.domain.model.RunningBest

/**
 * `daily_load`, `running_best`, `ride_best` and `import_record` ⇄ the domain load models
 * (PLAN §2.2.6 / P1.6, P12).
 */

// ---- daily_load ⇄ DailyLoad (flagsCsv ⇄ List<String>) ---------------------------------------------

private fun List<String>.toCsv(): String = joinToString(",")

private fun String.csvToList(): List<String> =
    if (isBlank()) emptyList() else split(",").map { it.trim() }.filter { it.isNotEmpty() }

fun DailyLoadEntity.toDomain(): DailyLoad = DailyLoad(
    day = day,
    trimp = trimp,
    sessionCount = sessionCount,
    atl = atl,
    ctl = ctl,
    acwr = acwr,
    tsb = tsb,
    monotony = monotony,
    strain = strain,
    recoveryScore = recoveryScore,
    recoveryBand = recoveryBand,
    recoveryConfidence = recoveryConfidence,
    flags = flagsCsv.csvToList(),
    computedAtMillis = computedAtMillis,
)

fun DailyLoad.toEntity(): DailyLoadEntity = DailyLoadEntity(
    day = day,
    trimp = trimp,
    sessionCount = sessionCount,
    atl = atl,
    ctl = ctl,
    acwr = acwr,
    tsb = tsb,
    monotony = monotony,
    strain = strain,
    recoveryScore = recoveryScore,
    recoveryBand = recoveryBand,
    recoveryConfidence = recoveryConfidence,
    flagsCsv = flags.toCsv(),
    computedAtMillis = computedAtMillis,
)

// ---- running_best ⇄ RunningBest ---------------------------------------------------------------------

fun RunningBestEntity.toDomain(): RunningBest = RunningBest(
    id = id,
    distanceMeters = distanceMeters,
    timeSec = timeSec,
    activityId = activityId,
    day = day,
    method = method,
    isEstimated = isEstimated,
    paceSecPerKm = paceSecPerKm,
    createdAtMillis = createdAtMillis,
)

fun RunningBest.toEntity(): RunningBestEntity = RunningBestEntity(
    id = id,
    distanceMeters = distanceMeters,
    timeSec = timeSec,
    activityId = activityId,
    day = day,
    method = method,
    isEstimated = isEstimated,
    paceSecPerKm = paceSecPerKm,
    createdAtMillis = createdAtMillis,
)

// ---- ride_best ⇄ RideBest (P12) ---------------------------------------------------------------------

fun RideBestEntity.toDomain(): RideBest = RideBest(
    id = id,
    kind = kind,
    value = value,
    activityId = activityId,
    day = day,
    isEstimated = isEstimated,
    createdAtMillis = createdAtMillis,
)

/** The PR projection of `RideBestDao.observeBestPerKind`; its `ordering` column is not a value. */
fun RideBestRow.toDomain(): RideBest = RideBest(
    id = id,
    kind = kind,
    value = value,
    activityId = activityId,
    day = day,
    isEstimated = isEstimated,
    createdAtMillis = createdAtMillis,
)

fun RideBest.toEntity(): RideBestEntity = RideBestEntity(
    id = id,
    kind = kind,
    value = value,
    activityId = activityId,
    day = day,
    isEstimated = isEstimated,
    createdAtMillis = createdAtMillis,
)

// ---- import_record ⇄ ImportRecord -------------------------------------------------------------------

fun ImportRecordEntity.toDomain(): ImportRecord = ImportRecord(
    id = id,
    kind = kind,
    fileName = fileName,
    fileHashSha256 = fileHashSha256,
    importedAtMillis = importedAtMillis,
    itemsParsed = itemsParsed,
    itemsInserted = itemsInserted,
    itemsDuplicate = itemsDuplicate,
    errorsJson = errorsJson,
)

fun ImportRecord.toEntity(): ImportRecordEntity = ImportRecordEntity(
    id = id,
    kind = kind,
    fileName = fileName,
    fileHashSha256 = fileHashSha256,
    importedAtMillis = importedAtMillis,
    itemsParsed = itemsParsed,
    itemsInserted = itemsInserted,
    itemsDuplicate = itemsDuplicate,
    errorsJson = errorsJson,
)
