package com.myhealth.domain.model

/** Mirrors `daily_health_summary` (PLAN §2.2.3), PK is [day] (epoch day). */
data class DailyHealthSummary(
    val day: Long,
    val steps: Int?,
    val totalEnergyKcal: Double?,
    val activeEnergyKcal: Double?,
    val restingHr: Int?,
    val distanceMeters: Double?,
    val floors: Double?,
    val avgSpo2Percent: Double?,
    val avgRespiratoryRate: Double?,
    val hrvRmssdMs: Double?,
    val vo2Max: Double?,
    val bodyBattery: Int?,
    val stressAvg: Int?,
    val trainingReadiness: Int?,
    val source: ActivitySource,
    val updatedAtMillis: Long,
)

/** One contiguous sleep stage interval, decoded from `sleep_session.stagesJson`. */
data class SleepStageInterval(val startAtMillis: Long, val endAtMillis: Long, val stage: SleepStage)

/** Mirrors `sleep_session` (§2.2.3). [night] is the epoch day the sleep is attributed to. */
data class SleepRecord(
    val id: Long,
    val startAtMillis: Long,
    val endAtMillis: Long,
    val night: Long,
    val totalSleepMin: Int,
    val lightMin: Int?,
    val deepMin: Int?,
    val remMin: Int?,
    val awakeMin: Int?,
    val stages: List<SleepStageInterval>?,
    val source: ActivitySource,
    val externalId: String?,
    val sleepScore: Int?,
)

/**
 * Mirrors `sync_state` (§2.2.3), PK [key] — one of `hc.exercise`, `hc.daily`, `hc.sleep`,
 * `hc.body`, `targets.recompute`, `load.recompute`.
 */
data class SyncState(
    val key: String,
    val changesToken: String?,
    val lastSuccessAtMillis: Long?,
    val lastErrorAtMillis: Long?,
    val lastError: String?,
    val backfillCompleteDay: Long?,
)
