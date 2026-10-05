package com.myhealth.data.healthconnect

import kotlinx.serialization.Serializable

/**
 * Plain, Health-Connect-free snapshots of what `HcReader` (P2.2) read (PLAN P2.2/P2.3).
 *
 * Everything above `data/healthconnect` — and `HcMapper` itself — works on these, never on
 * `androidx.health.connect.*` types. That keeps `HcMapperTest` a pure JVM test and lets the sync
 * pipeline (P2.6) be driven by a fake `HcReader`.
 *
 * Conventions: instants are UTC epoch millis (`…Millis`), durations are seconds, energy is
 * kilocalories, distance metres, speed m/s. Every field a source may omit is nullable — no
 * sentinel values.
 */

/** One timestamped scalar sample (speed in m/s, step cadence in steps/min, power in watts). */
@Serializable
data class HcSample(val timeMillis: Long, val value: Double)

/** One heart-rate sample. `bpm` is an `Int` because Health Connect guarantees 1..300. */
@Serializable
data class HcHeartRateSample(val timeMillis: Long, val bpm: Int)

/** One `ExerciseLap`. Health Connect laps carry a length but no per-lap HR or energy. */
@Serializable
data class HcLap(
    val lapIndex: Int,
    val startMillis: Long,
    val endMillis: Long,
    val distanceMeters: Double?,
)

/** One `ExerciseSegment` (`EXERCISE_SEGMENT_TYPE_*` as a raw Int — see `ExerciseTypeMap`). */
@Serializable
data class HcSegment(
    val startMillis: Long,
    val endMillis: Long,
    val segmentType: Int,
    val repetitions: Int,
)

/**
 * One `ExerciseSessionRecord` plus everything read with that session's own time window:
 * heart rate, distance, speed, step cadence, cycling power, pedalling cadence, elevation and both
 * calorie channels.
 *
 * [externalId] is `metadata.id` — the idempotency key of `activity_source_record` (§2.4).
 * [exerciseType] is the raw `EXERCISE_TYPE_*` Int; only `ExerciseTypeMap` interprets it.
 */
@Serializable
data class HcExercise(
    val externalId: String,
    val packageName: String?,
    val startMillis: Long,
    val endMillis: Long,
    val exerciseType: Int,
    val title: String?,
    val notes: String?,
    val distanceMeters: Double? = null,
    val totalEnergyKcal: Double? = null,
    val activeEnergyKcal: Double? = null,
    val elevationGainM: Double? = null,
    val heartRateSamples: List<HcHeartRateSample> = emptyList(),
    val speedSamples: List<HcSample> = emptyList(),
    /** `StepsCadenceRecord`, steps per minute. */
    val cadenceSamples: List<HcSample> = emptyList(),
    /** `PowerRecord`, watts (P12); empty when the permission is not granted. */
    val powerSamples: List<HcSample> = emptyList(),
    /** `CyclingPedalingCadenceRecord`, revolutions per minute (P12). */
    val pedalCadenceSamples: List<HcSample> = emptyList(),
    val laps: List<HcLap> = emptyList(),
    val segments: List<HcSegment> = emptyList(),
)

/** The five aggregate totals of one local day (`HcAggregates.DAILY_METRICS`). */
@Serializable
data class HcDailyTotals(
    val steps: Int? = null,
    val totalEnergyKcal: Double? = null,
    val activeEnergyKcal: Double? = null,
    val distanceMeters: Double? = null,
    val floors: Double? = null,
)

/**
 * One local day of non-exercise data: the aggregate totals plus the vitals that are read raw and
 * averaged per day. [day] is an epoch day (§1.6).
 */
@Serializable
data class HcDailySummary(
    val day: Long,
    val steps: Int? = null,
    val totalEnergyKcal: Double? = null,
    val activeEnergyKcal: Double? = null,
    val distanceMeters: Double? = null,
    val floors: Double? = null,
    val restingHr: Int? = null,
    val avgSpo2Percent: Double? = null,
    val avgRespiratoryRate: Double? = null,
    val hrvRmssdMs: Double? = null,
    val vo2Max: Double? = null,
)

/** One `SleepSessionRecord.Stage` (`STAGE_TYPE_*` as a raw Int — see `SleepStageMap`). */
@Serializable
data class HcSleepStage(val startMillis: Long, val endMillis: Long, val stage: Int)

/** One `SleepSessionRecord`. Several of these may fall on the same night and are then merged. */
@Serializable
data class HcSleep(
    val externalId: String,
    val packageName: String?,
    val startMillis: Long,
    val endMillis: Long,
    val title: String? = null,
    val stages: List<HcSleepStage> = emptyList(),
)

/**
 * One `WeightRecord` or `BodyFatRecord`. The two are separate records in Health Connect with
 * separate ids, so the reader emits one [HcBody] per record and `HcMapper` merges the ones that
 * share a timestamp into a single `BodyMeasurement`.
 */
@Serializable
data class HcBody(
    val externalId: String,
    val packageName: String?,
    val timeMillis: Long,
    val weightKg: Double? = null,
    val bodyFatPercent: Double? = null,
)

/**
 * The four Health Connect record families the app syncs, one per `sync_state` key (P2.6). Each
 * gets its own changes token, which is what makes a `DeletionChange` unambiguous: a deletion only
 * carries a record id, so the token it arrived on is the only thing that says what was deleted.
 */
enum class HcRecordKind { EXERCISE, DAILY, SLEEP, BODY }

/**
 * One record delivered by an `UpsertionChange`, already flattened to the DTOs above. Records that
 * only feed the daily aggregates (steps, calories, resting HR, …) are reduced to
 * [HcRecordDto.DailyPoint]: the daily rows are re-aggregated for the affected days rather than
 * patched record by record, so only the time span matters.
 */
sealed interface HcRecordDto {
    val externalId: String

    data class Exercise(val exercise: HcExercise) : HcRecordDto {
        override val externalId: String get() = exercise.externalId
    }

    data class Sleep(val sleep: HcSleep) : HcRecordDto {
        override val externalId: String get() = sleep.externalId
    }

    data class Body(val body: HcBody) : HcRecordDto {
        override val externalId: String get() = body.externalId
    }

    data class DailyPoint(
        override val externalId: String,
        val startMillis: Long,
        val endMillis: Long,
    ) : HcRecordDto
}

/**
 * One page of `getChanges` (amendment A6), free of Health Connect types.
 *
 * [nextToken] must be persisted after every page so an interrupted sync resumes where it stopped.
 * [expired] means the token is too old: the caller does exactly one full 30-day re-read and asks
 * for a fresh token — never a retry loop.
 */
data class HcChanges(
    val upserts: List<HcRecordDto> = emptyList(),
    val deletedIds: List<String> = emptyList(),
    val nextToken: String = "",
    val hasMore: Boolean = false,
    val expired: Boolean = false,
)
