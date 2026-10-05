package com.myhealth.data.mapper

import com.myhealth.data.db.entity.DailyHealthSummaryEntity
import com.myhealth.data.db.entity.SleepSessionEntity
import com.myhealth.data.db.entity.SyncStateEntity
import com.myhealth.domain.model.DailyHealthSummary
import com.myhealth.domain.model.SleepRecord
import com.myhealth.domain.model.SleepStage
import com.myhealth.domain.model.SleepStageInterval
import com.myhealth.domain.model.SyncState
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * `daily_health_summary`, `sleep_session` and `sync_state` ⇄ the domain health models
 * (PLAN §2.2.3 / P1.6).
 */

// ---- daily_health_summary ⇄ DailyHealthSummary -------------------------------------------------

fun DailyHealthSummaryEntity.toDomain(): DailyHealthSummary = DailyHealthSummary(
    day = day,
    steps = steps,
    totalEnergyKcal = totalEnergyKcal,
    activeEnergyKcal = activeEnergyKcal,
    restingHr = restingHr,
    distanceMeters = distanceMeters,
    floors = floors,
    avgSpo2Percent = avgSpo2Percent,
    avgRespiratoryRate = avgRespiratoryRate,
    hrvRmssdMs = hrvRmssdMs,
    vo2Max = vo2Max,
    bodyBattery = bodyBattery,
    stressAvg = stressAvg,
    trainingReadiness = trainingReadiness,
    source = source,
    updatedAtMillis = updatedAtMillis,
)

fun DailyHealthSummary.toEntity(): DailyHealthSummaryEntity = DailyHealthSummaryEntity(
    day = day,
    steps = steps,
    totalEnergyKcal = totalEnergyKcal,
    activeEnergyKcal = activeEnergyKcal,
    restingHr = restingHr,
    distanceMeters = distanceMeters,
    floors = floors,
    avgSpo2Percent = avgSpo2Percent,
    avgRespiratoryRate = avgRespiratoryRate,
    hrvRmssdMs = hrvRmssdMs,
    vo2Max = vo2Max,
    bodyBattery = bodyBattery,
    stressAvg = stressAvg,
    trainingReadiness = trainingReadiness,
    source = source,
    updatedAtMillis = updatedAtMillis,
)

// ---- sleep_session ⇄ SleepRecord (stagesJson ⇄ List<SleepStageInterval>) -----------------------

private val json = Json { ignoreUnknownKeys = true }

@Serializable
private data class SleepStageDto(val s: Long, val e: Long, val stage: String)

private fun List<SleepStageInterval>.toStagesJson(): String =
    json.encodeToString(map { SleepStageDto(it.startAtMillis, it.endAtMillis, it.stage.name) })

private fun String.toStageIntervals(): List<SleepStageInterval> =
    json.decodeFromString<List<SleepStageDto>>(this).map { dto ->
        SleepStageInterval(
            startAtMillis = dto.s,
            endAtMillis = dto.e,
            stage = SleepStage.entries.firstOrNull { it.name == dto.stage } ?: SleepStage.UNKNOWN,
        )
    }

fun SleepSessionEntity.toDomain(): SleepRecord = SleepRecord(
    id = id,
    startAtMillis = startAtMillis,
    endAtMillis = endAtMillis,
    night = night,
    totalSleepMin = totalSleepMin,
    lightMin = lightMin,
    deepMin = deepMin,
    remMin = remMin,
    awakeMin = awakeMin,
    stages = stagesJson?.toStageIntervals(),
    source = source,
    externalId = externalId,
    sleepScore = sleepScore,
)

fun SleepRecord.toEntity(): SleepSessionEntity = SleepSessionEntity(
    id = id,
    startAtMillis = startAtMillis,
    endAtMillis = endAtMillis,
    night = night,
    totalSleepMin = totalSleepMin,
    lightMin = lightMin,
    deepMin = deepMin,
    remMin = remMin,
    awakeMin = awakeMin,
    stagesJson = stages?.toStagesJson(),
    source = source,
    externalId = externalId,
    sleepScore = sleepScore,
)

// ---- sync_state ⇄ SyncState ---------------------------------------------------------------------

fun SyncStateEntity.toDomain(): SyncState = SyncState(
    key = key,
    changesToken = changesToken,
    lastSuccessAtMillis = lastSuccessAtMillis,
    lastErrorAtMillis = lastErrorAtMillis,
    lastError = lastError,
    backfillCompleteDay = backfillCompleteDay,
)

fun SyncState.toEntity(): SyncStateEntity = SyncStateEntity(
    key = key,
    changesToken = changesToken,
    lastSuccessAtMillis = lastSuccessAtMillis,
    lastErrorAtMillis = lastErrorAtMillis,
    lastError = lastError,
    backfillCompleteDay = backfillCompleteDay,
)
