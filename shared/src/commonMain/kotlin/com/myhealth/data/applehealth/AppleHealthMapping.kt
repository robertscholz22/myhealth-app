package com.myhealth.data.applehealth

import com.myhealth.data.healthconnect.HcDailySummary
import com.myhealth.data.healthconnect.HcExerciseType
import com.myhealth.data.healthconnect.HcRecordKind
import com.myhealth.data.healthconnect.HcSleep
import com.myhealth.data.healthconnect.HcSleepStage
import com.myhealth.data.healthconnect.HcSleepStageType
import com.myhealth.data.healthconnect.roundHalfUp
import com.myhealth.data.healthconnect.toLocalDay
import kotlinx.datetime.TimeZone
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The platform-free half of the Apple Health reader (P22.1): everything that turns raw HealthKit
 * values into the Health Connect DTOs lives here, so the JVM tests cover it on Linux and the
 * `iosMain` reader only runs queries. Raw values are HealthKit's own enum numbers (pinned to the
 * SDK in the iOS reader, see `HealthKitReader`).
 */
object AppleWorkoutType {
    const val CROSS_TRAINING: Long = 11
    const val CYCLING: Long = 13
    const val ELLIPTICAL: Long = 16
    const val FUNCTIONAL_STRENGTH_TRAINING: Long = 20
    const val HIKING: Long = 24
    const val PREPARATION_AND_RECOVERY: Long = 33
    const val ROWING: Long = 35
    const val RUNNING: Long = 37
    const val SOCCER: Long = 41
    const val STAIR_CLIMBING: Long = 44
    const val SWIMMING: Long = 46
    const val TRADITIONAL_STRENGTH_TRAINING: Long = 50
    const val WALKING: Long = 52
    const val YOGA: Long = 57
    const val CORE_TRAINING: Long = 59
    const val FLEXIBILITY: Long = 62
    const val HIGH_INTENSITY_INTERVAL_TRAINING: Long = 63
    const val PILATES: Long = 66
    const val MIXED_CARDIO: Long = 73
    const val COOLDOWN: Long = 80
    const val OTHER: Long = 3000

    /** `HKWorkoutSwimmingLocationType.openWater`. */
    const val SWIM_LOCATION_OPEN_WATER: Long = 2

    /**
     * `HKWorkoutActivityType` → Health Connect exercise type, so [com.myhealth.data.healthconnect.ExerciseTypeMap]
     * decides the sport exactly as on Android. [indoor] is `HKMetadataKeyIndoorWorkout`.
     * Anything without a clear counterpart stays "other workout" — a wrong guess would poison the load engine.
     */
    fun toHcExerciseType(activityType: Long, indoor: Boolean?, swimLocation: Long?): Int = when (activityType) {
        RUNNING -> if (indoor == true) HcExerciseType.RUNNING_TREADMILL else HcExerciseType.RUNNING
        CYCLING -> if (indoor == true) HcExerciseType.BIKING_STATIONARY else HcExerciseType.BIKING
        WALKING -> HcExerciseType.WALKING
        HIKING -> HcExerciseType.HIKING
        SWIMMING ->
            if (swimLocation == SWIM_LOCATION_OPEN_WATER) HcExerciseType.SWIMMING_OPEN_WATER else HcExerciseType.SWIMMING_POOL
        ROWING -> if (indoor == true) HcExerciseType.ROWING_MACHINE else HcExerciseType.ROWING
        SOCCER -> HcExerciseType.SOCCER
        TRADITIONAL_STRENGTH_TRAINING,
        FUNCTIONAL_STRENGTH_TRAINING,
        CORE_TRAINING,
        -> HcExerciseType.STRENGTH_TRAINING
        YOGA -> HcExerciseType.YOGA
        PILATES -> HcExerciseType.PILATES
        FLEXIBILITY, PREPARATION_AND_RECOVERY, COOLDOWN -> HcExerciseType.STRETCHING
        HIGH_INTENSITY_INTERVAL_TRAINING -> HcExerciseType.HIGH_INTENSITY_INTERVAL_TRAINING
        else -> HcExerciseType.OTHER_WORKOUT
    }
}

/** `HKCategoryValueSleepAnalysis` raw values. */
object AppleSleepValue {
    const val IN_BED: Long = 0
    const val ASLEEP_UNSPECIFIED: Long = 1
    const val AWAKE: Long = 2
    const val ASLEEP_CORE: Long = 3
    const val ASLEEP_DEEP: Long = 4
    const val ASLEEP_REM: Long = 5

    /** Stage value → Health Connect stage; `null` for "in bed", which is an envelope, not a stage. */
    fun toHcStage(value: Long): Int? = when (value) {
        ASLEEP_UNSPECIFIED -> HcSleepStageType.SLEEPING
        AWAKE -> HcSleepStageType.AWAKE
        ASLEEP_CORE -> HcSleepStageType.LIGHT
        ASLEEP_DEEP -> HcSleepStageType.DEEP
        ASLEEP_REM -> HcSleepStageType.REM
        else -> null
    }
}

/** One `HKCategorySample` of the sleep-analysis type. */
data class AppleSleepSample(
    val id: String,
    val source: String,
    val startMillis: Long,
    val endMillis: Long,
    val value: Long,
)

/**
 * HealthKit has no sleep sessions, only stage samples (and "in bed" envelopes) per source. They
 * are assembled into one [HcSleep] per night:
 * - per source, samples closer than [SESSION_GAP_MILLIS] form one session; its night is the local
 *   day it ends on (as on Android);
 * - a source's "in bed" samples only count when it wrote no stages at all for that session (an
 *   in-bed-only phone); with stages they would double the time asleep;
 * - per night one source wins — the one with stage detail, then the longest — so a watch and the
 *   phone writing the same night are not added up;
 * - the id is `applehealth-sleep:<night>`, stable across re-reads, so a re-read replaces the night.
 */
object AppleSleepAssembler {

    const val SESSION_GAP_MILLIS: Long = 3 * 60 * 60 * 1000L
    const val ID_PREFIX: String = "applehealth-sleep:"

    fun assemble(samples: List<AppleSleepSample>, zone: TimeZone): List<HcSleep> {
        val sessions = samples.groupBy { it.source }.flatMap { (source, list) -> sessionsOf(source, list, zone) }
        return sessions.groupBy { it.night }
            .map { (night, candidates) ->
                val winner = candidates.groupBy { it.source }.values.maxWith(
                    compareBy<List<Session>> { group -> group.any { it.stages.isNotEmpty() } }
                        .thenBy { group -> group.sumOf { it.endMillis - it.startMillis } },
                )
                HcSleep(
                    externalId = "$ID_PREFIX$night",
                    packageName = winner.first().source,
                    startMillis = winner.minOf { it.startMillis },
                    endMillis = winner.maxOf { it.endMillis },
                    stages = winner.flatMap { it.stages }.sortedBy { it.startMillis },
                )
            }
            .sortedBy { it.startMillis }
    }

    private data class Session(
        val source: String,
        val night: Long,
        val startMillis: Long,
        val endMillis: Long,
        val stages: List<HcSleepStage>,
    )

    private fun sessionsOf(source: String, samples: List<AppleSleepSample>, zone: TimeZone): List<Session> {
        val clusters = mutableListOf<MutableList<AppleSleepSample>>()
        var clusterEnd = Long.MIN_VALUE
        for (sample in samples.sortedBy { it.startMillis }) {
            if (clusters.isEmpty() || sample.startMillis > clusterEnd + SESSION_GAP_MILLIS) {
                clusters += mutableListOf(sample)
            } else {
                clusters.last() += sample
            }
            clusterEnd = maxOf(clusterEnd, sample.endMillis)
        }
        return clusters.mapNotNull { cluster ->
            val staged = cluster.filter { AppleSleepValue.toHcStage(it.value) != null }
            val envelope = staged.ifEmpty { cluster.filter { it.value == AppleSleepValue.IN_BED } }
            if (envelope.isEmpty()) return@mapNotNull null
            val end = envelope.maxOf { it.endMillis }
            Session(
                source = source,
                night = end.toLocalDay(zone),
                startMillis = envelope.minOf { it.startMillis },
                endMillis = end,
                stages = staged.map { HcSleepStage(it.startMillis, it.endMillis, AppleSleepValue.toHcStage(it.value)!!) },
            )
        }
    }
}

/** The per-day HealthKit statistics [AppleDailyAssembler] combines. */
enum class AppleDailyMetric {
    STEPS,
    ACTIVE_KCAL,
    BASAL_KCAL,
    DISTANCE_M,
    FLOORS,
    RESTING_HR,

    /** `HKQuantityTypeIdentifierOxygenSaturation` in HealthKit's percent unit, i.e. a 0…1 fraction. */
    SPO2_FRACTION,
    RESPIRATORY_RATE,
    VO2_MAX,
}

/**
 * Per-day statistics → [HcDailySummary]. Total energy is active + basal (Health Connect's "total
 * calories burned" includes the BMR), so it stays empty when HealthKit has no basal energy for the
 * day. HealthKit's heart-rate variability is SDNN, not RMSSD — it is not reported as RMSSD.
 */
object AppleDailyAssembler {
    fun assemble(values: Map<AppleDailyMetric, Map<Long, Double>>): List<HcDailySummary> {
        val days = values.values.flatMap { it.keys }.distinct().sorted()
        return days.map { day ->
            fun v(metric: AppleDailyMetric): Double? = values[metric]?.get(day)
            val active = v(AppleDailyMetric.ACTIVE_KCAL)
            val basal = v(AppleDailyMetric.BASAL_KCAL)
            HcDailySummary(
                day = day,
                steps = v(AppleDailyMetric.STEPS)?.roundHalfUp(),
                totalEnergyKcal = basal?.let { it + (active ?: 0.0) },
                activeEnergyKcal = active,
                distanceMeters = v(AppleDailyMetric.DISTANCE_M),
                floors = v(AppleDailyMetric.FLOORS),
                restingHr = v(AppleDailyMetric.RESTING_HR)?.roundHalfUp(),
                avgSpo2Percent = v(AppleDailyMetric.SPO2_FRACTION)?.let { it * 100.0 },
                avgRespiratoryRate = v(AppleDailyMetric.RESPIRATORY_RATE),
                hrvRmssdMs = null,
                vo2Max = v(AppleDailyMetric.VO2_MAX),
            )
        }
    }
}

/**
 * The changes token of the Apple Health reader: one archived `HKQueryAnchor` (base64) per
 * HealthKit type identifier, plus the channel it belongs to. An unreadable token counts as expired,
 * which makes `HcSyncService` re-read its window once and take a fresh one.
 */
@Serializable
data class AppleAnchorToken(
    val kind: HcRecordKind,
    val anchors: Map<String, String>,
) {
    fun encode(): String = PREFIX + json.encodeToString(serializer(), this)

    companion object {
        private const val PREFIX = "hk1:"
        private val json = Json { ignoreUnknownKeys = true }

        fun decode(token: String): AppleAnchorToken? {
            if (!token.startsWith(PREFIX)) return null
            return runCatching { json.decodeFromString(serializer(), token.removePrefix(PREFIX)) }.getOrNull()
        }
    }
}
